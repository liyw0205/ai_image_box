package com.aiimagebox.generation

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class AgentStage(val wireName: String) {
    PRE_REQUEST("pre_request"),
    POST_RESULT("post_result"),
}

data class AgentDefinition(
    val id: String = UUID.randomUUID().toString(),
    val type: String,
    val name: String,
    val stage: AgentStage,
    val enabled: Boolean = true,
    val order: Int = 0,
    val config: Map<String, String> = emptyMap(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type)
        .put("name", name)
        .put("stage", stage.wireName)
        .put("enabled", enabled)
        .put("order", order)
        .put("config", JSONObject(config))

    companion object {
        fun fromJson(value: JSONObject): AgentDefinition {
            val configObject = value.optJSONObject("config") ?: JSONObject()
            val config = buildMap {
                val keys = configObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, configObject.optString(key, ""))
                }
            }
            return AgentDefinition(
                id = value.optString("id", "").ifBlank { UUID.randomUUID().toString() },
                type = value.optString("type", ""),
                name = value.optString("name", ""),
                stage = AgentStage.values().firstOrNull { it.wireName == value.optString("stage", "") }
                    ?: AgentStage.PRE_REQUEST,
                enabled = value.optBoolean("enabled", true),
                order = value.optInt("order", 0),
                config = config,
            )
        }
    }
}

data class AgentExecution(
    val agentId: String,
    val agentType: String,
    val agentName: String,
    val stage: AgentStage,
    val startedAt: Long,
    val durationMs: Long,
    val output: String,
    val error: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("agent_id", agentId)
        .put("agent_type", agentType)
        .put("agent_name", agentName)
        .put("stage", stage.wireName)
        .put("started_at", startedAt)
        .put("duration_ms", durationMs)
        .put("output", output)
        .put("error", error)
}

data class AgentContext(
    val request: GenerationRequest,
    val result: GenerationResult? = null,
    val metadata: MutableMap<String, String> = linkedMapOf(),
)

data class AgentOutput(
    val request: GenerationRequest? = null,
    val result: GenerationResult? = null,
    val output: String = "",
)

fun interface GenerationAgent {
    suspend fun execute(context: AgentContext, definition: AgentDefinition): AgentOutput
}

class AgentRegistry {
    private val agents = linkedMapOf<String, GenerationAgent>()

    fun register(type: String, agent: GenerationAgent): AgentRegistry {
        agents[type] = agent
        return this
    }

    fun get(type: String): GenerationAgent? = agents[type]

    companion object {
        fun defaults(): AgentRegistry = AgentRegistry()
            .register("prompt_enhancer") { context, definition ->
                val prefix = definition.config["prefix"].orEmpty()
                val suffix = definition.config["suffix"].orEmpty()
                val prompt = listOf(prefix, context.request.prompt, suffix)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                AgentOutput(request = context.request.copy(prompt = prompt), output = prompt)
            }
            .register("reference_analyzer") { context, _ ->
                val paths = context.request.parameters.referenceImagePaths
                val summary = if (paths.isEmpty()) "无参考图" else "参考图 ${paths.size} 张：${paths.joinToString { it.substringAfterLast('/') }}"
                context.metadata["reference_analysis"] = summary
                AgentOutput(output = summary)
            }
            .register("review") { context, definition ->
                val blocked = definition.config["blocked_words"].orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .firstOrNull { context.request.prompt.contains(it, ignoreCase = true) }
                if (blocked != null) error("提示词包含已配置的拦截词：$blocked")
                AgentOutput(output = "通过")
            }
            .register("metadata_writer") { context, definition ->
                val key = definition.config["key"].orEmpty().ifBlank { "agent_note" }
                val value = definition.config["value"].orEmpty().ifBlank { definition.name }
                context.metadata[key] = value
                AgentOutput(output = "$key=$value")
            }
    }
}

data class PipelineResult(
    val request: GenerationRequest,
    val result: GenerationResult? = null,
    val executions: List<AgentExecution> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
)

class AgentPipeline(
    private val registry: AgentRegistry,
    private val definitions: () -> List<AgentDefinition>,
) {
    suspend fun before(request: GenerationRequest): PipelineResult = runStage(AgentStage.PRE_REQUEST, request, null, emptyList(), emptyMap())

    suspend fun after(
        request: GenerationRequest,
        result: GenerationResult,
        previous: PipelineResult,
    ): PipelineResult = runStage(AgentStage.POST_RESULT, request, result, previous.executions, previous.metadata)

    private suspend fun runStage(
        stage: AgentStage,
        initialRequest: GenerationRequest,
        initialResult: GenerationResult?,
        previousExecutions: List<AgentExecution>,
        previousMetadata: Map<String, String>,
    ): PipelineResult {
        var request = initialRequest
        var result = initialResult
        val metadata = previousMetadata.toMutableMap()
        val executions = previousExecutions.toMutableList()
        definitions()
            .filter { it.enabled && it.stage == stage }
            .sortedWith(compareBy<AgentDefinition> { it.order }.thenBy { it.id })
            .forEach { definition ->
                val agent = registry.get(definition.type) ?: return@forEach
                val startedAt = System.currentTimeMillis()
                try {
                    val output = agent.execute(AgentContext(request, result, metadata), definition)
                    request = output.request ?: request
                    result = output.result ?: result
                    executions += AgentExecution(
                        agentId = definition.id,
                        agentType = definition.type,
                        agentName = definition.name,
                        stage = stage,
                        startedAt = startedAt,
                        durationMs = System.currentTimeMillis() - startedAt,
                        output = output.output,
                    )
                } catch (error: Throwable) {
                    executions += AgentExecution(
                        agentId = definition.id,
                        agentType = definition.type,
                        agentName = definition.name,
                        stage = stage,
                        startedAt = startedAt,
                        durationMs = System.currentTimeMillis() - startedAt,
                        output = "",
                        error = error.message ?: error::class.java.simpleName,
                    )
                    throw AgentPipelineException(executions, error)
                }
            }
        return PipelineResult(request, result, executions, metadata)
    }
}

class AgentPipelineException(
    val executions: List<AgentExecution>,
    cause: Throwable,
) : IllegalStateException(cause.message, cause)

fun List<AgentExecution>.toJsonArray(): JSONArray = JSONArray().also { array -> forEach { array.put(it.toJson()) } }
