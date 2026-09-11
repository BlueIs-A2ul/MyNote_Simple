package com.mynote.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeepSeekDriverTest {

    private val driver = DeepSeekDriver()

    @Test
    fun parseChatIdFromChatUrl() {
        assertEquals(
            "abc-123",
            driver.parseChatId("https://chat.deepseek.com/a/chat/s/abc-123")
        )
    }

    @Test
    fun parseChatIdReturnsNullForHome() {
        assertNull(driver.parseChatId("https://chat.deepseek.com/"))
    }

    @Test
    fun chatUrlBuildsDeepLink() {
        assertEquals("https://chat.deepseek.com/a/chat/s/x9", driver.chatUrl("x9"))
    }

    @Test
    fun sendMessageJsEscapesQuotesAndNewlines() {
        val js = driver.sendMessageJs("他\"说\"\n第二行")
        assertTrue(js.contains("window.__mynoteText = "))
        assertTrue(js.contains("\\\"说\\\""))
        assertTrue(js.contains("\\n"))
    }

    @Test
    fun scriptsSpeakBridgeProtocol() {
        assertTrue(driver.loginCheckJs().contains("loginState"))
        assertTrue(driver.observeReplyJs().contains("replyChunk"))
        assertTrue(driver.observeReplyJs().contains("replyDone"))
        assertTrue(driver.newChatJs().contains("新对话"))
        assertTrue(driver.stopObservingJs().contains("__mynoteObserver"))
    }
}
