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
class AiWebEventParserTest {

    @Test
    fun parsesLoginState() {
        val event = AiWebEventParser.parse("""{"type":"loginState","payload":{"loggedIn":true}}""")
        assertEquals(AiWebEvent.LoginState(true), event)
    }

    @Test
    fun parsesReplyChunkAndDone() {
        assertEquals(
            AiWebEvent.ReplyChunk("你好"),
            AiWebEventParser.parse("""{"type":"replyChunk","payload":{"text":"你好"}}""")
        )
        assertEquals(
            AiWebEvent.ReplyDone("完整"),
            AiWebEventParser.parse("""{"type":"replyDone","payload":{"text":"完整"}}""")
        )
    }

    @Test
    fun sendFailedBecomesReplyError() {
        val event = AiWebEventParser.parse("""{"type":"sendFailed","payload":{"reason":"没找到"}}""")
        assertTrue(event is AiWebEvent.ReplyError)
        assertEquals("没找到", (event as AiWebEvent.ReplyError).reason)
    }

    @Test
    fun unknownTypeAndMalformedJsonReturnNull() {
        assertNull(AiWebEventParser.parse("""{"type":"whatever","payload":{}}"""))
        assertNull(AiWebEventParser.parse("not json"))
    }

    @Test
    fun parsesChatIdAndRejectsBlank() {
        assertEquals(
            AiWebEvent.ChatId("abc-123"),
            AiWebEventParser.parse("""{"type":"chatId","payload":{"id":"abc-123"}}""")
        )
        assertNull(AiWebEventParser.parse("""{"type":"chatId","payload":{"id":""}}"""))
        assertNull(AiWebEventParser.parse("""{"type":"chatId","payload":{"id":null}}"""))
    }

    @Test
    fun replyChunkWithNullOrEmptyTextIsIgnored() {
        assertNull(AiWebEventParser.parse("""{"type":"replyChunk","payload":{"text":null}}"""))
        assertNull(AiWebEventParser.parse("""{"type":"replyChunk","payload":{"text":""}}"""))
        assertNull(AiWebEventParser.parse("""{"type":"replyChunk","payload":{}}"""))
    }

    @Test
    fun replyDoneWithNullTextBecomesEmpty() {
        assertEquals(
            AiWebEvent.ReplyDone(""),
            AiWebEventParser.parse("""{"type":"replyDone","payload":{"text":null}}""")
        )
        assertEquals(
            AiWebEvent.ReplyDone(""),
            AiWebEventParser.parse("""{"type":"replyDone","payload":{}}""")
        )
    }

    @Test
    fun replyErrorWithNullOrEmptyReasonFallsBackToDefault() {
        assertEquals(
            AiWebEvent.ReplyError("未知错误"),
            AiWebEventParser.parse("""{"type":"replyError","payload":{"reason":null}}""")
        )
        assertEquals(
            AiWebEvent.ReplyError("未知错误"),
            AiWebEventParser.parse("""{"type":"sendFailed","payload":{"reason":""}}""")
        )
    }

    @Test
    fun missingPayloadDoesNotCrash() {
        assertEquals(AiWebEvent.LoginState(false), AiWebEventParser.parse("""{"type":"loginState"}"""))
        assertNull(AiWebEventParser.parse("""{"type":"chatId"}"""))
        assertEquals(AiWebEvent.ReplyDone(""), AiWebEventParser.parse("""{"type":"replyDone"}"""))
        assertEquals(AiWebEvent.PageReady, AiWebEventParser.parse("""{"type":"pageReady"}"""))
        assertNull(AiWebEventParser.parse("""{"type":"whatever"}"""))
    }
}
