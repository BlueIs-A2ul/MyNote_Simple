package com.mynote.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun parseChatIdReturnsNullForNonChatPath() {
        assertNull(driver.parseChatId("https://chat.deepseek.com/xxx"))
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
    fun sendMessageJsEscapesBackslashAndEmoji() {
        val js = driver.sendMessageJs("C:\\tmp\\notes 😀")
        assertTrue(js.contains("C:\\\\tmp\\\\notes"))
        assertTrue(js.contains("😀"))
    }

    @Test
    fun sendMessageJsEscapesUnicodeLineSeparators() {
        val js = driver.sendMessageJs("前\u2028中\u2029后")
        assertFalse(js.contains("\u2028"))
        assertFalse(js.contains("\u2029"))
        assertTrue(js.contains("\\u2028"))
        assertTrue(js.contains("\\u2029"))
    }

    @Test
    fun sendMessageJsClearsPayloadAfterRead() {
        assertTrue(driver.sendMessageJs("x").contains("window.__mynoteText = null"))
    }

    @Test
    fun sendScriptDoesNotBlindlyClickLastButton() {
        assertFalse(driver.sendMessageJs("x").contains("enabled[enabled.length - 1]"))
    }

    @Test
    fun observeScriptThrottlesHeavyScanAndTracksInitialText() {
        val js = driver.observeReplyJs()
        assertTrue(js.contains("initialText"))
        assertTrue(js.contains("now - lastScan < 150"))
        assertTrue(js.contains("tick(true)"))
    }

    @Test
    fun scriptsSpeakBridgeProtocol() {
        assertTrue(driver.loginCheckJs().contains("loginState"))
        assertTrue(driver.observeReplyJs().contains("replyChunk"))
        assertTrue(driver.observeReplyJs().contains("replyDone"))
        assertTrue(driver.newChatJs().contains("新对话"))
        assertTrue(driver.stopObservingJs().contains("__mynoteObserver"))
    }

    @Test
    fun scriptsGuardEmitCalls() {
        val guard = "{ try { if (window.__mynote && window.__mynote.emit) window.__mynote.emit(type, payload); } catch (e) {} }"
        assertTrue(driver.loginCheckJs().contains(guard))
        assertTrue(driver.sendMessageJs("x").contains(guard))
        assertTrue(driver.observeReplyJs().contains(guard))
        assertTrue(driver.newChatJs().contains(guard))
        assertTrue(driver.stopObservingJs().contains(guard))
        assertTrue(driver.stopGeneratingJs().contains(guard))
    }
}
