package com.mynote.app.ui.ai

import com.mynote.app.ui.ai.MarkdownParser.MdAlign
import com.mynote.app.ui.ai.MarkdownParser.MdBlock
import com.mynote.app.ui.ai.MarkdownParser.MdListItem
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownParserTest {

    // ---------- 段落 / 标题 / 分隔线 ----------

    @Test
    fun emptyInputReturnsEmptyList() {
        assertEquals(emptyList<MdBlock>(), MarkdownParser.parse(""))
    }

    @Test
    fun plainLinesFormOneParagraphPreservingNewlines() {
        assertEquals(
            listOf(MdBlock.Paragraph("第一行\n第二行")),
            MarkdownParser.parse("第一行\n第二行")
        )
    }

    @Test
    fun blankLineSeparatesParagraphs() {
        assertEquals(
            listOf(MdBlock.Paragraph("第一段"), MdBlock.Paragraph("第二段")),
            MarkdownParser.parse("第一段\n\n第二段")
        )
    }

    @Test
    fun headingLevelsAndContent() {
        assertEquals(
            listOf(
                MdBlock.Heading(1, "标题"),
                MdBlock.Heading(3, "小标题"),
                MdBlock.Heading(6, "最小")
            ),
            MarkdownParser.parse("# 标题\n### 小标题\n###### 最小")
        )
    }

    @Test
    fun hashWithoutSpaceIsNotHeading() {
        assertEquals(listOf(MdBlock.Paragraph("#标签")), MarkdownParser.parse("#标签"))
    }

    @Test
    fun sevenHashesIsParagraph() {
        assertEquals(
            listOf(MdBlock.Paragraph("####### 不是标题")),
            MarkdownParser.parse("####### 不是标题")
        )
    }

    @Test
    fun headingTrailingHashesAreTrimmed() {
        assertEquals(listOf(MdBlock.Heading(2, "标题")), MarkdownParser.parse("## 标题 ##"))
    }

    @Test
    fun headingKeepsInlineMarkersRaw() {
        assertEquals(listOf(MdBlock.Heading(1, "**粗**")), MarkdownParser.parse("# **粗**"))
    }

    @Test
    fun rules() {
        assertEquals(
            listOf(MdBlock.Rule, MdBlock.Rule, MdBlock.Rule, MdBlock.Rule),
            MarkdownParser.parse("---\n***\n___\n- - -")
        )
    }

    @Test
    fun shortDashesAreParagraph() {
        assertEquals(listOf(MdBlock.Paragraph("--")), MarkdownParser.parse("--"))
    }

    // ---------- 围栏代码块（移植旧 CodeBlockParserTest） ----------

    @Test
    fun singleCodeBlockSplitsSurroundingText() {
        assertEquals(
            listOf(
                MdBlock.Paragraph("说明"),
                MdBlock.CodeBlock(null, "val x = 1"),
                MdBlock.Paragraph("结束")
            ),
            MarkdownParser.parse("说明\n```\nval x = 1\n```\n结束")
        )
    }

    @Test
    fun languageMarkerIsCapturedAndTrimmed() {
        assertEquals(
            listOf(MdBlock.CodeBlock("kotlin", "fun main() = Unit")),
            MarkdownParser.parse("``` kotlin \nfun main() = Unit\n```")
        )
    }

    @Test
    fun multipleCodeBlocksKeepOrder() {
        val text = "开头\n```kotlin\nval a = 1\n```\n中间\n```python\nprint(1)\n```\n结尾"
        assertEquals(
            listOf(
                MdBlock.Paragraph("开头"),
                MdBlock.CodeBlock("kotlin", "val a = 1"),
                MdBlock.Paragraph("中间"),
                MdBlock.CodeBlock("python", "print(1)"),
                MdBlock.Paragraph("结尾")
            ),
            MarkdownParser.parse(text)
        )
    }

    @Test
    fun unclosedFenceExtendsToEnd() {
        assertEquals(
            listOf(
                MdBlock.Paragraph("前言"),
                MdBlock.CodeBlock("java", "int a = 1;\nint b = 2;")
            ),
            MarkdownParser.parse("前言\n```java\nint a = 1;\nint b = 2;")
        )
    }

    @Test
    fun emptyCodeBlockIsIgnoredAndTextMerges() {
        assertEquals(listOf(MdBlock.Paragraph("前\n后")), MarkdownParser.parse("前\n```\n```\n后"))
    }

    @Test
    fun fenceAllowsLeadingIndent() {
        assertEquals(
            listOf(MdBlock.CodeBlock("python", "print(1)")),
            MarkdownParser.parse("  ```python\nprint(1)\n    ```")
        )
    }

    @Test
    fun crlfIsParsedLikeLf() {
        assertEquals(
            listOf(
                MdBlock.Paragraph("说明"),
                MdBlock.CodeBlock("kotlin", "val x = 1"),
                MdBlock.Paragraph("结束")
            ),
            MarkdownParser.parse("说明\r\n```kotlin\r\nval x = 1\r\n```\r\n结束")
        )
    }

    @Test
    fun blankLinesInsideCodeArePreserved() {
        assertEquals(
            listOf(MdBlock.CodeBlock(null, "a\n\nb")),
            MarkdownParser.parse("```\na\n\nb\n```")
        )
    }

    @Test
    fun fenceMustStartLineSoInlineBackticksStayText() {
        assertEquals(
            listOf(MdBlock.Paragraph("文本```\n后续")),
            MarkdownParser.parse("文本```\n后续")
        )
    }

    @Test
    fun tildeFenceIsParagraph() {
        assertEquals(
            listOf(MdBlock.Paragraph("~~~\ncode\n~~~")),
            MarkdownParser.parse("~~~\ncode\n~~~")
        )
    }

    // ---------- 引用 ----------

    @Test
    fun quoteParsesInnerBlocks() {
        assertEquals(
            listOf(MdBlock.Quote(listOf(MdBlock.Paragraph("引用一\n引用二")))),
            MarkdownParser.parse("> 引用一\n> 引用二")
        )
    }

    @Test
    fun nestedQuote() {
        assertEquals(
            listOf(MdBlock.Quote(listOf(MdBlock.Quote(listOf(MdBlock.Paragraph("内层")))))),
            MarkdownParser.parse("> > 内层")
        )
    }

    @Test
    fun quoteCanContainList() {
        assertEquals(
            listOf(MdBlock.Quote(listOf(MdBlock.BulletList(listOf(MdListItem("项")))))),
            MarkdownParser.parse("> - 项")
        )
    }

    // ---------- 列表 ----------

    @Test
    fun bulletListItems() {
        assertEquals(
            listOf(MdBlock.BulletList(listOf(MdListItem("第一"), MdListItem("第二")))),
            MarkdownParser.parse("- 第一\n- 第二")
        )
    }

    @Test
    fun orderedListKeepsStartNumber() {
        assertEquals(
            listOf(MdBlock.OrderedList(3, listOf(MdListItem("三"), MdListItem("四")))),
            MarkdownParser.parse("3. 三\n4) 四")
        )
    }

    @Test
    fun listTypeSwitchStartsNewList() {
        assertEquals(
            listOf(
                MdBlock.BulletList(listOf(MdListItem("项"))),
                MdBlock.OrderedList(1, listOf(MdListItem("一")))
            ),
            MarkdownParser.parse("- 项\n1. 一")
        )
    }

    @Test
    fun nestedSubList() {
        assertEquals(
            listOf(
                MdBlock.BulletList(
                    listOf(
                        MdListItem("父", subList = MdBlock.BulletList(listOf(MdListItem("子")))),
                        MdListItem("父二")
                    )
                )
            ),
            MarkdownParser.parse("- 父\n  - 子\n- 父二")
        )
    }

    @Test
    fun threeLevelNestedLists() {
        assertEquals(
            listOf(
                MdBlock.BulletList(
                    listOf(
                        MdListItem(
                            "一",
                            subList = MdBlock.BulletList(
                                listOf(
                                    MdListItem(
                                        "二",
                                        subList = MdBlock.BulletList(listOf(MdListItem("三")))
                                    )
                                )
                            )
                        )
                    )
                )
            ),
            MarkdownParser.parse("- 一\n  - 二\n    - 三")
        )
    }

    @Test
    fun taskListItems() {
        assertEquals(
            listOf(
                MdBlock.BulletList(
                    listOf(
                        MdListItem("完成", checked = true),
                        MdListItem("未完成", checked = false),
                        MdListItem("普通")
                    )
                )
            ),
            MarkdownParser.parse("- [x] 完成\n- [ ] 未完成\n- 普通")
        )
    }

    @Test
    fun lazyContinuationAppendsToItem() {
        assertEquals(
            listOf(MdBlock.BulletList(listOf(MdListItem("第一行\n第二行")))),
            MarkdownParser.parse("- 第一行\n第二行")
        )
    }

    @Test
    fun blankLineInsideListContinuesWhenNextItemFollows() {
        assertEquals(
            listOf(MdBlock.BulletList(listOf(MdListItem("一"), MdListItem("二")))),
            MarkdownParser.parse("- 一\n\n- 二")
        )
    }

    @Test
    fun blankLineThenParagraphEndsList() {
        assertEquals(
            listOf(
                MdBlock.BulletList(listOf(MdListItem("一"))),
                MdBlock.Paragraph("段落")
            ),
            MarkdownParser.parse("- 一\n\n段落")
        )
    }

    @Test
    fun listIsInterruptedByHeading() {
        assertEquals(
            listOf(
                MdBlock.BulletList(listOf(MdListItem("项"))),
                MdBlock.Heading(1, "标题")
            ),
            MarkdownParser.parse("- 项\n# 标题")
        )
    }

    // ---------- 表格 ----------

    @Test
    fun basicTable() {
        assertEquals(
            listOf(
                MdBlock.Table(
                    alignments = listOf(MdAlign.START, MdAlign.CENTER, MdAlign.END),
                    header = listOf("左", "中", "右"),
                    rows = listOf(listOf("a", "b", "c"), listOf("d", "e", "f"))
                )
            ),
            MarkdownParser.parse("| 左 | 中 | 右 |\n| :--- | :---: | ---: |\n| a | b | c |\n| d | e | f |")
        )
    }

    @Test
    fun tableWithoutOuterPipesPadsShortRows() {
        assertEquals(
            listOf(
                MdBlock.Table(
                    alignments = listOf(MdAlign.START, MdAlign.START),
                    header = listOf("A", "B"),
                    rows = listOf(listOf("1", ""))
                )
            ),
            MarkdownParser.parse("A | B\n--- | ---\n| 1 |")
        )
    }

    @Test
    fun tableExtraCellsAreTruncated() {
        assertEquals(
            listOf(
                MdBlock.Table(
                    alignments = listOf(MdAlign.START),
                    header = listOf("A"),
                    rows = listOf(listOf("1"))
                )
            ),
            MarkdownParser.parse("| A |\n| --- |\n| 1 | 2 |")
        )
    }

    @Test
    fun tableEscapedPipeStaysInCell() {
        assertEquals(
            listOf(
                MdBlock.Table(
                    alignments = listOf(MdAlign.START, MdAlign.START),
                    header = listOf("A", "B"),
                    rows = listOf(listOf("x|y", "z"))
                )
            ),
            MarkdownParser.parse("| A | B |\n| --- | --- |\n| x\\|y | z |")
        )
    }

    @Test
    fun pipeLineWithoutDelimiterIsParagraph() {
        assertEquals(
            listOf(MdBlock.Paragraph("| A | B |\njust text")),
            MarkdownParser.parse("| A | B |\njust text")
        )
    }

    @Test
    fun tableEndsAtNonPipeLine() {
        assertEquals(
            listOf(
                MdBlock.Table(
                    alignments = listOf(MdAlign.START),
                    header = listOf("A"),
                    rows = listOf(listOf("1"))
                ),
                MdBlock.Paragraph("结束")
            ),
            MarkdownParser.parse("| A |\n| --- |\n| 1 |\n结束")
        )
    }
}
