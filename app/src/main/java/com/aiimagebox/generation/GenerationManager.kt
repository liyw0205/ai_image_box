package com.aiimagebox.generation

import com.aiimagebox.data.ModelTarget
import com.aiimagebox.data.ProviderChannel
import com.aiimagebox.provider.AttemptRecord as ProviderAttemptRecord
import com.aiimagebox.provider.GenerationMode as ProviderGenerationMode
import com.aiimagebox.provider.GenerationRequest as ProviderGenerationRequest
import com.aiimagebox.provider.GenerationResult as ProviderGenerationResult
import com.aiimagebox.provider.GenerationStatus as ProviderGenerationStatus
import com.aiimagebox.provider.ImageResponseFormat
import com.aiimagebox.provider.MediaReference
import com.aiimagebox.provider.ProviderCapabilities
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

interface GenerationExecutor {
    suspend fun generate(request: GenerationRequest): GenerationResult
}

class GenerationProviderException(
    val providerResult: ProviderGenerationResult,
    message: String = providerResult.error.ifBlank { "Provider generation failed: ${providerResult.status}" },
) : IllegalStateException(message)

class ProviderRegistryGenerationExecutor(
    private val registry: ProviderRegistry = ProviderRegistry,
    private val agentPipeline: AgentPipeline? = null,
    private val channelProvider: () -> List<ProviderChannel> = { emptyList() },
    private val jobObserver: suspend (String, com.aiimagebox.provider.ProviderJob) -> Unit = { _, _ -> },
) : GenerationExecutor {
    override suspend fun generate(request: GenerationRequest): GenerationResult {
        val pipelineBefore = agentPipeline?.before(request) ?: PipelineResult(request)
        val effectiveRequest = pipelineBefore.request
        val channel = effectiveRequest.target.channel
            ?: error(
                "ProviderRegistryGenerationExecutor requires GenerationTarget.channel. " +
                    "Use GenerationTarget.fromChannel(...) or pass a custom GenerationExecutor.",
            )
        val providerRequest = effectiveRequest.toProviderRequest()
        val attempts = mutableListOf<ProviderAttemptRecord>()
        var lastResult: ProviderGenerationResult? = null

        for (fallbackTarget in effectiveRequest.fallbackTargetPairs(channel)) {
            val providerChannel = fallbackTarget.channel
            val providerTarget = fallbackTarget.target
            val adapter = registry.get(providerTarget.providerType)
            if (adapter == null) {
                val skipped = localFailure(providerTarget, "Unsupported provider type: ${providerTarget.providerType}")
                attempts.addAll(skipped.attempts)
                lastResult = skipped.copy(attempts = attempts.toList())
                continue
            }
            val capabilities = adapter.capabilities(providerChannel)
            if (!providerRequest.mode.isSupportedBy(capabilities)) {
                val skipped = localFailure(
                    providerTarget,
                    "Provider ${providerTarget.providerType} does not support ${providerRequest.mode}.",
                )
                attempts.addAll(skipped.attempts)
                lastResult = skipped.copy(attempts = attempts.toList())
                continue
            }

            val providerResult = adapter.generate(providerChannel, providerRequest, providerTarget)
            attempts.addAll(
                providerResult.attempts.ifEmpty {
                    listOf(providerResult.toAttemptRecord(providerTarget))
                },
            )
            val resultWithAttempts = providerResult.copy(attempts = attempts.toList())
            if (providerResult.status == ProviderGenerationStatus.SUCCEEDED) {
                val generated = resultWithAttempts.toGenerationResult(effectiveRequest)
                val pipelineAfter = agentPipeline?.after(effectiveRequest, generated, pipelineBefore)
                    ?: pipelineBefore.copy(result = generated)
                val executionsJson = pipelineAfter.executions.toJsonArray().toString()
                val metadata = buildMap {
                    putAll(generated.metadata)
                    putAll(pipelineAfter.metadata)
                    put("agent_executions", executionsJson)
                }
                return GenerationResult(
                    assets = generated.assets.map { asset ->
                        asset.copy(metadata = buildMap { putAll(asset.metadata); putAll(metadata) })
                    },
                    metadata = metadata,
                    attempts = generated.attempts,
                )
            }
            lastResult = resultWithAttempts
        }

        val finalResult = lastResult ?: localFailure(
            target = effectiveRequest.target.toModelTarget(channel),
            error = "No usable provider target was available.",
        )
        throw GenerationProviderException(finalResult)
    }

    private fun GenerationRequest.toProviderRequest(): ProviderGenerationRequest {
        val videoTarget = target.providerType.contains("video", ignoreCase = true) ||
            parameters.extra["mode"]?.toString()?.contains("video", ignoreCase = true) == true
        return ProviderGenerationRequest(
            mode = if (videoTarget && parameters.referenceImagePaths.isEmpty()) {
                ProviderGenerationMode.TEXT_TO_VIDEO
            } else if (videoTarget) {
                ProviderGenerationMode.IMAGE_TO_VIDEO
            } else if (parameters.referenceImagePaths.isEmpty()) {
                ProviderGenerationMode.TEXT_TO_IMAGE
            } else {
                ProviderGenerationMode.IMAGE_TO_IMAGE
            },
            prompt = prompt,
            negativePrompt = parameters.negativePrompt.orEmpty(),
            aspectRatio = parameters.aspectRatio.ifBlank { "1:1" },
            resolution = parameters.resolution ?: parameters.size ?: imageResolution(parameters),
            count = parameters.count.coerceIn(1, 10),
            durationSeconds = parameters.durationSeconds,
            responseFormat = responseFormatConstant(parameters.responseFormat),
            quality = parameters.quality,
            style = parameters.style,
            seed = parameters.seed,
            references = parameters.referenceImagePaths.mapNotNull { path ->
                val file = File(path)
                if (!file.isFile) return@mapNotNull null
                MediaReference(
                    bytes = file.readBytes(),
                    mimeType = mimeTypeForFile(file),
                    name = file.name,
                )
            },
            extra = parameters.toExtraJson(),
            resumeJob = parameters.resumeJob,
            onJobUpdated = { job -> jobObserver(id, job) },
        )
    }

    private fun mimeTypeForFile(file: File): String {
        return when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            else -> "image/png"
        }
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

    private fun GenerationRequest.fallbackTargetPairs(channel: ProviderChannel): List<FallbackTarget> {
        val loadedChannels = runCatching { channelProvider() }
            .getOrDefault(emptyList())
            .filter { it.enabled }
        val loadedById = loadedChannels.associateBy { it.id }
        val selectedChannel = channel
        val currentChannelForConfiguredTargets = loadedById[selectedChannel.id] ?: selectedChannel
        val channelById = (loadedChannels + selectedChannel + currentChannelForConfiguredTargets)
            .associateBy { it.id }

        val selected = target.toModelTarget(selectedChannel)
        val targets = mutableListOf(FallbackTarget(selectedChannel, selected))
        targets += registry.targetsFor(listOf(currentChannelForConfiguredTargets))
            .map { FallbackTarget(channelById[it.channelId] ?: currentChannelForConfiguredTargets, it) }
        targets += registry.targetsFor(loadedChannels)
            .mapNotNull { configuredTarget ->
                channelById[configuredTarget.channelId]?.let { FallbackTarget(it, configuredTarget) }
            }

        return targets
            .filter { it.target.model.isNotBlank() }
            .distinctBy { "${it.target.channelId}\u0000${it.target.providerType}\u0000${it.target.model}" }
    }

    private fun ProviderGenerationMode.isSupportedBy(capabilities: ProviderCapabilities): Boolean {
        return when (this) {
            ProviderGenerationMode.TEXT_TO_IMAGE -> capabilities.textToImage
            ProviderGenerationMode.IMAGE_TO_IMAGE -> capabilities.imageToImage
            ProviderGenerationMode.TEXT_TO_VIDEO -> capabilities.textToVideo
            ProviderGenerationMode.IMAGE_TO_VIDEO -> capabilities.imageToVideo
        }
    }

    private fun localFailure(target: ModelTarget, error: String): ProviderGenerationResult {
        return ProviderGenerationResult(
            status = ProviderGenerationStatus.FAILED,
            usedModel = target.model,
            error = error,
            attempts = listOf(
                ProviderAttemptRecord(
                    providerType = target.providerType,
                    channelId = target.channelId,
                    channelName = target.channelName,
                    model = target.model,
                    error = error,
                ),
            ),
        )
    }

    private fun ProviderGenerationResult.toAttemptRecord(target: ModelTarget): ProviderAttemptRecord {
        return ProviderAttemptRecord(
            providerType = target.providerType,
            channelId = target.channelId,
            channelName = target.channelName,
            model = usedModel.ifBlank { target.model },
            httpStatus = httpStatus,
            elapsedMillis = elapsedMillis,
            requestId = requestId,
            error = error,
            rawPreview = rawPreview,
        )
    }

    private fun ProviderGenerationResult.toGenerationResult(request: GenerationRequest): GenerationResult {
        if (status != ProviderGenerationStatus.SUCCEEDED) {
            throw GenerationProviderException(this)
        }
        if (images.isEmpty() && videos.isEmpty()) {
            throw GenerationProviderException(this, "Provider result did not include image or video assets.")
        }

        val metadata = buildMap {
            put("request_id", request.id)
            requestId.takeIf { it.isNotBlank() }?.let { put("provider_request_id", it) }
            usedModel.takeIf { it.isNotBlank() }?.let { put("model", it) }
            httpStatus?.let { put("http_status", it.toString()) }
            put("elapsed_ms", elapsedMillis.toString())
            rawPreview.takeIf { it.isNotBlank() }?.let { put("raw_preview", it) }
            put("image_count", images.size.toString())
            put("video_count", videos.size.toString())
            put("attempt_count", attempts.size.toString())
            job?.id?.takeIf { it.isNotBlank() }?.let { put("job_id", it) }
            job?.pollUrl?.takeIf { it.isNotBlank() }?.let { put("poll_url", it) }
            if (attempts.isNotEmpty()) put("attempts", attempts.toJson().toString())
        }
        val assets = images.mapIndexed { index, asset ->
            GeneratedAssetIntegrity.requireValid(
                bytes = asset.bytes,
                declaredMimeType = asset.mimeType.ifBlank { "image/png" },
                expectedKind = GeneratedAssetIntegrity.MediaKind.IMAGE,
            )
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
        } + videos.mapIndexed { index, asset ->
            GeneratedAssetIntegrity.requireValid(
                bytes = asset.bytes,
                declaredMimeType = asset.mimeType.ifBlank { "video/mp4" },
                expectedKind = GeneratedAssetIntegrity.MediaKind.VIDEO,
            )
            GenerationAsset(
                bytes = asset.bytes,
                mimeType = asset.mimeType.ifBlank { "video/mp4" },
                fileNameHint = "video_${index + 1}",
                metadata = buildMap {
                    putAll(metadata)
                    put("media_type", "video")
                    put("video_index", (index + 1).toString())
                    put("source", asset.source.name.lowercase())
                    asset.remoteUrl.takeIf { it.isNotBlank() }?.let { put("remote_url", it) }
                },
            )
        }
        return GenerationResult(
            assets = assets,
            metadata = metadata,
            attempts = attempts.map { it.toSummary(request) },
        )
    }

    private fun List<ProviderAttemptRecord>.toJson(): JSONArray {
        val array = JSONArray()
        forEachIndexed { index, attempt ->
            array.put(
                JSONObject()
                    .put("attempt_number", index + 1)
                    .put("channel_id", attempt.channelId)
                    .put("provider_type", attempt.providerType)
                    .put("channel_name", attempt.channelName)
                    .put("model", attempt.model)
                    .put("status", attempt.statusName())
                    .put("http_status", attempt.httpStatus ?: JSONObject.NULL)
                    .put("elapsed_ms", attempt.elapsedMillis)
                    .put("request_id", attempt.requestId)
                    .put("error", attempt.error)
                    .put("raw_preview", attempt.rawPreview),
            )
        }
        return array
    }

    private fun ProviderAttemptRecord.toSummary(request: GenerationRequest): GenerationAttemptSummary {
        return GenerationAttemptSummary(
            status = if (isSuccessful()) GenerationStatus.SUCCEEDED else GenerationStatus.FAILED,
            channelId = channelId.ifBlank { request.target.channelId },
            channelName = channelName.ifBlank { request.target.channelName },
            providerType = providerType.ifBlank { request.target.providerType },
            model = model.ifBlank { request.target.model },
            requestId = requestId,
            httpStatus = httpStatus,
            elapsedMillis = elapsedMillis,
            errorMessage = error,
            rawPreview = rawPreview,
        )
    }

    private fun ProviderAttemptRecord.statusName(): String {
        return if (isSuccessful()) "SUCCEEDED" else "FAILED"
    }

    private fun ProviderAttemptRecord.isSuccessful(): Boolean {
        return error.isBlank() && (httpStatus == null || httpStatus in 200..299)
    }

    private data class FallbackTarget(
        val channel: ProviderChannel,
        val target: ModelTarget,
    )
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
