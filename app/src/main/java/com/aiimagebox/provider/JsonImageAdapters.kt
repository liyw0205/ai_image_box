package com.aiimagebox.provider

import android.util.Base64
import com.aiimagebox.data.ModelTarget
import com.aiimagebox.data.ProviderChannel
import com.aiimagebox.data.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

abstract class JsonImageAdapter(
    override val type: String,
    override val aliases: Set<String> = emptySet(),
    private val defaultBaseUrl: String = "",
    private val defaultModel: String = "",
    private val supportsReferenceImages: Boolean = false,
    private val baseClient: OkHttpClient = OkHttpClient(),
) : ProviderAdapter {
    override suspend fun listModels(channel: ProviderChannel): ModelListResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val apiKey = decryptApiKey(channel)
        val secrets = listOf(apiKey)
        val url = endpoint(channel.baseUrl.ifBlank { defaultBaseUrl }, "/v1/models")
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
                val requestId = requestId(response.headers)
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
        val model = target.model.ifBlank { channel.defaultModel }.ifBlank { defaultModel }

        if (request.mode != GenerationMode.TEXT_TO_IMAGE && request.mode != GenerationMode.IMAGE_TO_IMAGE) {
            return@withContext failedResult(
                target = target,
                error = "${target.providerType} only supports TEXT_TO_IMAGE and IMAGE_TO_IMAGE.",
                startedAt = startedAt,
            )
        }
        if (request.prompt.isBlank()) {
            return@withContext failedResult(
                target = target,
                error = ProviderErrorLocalizer.localMessage("Prompt is required."),
                startedAt = startedAt,
            )
        }
        if (request.mode == GenerationMode.IMAGE_TO_IMAGE) {
            if (!supportsReferenceImages) {
                return@withContext failedResult(
                    target = target,
                    error = "${target.providerType} does not support reference images yet.",
                    startedAt = startedAt,
                )
            }
            if (request.references.isEmpty()) {
                return@withContext failedResult(
                    target = target,
                    error = "IMAGE_TO_IMAGE requires at least one readable reference image.",
                    startedAt = startedAt,
                )
            }
        }

        val url = generationEndpoint(channel, target, model)
            ?: return@withContext failedResult(
                target = target,
                error = ProviderErrorLocalizer.localMessage(
                    "Invalid base URL: ${safeBaseUrl(target.baseUrl.ifBlank { channel.baseUrl }.ifBlank { defaultBaseUrl })}",
                ),
                startedAt = startedAt,
            )
        val client = clientFor(target.timeoutSeconds, target.proxy.ifBlank { channel.proxy })
        val payload = buildPayload(request, model)
        val httpRequest = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .applyGenerationAuth(apiKey)
            .build()

        runCatching {
            client.newCall(httpRequest).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                val preview = ResponseParser.preview(bodyText, secrets)
                val providerRequestId = requestId(response.headers)
                val elapsed = System.currentTimeMillis() - startedAt

                if (!response.isSuccessful) {
                    val errorMessage = ResponseParser.errorMessage(bodyText, secrets)
                    return@withContext failedResult(
                        target = target,
                        error = ProviderErrorLocalizer.httpError(response.code, errorMessage),
                        startedAt = startedAt,
                        httpStatus = response.code,
                        requestId = providerRequestId,
                        rawPreview = preview,
                    )
                }

                val candidates = ResponseParser.parseImageCandidates(bodyText, url.toString())
                if (candidates.isEmpty()) {
                    return@withContext failedResult(
                        target = target,
                        error = ProviderErrorLocalizer.localMessage(
                            "Provider response did not contain b64_json or url image data.",
                        ),
                        startedAt = startedAt,
                        httpStatus = response.code,
                        requestId = providerRequestId,
                        rawPreview = preview,
                    )
                }

                val downloads = candidates.mapNotNull { candidate ->
                    runCatching { candidate.toAsset(client, apiKey, url.host) }.getOrNull()
                }
                if (downloads.isEmpty()) {
                    return@withContext failedResult(
                        target = target,
                        error = ProviderErrorLocalizer.localMessage(
                            "Provider returned image references, but all downloads or decodes failed.",
                        ),
                        startedAt = startedAt,
                        httpStatus = response.code,
                        requestId = providerRequestId,
                        rawPreview = preview,
                    )
                }

                GenerationResult(
                    status = GenerationStatus.SUCCEEDED,
                    images = downloads,
                    usedModel = model,
                    requestId = providerRequestId,
                    httpStatus = response.code,
                    rawPreview = preview,
                    elapsedMillis = elapsed,
                    attempts = listOf(
                        AttemptRecord(
                            providerType = target.providerType,
                            channelId = target.channelId,
                            channelName = target.channelName,
                            model = model,
                            httpStatus = response.code,
                            elapsedMillis = elapsed,
                            requestId = providerRequestId,
                            rawPreview = preview,
                        ),
                    ),
                )
            }
        }.getOrElse { error ->
            failedResult(
                target = target,
                error = ResponseParser.preview(ProviderErrorLocalizer.networkError(error), secrets),
                startedAt = startedAt,
            )
        }
    }

    override fun capabilities(channel: ProviderChannel): ProviderCapabilities {
        return ProviderCapabilities(
            textToImage = true,
            imageToImage = supportsReferenceImages,
            textToVideo = false,
            imageToVideo = false,
            synchronous = true,
            asyncJob = false,
            supportsModelList = true,
            supportsCancel = false,
        )
    }

    protected open fun generationEndpoint(
        channel: ProviderChannel,
        target: ModelTarget,
        model: String,
    ): HttpUrl? {
        return endpoint(target.baseUrl.ifBlank { channel.baseUrl }.ifBlank { defaultBaseUrl }, "/v1/images/generations")
    }

    protected abstract fun buildPayload(request: GenerationRequest, model: String): JSONObject

    protected open fun Request.Builder.applyGenerationAuth(apiKey: String): Request.Builder {
        return applyAuth(apiKey)
    }

    protected fun referenceDataUrl(reference: MediaReference): String {
        val mimeType = reference.mimeType.ifBlank { "image/png" }
        val encoded = Base64.encodeToString(reference.bytes, Base64.NO_WRAP)
        return "data:$mimeType;base64,$encoded"
    }

    protected fun imageSize(request: GenerationRequest): String {
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
                if (bytes.size.toLong() > MAX_IMAGE_BYTES) {
                    throw IllegalStateException("Image exceeds max size: ${bytes.size}")
                }
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

    protected fun failedResult(
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
                    channelId = target.channelId,
                    channelName = target.channelName,
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

    protected fun clientFor(timeoutSeconds: Int, proxy: String): OkHttpClient {
        val builder = baseClient.newBuilder()
            .callTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .connectTimeout(timeoutSeconds.coerceAtMost(60).toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        parseProxy(proxy)?.let { builder.proxy(it) }
        return builder.build()
    }

    protected fun endpoint(baseUrl: String, path: String): HttpUrl? {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isBlank()) return null
        val finalUrl = if (base.endsWith("/v1") && path.startsWith("/v1/")) {
            base + path.removePrefix("/v1")
        } else {
            base + path
        }
        return finalUrl.toHttpUrlOrNull()
    }

    protected fun decryptApiKey(channel: ProviderChannel): String {
        return runCatching { SecureKeyStore.decrypt(channel.apiKey).trim() }.getOrDefault("")
    }

    protected fun Request.Builder.applyAuth(apiKey: String): Request.Builder {
        if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
        return this
    }

    protected fun safeBaseUrl(baseUrl: String): String {
        return ResponseParser.redact(baseUrl).substringBefore('?')
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

    private fun requestId(headers: okhttp3.Headers): String {
        return headers["x-request-id"].orEmpty()
            .ifBlank { headers["openai-request-id"].orEmpty() }
            .ifBlank { headers["x-goog-request-id"].orEmpty() }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_IMAGE_BYTES = 30L * 1024L * 1024L
    }
}

class GrokImageAdapter : JsonImageAdapter(
    type = "grok_image",
    aliases = setOf("grok"),
    defaultBaseUrl = "https://api.x.ai",
    defaultModel = "grok-imagine-image",
    supportsReferenceImages = false,
) {
    override fun buildPayload(request: GenerationRequest, model: String): JSONObject {
        return JSONObject()
            .put("model", model)
            .put("prompt", request.prompt)
            .put("aspect_ratio", request.aspectRatio.ifBlank { "auto" })
            .put("resolution", grokResolution(request.resolution))
            .put("response_format", request.responseFormat.apiValue)
    }

    private fun grokResolution(resolution: String): String {
        val longSide = resolution
            .lowercase()
            .split('x')
            .mapNotNull { it.filter(Char::isDigit).toIntOrNull() }
            .maxOrNull()
        return when {
            longSide == null -> resolution.ifBlank { "2k" }.lowercase()
            longSide <= 1024 -> "1k"
            longSide <= 2048 -> "2k"
            else -> "4k"
        }
    }
}

class AgnesImageAdapter : JsonImageAdapter(
    type = "agnes_image",
    aliases = setOf("agnes"),
    defaultBaseUrl = "https://apihub.agnes-ai.com",
    defaultModel = "agnes-image-2.1-flash",
    supportsReferenceImages = true,
) {
    override fun buildPayload(request: GenerationRequest, model: String): JSONObject {
        val payload = JSONObject()
            .put("model", model)
            .put("prompt", request.prompt)
            .put("size", agnesSize(request.aspectRatio))
        if (request.references.isNotEmpty()) {
            val images = JSONArray()
            request.references.forEach { reference ->
                if (reference.bytes.isNotEmpty()) images.put(referenceDataUrl(reference))
            }
            payload.put(
                "extra_body",
                JSONObject()
                    .put("image", images)
                    .put("response_format", "url"),
            )
        }
        return payload
    }

    private fun agnesSize(aspectRatio: String): String {
        return when (aspectRatio.trim()) {
            "16:9" -> "1024x576"
            "9:16" -> "576x1024"
            "3:2" -> "1024x682"
            "2:3" -> "682x1024"
            "4:3" -> "1024x768"
            "3:4" -> "768x1024"
            "4:5" -> "819x1024"
            "5:4" -> "1024x819"
            "21:9" -> "1024x439"
            else -> "1024x1024"
        }
    }
}

class GeminiImageAdapter : JsonImageAdapter(
    type = "gemini_image",
    aliases = setOf("gemini"),
    defaultBaseUrl = "https://generativelanguage.googleapis.com",
    defaultModel = "gemini-2.0-flash-preview-image-generation",
    supportsReferenceImages = true,
) {
    override suspend fun listModels(channel: ProviderChannel): ModelListResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val apiKey = decryptApiKey(channel)
        val secrets = listOf(apiKey)
        val base = geminiBase(channel.baseUrl.ifBlank { "https://generativelanguage.googleapis.com" })
        val url = "$base/v1beta/models".toHttpUrlOrNull()
            ?: return@withContext ModelListResult(
                error = ProviderErrorLocalizer.localMessage("Invalid base URL: ${safeBaseUrl(channel.baseUrl)}"),
            )
        val client = clientFor(channel.timeoutSeconds, channel.proxy)
        val httpRequest = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("x-goog-api-key", apiKey)
            .build()

        runCatching {
            client.newCall(httpRequest).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                val preview = ResponseParser.preview(bodyText, secrets)
                val elapsed = System.currentTimeMillis() - startedAt
                if (!response.isSuccessful) {
                    val errorMessage = ResponseParser.errorMessage(bodyText, secrets)
                    return@withContext ModelListResult(
                        httpStatus = response.code,
                        error = ProviderErrorLocalizer.httpError(response.code, errorMessage),
                        rawPreview = preview,
                        elapsedMillis = elapsed,
                    )
                }
                ModelListResult(
                    models = parseGeminiModels(bodyText, secrets),
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

    override fun generationEndpoint(channel: ProviderChannel, target: ModelTarget, model: String): HttpUrl? {
        val base = geminiBase(target.baseUrl.ifBlank { channel.baseUrl }.ifBlank { "https://generativelanguage.googleapis.com" })
        val modelPath = if (model.startsWith("models/")) model else "models/$model"
        return "$base/v1beta/$modelPath:generateContent".toHttpUrlOrNull()
    }

    override fun buildPayload(request: GenerationRequest, model: String): JSONObject {
        val parts = JSONArray().put(JSONObject().put("text", request.prompt))
        request.references.forEach { reference ->
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", reference.mimeType.ifBlank { "image/png" })
                        .put("data", Base64.encodeToString(reference.bytes, Base64.NO_WRAP)),
                ),
            )
        }
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("IMAGE")))
    }

    override fun Request.Builder.applyGenerationAuth(apiKey: String): Request.Builder {
        if (apiKey.isNotBlank()) header("x-goog-api-key", apiKey)
        return this
    }

    private fun geminiBase(baseUrl: String): String {
        return baseUrl
            .trim()
            .trimEnd('/')
            .removeSuffix("/v1beta")
            .removeSuffix("/v1")
            .ifBlank { "https://generativelanguage.googleapis.com" }
    }

    private fun parseGeminiModels(raw: String, secrets: Collection<String>): List<ModelInfo> {
        val array = runCatching { JSONObject(raw).optJSONArray("models") }.getOrNull() ?: return emptyList()
        val models = mutableListOf<ModelInfo>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name", "").trim().removePrefix("models/")
            if (name.isNotBlank()) {
                models.add(
                    ModelInfo(
                        id = name,
                        ownedBy = item.optString("displayName", "").trim(),
                        rawPreview = ResponseParser.preview(item.toString(), secrets, 300),
                    ),
                )
            }
        }
        return models.distinctBy { it.id }
    }
}
