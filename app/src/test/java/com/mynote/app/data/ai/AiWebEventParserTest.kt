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
}
