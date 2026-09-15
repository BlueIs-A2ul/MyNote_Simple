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

    @Test
    fun noteBodyOverLimitIsTruncatedWithNotice() {
        val text = AiPromptBuilder.build(
            noteTitle = "",
            noteContent = "0123456789",
            userInput = "总结",
            includeNoteContext = true,
            maxNoteChars = 4
        )
        assertTrue(text.contains("【笔记正文】\n0123（正文过长，已截断）"))
        assertFalse(text.contains("456789"))
        assertTrue(text.contains("【要求】\n总结"))
    }

    @Test
    fun noteBodyAtLimitIsNotTruncated() {
        val text = AiPromptBuilder.build("", "0123", "总结", includeNoteContext = true, maxNoteChars = 4)
        assertFalse(text.contains("（正文过长，已截断）"))
        assertTrue(text.contains("0123"))
    }

    @Test
    fun defaultMaxNoteCharsKeepsFullBody() {
        val text = AiPromptBuilder.build("", "0123456789", "总结", includeNoteContext = true)
        assertFalse(text.contains("（正文过长，已截断）"))
        assertTrue(text.contains("0123456789"))
    }

    @Test
    fun truncationCountsPlainTextAfterImageMarkupStripped() {
        val text = AiPromptBuilder.build(
            noteTitle = "",
            noteContent = "ab![](img/x.png)cd",
            userInput = "问",
            includeNoteContext = true,
            maxNoteChars = 3
        )
        assertTrue(text.contains("【笔记正文】\nabc（正文过长，已截断）"))
        assertFalse(text.contains("![]("))
    }

    @Test
    fun noteTitleIsNotCountedTowardLimit() {
        val text = AiPromptBuilder.build(
            noteTitle = "很长的标题文本",
            noteContent = "ab",
            userInput = "问",
            includeNoteContext = true,
            maxNoteChars = 2
        )
        assertTrue(text.contains("# 很长的标题文本"))
        assertFalse(text.contains("（正文过长，已截断）"))
    }

    @Test
    fun maxNoteCharsIgnoredWhenNoteContextExcluded() {
        val text = AiPromptBuilder.build(
            noteTitle = "标题",
            noteContent = "很长的正文",
            userInput = "只发输入",
            includeNoteContext = false,
            maxNoteChars = 1
        )
        assertEquals("只发输入", text)
    }
}
