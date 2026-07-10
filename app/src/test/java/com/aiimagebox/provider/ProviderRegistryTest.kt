package com.aiimagebox.provider

import com.aiimagebox.data.ProviderChannel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRegistryTest {
    @Test
    fun videoAliasesResolveToGenericAdapter() {
        val generic = ProviderRegistry.get("generic_async_video")

        assertNotNull(generic)
        assertSame(generic, ProviderRegistry.get("openai_compatible_video"))
        assertSame(generic, ProviderRegistry.get("grok_video"))
        assertSame(generic, ProviderRegistry.get("seedance_video"))
    }

    @Test
    fun genericVideoAdapterDeclaresVideoCapabilities() {
        val adapter = ProviderRegistry.require("generic_async_video")
        val capabilities = adapter.capabilities(
            ProviderChannel(
                name = "test",
                providerType = "generic_async_video",
                baseUrl = "https://example.invalid",
                apiKey = null,
                defaultModel = "video-model",
                enabledModels = listOf("video-model"),
            ),
        )

        assertTrue(capabilities.textToVideo)
        assertTrue(capabilities.imageToVideo)
        assertTrue(capabilities.asyncJob)
        assertFalse(capabilities.textToImage)
        assertFalse(capabilities.imageToImage)
    }
}
