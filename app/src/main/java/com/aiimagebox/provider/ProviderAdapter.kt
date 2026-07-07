package com.aiimagebox.provider

import com.aiimagebox.data.ModelTarget
import com.aiimagebox.data.ProviderChannel

interface ProviderAdapter {
    val type: String
    val aliases: Set<String>
        get() = emptySet()

    suspend fun listModels(channel: ProviderChannel): ModelListResult

    suspend fun generate(
        channel: ProviderChannel,
        request: GenerationRequest,
        target: ModelTarget,
    ): GenerationResult

    suspend fun poll(job: ProviderJob, target: ModelTarget): GenerationResult {
        return GenerationResult(
            status = GenerationStatus.FAILED,
            usedModel = target.model,
            error = "Provider ${target.providerType} does not support async polling.",
            rawPreview = job.rawPreview,
        )
    }

    fun capabilities(channel: ProviderChannel): ProviderCapabilities
}
