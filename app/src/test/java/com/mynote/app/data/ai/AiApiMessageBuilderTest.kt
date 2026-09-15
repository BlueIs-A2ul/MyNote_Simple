package com.mynote.app.data.ai

import com.mynote.app.data.db.AiMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiApiMessageBuilderTest {

    private fun message(
        role: String,
        content: String,
        status: String = AiMessageEntity.STATUS_DONE,
        id: Long = 1
    ) = AiMessageEntity(id, 1, role, content, status, 1)

    @Test
    fun firstMessageIncludesNoteContext() {
        val messages = AiApiMessageBuilder.build("标题", "正文", emptyList(), "帮我总结")

        assertEquals(1, messages.size)
        assertEquals(AiChatMessage.ROLE_USER, messages[0].role)
        assertTrue(messages[0].content.contains("【笔记正文】"))
        assertTrue(messages[0].content.contains("# 标题"))
        assertTrue(messages[0].content.contains("正文"))
        assertTrue(messages[0].content.contains("帮我总结"))
    }

    @Test
    fun laterMessageOmitsNoteContextAndKeepsHistory() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "【笔记正文】\n# 标题\n正文\n\n【要求】\n帮我总结", id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "好的", id = 2)
        )
        val messages = AiApiMessageBuilder.build("标题", "正文", history, "第二问")

        assertEquals(3, messages.size)
        assertEquals(AiChatMessage.ROLE_USER, messages[0].role)
        assertEquals(AiChatMessage.ROLE_ASSISTANT, messages[1].role)
        assertEquals("第二问", messages[2].content)
        assertFalse(messages[2].content.contains("【笔记正文】"))
    }

    @Test
    fun blankHistoryContentIsFiltered() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "问", id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "", AiMessageEntity.STATUS_FAILED, id = 2),
            message(AiMessageEntity.ROLE_ASSISTANT, "半截", AiMessageEntity.STATUS_INTERRUPTED, id = 3)
        )
        val messages = AiApiMessageBuilder.build("标题", "正文", history, "继续")

        assertEquals(3, messages.size)
        assertEquals(listOf("问", "半截", "继续"), messages.map { it.content })
    }

    @Test
    fun blankNoteContentOnlySendsInput() {
        val messages = AiApiMessageBuilder.build("", "", emptyList(), "你好")

        assertEquals(1, messages.size)
        assertEquals("你好", messages[0].content)
    }

    @Test
    fun inputIsTrimmed() {
        val messages = AiApiMessageBuilder.build("", "", emptyList(), "  你好  ")

        assertEquals("你好", messages[0].content)
    }

    @Test
    fun imageMarkupIsStrippedFromNoteContext() {
        val messages = AiApiMessageBuilder.build("标题", "看图 ![](img/a.png) 结束", emptyList(), "问")

        assertFalse(messages[0].content.contains("![]("))
        assertTrue(messages[0].content.contains("看图"))
    }
}
