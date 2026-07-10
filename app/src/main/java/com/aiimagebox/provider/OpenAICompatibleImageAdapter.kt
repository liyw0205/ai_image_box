package com.aiimagebox.provider

import com.aiimagebox.data.ModelTarget
import com.aiimagebox.data.ProviderChannel
import com.aiimagebox.data.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

class OpenAICompatibleImageAdapter(
    private val baseClient: OkHttpClient = OkHttpClient(),
) : ProviderAdapter {
    override val type: String = TYPE
    override val aliases: Set<String> = setOf("openai_image")

    override suspend fun listModels(channel: ProviderChannel): ModelListResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val apiKey = decryptApiKey(channel)
        val secrets = listOf(apiKey)
        val url = endpoint(channel.baseUrl, "/v1/models")
            ?: return@withContext ModelListResult(
                error = ProviderErrorLocalizer.localMessage("Invalid base URL: ${safeBaseUrl(channel.baseUrl)}"),
            )
        val client = clientFor(channel.timeoutSeconds, channel.proxy)
        val httpRequest = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .applyAuth(apiKey)
            .build()

        runCatching {
            client.newCall(httpRequest).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                val preview = ResponseParser.preview(bodyText, secrets)
                val requestId = response.header("x-request-id").orEmpty()
                    .ifBlank { response.header("openai-request-id").orEmpty() }
                val elapsed = System.currentTimeMillis() - startedAt

                if (!response.isSuccessful) {
                    val errorMessage = ResponseParser.errorMessage(bodyText, secrets)
                    return@withContext ModelListResult(
                        requestId = requestId,
                        httpStatus = response.code,
                        error = ProviderErrorLocalizer.httpError(response.code, errorMessage),
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
        if (request.mode != GenerationMode.TEXT_TO_IMAGE && request.mode != GenerationMode.IMAGE_TO_IMAGE) {
            return@withContext failedResult(
                channel = channel,
                target = target,
                error = ProviderErrorLocalizer.localMessage(
                    "OpenAI-compatible image adapter only supports TEXT_TO_IMAGE and IMAGE_TO_IMAGE.",
                ),
                startedAt = startedAt,
            )
        }
        if (request.prompt.isBlank()) {
            return@withContext failedResult(
                channel = channel,
                target = target,
                error = ProviderErrorLocalizer.localMessage("Prompt is required."),
                startedAt = startedAt,
            )
        }

        if (request.mode == GenerationMode.IMAGE_TO_IMAGE && request.references.isEmpty()) {
            return@withContext failedResult(
                channel = channel,
                target = target,
                error = ProviderErrorLocalizer.localMessage("IMAGE_TO_IMAGE requires at least one reference image."),
                startedAt = startedAt,
            )
        }

        val path = if (request.mode == GenerationMode.IMAGE_TO_IMAGE) {
            "/v1/images/edits"
        } else {
            "/v1/images/generations"
        }
        val url = endpoint(target.baseUrl.ifBlank { channel.baseUrl }, path)
            ?: return@withContext failedResult(
                channel = channel,
                target = target,
                error = ProviderErrorLocalizer.localMessage(
                    "Invalid base URL: ${safeBaseUrl(target.baseUrl.ifBlank { channel.baseUrl })}",
                ),
                startedAt = startedAt,
            )
        val client = clientFor(target.timeoutSeconds, target.proxy.ifBlank { channel.proxy })
        val payload = requestBody(request, target.model.ifBlank { channel.defaultModel })
        val httpRequest = Request.Builder()
            .url(url)
            .post(payload)
            .header("Accept", "application/json")
            .apply {
                if (request.mode == GenerationMode.TEXT_TO_IMAGE) {
                    header("Content-Type", "application/json")
                }
            }
            .applyAuth(apiKey)
            .build()

        runCatching {
            client.newCall(httpRequest).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                val preview = ResponseParser.preview(bodyText, secrets)
                val requestId = response.header("x-request-id").orEmpty()
                    .ifBlank { response.header("openai-request-id").orEmpty() }
                val elapsed = System.currentTimeMillis() - startedAt

                if (!response.isSuccessful) {
                    val errorMessage = ResponseParser.errorMessage(bodyText, secrets)
                    return@withContext failedResult(
                        channel = channel,
                        target = target,
                        error = ProviderErrorLocalizer.httpError(response.code, errorMessage),
                        startedAt = startedAt,
                        httpStatus = response.code,
                        requestId = requestId,
                        rawPreview = preview,
                    )
                }

                val candidates = ResponseParser.parseImageCandidates(bodyText, url.toString())
                if (candidates.isEmpty()) {
                    return@withContext failedResult(
                        channel = channel,
                        target = target,
                        error = ProviderErrorLocalizer.localMessage(
                            "Provider response did not contain b64_json or url image data.",
                        ),
                        startedAt = startedAt,
                        httpStatus = response.code,
                        requestId = requestId,
                        rawPreview = preview,
                    )
                }

                val downloads = candidates.mapNotNull { candidate ->
                    runCatching { candidate.toAsset(client, apiKey, url.host) }.getOrNull()
                }
                if (downloads.isEmpty()) {
                    return@withContext failedResult(
                        channel = channel,
                        target = target,
                        error = ProviderErrorLocalizer.localMessage(
                            "Provider returned image references, but all downloads or decodes failed.",
                        ),
                        startedAt = startedAt,
                        httpStatus = response.code,
                        requestId = requestId,
                        rawPreview = preview,
                    )
                }

                GenerationResult(
                    status = GenerationStatus.SUCCEEDED,
                    images = downloads,
                    usedModel = target.model,
                    requestId = requestId,
                    httpStatus = response.code,
                    rawPreview = preview,
                    elapsedMillis = elapsed,
                    attempts = listOf(
                        AttemptRecord(
                            providerType = target.providerType,
                            channelId = target.channelId.ifBlank { channel.id },
                            channelName = target.channelName.ifBlank { channel.name },
                            model = target.model,
                            httpStatus = response.code,
                            elapsedMillis = elapsed,
                            requestId = requestId,
                            rawPreview = preview,
                        ),
                    ),
                )
            }
        }.getOrElse { error ->
            failedResult(
                channel = channel,
                target = target,
                error = ResponseParser.preview(ProviderErrorLocalizer.networkError(error), secrets),
                startedAt = startedAt,
            )
        }
    }

    override fun capabilities(channel: ProviderChannel): ProviderCapabilities {
        return ProviderCapabilities(
            textToImage = true,
            imageToImage = true,
            textToVideo = false,
            imageToVideo = false,
            synchronous = true,
            asyncJob = false,
            supportsModelList = true,
            supportsCancel = false,
        )
    }

    private fun imagePayload(request: GenerationRequest, model: String): JSONObject {
        val payload = JSONObject()
            .put("model", model)
            .put("prompt", request.prompt)
            .put("n", request.count.coerceIn(1, 10))
            .put("size", imageSize(request))
            .put("response_format", request.responseFormat.apiValue)

        if (request.negativePrompt.isNotBlank()) payload.put("negative_prompt", request.negativePrompt)
        if (request.quality.isNotBlank()) payload.put("quality", request.quality)
        if (request.style.isNotBlank()) payload.put("style", request.style)
        request.seed?.let { payload.put("seed", it) }

        val keys = request.extra.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            payload.put(key, request.extra.opt(key))
        }
        return payload
    }

    private fun requestBody(request: GenerationRequest, model: String): RequestBody {
        if (request.mode == GenerationMode.IMAGE_TO_IMAGE) return imageEditPayload(request, model)
        return imagePayload(request, model).toString().toRequestBody(JSON_MEDIA_TYPE)
    }

    private fun imageEditPayload(request: GenerationRequest, model: String): RequestBody {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("prompt", request.prompt)
            .addFormDataPart("n", request.count.coerceIn(1, 10).toString())
            .addFormDataPart("size", imageSize(request))
            .addFormDataPart("response_format", request.responseFormat.apiValue)

        if (request.negativePrompt.isNotBlank()) builder.addFormDataPart("negative_prompt", request.negativePrompt)
        if (request.quality.isNotBlank()) builder.addFormDataPart("quality", request.quality)
        if (request.style.isNotBlank()) builder.addFormDataPart("style", request.style)
        request.seed?.let { builder.addFormDataPart("seed", it.toString()) }

        request.references.take(1).forEach { reference ->
            val mediaType = reference.mimeType.ifBlank { "image/png" }.toMediaType()
            val name = reference.name.ifBlank { "reference.png" }
            builder.addFormDataPart("image", name, reference.bytes.toRequestBody(mediaType))
        }
        return builder.build()
    }

    private fun imageSize(request: GenerationRequest): String {
        val resolution = request.resolution.trim()
        if (Regex("^\\d+x\\d+$").matches(resolution)) return resolution
        return when (request.aspectRatio.trim()) {
            "16:9", "landscape" -> "1792x1024"
            "9:16", "portrait" -> "1024x1792"
            "4:3" -> "1024x768"
            "3:4" -> "768x1024"
            else -> "1024x1024"
        }
    }

    private fun ParsedImageCandidate.toAsset(client: OkHttpClient, apiKey: String, apiHost: String): GeneratedAsset {
        return when (kind) {
            ParsedImageKind.B64_JSON, ParsedImageKind.DATA_URL -> {
                val bytes = ResponseParser.decodeBase64Image(value)
                GeneratedAsset(
                    bytes = bytes,
                    mimeType = ResponseParser.guessImageMimeType(bytes, mimeType),
                    source = if (kind == ParsedImageKind.DATA_URL) GeneratedAssetSource.DATA_URL else GeneratedAssetSource.B64_JSON,
                    revisedPrompt = revisedPrompt,
                )
            }
            ParsedImageKind.URL -> downloadAsset(client, value, apiKey, apiHost, revisedPrompt)
        }
    }

    private fun downloadAsset(
        client: OkHttpClient,
        imageUrl: String,
        apiKey: String,
        apiHost: String,
        revisedPrompt: String,
    ): GeneratedAsset {
        val parsed = imageUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid image URL")
        val request = Request.Builder()
            .url(parsed)
            .get()
            .header("Accept", "image/*,*/*;q=0.8")
            .apply {
                if (parsed.host == apiHost) applyAuth(apiKey)
            }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Image download HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("Image download response has no body")
            val contentLength = body.contentLength()
            if (contentLength > MAX_IMAGE_BYTES) {
                throw IllegalStateException("Image exceeds max size: $contentLength")
            }
            val bytes = body.bytes()
            if (bytes.size.toLong() > MAX_IMAGE_BYTES) throw IllegalStateException("Image exceeds max size: ${bytes.size}")

            val contentType = response.header("Content-Type").orEmpty().substringBefore(';').trim()
            val mimeType = ResponseParser.guessImageMimeType(bytes, contentType)
            if (!mimeType.startsWith("image/")) throw IllegalStateException("Downloaded content is not an image")
            return GeneratedAsset(
                bytes = bytes,
                mimeType = mimeType,
                source = GeneratedAssetSource.URL,
                remoteUrl = imageUrl,
                revisedPrompt = revisedPrompt,
            )
        }
    }

    private fun failedResult(
        channel: ProviderChannel,
        target: ModelTarget,
        error: String,
        startedAt: Long,
        httpStatus: Int? = null,
        requestId: String = "",
        rawPreview: String = "",
    ): GenerationResult {
        val elapsed = System.currentTimeMillis() - startedAt
        return GenerationResult(
            status = GenerationStatus.FAILED,
            usedModel = target.model,
            requestId = requestId,
            httpStatus = httpStatus,
            error = error,
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
                    requestId = requestId,
                    error = error,
                    rawPreview = rawPreview,
                ),
            ),
        )
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

    private fun endpoint(baseUrl: String, path: String): okhttp3.HttpUrl? {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isBlank()) return null
        val finalUrl = if (base.endsWith("/v1") && path.startsWith("/v1/")) {
            base + path.removePrefix("/v1")
        } else {
            base + path
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
        const val TYPE = "openai_compatible_image"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_IMAGE_BYTES = 30L * 1024L * 1024L
    }
}
