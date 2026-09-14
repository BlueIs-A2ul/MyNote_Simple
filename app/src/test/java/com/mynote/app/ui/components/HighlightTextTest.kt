package com.mynote.app.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** buildHighlighted 纯函数单测：不依赖 MaterialTheme 的纯 JUnit4 测试。 */
class HighlightTextTest {

    private val highlightBackground = Color(0xFF0000FF)

    /** 空 query 返回与原文本相同的 AnnotatedString，且无 spanStyles。 */
    @Test
    fun blankQuery_returnsPlainTextWithoutSpans() {
        val result = buildHighlighted("会议纪要ABC", "", highlightBackground)
        assertEquals("会议纪要ABC", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    /** 单词命中：文本不变，恰好一条 span，范围 [4,7)。 */
    @Test
    fun singleHit_hasOneSpanWithExpectedRange() {
        val result = buildHighlighted("会议纪要ABC", "abc", highlightBackground)
        assertEquals("会议纪要ABC", result.text)
        assertEquals(1, result.spanStyles.size)
        assertEquals(4, result.spanStyles[0].start)
        assertEquals(7, result.spanStyles[0].end)
    }

    /** 多命中："aaa aaa" 查 "aa" 得两条 span，范围 [0,2) 与 [4,6)。 */
    @Test
    fun multipleHits_haveTwoSpans() {
        val result = buildHighlighted("aaa aaa", "aa", highlightBackground)
        assertEquals(2, result.spanStyles.size)
        assertEquals(0, result.spanStyles[0].start)
        assertEquals(2, result.spanStyles[0].end)
        assertEquals(4, result.spanStyles[1].start)
        assertEquals(6, result.spanStyles[1].end)
    }

    /** 无命中：spanStyles 为空。 */
    @Test
    fun noHit_hasNoSpans() {
        val result = buildHighlighted("会议纪要", "xyz", highlightBackground)
        assertEquals("会议纪要", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    /** 大小写不敏感：大写 query "HELLO" 命中小写 "hello"。 */
    @Test
    fun caseInsensitiveMatch_highlightsLowercaseText() {
        val result = buildHighlighted("say hello world", "HELLO", highlightBackground)
        assertEquals(1, result.spanStyles.size)
        assertEquals(4, result.spanStyles[0].start)
        assertEquals(9, result.spanStyles[0].end)
    }

    /** 中文查询："会议纪要" 命中一次。 */
    @Test
    fun chineseQuery_matchesOnce() {
        val result = buildHighlighted("会议纪要ABC", "会议纪要", highlightBackground)
        assertEquals(1, result.spanStyles.size)
        assertEquals(0, result.spanStyles[0].start)
        assertEquals(4, result.spanStyles[0].end)
    }

    // ---------- snippetForHighlight 搜索摘要窗口 ----------

    /** 命中在开头附近（< maxChars）：直接取前缀，不截断。 */
    @Test
    fun snippet_hitNearStart_takesPlainPrefix() {
        val text = "abcdefghij" + "填充".repeat(50)
        assertEquals(text.take(80), snippetForHighlight(text, "abc", 80))
    }

    /** 命中在深处：取命中位置附近窗口，首尾省略号标记截断。 */
    @Test
    fun snippet_hitDeep_takesWindowAroundHit() {
        val text = "x".repeat(100) + "QUERY" + "y".repeat(100)
        val result = snippetForHighlight(text, "QUERY", 80)
        val start = 100 - 80 / 3
        assertEquals("…" + text.substring(start, start + 80) + "…", result)
        assertTrue(result.contains("QUERY"))
        assertTrue(result.length <= 80 + 2)
    }

    /** 无命中：取前缀。 */
    @Test
    fun snippet_noHit_takesPrefix() {
        assertEquals("x".repeat(80), snippetForHighlight("x".repeat(200), "zzz", 80))
    }

    /** 空白 query：取前缀（与搜索态外行为一致）。 */
    @Test
    fun snippet_blankQuery_takesPrefix() {
        assertEquals("x".repeat(80), snippetForHighlight("x".repeat(200), "", 80))
    }

    /** 命中恰好越过前缀边界：仍走窗口分支；文本不足 maxChars 时无省略号。 */
    @Test
    fun snippet_shortText_noEllipsis() {
        val text = "y".repeat(30) + "HIT"
        val result = snippetForHighlight(text, "HIT", 80)
        assertEquals(text, result)
    }
}
