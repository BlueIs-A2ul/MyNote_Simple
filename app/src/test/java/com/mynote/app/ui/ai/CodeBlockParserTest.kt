package com.mynote.app.ui.ai

import com.mynote.app.ui.ai.CodeBlockParser.Segment
import org.junit.Assert.assertEquals
import org.junit.Test

class CodeBlockParserTest {

    @Test
    fun plainTextIsSingleSegment() {
        assertEquals(
            listOf<Segment>(Segment.Text("第一行\n第二行")),
            CodeBlockParser.parse("第一行\n第二行")
        )
    }

    @Test
    fun singleCodeBlockSplitsSurroundingText() {
        val segments = CodeBlockParser.parse("说明\n```\nval x = 1\n```\n结束")

        assertEquals(3, segments.size)
        assertEquals(Segment.Text("说明"), segments[0])
        assertEquals(Segment.Code(null, "val x = 1"), segments[1])
        assertEquals(Segment.Text("结束"), segments[2])
    }

    @Test
    fun languageMarkerIsCapturedAndTrimmed() {
        assertEquals(
            listOf<Segment>(Segment.Code("kotlin", "fun main() = Unit")),
            CodeBlockParser.parse("``` kotlin \nfun main() = Unit\n```")
        )
    }

    @Test
    fun multipleCodeBlocksKeepOrder() {
        val text = "开头\n```kotlin\nval a = 1\n```\n中间\n```python\nprint(1)\n```\n结尾"
        val segments = CodeBlockParser.parse(text)

        assertEquals(5, segments.size)
        assertEquals(Segment.Text("开头"), segments[0])
        assertEquals(Segment.Code("kotlin", "val a = 1"), segments[1])
        assertEquals(Segment.Text("中间"), segments[2])
        assertEquals(Segment.Code("python", "print(1)"), segments[3])
        assertEquals(Segment.Text("结尾"), segments[4])
    }

    @Test
    fun unclosedFenceExtendsToEnd() {
        val segments = CodeBlockParser.parse("前言\n```java\nint a = 1;\nint b = 2;")

        assertEquals(2, segments.size)
        assertEquals(Segment.Text("前言"), segments[0])
        assertEquals(Segment.Code("java", "int a = 1;\nint b = 2;"), segments[1])
    }

    @Test
    fun emptyCodeBlockIsIgnored() {
        assertEquals(emptyList<Segment>(), CodeBlockParser.parse("```\n```"))
        assertEquals(emptyList<Segment>(), CodeBlockParser.parse("```kotlin\n```"))
    }

    @Test
    fun adjacentTextMergesAcrossEmptyCodeBlock() {
        assertEquals(
            listOf<Segment>(Segment.Text("前\n后")),
            CodeBlockParser.parse("前\n```\n```\n后")
        )
    }

    @Test
    fun fenceAllowsLeadingIndent() {
        assertEquals(
            listOf<Segment>(Segment.Code("python", "print(1)")),
            CodeBlockParser.parse("  ```python\nprint(1)\n    ```")
        )
    }

    @Test
    fun crlfIsParsedLikeLf() {
        val segments = CodeBlockParser.parse("说明\r\n```kotlin\r\nval x = 1\r\n```\r\n结束")

        assertEquals(3, segments.size)
        assertEquals(Segment.Text("说明"), segments[0])
        assertEquals(Segment.Code("kotlin", "val x = 1"), segments[1])
        assertEquals(Segment.Text("结束"), segments[2])
    }

    @Test
    fun blankLinesInsideCodeArePreserved() {
        assertEquals(
            listOf<Segment>(Segment.Code(null, "a\n\nb")),
            CodeBlockParser.parse("```\na\n\nb\n```")
        )
    }

    @Test
    fun emptyInputReturnsEmptyList() {
        assertEquals(emptyList<Segment>(), CodeBlockParser.parse(""))
    }

    @Test
    fun fenceMustStartLineSoInlineBackticksStayText() {
        assertEquals(
            listOf<Segment>(Segment.Text("文本```\n后续")),
            CodeBlockParser.parse("文本```\n后续")
        )
    }
}
