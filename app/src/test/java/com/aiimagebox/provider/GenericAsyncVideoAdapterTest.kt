package com.aiimagebox.provider

import com.aiimagebox.data.ModelTarget
import com.aiimagebox.data.ProviderChannel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericAsyncVideoAdapterTest {
    @Test
    fun submitPollAndDownloadFollowConfiguredContract() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("{\"id\":\"job-1\",\"status\":\"queued\"}"))
            server.enqueue(MockResponse().setBody("{\"id\":\"job-1\",\"status\":\"succeeded\",\"data\":[{\"video_url\":\"/video.mp4\"}]}"))
            val videoBytes = byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112, 105, 115, 111, 109)
            server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody(okio.Buffer().write(videoBytes)))

            var observedJob: ProviderJob? = null
            val baseUrl = server.url("/").toString().trimEnd('/')
            val channel = ProviderChannel(
                name = "mock",
                providerType = "grok_video",
                baseUrl = baseUrl,
                apiKey = null,
                defaultModel = "video-model",
                enabledModels = listOf("video-model"),
                extraJson = JSONObject()
                    .put("video_submit_path", "/submit")
                    .put("video_poll_path_template", "/poll/{id}")
                    .put("video_poll_interval_ms", 1_000)
                    .toString(),
            )
            val target = ModelTarget(channel.id, channel.name, "grok_video", baseUrl, "video-model", 10)
            val result = GenericAsyncVideoAdapter().generate(
                channel = channel,
                request = GenerationRequest(
                    mode = GenerationMode.TEXT_TO_VIDEO,
                    prompt = "orbiting camera",
                    durationSeconds = 6,
                    onJobUpdated = { observedJob = it },
                ),
                target = target,
            )

            assertEquals(GenerationStatus.SUCCEEDED, result.status)
            assertEquals(1, result.videos.size)
            assertArrayEquals(videoBytes, result.videos.single().bytes)
            assertNotNull(observedJob)

            val submit = server.takeRequest()
            assertEquals("/submit", submit.path)
            val payload = JSONObject(submit.body.readUtf8())
            assertEquals("video-model", payload.getString("model"))
            assertEquals(6, payload.getInt("duration"))
            assertEquals("orbiting camera", payload.getString("prompt"))
            assertEquals("/poll/job-1", server.takeRequest().path)
            assertEquals("/video.mp4", server.takeRequest().path)
            assertTrue(result.attempts.single().httpStatus == 200)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun submitFailureKeepsHttpStatusAndSanitizedPreview() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(429).setBody("{\"error\":{\"message\":\"retry later\",\"api_key\":\"never-show\"}}"))
            val baseUrl = server.url("/").toString().trimEnd('/')
            val channel = ProviderChannel(
                name = "mock",
                providerType = "grok_video",
                baseUrl = baseUrl,
                apiKey = null,
                defaultModel = "video-model",
                enabledModels = listOf("video-model"),
            )
            val target = ModelTarget(channel.id, channel.name, "grok_video", baseUrl, "video-model", 10)
            val result = GenericAsyncVideoAdapter().generate(channel, GenerationRequest(mode = GenerationMode.TEXT_TO_VIDEO, prompt = "test"), target)

            assertEquals(GenerationStatus.FAILED, result.status)
            assertEquals(429, result.httpStatus)
            assertTrue(result.rawPreview.contains("[REDACTED]"))
            assertTrue(result.error.contains("请求过于频繁"))
        } finally {
            server.shutdown()
        }
    }
}
