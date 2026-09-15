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

    @Test
    fun budgetConstantsAreFrozen() {
        assertEquals(20_000, AiApiMessageBuilder.MAX_NOTE_CHARS)
        assertEquals(60_000, AiApiMessageBuilder.MAX_HISTORY_CHARS)
    }

    @Test
    fun noteBodyOverBudgetIsTruncatedInFirstMessage() {
        val content = "x".repeat(AiApiMessageBuilder.MAX_NOTE_CHARS + 1)
        val messages = AiApiMessageBuilder.build("", content, emptyList(), "总结")

        assertEquals(1, messages.size)
        assertTrue(messages[0].content.contains("（正文过长，已截断）"))
        assertEquals(AiApiMessageBuilder.MAX_NOTE_CHARS, messages[0].content.count { it == 'x' })
    }

    @Test
    fun historyAtBudgetIsKeptIntact() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "u".repeat(20_000), id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "a".repeat(20_000), id = 2),
            message(AiMessageEntity.ROLE_USER, "v".repeat(20_000), id = 3)
        )
        val messages = AiApiMessageBuilder.build("标题", "正文", history, "继续")

        assertEquals(4, messages.size)
        assertEquals(listOf("u", "a", "v", "继"), messages.map { it.content.first().toString() })
    }

    @Test
    fun overBudgetByOneDropsSingleOldestMiddle() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "f".repeat(20_000), id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "m".repeat(20_000), id = 2),
            message(AiMessageEntity.ROLE_USER, "l".repeat(20_001), id = 3)
        )
        val messages = AiApiMessageBuilder.build("", "", history, "继续")

        assertEquals(3, messages.size)
        assertEquals(listOf("f", "l", "继"), messages.map { it.content.first().toString() })
    }

    @Test
    fun overBudgetDropsOldestMiddleMessagesKeepingOrder() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "first".padEnd(10_000, 'f'), id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "m1".padEnd(30_000, '1'), id = 2),
            message(AiMessageEntity.ROLE_USER, "m2".padEnd(30_000, '2'), id = 3),
            message(AiMessageEntity.ROLE_ASSISTANT, "latest".padEnd(1_000, 'l'), id = 4)
        )
        val messages = AiApiMessageBuilder.build("", "", history, "继续")

        assertEquals(4, messages.size)
        assertEquals(listOf('f', 'm', 'l', '继'), messages.map { it.content.first() })
        assertEquals("m2", messages[1].content.take(2))
        assertEquals("latest", messages[2].content.take(6))
    }

    @Test
    fun dropsOldestMiddleUntilBackWithinBudget() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "first".padEnd(30_000, 'f'), id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "m1".padEnd(30_000, '1'), id = 2),
            message(AiMessageEntity.ROLE_USER, "m2".padEnd(30_000, '2'), id = 3),
            message(AiMessageEntity.ROLE_ASSISTANT, "latest".padEnd(1_000, 'l'), id = 4)
        )
        val messages = AiApiMessageBuilder.build("", "", history, "继续")

        assertEquals(3, messages.size)
        assertEquals("first", messages[0].content.take(5))
        assertEquals("latest", messages[1].content.take(6))
        assertEquals("继续", messages[2].content)
        assertFalse(messages.any { it.content.startsWith("m") })
    }

    @Test
    fun firstAndLatestKeptEvenIfAloneOverBudget() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "f".repeat(50_000), id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "m".repeat(50_000), id = 2),
            message(AiMessageEntity.ROLE_USER, "l".repeat(50_000), id = 3)
        )
        val messages = AiApiMessageBuilder.build("", "", history, "继续")

        assertEquals(3, messages.size)
        assertEquals(listOf("f", "l", "继"), messages.map { it.content.first().toString() })
        assertTrue(messages.none { it.content.first() == 'm' })
    }

    @Test
    fun twoMessageHistoryIsNeverDropped() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "f".repeat(40_000), id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "l".repeat(40_000), id = 2)
        )
        val messages = AiApiMessageBuilder.build("", "", history, "继续")

        assertEquals(3, messages.size)
        assertEquals(listOf("f", "l", "继"), messages.map { it.content.first().toString() })
    }

    @Test
    fun blankHistoryContentIsFilteredBeforeBudget() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "问", id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "", AiMessageEntity.STATUS_FAILED, id = 2),
            message(AiMessageEntity.ROLE_ASSISTANT, "答", id = 3)
        )
        val messages = AiApiMessageBuilder.build("", "", history, "继续")

        assertEquals(3, messages.size)
        assertEquals(listOf("问", "答", "继续"), messages.map { it.content })
    }
}
