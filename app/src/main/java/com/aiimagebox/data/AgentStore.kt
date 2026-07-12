package com.aiimagebox.data

import com.aiimagebox.generation.AgentDefinition
import com.aiimagebox.generation.AgentStage
import com.aiimagebox.util.JsonFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AgentStore(appDirectories: AppDirectories) {
    private val file = File(appDirectories.config, "agents.json")

    @Synchronized
    fun load(): List<AgentDefinition> {
        val loaded = runCatching {
            val root = JsonFiles.readJsonValue(file)
            val array = when (root) {
                is JSONObject -> root.optJSONArray("agents") ?: JSONArray()
                is JSONArray -> root
                else -> JSONArray()
            }
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { add(AgentDefinition.fromJson(it)) }
                }
            }
        }.getOrDefault(emptyList())
        return loaded.ifEmpty { defaults().also(::save) }
    }

    @Synchronized
    fun save(definitions: List<AgentDefinition>) {
        JsonFiles.writeObject(
            file,
            JSONObject()
                .put("schema_version", 1)
                .put("agents", JSONArray().also { array -> definitions.forEach { array.put(it.toJson()) } }),
        )
    }

    private fun defaults(): List<AgentDefinition> = listOf(
        AgentDefinition(type = "prompt_enhancer", name = "提示词增强", stage = AgentStage.PRE_REQUEST, enabled = false, order = 10),
        AgentDefinition(type = "reference_analyzer", name = "参考图分析", stage = AgentStage.PRE_REQUEST, enabled = true, order = 20),
        AgentDefinition(type = "review", name = "可选审核", stage = AgentStage.PRE_REQUEST, enabled = false, order = 30),
        AgentDefinition(type = "metadata_writer", name = "元数据写入", stage = AgentStage.POST_RESULT, enabled = true, order = 40),
    )
}
