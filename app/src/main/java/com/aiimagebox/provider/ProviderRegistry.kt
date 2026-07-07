package com.aiimagebox.provider

import com.aiimagebox.data.ModelTarget
import com.aiimagebox.data.ProviderChannel

object ProviderRegistry {
    private val adaptersByType: Map<String, ProviderAdapter> = buildMap {
        register(OpenAICompatibleImageAdapter())
    }

    fun get(type: String): ProviderAdapter? = adaptersByType[type.normalizedType()]

    fun require(type: String): ProviderAdapter {
        return get(type) ?: throw IllegalArgumentException("Unsupported provider type: $type")
    }

    fun forChannel(channel: ProviderChannel): ProviderAdapter? = get(channel.providerType)

    fun supportedTypes(): List<String> = adaptersByType.keys.sorted()

    fun targetsFor(channels: List<ProviderChannel>, preferredModel: String = ""): List<ModelTarget> {
        return channels
            .filter { it.enabled && forChannel(it) != null }
            .flatMap { channel ->
                val models = when {
                    preferredModel.isNotBlank() -> listOf(preferredModel.trim())
                    channel.enabledModels.isNotEmpty() -> channel.enabledModels
                    channel.defaultModel.isNotBlank() -> listOf(channel.defaultModel)
                    else -> emptyList()
                }.map { it.trim() }.filter { it.isNotBlank() }.distinct()

                models.map { model ->
                    ModelTarget(
                        channelId = channel.id,
                        channelName = channel.name,
                        providerType = channel.providerType,
                        baseUrl = channel.baseUrl,
                        model = model,
                        timeoutSeconds = channel.timeoutSeconds,
                        proxy = channel.proxy,
                    )
                }
            }
    }

    private fun MutableMap<String, ProviderAdapter>.register(adapter: ProviderAdapter) {
        this[adapter.type.normalizedType()] = adapter
        adapter.aliases.forEach { alias ->
            this[alias.normalizedType()] = adapter
        }
    }

    private fun String.normalizedType(): String = trim().lowercase()
}
