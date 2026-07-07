package com.aiimagebox.generation

import com.aiimagebox.data.ModelTarget
import com.aiimagebox.data.ProviderChannel
import com.aiimagebox.provider.GenerationMode as ProviderGenerationMode
import com.aiimagebox.provider.GenerationRequest as ProviderGenerationRequest
import com.aiimagebox.provider.GenerationResult as ProviderGenerationResult
import com.aiimagebox.provider.GenerationStatus as ProviderGenerationStatus
import com.aiimagebox.provider.ImageResponseFormat
import com.aiimagebox.provider.ProviderRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

interface GenerationExecutor {
    suspend fun generate(request: GenerationRequest): GenerationResult
}

class ProviderRegistryGenerationExecutor(
    private val registry: ProviderRegistry = ProviderRegistry,
) : GenerationExecutor {
    override suspend fun generate(request: GenerationRequest): GenerationResult {
        val channel = request.target.channel
            ?: error(
                "ProviderRegistryGenerationExecutor requires GenerationTarget.channel. " +
                    "Use GenerationTarget.fromChannel(...) or pass a custom GenerationExecutor.",
            )
        val adapter = registry.require(channel.providerType.ifBlank { request.target.providerType })
        val providerRequest = request.toProviderRequest()
        val providerTarget = request.target.toModelTarget(channel)
        val providerResult = adapter.generate(channel, providerRequest, providerTarget)
        return providerResult.toGenerationResult(request)
    }

    private fun GenerationRequest.toProviderRequest(): ProviderGenerationRequest {
        return ProviderGenerationRequest(
            mode = ProviderGenerationMode.TEXT_TO_IMAGE,
            prompt = prompt,
            negativePrompt = parameters.negativePrompt.orEmpty(),
            aspectRatio = parameters.aspectRatio.ifBlank { "1:1" },
            resolution = parameters.resolution ?: parameters.size ?: imageResolution(parameters),
            count = parameters.count.coerceIn(1, 10),
            responseFormat = responseFormatConstant(parameters.responseFormat),
            quality = parameters.quality,
            style = parameters.style,
            seed = parameters.seed,
            references = emptyList(),
            extra = parameters.toExtraJson(),
        )
    }

    private fun GenerationParameters.toExtraJson(): JSONObject {
        val json = JSONObject()
        width?.let { json.put("width", it) }
        height?.let { json.put("height", it) }
        guidanceScale?.let { json.put("guidance_scale", it) }
        steps?.let { json.put("steps", it) }
        extra.forEach { (key, value) ->
            if (key.isNotBlank() && value != null) json.put(key, value)
        }
        return json
    }

    private fun imageResolution(parameters: GenerationParameters): String {
        val width = parameters.width
        val height = parameters.height
        if (width != null && height != null) return "${width}x$height"
        return "1024x1024"
    }

    private fun responseFormatConstant(value: String): ImageResponseFormat {
        val normalized = value.trim()
        return ImageResponseFormat.values().firstOrNull {
            it.name.equals(normalized, ignoreCase = true) ||
                it.apiValue.equals(normalized, ignoreCase = true)
        } ?: ImageResponseFormat.B64_JSON
    }

    private fun GenerationTarget.toModelTarget(channel: ProviderChannel): ModelTarget {
        return ModelTarget(
            channelId = channelId.ifBlank { channel.id },
            channelName = channelName.ifBlank { channel.name },
            providerType = providerType.ifBlank { channel.providerType },
            baseUrl = baseUrl.ifBlank { channel.baseUrl },
            model = model.ifBlank { channel.defaultModel },
            timeoutSeconds = timeoutSeconds ?: channel.timeoutSeconds,
            proxy = proxy.ifBlank { channel.proxy },
        )
    }

    private fun ProviderGenerationResult.toGenerationResult(request: GenerationRequest): GenerationResult {
        if (status != ProviderGenerationStatus.SUCCEEDED) {
            throw IllegalStateException(error.ifBlank { "Provider generation failed: $status" })
        }
        if (images.isEmpty()) {
            throw IllegalStateException("Provider result did not include image assets.")
        }

        val metadata = buildMap {
            put("request_id", request.id)
            requestId.takeIf { it.isNotBlank() }?.let { put("provider_request_id", it) }
            usedModel.takeIf { it.isNotBlank() }?.let { put("model", it) }
            put("image_count", images.size.toString())
        }
        val assets = images.mapIndexed { index, asset ->
            GenerationAsset(
                bytes = asset.bytes,
                mimeType = asset.mimeType.ifBlank { "image/png" },
                fileNameHint = "image_${index + 1}",
                metadata = buildMap {
                    putAll(metadata)
                    put("image_index", (index + 1).toString())
                    put("source", asset.source.name.lowercase())
                    asset.remoteUrl.takeIf { it.isNotBlank() }?.let { put("remote_url", it) }
                    asset.revisedPrompt.takeIf { it.isNotBlank() }?.let { put("revised_prompt", it) }
                },
            )
        }
        return GenerationResult(
            assets = assets,
            metadata = metadata,
        )
    }
}

class GenerationManager(
    private val executor: GenerationExecutor = ProviderRegistryGenerationExecutor(),
    parentScope: CoroutineScope? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val queue = GenerationQueue()
    private val ownsScope = parentScope == null
    private val ownedJob = SupervisorJob()
    private val scope = parentScope ?: CoroutineScope(ownedJob + dispatcher)
    private val workerLock = Any()
    private val wakeups = Channel<Unit>(capacity = Channel.CONFLATED)

    @Volatile
    private var closed = false

    @Volatile
    private var workerJob: Job? = null

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var activeRequestId: String? = null

    private val _state = MutableStateFlow(queue.snapshot())
    val state: StateFlow<GenerationQueueState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GenerationEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<GenerationEvent> = _events.asSharedFlow()

    fun enqueue(
        prompt: String,
        target: GenerationTarget,
        parameters: GenerationParameters = GenerationParameters(),
    ): GenerationRequest {
        return enqueue(
            GenerationRequest(
                prompt = prompt,
                target = target,
                parameters = parameters,
            ),
        )
    }

    fun enqueue(
        prompt: String,
        channel: ProviderChannel,
        model: String = channel.defaultModel,
        parameters: GenerationParameters = GenerationParameters(),
    ): GenerationRequest {
        return enqueue(
            prompt = prompt,
            target = GenerationTarget.fromChannel(channel, model),
            parameters = parameters,
        )
    }

    fun enqueue(request: GenerationRequest): GenerationRequest {
        check(!closed) { "GenerationManager is closed." }
        val item = queue.enqueue(request)
        publishState()
        _events.tryEmit(GenerationEvent.Enqueued(item))
        ensureWorker()
        wakeups.trySend(Unit)
        return item.request
    }

    fun cancel(requestId: String, reason: String? = "Cancelled by caller."): Boolean {
        val item = queue.cancel(requestId, reason) ?: return false
        if (activeRequestId == requestId) {
            activeJob?.cancel()
        }
        publishState()
        _events.tryEmit(GenerationEvent.Cancelled(item, reason))
        return true
    }

    fun cancelQueued(reason: String? = "Cancelled by caller."): Int {
        val cancelled = queue.cancelQueued(reason)
        if (cancelled.isEmpty()) return 0

        publishState()
        cancelled.forEach { _events.tryEmit(GenerationEvent.Cancelled(it, reason)) }
        return cancelled.size
    }

    fun clearFinished(): Int {
        val removed = queue.removeFinished()
        if (removed > 0) publishState()
        return removed
    }

    fun snapshot(): GenerationQueueState = queue.snapshot()

    override fun close() {
        if (closed) return
        closed = true

        val activeCancelled = activeRequestId?.let {
            queue.cancel(it, "GenerationManager closed.")
        }
        val cancelled = queue.cancelQueued("GenerationManager closed.")
        activeJob?.cancel()
        workerJob?.cancel()
        wakeups.close()
        if (ownsScope) ownedJob.cancel()

        val cancelledItems = listOfNotNull(activeCancelled) + cancelled
        if (cancelledItems.isNotEmpty()) {
            publishState()
            cancelledItems.forEach {
                _events.tryEmit(GenerationEvent.Cancelled(it, "GenerationManager closed."))
            }
        }
    }

    private fun ensureWorker() {
        synchronized(workerLock) {
            if (workerJob?.isActive == true) return
            workerJob = scope.launch(dispatcher) { runWorker() }
        }
    }

    private suspend fun runWorker() {
        while (currentCoroutineContext().isActive) {
            val item = queue.startNext()
            if (item == null) {
                wakeups.receive()
                continue
            }

            publishState()
            _events.emit(GenerationEvent.Started(item))
            process(item)
        }
    }

    private suspend fun process(item: GenerationQueueItem) {
        val request = item.request
        try {
            val result = kotlinx.coroutines.coroutineScope {
                val job = async(dispatcher) { executor.generate(request) }
                activeRequestId = request.id
                activeJob = job
                try {
                    job.await()
                } finally {
                    activeJob = null
                    activeRequestId = null
                }
            }
            val updated = queue.complete(request.id, result.byteCount)
            publishState()
            if (updated != null) {
                _events.emit(GenerationEvent.Succeeded(updated, result))
            }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) {
                val updated = queue.cancel(request.id, error.message ?: "Cancelled.")
                publishState()
                if (updated != null) {
                    _events.emit(GenerationEvent.Cancelled(updated, updated.errorMessage))
                }
                if (!currentCoroutineContext().isActive) throw error
                return
            }

            val updated = queue.fail(request.id, error)
            publishState()
            if (updated != null) {
                _events.emit(GenerationEvent.Failed(updated, error))
            }
        }
    }

    private fun publishState() {
        _state.value = queue.snapshot()
    }
}
