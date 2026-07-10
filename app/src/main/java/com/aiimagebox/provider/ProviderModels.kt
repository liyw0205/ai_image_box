package com.aiimagebox.provider

import org.json.JSONObject

enum class GenerationMode {
    TEXT_TO_IMAGE,
    IMAGE_TO_IMAGE,
    TEXT_TO_VIDEO,
    IMAGE_TO_VIDEO,
}

enum class GenerationStatus {
    SUCCEEDED,
    FAILED,
    RUNNING,
    CANCELED,
}

enum class ImageResponseFormat(val apiValue: String) {
    B64_JSON("b64_json"),
    URL("url"),
}

data class MediaReference(
    val bytes: ByteArray,
    val mimeType: String = "image/png",
    val name: String = "",
)

data class GenerationRequest(
    val mode: GenerationMode = GenerationMode.TEXT_TO_IMAGE,
    val prompt: String,
    val negativePrompt: String = "",
    val aspectRatio: String = "1:1",
    val resolution: String = "1024x1024",
    val count: Int = 1,
    val durationSeconds: Int? = null,
    val responseFormat: ImageResponseFormat = ImageResponseFormat.B64_JSON,
    val quality: String = "",
    val style: String = "",
    val seed: Long? = null,
    val references: List<MediaReference> = emptyList(),
    val extra: JSONObject = JSONObject(),
)

enum class GeneratedAssetSource {
    B64_JSON,
    DATA_URL,
    URL,
}

data class GeneratedAsset(
    val bytes: ByteArray,
    val mimeType: String = "",
    val source: GeneratedAssetSource,
    val remoteUrl: String = "",
    val revisedPrompt: String = "",
) {
    val sizeBytes: Int
        get() = bytes.size
}

data class GenerationResult(
    val status: GenerationStatus,
    val images: List<GeneratedAsset> = emptyList(),
    val videos: List<GeneratedAsset> = emptyList(),
    val job: ProviderJob? = null,
    val usedModel: String = "",
    val requestId: String = "",
    val httpStatus: Int? = null,
    val error: String = "",
    val rawPreview: String = "",
    val elapsedMillis: Long = 0L,
    val attempts: List<AttemptRecord> = emptyList(),
)

data class AttemptRecord(
    val providerType: String,
    val channelName: String,
    val model: String,
    val channelId: String = "",
    val httpStatus: Int? = null,
    val elapsedMillis: Long = 0L,
    val requestId: String = "",
    val error: String = "",
    val rawPreview: String = "",
)

data class ModelInfo(
    val id: String,
    val ownedBy: String = "",
    val rawPreview: String = "",
)

data class ModelListResult(
    val models: List<ModelInfo> = emptyList(),
    val requestId: String = "",
    val httpStatus: Int? = null,
    val error: String = "",
    val rawPreview: String = "",
    val elapsedMillis: Long = 0L,
) {
    val success: Boolean
        get() = error.isBlank()
}

data class ProviderCapabilities(
    val textToImage: Boolean,
    val imageToImage: Boolean,
    val textToVideo: Boolean,
    val imageToVideo: Boolean,
    val synchronous: Boolean,
    val asyncJob: Boolean,
    val supportsModelList: Boolean,
    val supportsCancel: Boolean,
)

data class ProviderJob(
    val id: String,
    val pollUrl: String = "",
    val resultUrl: String = "",
    val rawPreview: String = "",
)

enum class ParsedImageKind {
    B64_JSON,
    DATA_URL,
    URL,
}

data class ParsedImageCandidate(
    val kind: ParsedImageKind,
    val value: String,
    val mimeType: String = "",
    val revisedPrompt: String = "",
)
