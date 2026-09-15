package com.mynote.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderTest {

    @Test
    fun deepSeekDefaults() {
        val endpoint = AiProvider.DEEPSEEK.endpoint()

        assertEquals("deepseek-api", endpoint.serviceId)
        assertEquals("DeepSeek", endpoint.displayName)
        assertEquals("https://api.deepseek.com", endpoint.baseUrl)
        assertEquals(ThinkingStyle.DEEPSEEK_OBJECT, endpoint.thinkingStyle)
        assertEquals(BalanceStyle.DEEPSEEK, endpoint.balanceStyle)
        assertNull(endpoint.modelsQuery)
        assertTrue(AiProvider.DEEPSEEK.supportsThinking)
        assertTrue(AiProvider.DEEPSEEK.supportsBalance)
        assertFalse(AiProvider.DEEPSEEK.freeModelInput)
        assertEquals(DeepSeekModels.all, AiProvider.DEEPSEEK.defaultModels)
    }

    @Test
    fun siliconFlowDefaults() {
        val endpoint = AiProvider.SILICON_FLOW.endpoint()

        assertEquals("siliconflow-api", endpoint.serviceId)
        assertEquals("硅基流动", endpoint.displayName)
        assertEquals("https://api.siliconflow.cn/v1", endpoint.baseUrl)
        assertEquals(ThinkingStyle.BOOLEAN_ENABLE, endpoint.thinkingStyle)
        assertEquals(BalanceStyle.SILICON_FLOW, endpoint.balanceStyle)
        assertEquals("?sub_type=chat", endpoint.modelsQuery)
        assertTrue(AiProvider.SILICON_FLOW.supportsThinking)
        assertTrue(AiProvider.SILICON_FLOW.supportsBalance)
        assertFalse(AiProvider.SILICON_FLOW.freeModelInput)
        assertTrue(AiProvider.SILICON_FLOW.defaultModels.isNotEmpty())
    }

    @Test
    fun customEndpointUsesUserAddressAndTrimsTrailingSlash() {
        val endpoint = AiProvider.CUSTOM.endpoint("https://example.com/v1/")

        assertEquals("openai-compatible", endpoint.serviceId)
        assertEquals("自定义", endpoint.displayName)
        assertEquals("https://example.com/v1", endpoint.baseUrl)
        assertEquals(ThinkingStyle.NONE, endpoint.thinkingStyle)
        assertEquals(BalanceStyle.NONE, endpoint.balanceStyle)
        assertFalse(AiProvider.CUSTOM.supportsThinking)
        assertFalse(AiProvider.CUSTOM.supportsBalance)
        assertTrue(AiProvider.CUSTOM.freeModelInput)
    }

    @Test
    fun customEndpointWithoutAddressFallsBackToEmpty() {
        assertEquals("", AiProvider.CUSTOM.endpoint().baseUrl)
        assertEquals("", AiProvider.CUSTOM.endpoint("   ").baseUrl)
    }

    @Test
    fun presetIgnoresCustomBaseUrlOverride() {
        assertEquals("https://api.deepseek.com", AiProvider.DEEPSEEK.endpoint("https://x.example").baseUrl)
        assertEquals(
            "https://api.siliconflow.cn/v1",
            AiProvider.SILICON_FLOW.endpoint("https://x.example").baseUrl
        )
    }

    @Test
    fun ofResolvesKnownServiceIdsOnly() {
        assertEquals(AiProvider.DEEPSEEK, AiProvider.of("deepseek-api"))
        assertEquals(AiProvider.SILICON_FLOW, AiProvider.of("siliconflow-api"))
        assertEquals(AiProvider.CUSTOM, AiProvider.of("openai-compatible"))
        assertNull("历史网页版 serviceId 不再对应任何服务商", AiProvider.of("deepseek"))
        assertNull(AiProvider.of(null))
    }
}
