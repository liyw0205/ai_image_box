package com.aiimagebox.provider

import org.json.JSONObject

internal object VideoTemplateValidator {
    data class Result(val key: String = "", val message: String = "") {
        val valid: Boolean get() = key.isBlank()
    }

    fun validate(baseUrl: String, enabledModels: List<String>, extraJson: String): Result {
        val extra = runCatching { JSONObject(extraJson.ifBlank { "{}" }) }.getOrElse {
            return Result("extra", "扩展 JSON 格式不正确")
        }
        val modelTypes = extra.optJSONObject("model_types")
        val videoModels = enabledModels.filter { model ->
            modelTypes?.optString(model, "")?.contains("video", ignoreCase = true) == true
        }
        if (videoModels.isEmpty()) return Result()

        val provider = extra.optString("video_provider", "").trim().lowercase()
        if (provider.isNotBlank() && provider !in setOf("grok", "seedance", "ark", "volcengine")) {
            return Result("video_provider", "未知 video_provider：$provider")
        }
        val submitPath = extra.optString("video_submit_path", "").trim()
        val pollTemplate = extra.optString("video_poll_path_template", "").trim()
        if (provider in setOf("grok", "seedance", "ark", "volcengine") && submitPath.isBlank()) {
            return Result("video_submit_path", "视频模板需要 video_submit_path")
        }
        if (provider in setOf("grok", "seedance", "ark", "volcengine") && pollTemplate.isBlank()) {
            return Result("video_poll_path_template", "视频模板需要 video_poll_path_template")
        }
        if (submitPath.isNotBlank() && !isPathOrUrl(submitPath)) {
            return Result("video_submit_path", "video_submit_path 必须是 / 路径或 http(s) URL")
        }
        if (pollTemplate.isNotBlank()) {
            if (!isPathOrUrl(pollTemplate)) return Result("video_poll_path_template", "video_poll_path_template 必须是 / 路径或 http(s) URL")
            if ("{id}" !in pollTemplate && "{job_id}" !in pollTemplate) {
                return Result("video_poll_path_template", "video_poll_path_template 必须包含 {id} 或 {job_id}")
            }
        }
        return Result()
    }

    private fun isPathOrUrl(value: String): Boolean {
        return value.startsWith("/") || value.startsWith("http://") || value.startsWith("https://")
    }
}
