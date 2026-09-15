package com.mynote.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekSseParserTest {

    @Test
    fun parsesDeltaContent() {
        val frame = DeepSeekSseParser.parse(
            """data: {"id":"1","choices":[{"delta":{"content":"你好","role":"assistant"},"index":0,"finish_reason":null}]}"""
        )
        assertEquals("你好", frame?.text)
        assertNull(frame?.finishReason)
        assertTrue(frame?.done == false)
    }

    @Test
    fun emptyContentIsParsedButNoFinish() {
        val frame = DeepSeekSseParser.parse(
            """data: {"choices":[{"delta":{"content":"","role":"assistant"},"index":0,"finish_reason":null}]}"""
        )
        assertEquals("", frame?.text)
    }

    @Test
    fun parsesFinishReasonOnLastChunk() {
        val frame = DeepSeekSseParser.parse(
            """data: {"choices":[{"delta":{"content":""},"index":0,"finish_reason":"stop"}],"usage":{"total_tokens":1}}"""
        )
        assertEquals("stop", frame?.finishReason)
    }

    @Test
    fun parsesDoneMarker() {
        val frame = DeepSeekSseParser.parse("data: [DONE]")
        assertEquals(true, frame?.done)
    }

    @Test
    fun ignoresReasoningContent() {
        val frame = DeepSeekSseParser.parse(
            """data: {"choices":[{"delta":{"reasoning_content":"思考中"},"index":0}]}"""
        )
        assertNull(frame)
    }

    @Test
    fun parsesErrorFrame() {
        val frame = DeepSeekSseParser.parse("""data: {"error":{"message":"Invalid token","type":"authentication_error"}}""")
        assertEquals("Invalid token", frame?.error)
    }

    @Test
    fun ignoresNoiseLines() {
        assertNull(DeepSeekSseParser.parse(""))
        assertNull(DeepSeekSseParser.parse("   "))
        assertNull(DeepSeekSseParser.parse(": keep-alive"))
        assertNull(DeepSeekSseParser.parse("event: message"))
        assertNull(DeepSeekSseParser.parse("data:"))
        assertNull(DeepSeekSseParser.parse("data: not-json"))
        assertNull(DeepSeekSseParser.parse("""data: {"choices":[]}"""))
    }

    @Test
    fun handlesTrailingWhitespaceAndCr() {
        val frame = DeepSeekSseParser.parse(
            "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}  \r"
        )
        assertEquals("好", frame?.text)
    }
}
