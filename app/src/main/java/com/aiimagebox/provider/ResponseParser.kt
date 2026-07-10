package com.aiimagebox.provider

import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

object ResponseParser {
    private const val DEFAULT_PREVIEW_CHARS = 1_200
    private val bearerRegex = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+\\-/]+=*")
    private val quotedSecretRegex = Regex("(?i)\"(api[_-]?key|authorization|cookie|set-cookie|token|secret)\"\\s*:\\s*\"[^\"]*\"")
    private val urlCredentialRegex = Regex("(?i)(https?://)[^\\s/@]+:[^\\s/@]+@")
    private val dataUrlRegex = Regex("^data:(image/[A-Za-z0-9.+-]+);base64,(.+)$", RegexOption.IGNORE_CASE)
    private val dataVideoUrlRegex = Regex("^data:(video/[A-Za-z0-9.+-]+);base64,(.+)$", RegexOption.IGNORE_CASE)
    private val httpUrlRegex = Regex("https?://[^\\s\"'<>)]*", RegexOption.IGNORE_CASE)

    fun parseModels(raw: String, secrets: Collection<String> = emptyList()): List<ModelInfo> {
        val root = parseJson(raw) ?: return emptyList()
        val data = when (root) {
            is JSONObject -> root.optJSONArray("data") ?: return emptyList()
            is JSONArray -> root
            else -> return emptyList()
        }
        val models = mutableListOf<ModelInfo>()
        for (index in 0 until data.length()) {
            when (val item = data.opt(index)) {
                is JSONObject -> {
                    val id = item.optString("id", "").trim()
                    if (id.isNotBlank()) {
                        models.add(
                            ModelInfo(
                                id = id,
                                ownedBy = item.optString("owned_by", "").trim(),
                                rawPreview = preview(item.toString(), secrets, 300),
                            ),
                        )
                    }
                }
                is String -> {
                    val id = item.trim()
                    if (id.isNotBlank()) models.add(ModelInfo(id = id))
                }
            }
        }
        return models.distinctBy { it.id }
    }

    fun parseImageCandidates(raw: String, responseBaseUrl: String = ""): List<ParsedImageCandidate> {
        val root = parseJson(raw) ?: return emptyList()
        val out = linkedMapOf<String, ParsedImageCandidate>()
        scanValue(root, responseBaseUrl, revisedPrompt = "", out = out)
        return out.values.toList()
    }

    fun parseVideoCandidates(raw: String, responseBaseUrl: String = ""): List<ParsedImageCandidate> {
        val root = parseJson(raw) ?: return emptyList()
        val out = linkedMapOf<String, ParsedImageCandidate>()
        scanVideoValue(root, responseBaseUrl, out)
        return out.values.toList()
    }

    fun preview(raw: String, secrets: Collection<String> = emptyList(), maxChars: Int = DEFAULT_PREVIEW_CHARS): String {
        val redacted = redact(raw, secrets)
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (redacted.length <= maxChars) redacted else redacted.take(maxChars) + "..."
    }

    fun errorMessage(raw: String, secrets: Collection<String> = emptyList(), maxChars: Int = 500): String {
        val root = runCatching { JSONObject(raw) }.getOrNull()
        val message = when {
            root == null -> raw
            root.optJSONObject("error") != null -> {
                val error = root.optJSONObject("error")
                error?.optString("message", "").orEmpty()
                    .ifBlank { error?.optString("code", "").orEmpty() }
                    .ifBlank { error?.toString().orEmpty() }
            }
            else -> root.optString("message", "")
                .ifBlank { root.optString("detail", "") }
                .ifBlank { root.optString("error", "") }
                .ifBlank { root.toString() }
        }
        return preview(message, secrets, maxChars)
    }

    fun decodeBase64Image(value: String): ByteArray {
        val trimmed = value.trim()
        val dataUrl = dataUrlRegex.find(trimmed) ?: dataVideoUrlRegex.find(trimmed)
        val payload = dataUrl?.groupValues?.getOrNull(2) ?: value
        return Base64.decode(payload.trim(), Base64.DEFAULT)
    }

    fun mimeTypeForDataUrl(value: String): String {
        val trimmed = value.trim()
        return dataUrlRegex.find(trimmed)?.groupValues?.getOrNull(1)
            ?: dataVideoUrlRegex.find(trimmed)?.groupValues?.getOrNull(1)
            ?: ""
    }

    fun guessImageMimeType(bytes: ByteArray, fallback: String = ""): String {
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) {
            return "image/png"
        }
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) {
            return "image/jpeg"
        }
        if (bytes.size >= 6) {
            val header = String(bytes.copyOfRange(0, 6), Charsets.ISO_8859_1)
            if (header == "GIF87a" || header == "GIF89a") return "image/gif"
        }
        if (bytes.size >= 12) {
            val riff = String(bytes.copyOfRange(0, 4), Charsets.ISO_8859_1)
            val webp = String(bytes.copyOfRange(8, 12), Charsets.ISO_8859_1)
            if (riff == "RIFF" && webp == "WEBP") return "image/webp"
        }
        return fallback
    }

    fun guessVideoMimeType(bytes: ByteArray, fallback: String = ""): String {
        if (bytes.size >= 12) {
            val ftyp = String(bytes.copyOfRange(4, 8), Charsets.ISO_8859_1)
            if (ftyp == "ftyp") return "video/mp4"
            val riff = String(bytes.copyOfRange(0, 4), Charsets.ISO_8859_1)
            val webp = String(bytes.copyOfRange(8, 12), Charsets.ISO_8859_1)
            if (riff == "RIFF" && webp == "AVI ") return "video/avi"
        }
        return fallback
    }

    fun redact(value: String, secrets: Collection<String> = emptyList()): String {
        var redacted = value
        secrets
            .map { it.trim() }
            .filter { it.length >= 4 }
            .forEach { secret ->
                redacted = redacted.replace(secret, "[REDACTED]")
            }
        redacted = bearerRegex.replace(redacted, "Bearer [REDACTED]")
        redacted = urlCredentialRegex.replace(redacted, "$1[REDACTED]@")
        redacted = quotedSecretRegex.replace(redacted) { match ->
            val key = match.groupValues.getOrNull(1).orEmpty()
            "\"$key\":\"[REDACTED]\""
        }
        return redacted
    }

    private fun parseJson(raw: String): Any? {
        return runCatching { JSONTokener(raw).nextValue() }.getOrNull()
    }

    private fun scanValue(
        value: Any?,
        responseBaseUrl: String,
        revisedPrompt: String,
        out: MutableMap<String, ParsedImageCandidate>,
    ) {
        when (value) {
            is JSONObject -> scanObject(value, responseBaseUrl, revisedPrompt, out)
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    scanValue(value.opt(index), responseBaseUrl, revisedPrompt, out)
                }
            }
            is String -> addStringCandidate(value, responseBaseUrl, revisedPrompt, out)
        }
    }

    private fun scanObject(
        obj: JSONObject,
        responseBaseUrl: String,
        inheritedPrompt: String,
        out: MutableMap<String, ParsedImageCandidate>,
    ) {
        val revisedPrompt = obj.optString("revised_prompt", inheritedPrompt)
            .ifBlank { obj.optString("revisedPrompt", inheritedPrompt) }
        val preferredKeys = listOf("b64_json", "base64", "image_base64", "url", "image_url", "image")
        preferredKeys.forEach { key ->
            if (obj.has(key)) addStringCandidate(obj.optString(key, ""), responseBaseUrl, revisedPrompt, out)
        }

        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            scanValue(obj.opt(key), responseBaseUrl, revisedPrompt, out)
        }
    }

    private fun scanVideoValue(
        value: Any?,
        responseBaseUrl: String,
        out: MutableMap<String, ParsedImageCandidate>,
    ) {
        when (value) {
            is JSONObject -> scanVideoObject(value, responseBaseUrl, out)
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    scanVideoValue(value.opt(index), responseBaseUrl, out)
                }
            }
            is String -> addVideoStringCandidate(value, responseBaseUrl, out, allowAnyUrl = false)
        }
    }

    private fun scanVideoObject(
        obj: JSONObject,
        responseBaseUrl: String,
        out: MutableMap<String, ParsedImageCandidate>,
    ) {
        val preferredKeys = listOf(
            "video", "video_url", "videoUrl", "url", "download_url", "downloadUrl",
            "result_url", "resultUrl", "output_url", "outputUrl", "file_url", "fileUrl",
        )
        preferredKeys.forEach { key ->
            if (obj.has(key)) addVideoStringCandidate(obj.optString(key, ""), responseBaseUrl, out, allowAnyUrl = true)
        }
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            scanVideoValue(obj.opt(key), responseBaseUrl, out)
        }
    }

    private fun addVideoStringCandidate(
        rawValue: String,
        responseBaseUrl: String,
        out: MutableMap<String, ParsedImageCandidate>,
        allowAnyUrl: Boolean,
    ) {
        val value = rawValue.trim()
        if (value.isBlank()) return

        val dataUrl = dataVideoUrlRegex.find(value)
        if (dataUrl != null) {
            putCandidate(
                out,
                ParsedImageCandidate(
                    kind = ParsedImageKind.DATA_URL,
                    value = value,
                    mimeType = dataUrl.groupValues.getOrNull(1).orEmpty(),
                ),
            )
            return
        }

        httpUrlRegex.findAll(value).forEach { match ->
            val url = match.value.trimEnd('.', ',', ';')
            if (allowAnyUrl || looksLikeVideoUrl(url)) {
                putCandidate(out, ParsedImageCandidate(kind = ParsedImageKind.URL, value = url))
            }
        }

        val absolute = absoluteUrl(value, responseBaseUrl)
        if (absolute != null && (allowAnyUrl || looksLikeVideoUrl(absolute))) {
            putCandidate(out, ParsedImageCandidate(kind = ParsedImageKind.URL, value = absolute))
        }
    }

    private fun looksLikeVideoUrl(value: String): Boolean {
        val path = value.substringBefore('?').lowercase()
        return path.endsWith(".mp4") ||
            path.endsWith(".webm") ||
            path.endsWith(".mov") ||
            path.endsWith(".m4v") ||
            path.endsWith(".avi") ||
            path.endsWith(".m3u8")
    }

    private fun addStringCandidate(
        rawValue: String,
        responseBaseUrl: String,
        revisedPrompt: String,
        out: MutableMap<String, ParsedImageCandidate>,
    ) {
        val value = rawValue.trim()
        if (value.isBlank()) return

        val dataUrl = dataUrlRegex.find(value)
        if (dataUrl != null) {
            putCandidate(
                out,
                ParsedImageCandidate(
                    kind = ParsedImageKind.DATA_URL,
                    value = value,
                    mimeType = dataUrl.groupValues.getOrNull(1).orEmpty(),
                    revisedPrompt = revisedPrompt,
                ),
            )
            return
        }

        httpUrlRegex.findAll(value).forEach { match ->
            val url = match.value.trimEnd('.', ',', ';')
            putCandidate(
                out,
                ParsedImageCandidate(
                    kind = ParsedImageKind.URL,
                    value = url,
                    revisedPrompt = revisedPrompt,
                ),
            )
        }

        val absolute = absoluteUrl(value, responseBaseUrl)
        if (absolute != null) {
            putCandidate(
                out,
                ParsedImageCandidate(
                    kind = ParsedImageKind.URL,
                    value = absolute,
                    revisedPrompt = revisedPrompt,
                ),
            )
            return
        }

        if (looksLikeImageBase64(value)) {
            putCandidate(
                out,
                ParsedImageCandidate(
                    kind = ParsedImageKind.B64_JSON,
                    value = value,
                    revisedPrompt = revisedPrompt,
                ),
            )
        }
    }

    private fun absoluteUrl(value: String, responseBaseUrl: String): String? {
        if (!value.startsWith("/")) return null
        val base = responseBaseUrl.toHttpUrlOrNull() ?: return null
        return base.newBuilder(value)?.build()?.toString()
    }

    private fun looksLikeImageBase64(value: String): Boolean {
        if (value.length < 80) return false
        val compact = value.replace("\\s".toRegex(), "")
        if (!Regex("^[A-Za-z0-9+/=_-]+$").matches(compact)) return false
        val sample = compact.take(32)
        return sample.startsWith("iVBORw0KGgo") ||
            sample.startsWith("/9j/") ||
            sample.startsWith("R0lGOD") ||
            sample.startsWith("UklGR")
    }

    private fun putCandidate(out: MutableMap<String, ParsedImageCandidate>, candidate: ParsedImageCandidate) {
        val key = "${candidate.kind}:${candidate.value.take(200)}"
        if (!out.containsKey(key)) out[key] = candidate
    }
}
