package com.aiimagebox.provider

import com.aiimagebox.data.ModelTarget
import com.aiimagebox.data.ProviderChannel
import org.json.JSONObject

object ProviderRegistry {
    private val adaptersByType: Map<String, ProviderAdapter> = buildMap {
        register(OpenAICompatibleImageAdapter())
        register(GeminiImageAdapter())
        register(GrokImageAdapter())
        register(AgnesImageAdapter())
        register(GenericAsyncVideoAdapter())
    }

    fun get(type: String): ProviderAdapter? = adaptersByType[type.normalizedType()]

    fun require(type: String): ProviderAdapter {
        return get(type) ?: throw IllegalArgumentException("Unsupported provider type: $type")
    }

    fun forChannel(channel: ProviderChannel): ProviderAdapter? = get(channel.providerType)

    fun supportedTypes(): List<String> = adaptersByType.keys.sorted()

    fun targetsFor(channels: List<ProviderChannel>, preferredModel: String = ""): List<ModelTarget> {
        return channels
            .filter { it.enabled }
            .flatMap { channel ->
                val modelTypes = modelTypeOverrides(channel.extraJson)
                val models = when {
                    preferredModel.isNotBlank() -> listOf(preferredModel.trim())
                    channel.enabledModels.isNotEmpty() -> channel.enabledModels
                    else -> emptyList()
                }.map { it.trim() }.filter { it.isNotBlank() }.distinct()

                models.mapNotNull { model ->
                    val providerType = modelTypes[model]?.takeIf { get(it) != null } ?: channel.providerType
                    if (get(providerType) == null) return@mapNotNull null
                    ModelTarget(
                        channelId = channel.id,
                        channelName = channel.name,
                        providerType = providerType,
                        baseUrl = channel.baseUrl,
                        model = model,
                        timeoutSeconds = channel.timeoutSeconds,
                        proxy = channel.proxy,
                    )
                }
            }
    }

    private fun modelTypeOverrides(extraJson: String): Map<String, String> {
        val modelTypes = runCatching {
            JSONObject(extraJson.ifBlank { "{}" }).optJSONObject("model_types")
        }.getOrNull() ?: return emptyMap()

        val values = mutableMapOf<String, String>()
        val keys = modelTypes.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = modelTypes.optString(key, "").trim()
            if (key.isNotBlank() && value.isNotBlank()) values[key] = value
        }
        return values
    }

    private fun MutableMap<String, ProviderAdapter>.register(adapter: ProviderAdapter) {
        this[adapter.type.normalizedType()] = adapter
        adapter.aliases.forEach { alias ->
            this[alias.normalizedType()] = adapter
        }
    }

    private fun String.normalizedType(): String = trim().lowercase()
}
