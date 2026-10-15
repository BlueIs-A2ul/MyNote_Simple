package com.mynote.app.data.ai

import com.mynote.app.data.db.AiMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

        assertEquals(2, messages.size)
        assertEquals(AiChatMessage.ROLE_SYSTEM, messages[0].role)
        assertEquals(AiPromptBuilder.SYSTEM_PROMPT, messages[0].content)
        assertEquals(AiChatMessage.ROLE_USER, messages[1].role)
        assertTrue(messages[1].content.contains("【笔记正文】"))
        assertTrue(messages[1].content.contains("# 标题"))
        assertTrue(messages[1].content.contains("正文"))
        assertTrue(messages[1].content.contains("帮我总结"))
    }

    @Test
    fun laterMessageOmitsNoteContextAndKeepsHistory() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "【笔记正文】\n# 标题\n正文\n\n【要求】\n帮我总结", id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "好的", id = 2)
        )
        val messages = AiApiMessageBuilder.build("标题", "正文", history, "第二问")

        assertEquals(4, messages.size)
        assertEquals(AiChatMessage.ROLE_SYSTEM, messages[0].role)
        assertEquals(AiChatMessage.ROLE_USER, messages[1].role)
        assertEquals(AiChatMessage.ROLE_ASSISTANT, messages[2].role)
        assertEquals("第二问", messages[3].content)
        assertFalse(messages[3].content.contains("【笔记正文】"))
        // 首条已带正文表头：幂等，不重复包裹
        assertEquals("【笔记正文】\n# 标题\n正文\n\n【要求】\n帮我总结", messages[1].content)
    }

    /** 落库的是裸输入（不是包好正文的那条），第二轮起仍必须把笔记正文带回上下文。 */
    @Test
    fun rawFirstUserMessageGetsNoteContextRestored() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "帮我总结", id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "好的", id = 2)
        )
        val messages = AiApiMessageBuilder.build("标题", "正文", history, "再短一点")

        assertEquals(4, messages.size)
        assertEquals(AiChatMessage.ROLE_USER, messages[1].role)
        assertTrue(messages[1].content.contains(AiPromptBuilder.NOTE_CONTEXT_HEADER))
        assertTrue(messages[1].content.contains("# 标题"))
        assertTrue(messages[1].content.contains("正文"))
        assertTrue(messages[1].content.contains("帮我总结"))
        // 本轮新输入仍只发纯输入
        assertEquals("再短一点", messages[3].content)
    }

    /** 笔记正文为空时不包裹（与首轮口径一致）。 */
    @Test
    fun rawFirstUserMessageWithoutNoteBodyStaysRaw() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "你好", id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "好的", id = 2)
        )
        val messages = AiApiMessageBuilder.build("标题", "", history, "继续")

        assertEquals("你好", messages[1].content)
    }

    @Test
    fun blankHistoryContentIsFiltered() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "问", id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "", AiMessageEntity.STATUS_FAILED, id = 2),
            message(AiMessageEntity.ROLE_ASSISTANT, "半截", AiMessageEntity.STATUS_INTERRUPTED, id = 3)
        )
        val messages = AiApiMessageBuilder.build("标题", "正文", history, "继续")

        assertEquals(4, messages.size)
        // 首条为裸输入，请求时补回笔记正文；其后消息保持原样
        assertTrue(messages[1].content.contains(AiPromptBuilder.NOTE_CONTEXT_HEADER))
        assertTrue(messages[1].content.endsWith("问"))
        assertEquals(listOf("半截", "继续"), messages.drop(2).map { it.content })
    }

    @Test
    fun blankNoteContentOnlySendsInput() {
        val messages = AiApiMessageBuilder.build("", "", emptyList(), "你好")

        assertEquals(2, messages.size)
        assertEquals(AiChatMessage.ROLE_SYSTEM, messages[0].role)
        assertEquals("你好", messages[1].content)
    }

    @Test
    fun inputIsTrimmed() {
        val messages = AiApiMessageBuilder.build("", "", emptyList(), "  你好  ")

        assertEquals("你好", messages[1].content)
    }

    @Test
    fun imageMarkupIsStrippedFromNoteContext() {
        val messages = AiApiMessageBuilder.build("标题", "看图 ![](img/a.png) 结束", emptyList(), "问")

        assertFalse(messages[1].content.contains("![]("))
        assertTrue(messages[1].content.contains("看图"))
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

        assertEquals(2, messages.size)
        assertTrue(messages[1].content.contains("（正文过长，已截断）"))
        assertEquals(AiApiMessageBuilder.MAX_NOTE_CHARS, messages[1].content.count { it == 'x' })
    }

    @Test
    fun historyAtBudgetIsKeptIntact() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "u".repeat(20_000), id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "a".repeat(20_000), id = 2),
            message(AiMessageEntity.ROLE_USER, "v".repeat(20_000), id = 3)
        )
        val messages = AiApiMessageBuilder.build("标题", "正文", history, "继续")

        assertEquals(5, messages.size)
        // 首条是裸输入（历史里的落库形态），请求时补回笔记正文
        assertTrue(messages[1].content.contains(AiPromptBuilder.NOTE_CONTEXT_HEADER))
        assertTrue(messages[1].content.contains("正文"))
        assertEquals(listOf("a", "v", "继"), messages.drop(2).map { it.content.first().toString() })
    }

    @Test
    fun overBudgetByOneDropsSingleOldestMiddle() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "f".repeat(20_000), id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "m".repeat(20_000), id = 2),
            message(AiMessageEntity.ROLE_USER, "l".repeat(20_001), id = 3)
        )
        val messages = AiApiMessageBuilder.build("", "", history, "继续")

        assertEquals(4, messages.size)
        assertEquals(listOf("f", "l", "继"), messages.drop(1).map { it.content.first().toString() })
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

        assertEquals(5, messages.size)
        assertEquals(listOf('f', 'm', 'l', '继'), messages.drop(1).map { it.content.first() })
        assertEquals("m2", messages[2].content.take(2))
        assertEquals("latest", messages[3].content.take(6))
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

        assertEquals(4, messages.size)
        assertEquals("first", messages[1].content.take(5))
        assertEquals("latest", messages[2].content.take(6))
        assertEquals("继续", messages[3].content)
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

        assertEquals(4, messages.size)
        assertEquals(listOf("f", "l", "继"), messages.drop(1).map { it.content.first().toString() })
        assertTrue(messages.none { it.content.first() == 'm' })
    }

    @Test
    fun twoMessageHistoryIsNeverDropped() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "f".repeat(40_000), id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "l".repeat(40_000), id = 2)
        )
        val messages = AiApiMessageBuilder.build("", "", history, "继续")

        assertEquals(4, messages.size)
        assertEquals(listOf("f", "l", "继"), messages.drop(1).map { it.content.first().toString() })
    }

    @Test
    fun blankHistoryContentIsFilteredBeforeBudget() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "问", id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "", AiMessageEntity.STATUS_FAILED, id = 2),
            message(AiMessageEntity.ROLE_ASSISTANT, "答", id = 3)
        )
        val messages = AiApiMessageBuilder.build("", "", history, "继续")

        assertEquals(4, messages.size)
        assertEquals(listOf("问", "答", "继续"), messages.drop(1).map { it.content })
    }

    /** 大窗口端点：预算精确等于现状 60_000/20_000（coerceAtMost 不生效）。 */
    @Test
    fun largeContextKeepsCurrentBudgets() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "u".repeat(20_000), id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "a".repeat(20_000), id = 2),
            message(AiMessageEntity.ROLE_USER, "v".repeat(20_000), id = 3)
        )
        val messages = AiApiMessageBuilder.build("标题", "正文", history, "继续", contextTokens = 1_000_000)

        assertEquals(5, messages.size)
        assertEquals(listOf("a", "v", "继"), messages.drop(2).map { it.content.first().toString() })
    }

    /** 32k 窗口：总预算 22_400 → 正文 11_200 / 历史 11_200。 */
    @Test
    fun tightContextShrinksNoteBudget() {
        val content = "x".repeat(12_000)
        val messages = AiApiMessageBuilder.build("", content, emptyList(), "总结", contextTokens = 32_000)

        assertTrue(messages[1].content.contains("（正文过长，已截断）"))
        assertEquals(11_200, messages[1].content.count { it == 'x' })
    }

    @Test
    fun tightContextShrinksHistoryBudget() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "f".repeat(5_000), id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "m".repeat(5_000), id = 2),
            message(AiMessageEntity.ROLE_USER, "l".repeat(5_000), id = 3)
        )
        val messages = AiApiMessageBuilder.build("", "", history, "继续", contextTokens = 32_000)

        // 历史预算 11_200：15_000 超限，丢中间一条后 10_000 回到预算内
        assertEquals(4, messages.size)
        assertEquals(listOf("f", "l", "继"), messages.drop(1).map { it.content.first().toString() })
    }

    /** minOf 下限：正文上限永远不会被预算放大（1M 与刚好 40_000 边界都不超过 MAX_NOTE_CHARS）。 */
    @Test
    fun noteBudgetNeverExceedsDefaultCap() {
        val content = "x".repeat(AiApiMessageBuilder.MAX_NOTE_CHARS + 1)

        val huge = AiApiMessageBuilder.build("", content, emptyList(), "总结", contextTokens = 1_000_000)
        assertEquals(AiApiMessageBuilder.MAX_NOTE_CHARS, huge[1].content.count { it == 'x' })

        // 57_143 × 7 / 10 = 40_000（整数除法向下取整），正文下限仍取 MAX_NOTE_CHARS
        val mid = AiApiMessageBuilder.build("", content, emptyList(), "总结", contextTokens = 57_143)
        assertEquals(AiApiMessageBuilder.MAX_NOTE_CHARS, mid[1].content.count { it == 'x' })
    }

    @Test
    fun trimToCoreKeepsSystemFirstUserAndLatest() {
        val messages = listOf(
            AiChatMessage(AiChatMessage.ROLE_SYSTEM, "sys"),
            AiChatMessage(AiChatMessage.ROLE_USER, "首"),
            AiChatMessage(AiChatMessage.ROLE_ASSISTANT, "1"),
            AiChatMessage(AiChatMessage.ROLE_USER, "2"),
            AiChatMessage(AiChatMessage.ROLE_ASSISTANT, "3"),
            AiChatMessage(AiChatMessage.ROLE_USER, "尾")
        )

        val trimmed = AiApiMessageBuilder.trimToCore(messages)

        assertEquals(listOf("sys", "首", "尾"), trimmed.map { it.content })
        assertEquals(
            listOf(
                AiChatMessage.ROLE_SYSTEM,
                AiChatMessage.ROLE_USER,
                AiChatMessage.ROLE_USER
            ),
            trimmed.map { it.role }
        )
    }

    @Test
    fun trimToCoreReturnsSameWhenNoMiddle() {
        val messages = listOf(
            AiChatMessage(AiChatMessage.ROLE_SYSTEM, "sys"),
            AiChatMessage(AiChatMessage.ROLE_USER, "首"),
            AiChatMessage(AiChatMessage.ROLE_USER, "尾")
        )

        assertSame(messages, AiApiMessageBuilder.trimToCore(messages))
    }

    @Test
    fun trimToCoreWithoutSystemKeepsFirstUserAndLatest() {
        val messages = listOf(
            AiChatMessage(AiChatMessage.ROLE_USER, "首"),
            AiChatMessage(AiChatMessage.ROLE_ASSISTANT, "1"),
            AiChatMessage(AiChatMessage.ROLE_ASSISTANT, "2"),
            AiChatMessage(AiChatMessage.ROLE_USER, "尾")
        )

        assertEquals(listOf("首", "尾"), AiApiMessageBuilder.trimToCore(messages).map { it.content })
    }

    @Test
    fun trimToCoreDeduplicatesWhenFirstUserIsLatest() {
        val messages = listOf(
            AiChatMessage(AiChatMessage.ROLE_SYSTEM, "sys"),
            AiChatMessage(AiChatMessage.ROLE_ASSISTANT, "1"),
            AiChatMessage(AiChatMessage.ROLE_ASSISTANT, "2"),
            AiChatMessage(AiChatMessage.ROLE_USER, "尾")
        )

        val trimmed = AiApiMessageBuilder.trimToCore(messages)

        assertEquals(listOf("sys", "尾"), trimmed.map { it.content })
    }

    @Test
    fun trimToCoreKeepsDistinctMessagesWithSameContent() {
        val messages = listOf(
            AiChatMessage(AiChatMessage.ROLE_SYSTEM, "sys"),
            AiChatMessage(AiChatMessage.ROLE_USER, "同"),
            AiChatMessage(AiChatMessage.ROLE_ASSISTANT, "答"),
            AiChatMessage(AiChatMessage.ROLE_USER, "同")
        )

        val trimmed = AiApiMessageBuilder.trimToCore(messages)

        assertEquals("两条不同实例但同内容的 user 都应保留", listOf("sys", "同", "同"), trimmed.map { it.content })
    }
}
