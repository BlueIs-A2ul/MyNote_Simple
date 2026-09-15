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
    fun parsesFinishReasonAndUsageOnLastChunk() {
        val frame = DeepSeekSseParser.parse(
            """data: {"choices":[{"delta":{"content":""},"index":0,"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""
        )
        assertEquals("stop", frame?.finishReason)
        assertEquals(AiUsage(10, 5, 15), frame?.usage)
    }

    @Test
    fun parsesDoneMarker() {
        val frame = DeepSeekSseParser.parse("data: [DONE]")
        assertEquals(true, frame?.done)
    }

    @Test
    fun parsesReasoningContent() {
        val frame = DeepSeekSseParser.parse(
            """data: {"choices":[{"delta":{"reasoning_content":"思考中"},"index":0}]}"""
        )
        assertEquals("思考中", frame?.reasoning)
        assertNull(frame?.text)
        assertNull(frame?.finishReason)
    }

    @Test
    fun parsesReasoningAndContentTogether() {
        val frame = DeepSeekSseParser.parse(
            """data: {"choices":[{"delta":{"reasoning_content":"想","content":"答"}}]}"""
        )
        assertEquals("想", frame?.reasoning)
        assertEquals("答", frame?.text)
    }

    @Test
    fun parsesUsageOnEmptyChoices() {
        val frame = DeepSeekSseParser.parse(
            """data: {"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}"""
        )
        assertEquals(AiUsage(3, 1, 4), frame?.usage)
        assertNull(frame?.text)
        assertNull(frame?.reasoning)
    }

    @Test
    fun toleratesMissingOrNullUsage() {
        val partial = DeepSeekSseParser.parse(
            """data: {"choices":[{"delta":{"content":"好"}}],"usage":{"prompt_tokens":3,"completion_tokens":null}}"""
        )
        assertEquals("好", partial?.text)
        assertEquals(AiUsage(3, 0, 0), partial?.usage)

        val nullUsage = DeepSeekSseParser.parse(
            """data: {"choices":[{"delta":{"content":"好"}}],"usage":null}"""
        )
        assertEquals("好", nullUsage?.text)
        assertNull(nullUsage?.usage)

        val absent = DeepSeekSseParser.parse("""data: {"choices":[{"delta":{"content":"好"}}]}""")
        assertNull(absent?.usage)
    }

    @Test
    fun ignoresUsageWithoutTokenFields() {
        assertNull(
            DeepSeekSseParser.parse("""data: {"choices":[],"usage":{"prompt_tokens":null}}""")
        )
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
