package com.aiimagebox.util

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

object Redaction {
    const val MASK = "[REDACTED]"

    private val sensitiveKeys = setOf(
        "apikey",
        "authorization",
        "accesstoken",
        "refreshtoken",
        "token",
        "secret",
        "password",
        "proxyauthorization",
        "xapikey",
    )

    private val bearerPattern = Regex("""(?i)(Bearer\s+)[A-Za-z0-9._~+\-/]+=*""")
    private val assignmentPattern = Regex("""(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|authorization|password|secret)\s*[:=]\s*)("[^"]+"|'[^']+'|[^\s,;&]+)""")
    private val queryPattern = Regex("""(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|token)=)[^&\s]+""")
    private val urlCredentialPattern = Regex("""(?i)(https?://[^/\s:@]+:)[^@\s/]+(@)""")

    fun isSensitiveKey(key: String): Boolean {
        val normalized = key.filter { it.isLetterOrDigit() }.lowercase()
        return normalized in sensitiveKeys || normalized.endsWith("token") || normalized.endsWith("secret")
    }

    fun redactText(text: String): String {
        return urlCredentialPattern.replace(
            queryPattern.replace(
                assignmentPattern.replace(
                    bearerPattern.replace(text) { it.groupValues[1] + MASK },
                ) { it.groupValues[1] + MASK },
            ) { it.groupValues[1] + MASK },
        ) { it.groupValues[1] + MASK + it.groupValues[2] }
    }

    fun redactJsonString(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return raw
        return runCatching {
            when (val value = JSONTokener(trimmed).nextValue()) {
                is JSONObject -> redactJsonObject(value).toString()
                is JSONArray -> redactJsonArray(value).toString()
                else -> redactText(raw)
            }
        }.getOrElse { redactText(raw) }
    }

    fun redactJsonObject(source: JSONObject): JSONObject {
        val redacted = JSONObject()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = source.opt(key)
            redacted.put(key, if (isSensitiveKey(key)) MASK else redactJsonValue(value))
        }
        return redacted
    }

    fun redactJsonArray(source: JSONArray): JSONArray {
        val redacted = JSONArray()
        for (index in 0 until source.length()) {
            redacted.put(redactJsonValue(source.opt(index)))
        }
        return redacted
    }

    private fun redactJsonValue(value: Any?): Any? {
        return when (value) {
            null, JSONObject.NULL -> JSONObject.NULL
            is JSONObject -> redactJsonObject(value)
            is JSONArray -> redactJsonArray(value)
            is String -> redactText(value)
            else -> value
        }
    }
}
