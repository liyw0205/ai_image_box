package com.aiimagebox.data

import org.json.JSONArray
import java.io.File

class ChannelStore(appDirectories: AppDirectories) {
    private val file: File = File(appDirectories.config, "channels.json")

    @Synchronized
    fun load(): List<ProviderChannel> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val channel = ProviderChannel.fromJson(item)
                    if (channel.name.isNotBlank()) add(channel)
                }
            }
        }.getOrElse { emptyList() }
    }

    @Synchronized
    fun save(channels: List<ProviderChannel>) {
        file.parentFile?.mkdirs()
        val array = JSONArray()
        channels.forEach { array.put(it.toJson()) }
        file.writeText(array.toString(2))
    }

    @Synchronized
    fun upsert(channel: ProviderChannel) {
        val channels = load().toMutableList()
        val index = channels.indexOfFirst { it.id == channel.id }
        if (index >= 0) {
            channels[index] = channel.copy(updatedAt = System.currentTimeMillis())
        } else {
            channels.add(channel)
        }
        save(channels)
    }

    @Synchronized
    fun delete(id: String) {
        save(load().filterNot { it.id == id })
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) {
        save(
            load().map {
                if (it.id == id) it.copy(enabled = enabled, updatedAt = System.currentTimeMillis()) else it
            },
        )
    }

    @Synchronized
    fun duplicate(id: String): ProviderChannel? {
        val source = load().firstOrNull { it.id == id } ?: return null
        val copy = source.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = "${source.name} Copy",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        upsert(copy)
        return copy
    }
}

