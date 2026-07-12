package com.aiimagebox

import android.app.Application
import com.aiimagebox.data.ChannelStore
import com.aiimagebox.data.GenerationStore
import com.aiimagebox.data.AgentStore
import org.json.JSONObject
import com.aiimagebox.generation.GenerationManager
import com.aiimagebox.generation.AgentPipeline
import com.aiimagebox.generation.AgentRegistry
import com.aiimagebox.generation.ProviderRegistryGenerationExecutor
import com.aiimagebox.data.AppDirectories

class AIImageBoxApp : Application() {
    lateinit var appDirectories: AppDirectories
        private set
    lateinit var channelStore: ChannelStore
        private set
    lateinit var agentStore: AgentStore
        private set
    lateinit var generationStore: GenerationStore
        private set
    lateinit var generationManager: GenerationManager
        private set

    override fun onCreate() {
        super.onCreate()
        appDirectories = AppDirectories.ensure(filesDir)
        channelStore = ChannelStore(appDirectories)
        agentStore = AgentStore(appDirectories)
        generationStore = GenerationStore(appDirectories)
        generationManager = GenerationManager(
            executor = ProviderRegistryGenerationExecutor(
                channelProvider = { channelStore.load() },
                agentPipeline = AgentPipeline(AgentRegistry.defaults()) { agentStore.load() },
                jobObserver = { taskId, job ->
                    generationStore.updateTask(taskId) { task ->
                        val parameters = runCatching { JSONObject(task.parametersJson) }.getOrDefault(JSONObject())
                            .put("provider_job", JSONObject()
                                .put("id", job.id)
                                .put("poll_url", job.pollUrl)
                                .put("result_url", job.resultUrl)
                                .put("raw_preview", job.rawPreview))
                        task.copy(parametersJson = parameters.toString())
                    }
                },
            ),
        )
    }
}

