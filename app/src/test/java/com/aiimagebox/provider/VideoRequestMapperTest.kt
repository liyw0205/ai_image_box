package com.aiimagebox.provider

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRequestMapperTest {
    @Test
    fun standardProfileUsesOpenAiStyleFieldsAndOverrides() {
        val payload = VideoRequestMapper.payload(
            profile = "grok",
            model = "grok-imagine-video",
            request = GenerationRequest(
                mode = GenerationMode.IMAGE_TO_VIDEO,
                prompt = "city at night",
                aspectRatio = "16:9",
                resolution = "720p",
                count = 2,
                durationSeconds = 8,
                references = listOf(MediaReference(byteArrayOf(1, 2, 3), "image/png")),
                extra = JSONObject().put("video_provider", "grok").put("fps", 24),
            ),
        )

        assertEquals("grok-imagine-video", payload.getString("model"))
        assertEquals("city at night", payload.getString("prompt"))
        assertEquals("16:9", payload.getString("aspect_ratio"))
        assertEquals(8, payload.getInt("duration"))
        assertEquals(24, payload.getInt("fps"))
        assertTrue(payload.getString("image").startsWith("data:image/png;base64,"))
        assertFalse(payload.has("video_provider"))
    }

    @Test
    fun seedanceProfileBuildsContentArrayAndPromptParameters() {
        val payload = VideoRequestMapper.payload(
            profile = "seedance",
            model = "seedance-1-0-pro-250528",
            request = GenerationRequest(
                mode = GenerationMode.IMAGE_TO_VIDEO,
                prompt = "camera circles the subject",
                aspectRatio = "9:16",
                resolution = "1080p",
                durationSeconds = 5,
                seed = 42,
                references = listOf(MediaReference(byteArrayOf(4, 5, 6), "image/jpeg")),
                extra = JSONObject().put("video_provider", "seedance"),
            ),
        )

        val content = payload.getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        val text = content.getJSONObject(0).getString("text")
        assertTrue(text.contains("--ratio 9:16"))
        assertTrue(text.contains("--resolution 1080p"))
        assertTrue(text.contains("--duration 5"))
        assertTrue(text.contains("--seed 42"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertTrue(content.getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,"))
        assertFalse(payload.has("prompt"))
    }
}
