package com.aiimagebox.util

import com.aiimagebox.data.AgentStore
import com.aiimagebox.data.ChannelStore
import com.aiimagebox.data.ProviderChannel
import com.aiimagebox.generation.AgentDefinition
import org.json.JSONArray
import org.json.JSONObject

object ConfigTransfer {
    fun export(channelStore: ChannelStore, agentStore: AgentStore): JSONObject {
        val channels = JSONArray().also { array ->
            channelStore.load().forEach { channel -> array.put(channel.toExportJson()) }
        }
        val agents = JSONArray().also { array ->
            agentStore.load().forEach { agent -> array.put(agent.toJson()) }
        }
        return JSONObject()
            .put("schema_version", 1)
            .put("exported_at", System.currentTimeMillis())
            .put("channels", channels)
            .put("agents", agents)
    }

    fun import(value: JSONObject): ImportedConfig {
        val channels = buildList {
            val array = value.optJSONArray("channels") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(ProviderChannel.fromJson(item).copy(apiKey = null, enabled = false))
            }
        }
        val agents = buildList {
            val array = value.optJSONArray("agents") ?: JSONArray()
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(AgentDefinition.fromJson(it)) }
            }
        }
        return ImportedConfig(channels, agents)
    }

    private fun ProviderChannel.toExportJson(): JSONObject {
        return toJson().apply {
            remove("api_key")
            put("api_key_required", true)
            put("enabled", false)
        }
    }
}

data class ImportedConfig(
    val channels: List<ProviderChannel>,
    val agents: List<AgentDefinition>,
)
