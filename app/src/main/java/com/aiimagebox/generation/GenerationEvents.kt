package com.aiimagebox.generation

import com.aiimagebox.data.ProviderChannel
import java.util.UUID

data class GenerationTarget(
    val channelId: String,
    val model: String,
    val channelName: String = "",
    val providerType: String = "",
    val baseUrl: String = "",
    val timeoutSeconds: Int? = null,
    val proxy: String = "",
    val extra: Map<String, Any?> = emptyMap(),
    val channel: ProviderChannel? = null,
) {
    val label: String
        get() = listOf(
            channelName.ifBlank { channel?.name ?: channelId },
            model.ifBlank { channel?.defaultModel.orEmpty() },
        )
            .filter { it.isNotBlank() }
            .joinToString("/")

    companion object {
        fun fromChannel(
            channel: ProviderChannel,
            model: String = channel.defaultModel,
        ): GenerationTarget {
            return GenerationTarget(
                channelId = channel.id,
                model = model.ifBlank { channel.defaultModel },
                channelName = channel.name,
                providerType = channel.providerType,
                baseUrl = channel.baseUrl,
                timeoutSeconds = channel.timeoutSeconds,
                proxy = channel.proxy,
                channel = channel,
            )
        }
    }
}

data class GenerationParameters(
    val width: Int? = null,
    val height: Int? = null,
    val size: String? = null,
    val aspectRatio: String = "",
    val resolution: String? = null,
    val count: Int = 1,
    val negativePrompt: String? = null,
    val seed: Long? = null,
    val guidanceScale: Double? = null,
    val steps: Int? = null,
    val quality: String = "",
    val style: String = "",
    val responseFormat: String = "b64_json",
    val referenceImagePaths: List<String> = emptyList(),
    val extra: Map<String, Any?> = emptyMap(),
)

data class GenerationRequest(
    val prompt: String,
    val target: GenerationTarget,
    val parameters: GenerationParameters = GenerationParameters(),
    val id: String = UUID.randomUUID().toString(),
    val createdAtMillis: Long = System.currentTimeMillis(),
)

data class GenerationAsset(
    val bytes: ByteArray,
    val mimeType: String = "image/png",
    val fileNameHint: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class GenerationAttemptSummary(
    val status: GenerationStatus,
    val channelId: String,
    val channelName: String,
    val providerType: String,
    val model: String,
    val requestId: String = "",
    val httpStatus: Int? = null,
    val elapsedMillis: Long? = null,
    val errorMessage: String = "",
    val rawPreview: String = "",
)

class GenerationResult(
    val assets: List<GenerationAsset>,
    val metadata: Map<String, String> = emptyMap(),
    val attempts: List<GenerationAttemptSummary> = emptyList(),
) {
    constructor(
        bytes: ByteArray,
        mimeType: String = "image/png",
        fileNameHint: String? = null,
        metadata: Map<String, String> = emptyMap(),
        attempts: List<GenerationAttemptSummary> = emptyList(),
    ) : this(
        assets = listOf(
            GenerationAsset(
                bytes = bytes,
                mimeType = mimeType,
                fileNameHint = fileNameHint,
                metadata = metadata,
            ),
        ),
        metadata = metadata,
        attempts = attempts,
    )

    val bytes: ByteArray
        get() = assets.firstOrNull()?.bytes ?: ByteArray(0)

    val mimeType: String
        get() = assets.firstOrNull()?.mimeType ?: "image/png"

    val fileNameHint: String?
        get() = assets.firstOrNull()?.fileNameHint

    val byteCount: Int
        get() = assets.sumOf { it.bytes.size }
}

enum class GenerationStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

sealed class GenerationEvent {
    abstract val requestId: String

    data class Enqueued(val item: GenerationQueueItem) : GenerationEvent() {
        override val requestId: String = item.request.id
    }

    data class Started(val item: GenerationQueueItem) : GenerationEvent() {
        override val requestId: String = item.request.id
    }

    data class Succeeded(
        val item: GenerationQueueItem,
        val result: GenerationResult,
    ) : GenerationEvent() {
        override val requestId: String = item.request.id
    }

    data class Failed(
        val item: GenerationQueueItem,
        val error: Throwable,
    ) : GenerationEvent() {
        override val requestId: String = item.request.id
    }

    data class Cancelled(
        val item: GenerationQueueItem,
        val reason: String? = null,
    ) : GenerationEvent() {
        override val requestId: String = item.request.id
    }
}
