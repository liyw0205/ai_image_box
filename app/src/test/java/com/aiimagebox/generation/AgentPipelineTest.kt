package com.aiimagebox.generation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPipelineTest {
    @Test
    fun executesEnabledAgentsInOrderAndRecordsOutput() = runBlocking {
        val definitions = listOf(
            AgentDefinition(
                id = "second",
                type = "prompt_enhancer",
                name = "后置增强",
                stage = AgentStage.PRE_REQUEST,
                order = 20,
                config = mapOf("suffix" to "suffix"),
            ),
            AgentDefinition(
                id = "first",
                type = "prompt_enhancer",
                name = "前置增强",
                stage = AgentStage.PRE_REQUEST,
                order = 10,
                config = mapOf("prefix" to "prefix"),
            ),
            AgentDefinition(
                id = "disabled",
                type = "prompt_enhancer",
                name = "禁用",
                stage = AgentStage.PRE_REQUEST,
                enabled = false,
                order = 0,
                config = mapOf("prefix" to "disabled"),
            ),
        )
        val pipeline = AgentPipeline(AgentRegistry.defaults()) { definitions }

        val result = pipeline.before(
            GenerationRequest(prompt = "original", target = GenerationTarget("channel", "model")),
        )

        assertEquals("prefix original suffix", result.request.prompt)
        assertEquals(listOf("first", "second"), result.executions.map { it.agentId })
        assertTrue(result.executions.all { it.error.isBlank() })
    }

    @Test
    fun metadataWriterAddsPostResultMetadata() = runBlocking {
        val pipeline = AgentPipeline(AgentRegistry.defaults()) {
            listOf(
                AgentDefinition(
                    id = "metadata",
                    type = "metadata_writer",
                    name = "元数据",
                    stage = AgentStage.POST_RESULT,
                    config = mapOf("key" to "source", "value" to "pipeline"),
                ),
            )
        }
        val request = GenerationRequest(prompt = "prompt", target = GenerationTarget("channel", "model"))
        val before = pipeline.before(request)
        val result = pipeline.after(request, GenerationResult(ByteArray(1)), before)

        assertEquals("pipeline", result.metadata["source"])
        assertEquals("metadata", result.executions.single().agentId)
    }
}
