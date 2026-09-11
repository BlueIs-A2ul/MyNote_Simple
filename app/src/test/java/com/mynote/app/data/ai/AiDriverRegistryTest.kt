package com.mynote.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiDriverRegistryTest {

    private class FakeDriver(override val id: String) : AiWebDriver {
        override val displayName = id
        override val homeUrl = "https://example.com/"
        override fun chatUrl(remoteChatId: String) = homeUrl + remoteChatId
        override fun parseChatId(url: String): String? = null
        override fun loginCheckJs() = ""
        override fun newChatJs() = ""
        override fun sendMessageJs(text: String) = ""
        override fun observeReplyJs() = ""
        override fun stopObservingJs() = ""
        override fun stopGeneratingJs() = ""
    }

    @Test
    fun defaultIsFirstAndFindMatchesId() {
        val a = FakeDriver("a")
        val b = FakeDriver("b")
        val registry = AiDriverRegistry(listOf(a, b))
        assertEquals(a, registry.default)
        assertEquals(b, registry.find("b"))
        assertNull(registry.find("missing"))
        assertEquals(listOf(a, b), registry.all)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyRegistryRejected() {
        AiDriverRegistry(emptyList())
    }
}
