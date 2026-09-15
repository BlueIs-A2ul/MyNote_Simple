package com.mynote.app.ui.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/** Markdown → 纯文本（插入正文/存为新笔记用）纯函数单测。 */
class MarkdownPlainTextTest {

    @Test
    fun headingLosesMarker() {
        assertEquals("标题", MarkdownPlainText.convert("# 标题"))
    }

    @Test
    fun inlineMarkersAreStripped() {
        assertEquals("粗", MarkdownPlainText.convert("**粗**"))
    }

    @Test
    fun bulletsKeepDashPrefix() {
        assertEquals("- 一\n- 二", MarkdownPlainText.convert("- 一\n- 二"))
    }

    @Test
    fun nestedListUsesIndent() {
        assertEquals("- 父\n  - 子", MarkdownPlainText.convert("- 父\n  - 子"))
    }

    @Test
    fun orderedListNumbersAdvance() {
        assertEquals("3. 三\n4. 四", MarkdownPlainText.convert("3. 三\n4. 四"))
    }

    @Test
    fun taskListKeepsCheckbox() {
        assertEquals(
            "- [x] 完成\n- [ ] 未完成",
            MarkdownPlainText.convert("- [x] 完成\n- [ ] 未完成")
        )
    }

    @Test
    fun quotePrefixesLines() {
        assertEquals("> 引用一\n> 引用二", MarkdownPlainText.convert("> 引用一\n> 引用二"))
    }

    @Test
    fun codeBlockLosesFence() {
        assertEquals("val x = 1", MarkdownPlainText.convert("```kotlin\nval x = 1\n```"))
    }

    @Test
    fun ruleStaysDashes() {
        assertEquals("---", MarkdownPlainText.convert("---"))
    }

    @Test
    fun tableRowsJoinWithPipes() {
        assertEquals(
            "A | B\n1 | 2",
            MarkdownPlainText.convert("| A | B |\n| --- | --- |\n| 1 | 2 |")
        )
    }

    @Test
    fun linkExpandsUrl() {
        assertEquals("文档（https://a.b）", MarkdownPlainText.convert("[文档](https://a.b)"))
    }

    @Test
    fun blocksSeparatedByBlankLine() {
        assertEquals("第一段\n\n- 项", MarkdownPlainText.convert("第一段\n\n- 项"))
    }
}
