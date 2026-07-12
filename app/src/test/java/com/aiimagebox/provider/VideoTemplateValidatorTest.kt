package com.aiimagebox.provider

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTemplateValidatorTest {
    @Test
    fun acceptsBuiltInGrokAndSeedanceTemplates() {
        val grok = JSONObject()
            .put("video_provider", "grok")
            .put("video_submit_path", "/v1/videos/generations")
            .put("video_poll_path_template", "/v1/videos/generations/{id}")
            .put("model_types", JSONObject().put("grok-imagine-video", "grok_video"))
        val seedance = JSONObject()
            .put("video_provider", "seedance")
            .put("video_submit_path", "/api/v3/contents/generations/tasks")
            .put("video_poll_path_template", "/api/v3/contents/generations/tasks/{id}")
            .put("model_types", JSONObject().put("seedance", "seedance_video"))

        assertTrue(VideoTemplateValidator.validate("https://api.x.ai", listOf("grok-imagine-video"), grok.toString()).valid)
        assertTrue(VideoTemplateValidator.validate("https://ark.cn-beijing.volces.com", listOf("seedance"), seedance.toString()).valid)
    }

    @Test
    fun rejectsUnknownProviderAndInvalidPollTemplate() {
        val unknownProvider = JSONObject()
            .put("video_provider", "other")
            .put("model_types", JSONObject().put("video", "grok_video"))
        val invalidPoll = JSONObject()
            .put("video_provider", "grok")
            .put("video_submit_path", "/submit")
            .put("video_poll_path_template", "/poll/no-placeholder")
            .put("model_types", JSONObject().put("video", "grok_video"))

        assertFalse(VideoTemplateValidator.validate("https://example.test", listOf("video"), unknownProvider.toString()).valid)
        assertFalse(VideoTemplateValidator.validate("https://example.test", listOf("video"), invalidPoll.toString()).valid)
    }

    @Test
    fun ignoresImageOnlyChannelsAndAllowsGenericVideoDefaults() {
        val imageOnly = JSONObject().put("model_types", JSONObject().put("image", "openai_compatible_image"))
        val genericVideo = JSONObject().put("model_types", JSONObject().put("video", "openai_compatible_video"))

        assertTrue(VideoTemplateValidator.validate("https://example.test", listOf("image"), imageOnly.toString()).valid)
        assertTrue(VideoTemplateValidator.validate("https://example.test", listOf("video"), genericVideo.toString()).valid)
    }
}
