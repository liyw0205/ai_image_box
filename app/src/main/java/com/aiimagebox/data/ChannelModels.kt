package com.aiimagebox.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class EncryptedSecret(
    val iv: String,
    val cipherText: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("iv", iv)
        .put("cipher_text", cipherText)

    companion object {
        fun fromJson(value: JSONObject?): EncryptedSecret? {
            if (value == null) return null
            val iv = value.optString("iv", "").trim()
            val cipherText = value.optString("cipher_text", "").trim()
            if (iv.isBlank() || cipherText.isBlank()) return null
            return EncryptedSecret(iv, cipherText)
        }
    }
}

data class ProviderChannel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val providerType: String,
    val baseUrl: String,
    val apiKey: EncryptedSecret?,
    val defaultModel: String,
    val enabledModels: List<String>,
    val timeoutSeconds: Int = 280,
    val enabled: Boolean = true,
    val proxy: String = "",
    val extraJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("provider_type", providerType)
        .put("base_url", baseUrl)
        .put("api_key", apiKey?.toJson())
        .put("default_model", defaultModel)
        .put("enabled_models", JSONArray(enabledModels))
        .put("timeout_seconds", timeoutSeconds)
        .put("enabled", enabled)
        .put("proxy", proxy)
        .put("extra", JSONObject(extraJson.ifBlank { "{}" }))
        .put("created_at", createdAt)
        .put("updated_at", updatedAt)

    fun targetLabels(): String {
        val models = enabledModels.filter { it.isNotBlank() }
        return if (models.isEmpty()) "未启用模型" else models.joinToString(", ")
    }

    companion object {
        fun fromJson(value: JSONObject): ProviderChannel {
            val models = mutableListOf<String>()
            val array = value.optJSONArray("enabled_models") ?: JSONArray()
            for (index in 0 until array.length()) {
                val model = array.optString(index, "").trim()
                if (model.isNotBlank()) models.add(model)
            }
            val extra = value.optJSONObject("extra")?.toString() ?: "{}"
            return ProviderChannel(
                id = value.optString("id", UUID.randomUUID().toString()),
                name = value.optString("name", "").trim(),
                providerType = value.optString("provider_type", "openai_compatible_image").trim(),
                baseUrl = value.optString("base_url", "").trim(),
                apiKey = EncryptedSecret.fromJson(value.optJSONObject("api_key")),
                defaultModel = value.optString("default_model", "").trim(),
                enabledModels = models,
                timeoutSeconds = value.optInt("timeout_seconds", 280).coerceIn(10, 900),
                enabled = value.optBoolean("enabled", true),
                proxy = value.optString("proxy", "").trim(),
                extraJson = extra,
                createdAt = value.optLong("created_at", System.currentTimeMillis()),
                updatedAt = value.optLong("updated_at", System.currentTimeMillis()),
            )
        }
    }
}

data class ModelTarget(
    val channelId: String,
    val channelName: String,
    val providerType: String,
    val baseUrl: String,
    val model: String,
    val timeoutSeconds: Int,
    val proxy: String = "",
) {
    val label: String
        get() = "$channelName/$model"
}
