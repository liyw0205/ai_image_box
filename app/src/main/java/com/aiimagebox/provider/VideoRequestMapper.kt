package com.aiimagebox.provider

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

internal object VideoRequestMapper {
    fun payload(profile: String, model: String, request: GenerationRequest): JSONObject {
        val normalized = profile.trim().lowercase()
        return when (normalized) {
            "seedance", "ark", "volcengine" -> seedancePayload(model, request)
            else -> standardPayload(model, request)
        }.also { payload -> mergeOverrides(payload, request.extra) }
    }

    private fun standardPayload(model: String, request: GenerationRequest): JSONObject {
        return JSONObject()
            .put("model", model)
            .put("prompt", request.prompt)
            .put("n", request.count.coerceIn(1, 4))
            .put("aspect_ratio", request.aspectRatio.ifBlank { "1:1" })
            .put("resolution", request.resolution.ifBlank { "720p" })
            .apply {
                request.durationSeconds?.let { put("duration", it) }
                if (request.negativePrompt.isNotBlank()) put("negative_prompt", request.negativePrompt)
                request.references.firstOrNull()?.let { put("image", dataUrl(it)) }
            }
    }

    private fun seedancePayload(model: String, request: GenerationRequest): JSONObject {
        val parameters = buildList {
            add(request.prompt)
            if (request.aspectRatio.isNotBlank()) add("--ratio ${request.aspectRatio}")
            if (request.resolution.isNotBlank()) add("--resolution ${request.resolution}")
            request.durationSeconds?.let { add("--duration $it") }
            request.seed?.let { add("--seed $it") }
        }.joinToString(" ")
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", parameters))
        request.references.firstOrNull()?.let { reference ->
            content.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", dataUrl(reference)))
            )
        }
        return JSONObject().put("model", model).put("content", content)
    }

    private fun mergeOverrides(payload: JSONObject, extra: JSONObject) {
        val internalKeys = setOf(
            "submit_path", "max_polls", "poll_interval_ms", "mode", "video_provider",
            "video_submit_path", "video_poll_path_template", "video_max_polls", "video_poll_interval_ms",
            "poll_path_template", "model_types",
        )
        val keys = extra.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in internalKeys) payload.put(key, extra.opt(key))
        }
    }

    private fun dataUrl(reference: MediaReference): String {
        val mime = reference.mimeType.ifBlank { "image/png" }
        return "data:$mime;base64,${Base64.getEncoder().encodeToString(reference.bytes)}"
    }
}
