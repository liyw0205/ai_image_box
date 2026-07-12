package com.aiimagebox.provider

import com.aiimagebox.data.ModelTarget
import com.aiimagebox.data.ProviderChannel
import com.aiimagebox.data.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONTokener
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

class GenericAsyncVideoAdapter(
    private val baseClient: OkHttpClient = OkHttpClient(),
) : ProviderAdapter {
    override val type: String = TYPE
    override val aliases: Set<String> = setOf("openai_compatible_video", "grok_video", "seedance_video")

    override suspend fun listModels(channel: ProviderChannel): ModelListResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val apiKey = decryptApiKey(channel)
        val secrets = listOf(apiKey)
        val url = endpoint(channel.baseUrl, "/v1/models")
            ?: return@withContext ModelListResult(
                error = ProviderErrorLocalizer.localMessage("Invalid base URL: ${safeBaseUrl(channel.baseUrl)}"),
            )
        val httpRequest = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .applyAuth(apiKey)
            .build()

        runCatching {
            clientFor(channel.timeoutSeconds, channel.proxy).newCall(httpRequest).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                val preview = ResponseParser.preview(bodyText, secrets)
                val requestId = requestId(response.headers["x-request-id"].orEmpty(), bodyText)
                val elapsed = System.currentTimeMillis() - startedAt
                if (!response.isSuccessful) {
                    return@withContext ModelListResult(
                        requestId = requestId,
                        httpStatus = response.code,
                        error = ProviderErrorLocalizer.httpError(response.code, ResponseParser.errorMessage(bodyText, secrets)),
                        rawPreview = preview,
                        elapsedMillis = elapsed,
                    )
                }
                ModelListResult(
                    models = ResponseParser.parseModels(bodyText, secrets),
                    requestId = requestId,
                    httpStatus = response.code,
                    rawPreview = preview,
                    elapsedMillis = elapsed,
                )
            }
        }.getOrElse { error ->
            ModelListResult(
                error = ResponseParser.preview(ProviderErrorLocalizer.networkError(error), secrets),
                elapsedMillis = System.currentTimeMillis() - startedAt,
            )
        }
    }

    override suspend fun generate(
        channel: ProviderChannel,
        request: GenerationRequest,
        target: ModelTarget,
    ): GenerationResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val apiKey = decryptApiKey(channel)
        val secrets = listOf(apiKey)
        if (request.mode != GenerationMode.TEXT_TO_VIDEO && request.mode != GenerationMode.IMAGE_TO_VIDEO) {
            return@withContext failedResult(channel, target, "Video adapter only supports TEXT_TO_VIDEO and IMAGE_TO_VIDEO.", startedAt)
        }
        if (request.prompt.isBlank()) {
            return@withContext failedResult(channel, target, "Prompt is required.", startedAt)
        }

        val extra = channel.extraObject()
        val resumeJob = request.resumeJob
        if (resumeJob != null) {
            val job = resumeJob
            request.onJobUpdated(job)
            val pollHost = job.pollUrl.toHttpUrlOrNull()?.host
                ?: target.baseUrl.ifBlank { channel.baseUrl }.toHttpUrlOrNull()?.host
                ?: return@withContext failedResult(channel, target, "Invalid video poll URL for job " + job.id + ".", startedAt)
            return@withContext pollUntilComplete(
                channel, target, request, clientFor(target.timeoutSeconds, target.proxy), apiKey, pollHost,
                job, startedAt, 200, job.id, job.rawPreview,
            )
        }
        val submitPath = request.extra.optString("submit_path", "")
            .ifBlank { extra.optString("video_submit_path", "") }
            .ifBlank { "/v1/videos/generations" }
        val submitUrl = endpoint(target.baseUrl.ifBlank { channel.baseUrl }, submitPath)
            ?: return@withContext failedResult(channel, target, "Invalid base URL: ${safeBaseUrl(target.baseUrl.ifBlank { channel.baseUrl })}", startedAt)
        val client = clientFor(target.timeoutSeconds, target.proxy)
        val httpRequest = Request.Builder()
            .url(submitUrl)
            .post(videoPayload(request, target.model).toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .applyAuth(apiKey)
            .build()

        runCatching {
            client.newCall(httpRequest).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                val preview = ResponseParser.preview(bodyText, secrets)
                val submitRequestId = requestId(response.header("x-request-id").orEmpty(), bodyText)
                if (!response.isSuccessful) {
                    return@withContext failedResult(
                        channel = channel,
                        target = target,
                        error = ProviderErrorLocalizer.httpError(response.code, ResponseParser.errorMessage(bodyText, secrets)),
                        startedAt = startedAt,
                        httpStatus = response.code,
                        requestId = submitRequestId,
                        rawPreview = preview,
                    )
                }

                val immediate = downloadVideos(ResponseParser.parseVideoCandidates(bodyText, submitUrl.toString()), client, apiKey, submitUrl.host)
                if (immediate.isNotEmpty()) {
                    val elapsed = System.currentTimeMillis() - startedAt
                    return@withContext GenerationResult(
                        status = GenerationStatus.SUCCEEDED,
                        videos = immediate,
                        usedModel = target.model,
                        requestId = submitRequestId,
                        httpStatus = response.code,
                        rawPreview = preview,
                        elapsedMillis = elapsed,
                        attempts = listOf(successAttempt(channel, target, response.code, elapsed, submitRequestId, preview)),
                    )
                }

                val job = parseJob(bodyText, submitUrl, extra)
                    ?: return@withContext failedResult(
                        channel = channel,
                        target = target,
                        error = "Video submit response did not include video URL or job id.",
                        startedAt = startedAt,
                        httpStatus = response.code,
                        requestId = submitRequestId,
                        rawPreview = preview,
                    )
                request.onJobUpdated(job)
                pollUntilComplete(channel, target, request, client, apiKey, submitUrl.host, job, startedAt, response.code, submitRequestId, preview)
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            failedResult(
                channel = channel,
                target = target,
                error = ResponseParser.preview(ProviderErrorLocalizer.networkError(error), secrets),
                startedAt = startedAt,
            )
        }
    }

    override suspend fun poll(job: ProviderJob, target: ModelTarget): GenerationResult {
        return GenerationResult(
            status = GenerationStatus.RUNNING,
            job = job,
            usedModel = target.model,
            rawPreview = job.rawPreview,
        )
    }

    override fun capabilities(channel: ProviderChannel): ProviderCapabilities {
        return ProviderCapabilities(
            textToImage = false,
            imageToImage = false,
            textToVideo = true,
            imageToVideo = true,
            synchronous = false,
            asyncJob = true,
            supportsModelList = true,
            supportsCancel = false,
        )
    }

    private suspend fun pollUntilComplete(
        channel: ProviderChannel,
        target: ModelTarget,
        request: GenerationRequest,
        client: OkHttpClient,
        apiKey: String,
        apiHost: String,
        initialJob: ProviderJob,
        startedAt: Long,
        submitHttpStatus: Int,
        submitRequestId: String,
        submitPreview: String,
    ): GenerationResult {
        val secrets = listOf(apiKey)
        val extra = channel.extraObject()
        val maxPolls = request.extra.optInt("max_polls", extra.optInt("video_max_polls", DEFAULT_MAX_POLLS)).coerceIn(1, 300)
        val intervalMs = request.extra.optLong("poll_interval_ms", extra.optLong("video_poll_interval_ms", DEFAULT_POLL_INTERVAL_MS))
            .coerceIn(1_000L, 60_000L)
        var job = initialJob
        var lastHttpStatus: Int? = submitHttpStatus
        var lastRequestId = submitRequestId
        var lastPreview = submitPreview
        repeat(maxPolls) { index ->
            if (index > 0) delay(intervalMs)
            val pollUrl = job.pollUrl.toHttpUrlOrNull()
                ?: return failedResult(channel, target, "Invalid video poll URL for job ${job.id}.", startedAt, lastHttpStatus, lastRequestId, lastPreview)
            val httpRequest = Request.Builder()
                .url(pollUrl)
                .get()
                .header("Accept", "application/json")
                .applyAuth(apiKey)
                .build()
            client.newCall(httpRequest).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                lastHttpStatus = response.code
                lastRequestId = requestId(response.header("x-request-id").orEmpty(), bodyText).ifBlank { lastRequestId }
                lastPreview = ResponseParser.preview(bodyText, secrets)
                if (!response.isSuccessful) {
                    return failedResult(
                        channel,
                        target,
                        ProviderErrorLocalizer.httpError(response.code, ResponseParser.errorMessage(bodyText, secrets)),
                        startedAt,
                        response.code,
                        lastRequestId,
                        lastPreview,
                    )
                }
                val videos = downloadVideos(ResponseParser.parseVideoCandidates(bodyText, pollUrl.toString()), client, apiKey, apiHost)
                if (videos.isNotEmpty() || isSucceeded(bodyText)) {
                    val finalVideos = videos.ifEmpty {
                        val resultCandidates = job.resultUrl.takeIf { it.isNotBlank() }
                            ?.let { listOf(ParsedImageCandidate(ParsedImageKind.URL, it)) }
                            .orEmpty()
                        downloadVideos(resultCandidates, client, apiKey, apiHost)
                    }
                    if (finalVideos.isNotEmpty()) {
                        val elapsed = System.currentTimeMillis() - startedAt
                        return GenerationResult(
                            status = GenerationStatus.SUCCEEDED,
                            videos = finalVideos,
                            job = job.copy(rawPreview = lastPreview),
                            usedModel = target.model,
                            requestId = lastRequestId,
                            httpStatus = response.code,
                            rawPreview = lastPreview,
                            elapsedMillis = elapsed,
                            attempts = listOf(successAttempt(channel, target, response.code, elapsed, lastRequestId, lastPreview, job)),
                        )
                    }
                }
                if (isFailed(bodyText)) {
                    return failedResult(
                        channel,
                        target,
                        ResponseParser.errorMessage(bodyText, secrets).ifBlank { "Video job failed: ${job.id}" },
                        startedAt,
                        response.code,
                        lastRequestId,
                        lastPreview,
                        job,
                    )
                }
                job = parseJob(bodyText, pollUrl, extra) ?: job.copy(rawPreview = lastPreview)
                request.onJobUpdated(job)
            }
        }
        return failedResult(channel, target, "Video job polling timed out: ${initialJob.id}", startedAt, lastHttpStatus, lastRequestId, lastPreview, job)
    }

    private fun videoPayload(request: GenerationRequest, model: String): JSONObject {
        val payload = JSONObject()
            .put("model", model)
            .put("prompt", request.prompt)
            .put("n", request.count.coerceIn(1, 4))
            .put("aspect_ratio", request.aspectRatio.ifBlank { "1:1" })
            .put("resolution", request.resolution.ifBlank { "720p" })
        request.durationSeconds?.let { payload.put("duration", it) }
        if (request.negativePrompt.isNotBlank()) payload.put("negative_prompt", request.negativePrompt)
        request.references.firstOrNull()?.let { reference ->
            val mime = reference.mimeType.ifBlank { "image/png" }
            val encoded = android.util.Base64.encodeToString(reference.bytes, android.util.Base64.NO_WRAP)
            payload.put("image", "data:$mime;base64,$encoded")
        }
        val keys = request.extra.keys()
        val internalKeys = setOf("submit_path", "max_polls", "poll_interval_ms", "mode")
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in internalKeys) payload.put(key, request.extra.opt(key))
        }
        return payload
    }

    private fun parseJob(raw: String, currentUrl: HttpUrl, extra: JSONObject): ProviderJob? {
        val root = runCatching { JSONTokener(raw).nextValue() }.getOrNull()
        val obj = when (root) {
            is JSONObject -> root
            else -> return null
        }
        val data = obj.optJSONObject("data") ?: obj.optJSONObject("job") ?: obj.optJSONObject("task") ?: obj
        val id = listOf("job_id", "jobId", "task_id", "taskId", "id", "request_id")
            .firstNotNullOfOrNull { key -> data.optString(key, "").trim().takeIf { it.isNotBlank() } }
            ?: return null
        val pollUrl = listOf("poll_url", "pollUrl", "status_url", "statusUrl", "self")
            .firstNotNullOfOrNull { key -> data.optString(key, "").trim().takeIf { it.isNotBlank() } }
            ?.let { absoluteUrl(it, currentUrl) }
            ?: pollUrlFor(id, currentUrl, extra)
        val resultUrl = listOf("result_url", "resultUrl", "download_url", "downloadUrl", "video_url", "videoUrl")
            .firstNotNullOfOrNull { key -> data.optString(key, "").trim().takeIf { it.isNotBlank() } }
            ?.let { absoluteUrl(it, currentUrl) }
            .orEmpty()
        return ProviderJob(id = id, pollUrl = pollUrl, resultUrl = resultUrl, rawPreview = ResponseParser.preview(raw))
    }

    private fun pollUrlFor(jobId: String, currentUrl: HttpUrl, extra: JSONObject): String {
        val template = extra.optString("video_poll_path_template", "")
            .ifBlank { extra.optString("poll_path_template", "") }
        if (template.isNotBlank()) {
            return absoluteUrl(template.replace("{job_id}", jobId).replace("{id}", jobId), currentUrl)
        }
        return currentUrl.newBuilder("/v1/videos/generations/$jobId")?.build()?.toString().orEmpty()
    }

    private fun downloadVideos(
        candidates: List<ParsedImageCandidate>,
        client: OkHttpClient,
        apiKey: String,
        apiHost: String,
    ): List<GeneratedAsset> {
        return candidates.mapNotNull { candidate ->
            runCatching {
                when (candidate.kind) {
                    ParsedImageKind.B64_JSON, ParsedImageKind.DATA_URL -> {
                        val bytes = ResponseParser.decodeBase64Image(candidate.value)
                        GeneratedAsset(
                            bytes = bytes,
                            mimeType = ResponseParser.guessVideoMimeType(bytes, candidate.mimeType.ifBlank { "video/mp4" }),
                            source = if (candidate.kind == ParsedImageKind.DATA_URL) GeneratedAssetSource.DATA_URL else GeneratedAssetSource.B64_JSON,
                        )
                    }
                    ParsedImageKind.URL -> downloadVideo(client, candidate.value, apiKey, apiHost)
                }
            }.getOrNull()
        }
    }

    private fun downloadVideo(client: OkHttpClient, videoUrl: String, apiKey: String, apiHost: String): GeneratedAsset {
        val parsed = videoUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid video URL")
        val request = Request.Builder()
            .url(parsed)
            .get()
            .header("Accept", "video/*,*/*;q=0.8")
            .apply { if (parsed.host == apiHost) applyAuth(apiKey) }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Video download HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("Video download response has no body")
            val contentLength = body.contentLength()
            if (contentLength > MAX_VIDEO_BYTES) throw IllegalStateException("Video exceeds max size: $contentLength")
            val bytes = body.bytes()
            if (bytes.size.toLong() > MAX_VIDEO_BYTES) throw IllegalStateException("Video exceeds max size: ${bytes.size}")
            val contentType = response.header("Content-Type").orEmpty().substringBefore(';').trim()
            val mimeType = ResponseParser.guessVideoMimeType(bytes, contentType.ifBlank { "video/mp4" })
            if (!mimeType.startsWith("video/") && mimeType != "application/vnd.apple.mpegurl") {
                throw IllegalStateException("Downloaded content is not a video")
            }
            return GeneratedAsset(bytes = bytes, mimeType = mimeType, source = GeneratedAssetSource.URL, remoteUrl = videoUrl)
        }
    }

    private fun successAttempt(
        channel: ProviderChannel,
        target: ModelTarget,
        httpStatus: Int?,
        elapsedMillis: Long,
        requestId: String,
        rawPreview: String,
        job: ProviderJob? = null,
    ): AttemptRecord {
        return AttemptRecord(
            providerType = target.providerType,
            channelId = target.channelId.ifBlank { channel.id },
            channelName = target.channelName.ifBlank { channel.name },
            model = target.model,
            httpStatus = httpStatus,
            elapsedMillis = elapsedMillis,
            requestId = requestId.ifBlank { job?.id.orEmpty() },
            rawPreview = rawPreview,
        )
    }

    private fun failedResult(
        channel: ProviderChannel,
        target: ModelTarget,
        error: String,
        startedAt: Long,
        httpStatus: Int? = null,
        requestId: String = "",
        rawPreview: String = "",
        job: ProviderJob? = null,
    ): GenerationResult {
        val elapsed = System.currentTimeMillis() - startedAt
        return GenerationResult(
            status = GenerationStatus.FAILED,
            job = job,
            usedModel = target.model,
            requestId = requestId.ifBlank { job?.id.orEmpty() },
            httpStatus = httpStatus,
            error = ProviderErrorLocalizer.localMessage(error),
            rawPreview = rawPreview,
            elapsedMillis = elapsed,
            attempts = listOf(
                AttemptRecord(
                    providerType = target.providerType,
                    channelId = target.channelId.ifBlank { channel.id },
                    channelName = target.channelName.ifBlank { channel.name },
                    model = target.model,
                    httpStatus = httpStatus,
                    elapsedMillis = elapsed,
                    requestId = requestId.ifBlank { job?.id.orEmpty() },
                    error = ProviderErrorLocalizer.localMessage(error),
                    rawPreview = rawPreview,
                ),
            ),
        )
    }

    private fun isSucceeded(raw: String): Boolean {
        val status = statusValue(raw)
        return status in setOf("succeeded", "success", "completed", "complete", "done", "finished")
    }

    private fun isFailed(raw: String): Boolean {
        val status = statusValue(raw)
        return status in setOf("failed", "failure", "error", "cancelled", "canceled", "expired")
    }

    private fun statusValue(raw: String): String {
        val obj = runCatching { JSONTokener(raw).nextValue() as? JSONObject }.getOrNull() ?: return ""
        val data = obj.optJSONObject("data") ?: obj.optJSONObject("job") ?: obj.optJSONObject("task") ?: obj
        return listOf("status", "state", "phase")
            .firstNotNullOfOrNull { key -> data.optString(key, "").trim().lowercase().takeIf { it.isNotBlank() } }
            .orEmpty()
    }

    private fun requestId(headerValue: String, bodyText: String): String {
        if (headerValue.isNotBlank()) return headerValue
        val obj = runCatching { JSONTokener(bodyText).nextValue() as? JSONObject }.getOrNull() ?: return ""
        return listOf("request_id", "requestId", "id", "job_id", "task_id")
            .firstNotNullOfOrNull { key -> obj.optString(key, "").trim().takeIf { it.isNotBlank() } }
            .orEmpty()
    }

    private fun absoluteUrl(value: String, currentUrl: HttpUrl): String {
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        if (value.startsWith("/")) return currentUrl.newBuilder(value)?.build()?.toString().orEmpty()
        return currentUrl.resolve(value)?.toString().orEmpty()
    }

    private fun ProviderChannel.extraObject(): JSONObject {
        return runCatching { JSONObject(extraJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
    }

    private fun clientFor(timeoutSeconds: Int, proxy: String): OkHttpClient {
        val builder = baseClient.newBuilder()
            .callTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .connectTimeout(timeoutSeconds.coerceAtMost(60).toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        parseProxy(proxy)?.let { builder.proxy(it) }
        return builder.build()
    }

    private fun parseProxy(raw: String): Proxy? {
        val clean = raw.trim()
        if (clean.isBlank()) return null
        return runCatching {
            val normalized = if ("://" in clean) clean else "http://$clean"
            val uri = URI(normalized)
            val host = uri.host ?: return@runCatching null
            val type = when (uri.scheme?.lowercase()) {
                "socks", "socks4", "socks5" -> Proxy.Type.SOCKS
                else -> Proxy.Type.HTTP
            }
            val port = if (uri.port > 0) uri.port else if (type == Proxy.Type.SOCKS) 1080 else 8080
            Proxy(type, InetSocketAddress(host, port))
        }.getOrNull()
    }

    private fun endpoint(baseUrl: String, path: String): HttpUrl? {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isBlank()) return null
        val finalUrl = if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else if (base.endsWith("/v1") && path.startsWith("/v1/")) {
            base + path.removePrefix("/v1")
        } else {
            base + if (path.startsWith("/")) path else "/$path"
        }
        return finalUrl.toHttpUrlOrNull()
    }

    private fun decryptApiKey(channel: ProviderChannel): String {
        return runCatching { SecureKeyStore.decrypt(channel.apiKey).trim() }.getOrDefault("")
    }

    private fun Request.Builder.applyAuth(apiKey: String): Request.Builder {
        if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
        return this
    }

    private fun safeBaseUrl(baseUrl: String): String {
        return ResponseParser.redact(baseUrl).substringBefore('?')
    }

    companion object {
        const val TYPE = "generic_async_video"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val DEFAULT_MAX_POLLS = 60
        private const val DEFAULT_POLL_INTERVAL_MS = 5_000L
        private const val MAX_VIDEO_BYTES = 100L * 1024L * 1024L
    }
}
