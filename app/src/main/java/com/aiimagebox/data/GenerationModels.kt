package com.aiimagebox.data

import com.aiimagebox.util.Redaction
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class GenerationMode(val wireName: String) {
    IMAGE("image"),
    VIDEO("video");

    companion object {
        fun fromWireName(value: String?): GenerationMode {
            val normalized = value.orEmpty().trim().lowercase()
            return values().firstOrNull { it.wireName == normalized || it.name.lowercase() == normalized } ?: IMAGE
        }
    }
}

enum class GenerationStatus(val wireName: String) {
    QUEUED("queued"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELED("canceled");

    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == CANCELED

    companion object {
        fun fromWireName(value: String?): GenerationStatus {
            val normalized = value.orEmpty().trim().lowercase()
            if (normalized == "cancelled") return CANCELED
            return values().firstOrNull { it.wireName == normalized || it.name.lowercase() == normalized } ?: QUEUED
        }
    }
}

data class MediaReference(
    val id: String = UUID.randomUUID().toString(),
    val uri: String = "",
    val filePath: String = "",
    val mimeType: String = "",
    val displayName: String = "",
    val sizeBytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val sha256: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("uri", uri)
        .put("file_path", filePath)
        .put("mime_type", mimeType)
        .put("display_name", displayName)
        .putNullable("size_bytes", sizeBytes)
        .putNullable("width", width)
        .putNullable("height", height)
        .putNullable("duration_ms", durationMs)
        .put("sha256", sha256)
        .put("created_at", createdAt)

    companion object {
        fun fromJson(value: JSONObject): MediaReference = MediaReference(
            id = value.optCleanId(),
            uri = value.optString("uri", ""),
            filePath = value.optString("file_path", ""),
            mimeType = value.optString("mime_type", ""),
            displayName = value.optString("display_name", ""),
            sizeBytes = value.optNullableLong("size_bytes"),
            width = value.optNullableInt("width"),
            height = value.optNullableInt("height"),
            durationMs = value.optNullableLong("duration_ms"),
            sha256 = value.optString("sha256", ""),
            createdAt = value.optLong("created_at", System.currentTimeMillis()),
        )
    }
}

data class GeneratedAsset(
    val id: String = UUID.randomUUID().toString(),
    val mode: GenerationMode = GenerationMode.IMAGE,
    val media: MediaReference = MediaReference(),
    val channelId: String = "",
    val channelName: String = "",
    val providerType: String = "",
    val model: String = "",
    val seed: Long? = null,
    val metadataJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("mode", mode.wireName)
        .put("media", media.toJson())
        .put("channel_id", channelId)
        .put("channel_name", channelName)
        .put("provider_type", providerType)
        .put("model", model)
        .putNullable("seed", seed)
        .put("metadata", parseJsonObject(metadataJson))
        .put("created_at", createdAt)

    companion object {
        fun fromJson(value: JSONObject): GeneratedAsset = GeneratedAsset(
            id = value.optCleanId(),
            mode = GenerationMode.fromWireName(value.optString("mode", "")),
            media = value.optJSONObject("media")?.let { MediaReference.fromJson(it) } ?: MediaReference(),
            channelId = value.optString("channel_id", ""),
            channelName = value.optString("channel_name", ""),
            providerType = value.optString("provider_type", ""),
            model = value.optString("model", ""),
            seed = value.optNullableLong("seed"),
            metadataJson = value.optJSONObject("metadata")?.toString() ?: value.optString("metadata_json", "{}"),
            createdAt = value.optLong("created_at", System.currentTimeMillis()),
        )
    }
}

data class AttemptRecord(
    val id: String = UUID.randomUUID().toString(),
    val taskId: String = "",
    val attemptNumber: Int = 1,
    val status: GenerationStatus = GenerationStatus.RUNNING,
    val channelId: String = "",
    val channelName: String = "",
    val providerType: String = "",
    val model: String = "",
    val requestJson: String = "{}",
    val responseJson: String = "{}",
    val responseBodyPath: String = "",
    val httpStatusCode: Int? = null,
    val errorMessage: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val durationMs: Long? = null,
) {
    fun toJson(redact: Boolean = true): JSONObject {
        val request = if (redact) Redaction.redactJsonString(requestJson) else requestJson
        val response = if (redact) Redaction.redactJsonString(responseJson) else responseJson
        return JSONObject()
            .put("id", id)
            .put("task_id", taskId)
            .put("attempt_number", attemptNumber.coerceAtLeast(1))
            .put("status", status.wireName)
            .put("channel_id", channelId)
            .put("channel_name", channelName)
            .put("provider_type", providerType)
            .put("model", model)
            .put("request", parseJsonObject(request))
            .put("response", parseJsonObject(response))
            .put("response_body_path", responseBodyPath)
            .putNullable("http_status_code", httpStatusCode)
            .put("error_message", if (redact) Redaction.redactText(errorMessage) else errorMessage)
            .put("started_at", startedAt)
            .putNullable("ended_at", endedAt)
            .putNullable("duration_ms", durationMs)
    }

    companion object {
        fun fromJson(value: JSONObject): AttemptRecord = AttemptRecord(
            id = value.optCleanId(),
            taskId = value.optString("task_id", ""),
            attemptNumber = value.optInt("attempt_number", 1).coerceAtLeast(1),
            status = GenerationStatus.fromWireName(value.optString("status", "")),
            channelId = value.optString("channel_id", ""),
            channelName = value.optString("channel_name", ""),
            providerType = value.optString("provider_type", ""),
            model = value.optString("model", ""),
            requestJson = value.optJSONObject("request")?.toString() ?: value.optString("request_json", "{}"),
            responseJson = value.optJSONObject("response")?.toString() ?: value.optString("response_json", "{}"),
            responseBodyPath = value.optString("response_body_path", ""),
            httpStatusCode = value.optNullableInt("http_status_code"),
            errorMessage = value.optString("error_message", ""),
            startedAt = value.optLong("started_at", System.currentTimeMillis()),
            endedAt = value.optNullableLong("ended_at"),
            durationMs = value.optNullableLong("duration_ms"),
        )
    }
}

data class GenerationTask(
    val id: String = UUID.randomUUID().toString(),
    val mode: GenerationMode = GenerationMode.IMAGE,
    val status: GenerationStatus = GenerationStatus.QUEUED,
    val prompt: String = "",
    val negativePrompt: String = "",
    val inputMedia: List<MediaReference> = emptyList(),
    val assets: List<GeneratedAsset> = emptyList(),
    val attempts: List<AttemptRecord> = emptyList(),
    val channelId: String = "",
    val channelName: String = "",
    val providerType: String = "",
    val model: String = "",
    val parametersJson: String = "{}",
    val errorMessage: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
) {
    fun toJson(redact: Boolean = true): JSONObject = JSONObject()
        .put("id", id)
        .put("mode", mode.wireName)
        .put("status", status.wireName)
        .put("prompt", prompt)
        .put("negative_prompt", negativePrompt)
        .put("input_media", inputMedia.toJsonArray { it.toJson() })
        .put("assets", assets.toJsonArray { it.toJson() })
        .put("attempts", attempts.toJsonArray { it.toJson(redact) })
        .put("channel_id", channelId)
        .put("channel_name", channelName)
        .put("provider_type", providerType)
        .put("model", model)
        .put("parameters", parseJsonObject(parametersJson))
        .put("error_message", if (redact) Redaction.redactText(errorMessage) else errorMessage)
        .put("created_at", createdAt)
        .put("updated_at", updatedAt)
        .putNullable("started_at", startedAt)
        .putNullable("completed_at", completedAt)

    fun toRecord(recordedAt: Long = System.currentTimeMillis()): GenerationRecord {
        return GenerationRecord.fromTask(this, recordedAt)
    }

    companion object {
        fun fromJson(value: JSONObject): GenerationTask = GenerationTask(
            id = value.optCleanId(),
            mode = GenerationMode.fromWireName(value.optString("mode", "")),
            status = GenerationStatus.fromWireName(value.optString("status", "")),
            prompt = value.optString("prompt", ""),
            negativePrompt = value.optString("negative_prompt", ""),
            inputMedia = value.optJsonObjectList("input_media") { MediaReference.fromJson(it) },
            assets = value.optJsonObjectList("assets") { GeneratedAsset.fromJson(it) },
            attempts = value.optJsonObjectList("attempts") { AttemptRecord.fromJson(it) },
            channelId = value.optString("channel_id", ""),
            channelName = value.optString("channel_name", ""),
            providerType = value.optString("provider_type", ""),
            model = value.optString("model", ""),
            parametersJson = value.optJSONObject("parameters")?.toString() ?: value.optString("parameters_json", "{}"),
            errorMessage = value.optString("error_message", ""),
            createdAt = value.optLong("created_at", System.currentTimeMillis()),
            updatedAt = value.optLong("updated_at", System.currentTimeMillis()),
            startedAt = value.optNullableLong("started_at"),
            completedAt = value.optNullableLong("completed_at"),
        )
    }
}

data class GenerationRecord(
    val id: String = UUID.randomUUID().toString(),
    val taskId: String = "",
    val mode: GenerationMode = GenerationMode.IMAGE,
    val status: GenerationStatus = GenerationStatus.QUEUED,
    val prompt: String = "",
    val negativePrompt: String = "",
    val inputMedia: List<MediaReference> = emptyList(),
    val assets: List<GeneratedAsset> = emptyList(),
    val attempts: List<AttemptRecord> = emptyList(),
    val channelId: String = "",
    val channelName: String = "",
    val providerType: String = "",
    val model: String = "",
    val parametersJson: String = "{}",
    val errorMessage: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val recordedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(redact: Boolean = true): JSONObject = JSONObject()
        .put("id", id)
        .put("task_id", taskId)
        .put("mode", mode.wireName)
        .put("status", status.wireName)
        .put("prompt", prompt)
        .put("negative_prompt", negativePrompt)
        .put("input_media", inputMedia.toJsonArray { it.toJson() })
        .put("assets", assets.toJsonArray { it.toJson() })
        .put("attempts", attempts.toJsonArray { it.toJson(redact) })
        .put("channel_id", channelId)
        .put("channel_name", channelName)
        .put("provider_type", providerType)
        .put("model", model)
        .put("parameters", parseJsonObject(parametersJson))
        .put("error_message", if (redact) Redaction.redactText(errorMessage) else errorMessage)
        .put("created_at", createdAt)
        .put("updated_at", updatedAt)
        .putNullable("started_at", startedAt)
        .putNullable("completed_at", completedAt)
        .put("recorded_at", recordedAt)

    companion object {
        fun fromTask(task: GenerationTask, recordedAt: Long = System.currentTimeMillis()): GenerationRecord {
            return GenerationRecord(
                taskId = task.id,
                mode = task.mode,
                status = task.status,
                prompt = task.prompt,
                negativePrompt = task.negativePrompt,
                inputMedia = task.inputMedia,
                assets = task.assets,
                attempts = task.attempts,
                channelId = task.channelId,
                channelName = task.channelName,
                providerType = task.providerType,
                model = task.model,
                parametersJson = task.parametersJson,
                errorMessage = task.errorMessage,
                createdAt = task.createdAt,
                updatedAt = task.updatedAt,
                startedAt = task.startedAt,
                completedAt = task.completedAt,
                recordedAt = recordedAt,
            )
        }

        fun fromJson(value: JSONObject): GenerationRecord = GenerationRecord(
            id = value.optCleanId(),
            taskId = value.optString("task_id", ""),
            mode = GenerationMode.fromWireName(value.optString("mode", "")),
            status = GenerationStatus.fromWireName(value.optString("status", "")),
            prompt = value.optString("prompt", ""),
            negativePrompt = value.optString("negative_prompt", ""),
            inputMedia = value.optJsonObjectList("input_media") { MediaReference.fromJson(it) },
            assets = value.optJsonObjectList("assets") { GeneratedAsset.fromJson(it) },
            attempts = value.optJsonObjectList("attempts") { AttemptRecord.fromJson(it) },
            channelId = value.optString("channel_id", ""),
            channelName = value.optString("channel_name", ""),
            providerType = value.optString("provider_type", ""),
            model = value.optString("model", ""),
            parametersJson = value.optJSONObject("parameters")?.toString() ?: value.optString("parameters_json", "{}"),
            errorMessage = value.optString("error_message", ""),
            createdAt = value.optLong("created_at", System.currentTimeMillis()),
            updatedAt = value.optLong("updated_at", System.currentTimeMillis()),
            startedAt = value.optNullableLong("started_at"),
            completedAt = value.optNullableLong("completed_at"),
            recordedAt = value.optLong("recorded_at", System.currentTimeMillis()),
        )
    }
}

private fun JSONObject.putNullable(name: String, value: Any?): JSONObject {
    if (value != null) put(name, value)
    return this
}

private fun JSONObject.optCleanId(): String = optString("id", "").ifBlank { UUID.randomUUID().toString() }

private fun JSONObject.optNullableInt(name: String): Int? {
    return if (has(name) && !isNull(name)) optInt(name) else null
}

private fun JSONObject.optNullableLong(name: String): Long? {
    return if (has(name) && !isNull(name)) optLong(name) else null
}

private fun <T> JSONObject.optJsonObjectList(name: String, mapper: (JSONObject) -> T): List<T> {
    val array = optJSONArray(name) ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            add(mapper(item))
        }
    }
}

private fun <T> List<T>.toJsonArray(mapper: (T) -> JSONObject): JSONArray {
    val array = JSONArray()
    forEach { array.put(mapper(it)) }
    return array
}

private fun parseJsonObject(raw: String): JSONObject {
    val trimmed = raw.trim().ifBlank { "{}" }
    return runCatching { JSONObject(trimmed) }.getOrElse {
        JSONObject().put("_raw", raw)
    }
}
