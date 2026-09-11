package com.mynote.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiPromptBuilderTest {

    @Test
    fun newContextIncludesTitleBodyAndRequirement() {
        val text = AiPromptBuilder.build(
            noteTitle = "旅行清单",
            noteContent = "护照和充电器",
            userInput = "帮我补充几项",
            includeNoteContext = true
        )
        assertTrue(text.startsWith("【笔记正文】"))
        assertTrue(text.contains("# 旅行清单"))
        assertTrue(text.contains("护照和充电器"))
        assertTrue(text.contains("【要求】\n帮我补充几项"))
    }

    @Test
    fun existingContextSendsInputOnly() {
        val text = AiPromptBuilder.build("t", "c", "再短一点", includeNoteContext = false)
        assertEquals("再短一点", text)
    }

    @Test
    fun imageMarkupIsStripped() {
        val text = AiPromptBuilder.build(
            noteTitle = "",
            noteContent = "前文![](img/a.webp)后文",
            userInput = "润色",
            includeNoteContext = true
        )
        assertFalse(text.contains("img/a.webp"))
        assertTrue(text.contains("前文后文"))
    }

    @Test
    fun blankNoteContentFallsBackToInputOnly() {
        val text = AiPromptBuilder.build("", "   ", "随便聊聊", includeNoteContext = true)
        assertEquals("随便聊聊", text)
    }

    @Test
    fun inputIsTrimmed() {
        assertEquals("问题", AiPromptBuilder.build("", "", "  问题  ", includeNoteContext = false))
    }
}
