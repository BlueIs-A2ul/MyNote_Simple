# AI 回答 Markdown 渲染 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 AI 助手回答（含流式）按 Markdown 渲染：标题、列表、引用、分隔线、粗斜删、行内代码、链接、表格、代码块；插入笔记时转纯文本。

**Architecture:** 两层纯 Kotlin 解析器（块级 AST + 行内 AnnotatedString）→ Compose 渲染层 `MarkdownContent`；流式与终态走同一条无状态渲染路径；纯文本转换复用 AST + 行内解析。

**Tech Stack:** Kotlin 2.0 / Jetpack Compose Material 3（BOM 2024.09.03，`LinkAnnotation` 1.7）/ JUnit4 纯 JVM 单测；零新增依赖。

**Spec:** `docs/superpowers/specs/2026-09-15-ai-markdown-rendering-design.md`

**通用约定（Windows PowerShell，工作目录 `D:\desktop\myNote`）：**
- 单测：`.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.ai.MarkdownParserTest"`
- 全量：`.\gradlew :app:testDebugUnitTest`
- 提交信息中文 + 前缀；不要 push。

---

### Task 1: 块级解析器 `MarkdownParser`（TDD）

**Files:**
- Create: `app/src/test/java/com/mynote/app/ui/ai/MarkdownParserTest.kt`
- Create: `app/src/main/java/com/mynote/app/ui/ai/MarkdownParser.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/mynote/app/ui/ai/MarkdownParserTest.kt`：

```kotlin
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
```

- [ ] **Step 2: 运行确认失败（编译错误：MarkdownParser 未定义）**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.ai.MarkdownParserTest"`
Expected: FAIL，`Unresolved reference: MarkdownParser`

- [ ] **Step 3: 实现 `MarkdownParser.kt`**

创建 `app/src/main/java/com/mynote/app/ui/ai/MarkdownParser.kt`：

```kotlin
package com.mynote.app.ui.ai

/**
 * 把 AI 回答解析为块级 Markdown 结构，供聊天界面按块渲染。
 * 纯 Kotlin 实现，无 Android 依赖，可直接 JVM 单测；语法为务实子集（见设计文档）。
 */
object MarkdownParser {

    sealed interface MdBlock {
        sealed interface ListBlock : MdBlock { val items: List<MdListItem> }

        data class Heading(val level: Int, val text: String) : MdBlock
        data class Paragraph(val text: String) : MdBlock
        data class Quote(val blocks: List<MdBlock>) : MdBlock
        data class CodeBlock(val language: String?, val code: String) : MdBlock
        data object Rule : MdBlock
        data class BulletList(override val items: List<MdListItem>) : ListBlock
        data class OrderedList(val start: Int, override val items: List<MdListItem>) : ListBlock
        data class Table(
            val alignments: List<MdAlign>,
            val header: List<String>,
            val rows: List<List<String>>
        ) : MdBlock
    }

    data class MdListItem(
        val text: String,
        val checked: Boolean? = null,
        val subList: MdBlock.ListBlock? = null
    )

    enum class MdAlign { START, CENTER, END }

    private const val FENCE = "```"

    fun parse(text: String): List<MdBlock> {
        val lines = normalize(text)
        val blocks = mutableListOf<MdBlock>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) {
                index++
                continue
            }
            val fence = readFence(lines, index)
            if (fence != null) {
                if (fence.code.isNotEmpty()) blocks += MdBlock.CodeBlock(fence.language, fence.code)
                index = fence.nextIndex
                continue
            }
            if (isRule(line)) {
                blocks += MdBlock.Rule
                index++
                continue
            }
            val level = headingLevel(line)
            if (level != null) {
                blocks += MdBlock.Heading(level, headingText(line))
                index++
                continue
            }
            if (isTableStart(lines, index)) {
                val table = readTable(lines, index)
                blocks += table.block
                index = table.nextIndex
                continue
            }
            if (isQuote(line)) {
                val quote = readQuote(lines, index)
                val inner = parse(quote.inner)
                if (inner.isNotEmpty()) blocks += MdBlock.Quote(inner)
                index = quote.nextIndex
                continue
            }
            val item = listItem(line)
            if (item != null) {
                val list = readList(lines, index, item.indent, item.ordered, item.number)
                if (list.block.items.isNotEmpty()) blocks += list.block
                index = list.nextIndex
                continue
            }
            val paragraph = readParagraph(lines, index)
            blocks += MdBlock.Paragraph(paragraph.text)
            index = paragraph.nextIndex
        }
        return blocks
    }

    private fun normalize(text: String): List<String> =
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n')

    private fun isFence(line: String): Boolean = line.trimStart().startsWith(FENCE)

    private data class FenceRead(val language: String?, val code: String, val nextIndex: Int)

    private fun readFence(lines: List<String>, start: Int): FenceRead? {
        if (!isFence(lines[start])) return null
        val language = lines[start].trimStart().substring(FENCE.length).trim().ifEmpty { null }
        var end = start + 1
        while (end < lines.size && !isFence(lines[end])) end++
        val code = lines.subList(start + 1, end).joinToString("\n")
        val nextIndex = if (end < lines.size) end + 1 else end
        return FenceRead(language, code, nextIndex)
    }

    private fun isRule(line: String): Boolean {
        val compact = line.trim().replace(" ", "").replace("\t", "")
        if (compact.length < 3) return false
        val marker = compact[0]
        if (marker != '-' && marker != '*' && marker != '_') return false
        return compact.all { it == marker }
    }

    private fun headingLevel(line: String): Int? {
        val trimmed = line.trimStart()
        var level = 0
        while (level < trimmed.length && level < 7 && trimmed[level] == '#') level++
        if (level == 0 || level > 6) return null
        if (level < trimmed.length && !trimmed[level].isWhitespace()) return null
        return level
    }

    private fun headingText(line: String): String {
        val level = headingLevel(line) ?: return line.trim()
        var content = line.trimStart().substring(level).trim()
        while (content.endsWith("#")) {
            val withoutHashes = content.trimEnd('#')
            if (withoutHashes.isEmpty()) return ""
            if (!withoutHashes.last().isWhitespace()) break
            content = withoutHashes.trimEnd()
        }
        return content
    }

    private fun isQuote(line: String): Boolean = line.trimStart().startsWith(">")

    private data class QuoteRead(val inner: String, val nextIndex: Int)

    private fun readQuote(lines: List<String>, start: Int): QuoteRead {
        val inner = mutableListOf<String>()
        var index = start
        while (index < lines.size && isQuote(lines[index])) {
            var rest = lines[index].trimStart().substring(1)
            if (rest.startsWith(" ")) rest = rest.substring(1)
            inner += rest
            index++
        }
        return QuoteRead(inner.joinToString("\n"), index)
    }

    private fun isBlockStart(line: String): Boolean =
        isFence(line) || isRule(line) || headingLevel(line) != null || isQuote(line)

    private data class ParagraphRead(val text: String, val nextIndex: Int)

    private fun readParagraph(lines: List<String>, start: Int): ParagraphRead {
        val parts = mutableListOf<String>()
        var index = start
        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) break
            val fence = readFence(lines, index)
            if (fence != null) {
                if (fence.code.isEmpty()) {
                    index = fence.nextIndex
                    continue
                }
                break
            }
            if (isRule(line) || headingLevel(line) != null || isQuote(line) || listItem(line) != null) break
            if (isTableStart(lines, index)) break
            parts += line
            index++
        }
        return ParagraphRead(parts.joinToString("\n"), index)
    }

    private data class ListItemInfo(
        val ordered: Boolean,
        val number: Int,
        val checked: Boolean?,
        val text: String,
        val indent: Int
    )

    private val BULLET_ITEM = Regex("^[-*+] +(.*)$")
    private val ORDERED_ITEM = Regex("^(\\d{1,9})[.)] +(.*)$")
    private val TASK_ITEM = Regex("^\\[([ xX])\\] +(.*)$")

    private fun indentWidth(line: String): Int {
        var width = 0
        for (ch in line) {
            when (ch) {
                ' ' -> width++
                '\t' -> width += 4
                else -> return width
            }
        }
        return width
    }

    private fun listItem(line: String): ListItemInfo? {
        val content = line.trimStart()
        val indent = indentWidth(line)
        BULLET_ITEM.matchEntire(content)?.let { match ->
            val raw = match.groupValues[1]
            val task = TASK_ITEM.matchEntire(raw)
            return ListItemInfo(
                ordered = false,
                number = 1,
                checked = task?.let { it.groupValues[1] != " " },
                text = task?.groupValues?.get(2) ?: raw,
                indent = indent
            )
        }
        ORDERED_ITEM.matchEntire(content)?.let { match ->
            return ListItemInfo(
                ordered = true,
                number = match.groupValues[1].toIntOrNull() ?: 1,
                checked = null,
                text = match.groupValues[2],
                indent = indent
            )
        }
        return null
    }

    private data class ListRead(val block: MdBlock.ListBlock, val nextIndex: Int)

    private fun readList(
        lines: List<String>,
        start: Int,
        baseIndent: Int,
        ordered: Boolean,
        startNumber: Int
    ): ListRead {
        val items = mutableListOf<MdListItem>()
        var index = start
        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) {
                var next = index + 1
                while (next < lines.size && lines[next].isBlank()) next++
                val candidate = if (next < lines.size) listItem(lines[next]) else null
                if (candidate != null && candidate.ordered == ordered &&
                    candidate.indent in baseIndent until baseIndent + 2
                ) {
                    index = next
                    continue
                }
                break
            }
            val item = listItem(line)
            if (item != null) {
                when {
                    item.indent < baseIndent -> break
                    item.indent < baseIndent + 2 -> {
                        if (item.ordered != ordered) break
                        items += MdListItem(item.text, item.checked)
                        index++
                    }
                    else -> {
                        if (items.isEmpty()) break
                        val sub = readList(lines, index, item.indent, item.ordered, item.number)
                        val last = items.last()
                        items[items.size - 1] =
                            last.copy(subList = mergeSubLists(last.subList, sub.block))
                        index = sub.nextIndex
                    }
                }
                continue
            }
            if (isBlockStart(line)) break
            if (items.isEmpty()) break
            val last = items.last()
            items[items.size - 1] = last.copy(text = last.text + "\n" + line.trim())
            index++
        }
        val block = if (ordered) MdBlock.OrderedList(startNumber, items) else MdBlock.BulletList(items)
        return ListRead(block, index)
    }

    private fun mergeSubLists(existing: MdBlock.ListBlock?, incoming: MdBlock.ListBlock): MdBlock.ListBlock {
        if (existing == null) return incoming
        val items = existing.items + incoming.items
        return when (existing) {
            is MdBlock.BulletList -> MdBlock.BulletList(items)
            is MdBlock.OrderedList -> MdBlock.OrderedList(existing.start, items)
        }
    }

    private data class TableRead(val block: MdBlock.Table, val nextIndex: Int)

    private fun isTableStart(lines: List<String>, index: Int): Boolean {
        if (!lines[index].contains('|')) return false
        if (index + 1 >= lines.size) return false
        val delimiter = lines[index + 1].trim()
        if (!delimiter.contains('-') || !delimiter.contains('|')) return false
        return delimiter.all { it == '-' || it == ':' || it == '|' || it == ' ' || it == '\t' }
    }

    private fun readTable(lines: List<String>, start: Int): TableRead {
        val header = splitTableRow(lines[start])
        val delimiters = splitTableRow(lines[start + 1])
        val alignments = List(header.size) { index -> alignmentOf(delimiters.getOrNull(index)) }
        val rows = mutableListOf<List<String>>()
        var index = start + 2
        while (index < lines.size && lines[index].isNotBlank() && lines[index].contains('|')) {
            val cells = splitTableRow(lines[index])
            rows += List(header.size) { column -> cells.getOrNull(column).orEmpty() }
            index++
        }
        return TableRead(MdBlock.Table(alignments, header, rows), index)
    }

    private fun alignmentOf(cell: String?): MdAlign {
        val content = cell?.trim().orEmpty()
        return when {
            content.startsWith(":") && content.endsWith(":") -> MdAlign.CENTER
            content.endsWith(":") -> MdAlign.END
            else -> MdAlign.START
        }
    }

    private fun splitTableRow(line: String): List<String> {
        val trimmed = line.trim()
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < trimmed.length) {
            val ch = trimmed[index]
            if (ch == '\\' && index + 1 < trimmed.length &&
                (trimmed[index + 1] == '|' || trimmed[index + 1] == '\\')
            ) {
                current.append(trimmed[index + 1])
                index += 2
            } else if (ch == '|') {
                cells += current.toString().trim()
                current.clear()
                index++
            } else {
                current.append(ch)
                index++
            }
        }
        cells += current.toString().trim()
        if (trimmed.startsWith("|") && cells.isNotEmpty() && cells.first().isEmpty()) cells.removeAt(0)
        if (trimmed.endsWith("|") && cells.isNotEmpty() && cells.last().isEmpty()) {
            cells.removeAt(cells.size - 1)
        }
        return cells
    }
}
```

- [ ] **Step 4: 运行确认全绿**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.ai.MarkdownParserTest"`
Expected: PASS（约 33 条）

- [ ] **Step 5: 提交**

```powershell
git add app/src/main/java/com/mynote/app/ui/ai/MarkdownParser.kt app/src/test/java/com/mynote/app/ui/ai/MarkdownParserTest.kt
git commit -m "feat: 新增 Markdown 块级解析器（标题/列表/引用/表格/代码块）"
```

---

### Task 2: 行内解析器 `MarkdownInline`（TDD）

**Files:**
- Create: `app/src/test/java/com/mynote/app/ui/ai/MarkdownInlineTest.kt`
- Create: `app/src/main/java/com/mynote/app/ui/ai/MarkdownInline.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/mynote/app/ui/ai/MarkdownInlineTest.kt`：

```kotlin
package com.mynote.app.ui.ai

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 行内 Markdown 纯函数单测：不依赖 MaterialTheme 的纯 JUnit4 测试。 */
class MarkdownInlineTest {

    private val codeBackground = Color(0xFFEEEEEE)
    private val linkColor = Color(0xFF0000FF)

    private fun build(text: String) = MarkdownInline.build(text, codeBackground, linkColor)

    @Test
    fun plainTextIsUnchanged() {
        val result = build("普通文本")
        assertEquals("普通文本", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun boldTextHasBoldSpan() {
        val result = build("前**粗**后")
        assertEquals("前粗后", result.text)
        assertEquals(1, result.spanStyles.size)
        assertEquals(1, result.spanStyles[0].start)
        assertEquals(2, result.spanStyles[0].end)
        assertEquals(FontWeight.Bold, result.spanStyles[0].item.fontWeight)
    }

    @Test
    fun underscoreBoldIsSupported() {
        assertEquals("粗", build("__粗__").text)
        assertEquals(FontWeight.Bold, build("__粗__").spanStyles.single().item.fontWeight)
    }

    @Test
    fun italicAndStrike() {
        val italic = build("*斜*")
        assertEquals("斜", italic.text)
        assertEquals(FontStyle.Italic, italic.spanStyles.single().item.fontStyle)

        val strike = build("~~删~~")
        assertEquals("删", strike.text)
        assertEquals(TextDecoration.LineThrough, strike.spanStyles.single().item.textDecoration)
    }

    @Test
    fun underscoreWithinWordIsNotEmphasis() {
        val result = build("foo_bar_baz")
        assertEquals("foo_bar_baz", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun tripleAsteriskIsBoldItalic() {
        val result = build("***粗斜***")
        assertEquals("粗斜", result.text)
        val span = result.spanStyles.single().item
        assertEquals(FontWeight.Bold, span.fontWeight)
        assertEquals(FontStyle.Italic, span.fontStyle)
    }

    @Test
    fun boldContainingItalic() {
        val result = build("**粗 *斜* 粗**")
        assertEquals("粗 斜 粗", result.text)
        assertEquals(2, result.spanStyles.size)
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(result.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun inlineCodeIsMonospaceWithBackground() {
        val result = build("`code`")
        assertEquals("code", result.text)
        val span = result.spanStyles.single().item
        assertEquals(FontFamily.Monospace, span.fontFamily)
        assertEquals(codeBackground, span.background)
    }

    @Test
    fun inlineCodeIsNotParsedInside() {
        val result = build("`**not bold**`")
        assertEquals("**not bold**", result.text)
        assertTrue(result.spanStyles.none { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun inlineCodeStripsOneSpacePair() {
        assertEquals("code", build("` code `").text)
        assertEquals(" code ", build("`  code  `").text)
    }

    @Test
    fun linkHasUrlAnnotation() {
        val result = build("看[文档](https://example.com/a)吧")
        assertEquals("看文档吧", result.text)
        val links = result.getLinkAnnotations(0, result.length)
        assertEquals(1, links.size)
        assertEquals("https://example.com/a", (links[0].item as LinkAnnotation.Url).url)
        assertEquals(1, links[0].start)
        assertEquals(3, links[0].end)
    }

    @Test
    fun emphasisInsideLinkLabel() {
        val result = build("[**粗**链](https://a.b)")
        assertEquals("粗链", result.text)
        assertEquals(1, result.getLinkAnnotations(0, result.length).size)
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun imageRendersAltText() {
        assertEquals("图", build("![图](https://a.png)").text)
        assertEquals("图片", build("![](https://a.png)").text)
    }

    @Test
    fun escapeRendersLiteral() {
        val result = build("\\*字面\\*")
        assertEquals("*字面*", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun unclosedMarkerStaysLiteral() {
        assertEquals("**未闭合", build("**未闭合").text)
        assertEquals("*斜", build("*斜").text)
    }

    @Test
    fun plainTextExpandsLinks() {
        assertEquals(
            "看文档（https://example.com）吧",
            MarkdownInline.plainText("看[文档](https://example.com)吧")
        )
    }

    @Test
    fun plainTextStripsInlineMarkers() {
        assertEquals(
            "粗斜码删",
            MarkdownInline.plainText("**粗***斜*`码`~~删~~")
        )
    }
}
```

- [ ] **Step 2: 运行确认失败（编译错误：MarkdownInline 未定义）**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.ai.MarkdownInlineTest"`
Expected: FAIL，`Unresolved reference: MarkdownInline`

- [ ] **Step 3: 实现 `MarkdownInline.kt`**

创建 `app/src/main/java/com/mynote/app/ui/ai/MarkdownInline.kt`：

```kotlin
package com.mynote.app.ui.ai

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/**
 * 行内 Markdown 解析（粗体/斜体/删除线/行内代码/链接/图片降级/转义）。
 * 纯 Kotlin，可 JVM 单测；未闭合与病态嵌套一律按字面输出。
 */
object MarkdownInline {

    private const val IMAGE_LABEL = "图片"

    private const val ESCAPABLE = "\\`*_~[]()#>!-+.|"

    private data class Delimiter(val marker: String, val style: SpanStyle)

    private val DELIMITERS = listOf(
        Delimiter("***", SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)),
        Delimiter("**", SpanStyle(fontWeight = FontWeight.Bold)),
        Delimiter("*", SpanStyle(fontStyle = FontStyle.Italic)),
        Delimiter("___", SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)),
        Delimiter("__", SpanStyle(fontWeight = FontWeight.Bold)),
        Delimiter("_", SpanStyle(fontStyle = FontStyle.Italic)),
        Delimiter("~~", SpanStyle(textDecoration = TextDecoration.LineThrough))
    )

    fun build(text: String, codeBackground: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
        appendInline(this, text, 0, text.length, codeBackground, linkColor)
    }

    fun plainText(text: String): String {
        val annotated = build(text, Color.Transparent, Color.Unspecified)
        val links = annotated.getLinkAnnotations(0, annotated.length).sortedBy { it.start }
        if (links.isEmpty()) return annotated.text
        val out = StringBuilder()
        var last = 0
        for (range in links) {
            val url = (range.item as? LinkAnnotation.Url)?.url ?: continue
            if (range.start < last) continue
            out.append(annotated.text, last, range.end)
            out.append("（").append(url).append("）")
            last = range.end
        }
        out.append(annotated.text, last, annotated.text.length)
        return out.toString()
    }

    private fun appendInline(
        builder: AnnotatedString.Builder,
        text: String,
        start: Int,
        end: Int,
        codeBackground: Color,
        linkColor: Color
    ) {
        var index = start
        while (index < end) {
            val ch = text[index]
            when {
                ch == '\\' && index + 1 < end && text[index + 1] in ESCAPABLE -> {
                    builder.append(text[index + 1])
                    index += 2
                }
                ch == '`' -> {
                    val close = text.indexOf('`', index + 1)
                    if (close < 0 || close >= end) {
                        builder.append(ch)
                        index++
                    } else {
                        builder.withStyle(
                            SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
                        ) {
                            append(codeSpanContent(text.substring(index + 1, close)))
                        }
                        index = close + 1
                    }
                }
                ch == '!' && index + 1 < end && text[index + 1] == '[' -> {
                    val labelEnd = findBracketClose(text, index + 2, end)
                    val url = if (labelEnd >= 0) readUrl(text, labelEnd + 1, end) else null
                    if (labelEnd < 0 || url == null) {
                        builder.append(ch)
                        index++
                    } else {
                        val alt = text.substring(index + 2, labelEnd)
                        builder.append(alt.ifBlank { IMAGE_LABEL })
                        index = url.nextIndex
                    }
                }
                ch == '[' -> {
                    val labelEnd = findBracketClose(text, index + 1, end)
                    val url = if (labelEnd >= 0) readUrl(text, labelEnd + 1, end) else null
                    if (labelEnd < 0 || url == null) {
                        builder.append(ch)
                        index++
                    } else {
                        builder.withLink(
                            LinkAnnotation.Url(
                                url.value,
                                TextLinkStyles(
                                    SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                                )
                            )
                        ) {
                            appendInline(this, text, index + 1, labelEnd, codeBackground, linkColor)
                        }
                        index = url.nextIndex
                    }
                }
                else -> {
                    var matched = false
                    for (delimiter in matchDelimiters(text, index, end)) {
                        val close = findClosingDelimiter(text, delimiter.marker, index + delimiter.marker.length, end)
                        if (close >= 0) {
                            builder.withStyle(delimiter.style) {
                                appendInline(this, text, index + delimiter.marker.length, close, codeBackground, linkColor)
                            }
                            index = close + delimiter.marker.length
                            matched = true
                            break
                        }
                    }
                    if (!matched) {
                        builder.append(ch)
                        index++
                    }
                }
            }
        }
    }

    private fun matchDelimiters(text: String, index: Int, end: Int): List<Delimiter> {
        val result = mutableListOf<Delimiter>()
        for (delimiter in DELIMITERS) {
            val marker = delimiter.marker
            if (index + marker.length > end) continue
            if (!text.startsWith(marker, index)) continue
            val after = index + marker.length
            if (after >= end || text[after].isWhitespace()) continue
            if (marker.startsWith("_") && index > 0 && text[index - 1].isLetterOrDigit()) continue
            result += delimiter
        }
        return result
    }

    private fun findClosingDelimiter(text: String, marker: String, from: Int, end: Int): Int {
        var index = from
        while (index + marker.length <= end) {
            if (index > from && text.startsWith(marker, index)) {
                val before = text[index - 1]
                val after = index + marker.length
                val underscoreOk = !marker.startsWith("_") ||
                    after >= end || !text[after].isLetterOrDigit()
                if (!before.isWhitespace() && underscoreOk) return index
            }
            index++
        }
        return -1
    }

    private fun findBracketClose(text: String, from: Int, end: Int): Int {
        var index = from
        while (index < end) {
            when {
                text[index] == '\\' && index + 1 < end -> index += 2
                text[index] == ']' -> return index
                else -> index++
            }
        }
        return -1
    }

    private data class UrlRead(val value: String, val nextIndex: Int)

    private fun readUrl(text: String, from: Int, end: Int): UrlRead? {
        if (from >= end || text[from] != '(') return null
        val url = StringBuilder()
        var index = from + 1
        while (index < end) {
            val ch = text[index]
            when {
                ch == ')' -> {
                    val value = url.toString().trim()
                    return if (value.isEmpty()) null else UrlRead(value, index + 1)
                }
                ch == '\\' && index + 1 < end -> {
                    url.append(text[index + 1])
                    index += 2
                }
                ch.isWhitespace() -> return null
                else -> {
                    url.append(ch)
                    index++
                }
            }
        }
        return null
    }

    private fun codeSpanContent(raw: String): String {
        if (raw.length >= 2 && raw.first() == ' ' && raw.last() == ' ' && raw.any { !it.isWhitespace() }) {
            return raw.substring(1, raw.length - 1)
        }
        return raw
    }
}
```

- [ ] **Step 4: 运行确认全绿**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.ai.MarkdownInlineTest"`
Expected: PASS（17 条）

- [ ] **Step 5: 提交**

```powershell
git add app/src/main/java/com/mynote/app/ui/ai/MarkdownInline.kt app/src/test/java/com/mynote/app/ui/ai/MarkdownInlineTest.kt
git commit -m "feat: 新增 Markdown 行内解析（粗斜删/行内代码/链接/图片降级）"
```

---

### Task 3: 纯文本转换 `MarkdownPlainText`（TDD）

**Files:**
- Create: `app/src/test/java/com/mynote/app/ui/ai/MarkdownPlainTextTest.kt`
- Create: `app/src/main/java/com/mynote/app/ui/ai/MarkdownPlainText.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/mynote/app/ui/ai/MarkdownPlainTextTest.kt`：

```kotlin
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
```

- [ ] **Step 2: 运行确认失败（编译错误：MarkdownPlainText 未定义）**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.ai.MarkdownPlainTextTest"`
Expected: FAIL，`Unresolved reference: MarkdownPlainText`

- [ ] **Step 3: 实现 `MarkdownPlainText.kt`**

创建 `app/src/main/java/com/mynote/app/ui/ai/MarkdownPlainText.kt`：

```kotlin
package com.mynote.app.ui.ai

import com.mynote.app.ui.ai.MarkdownParser.MdBlock

/** 把 Markdown 转成适合纯文本笔记的正文：剥离标记、保留可读结构。 */
object MarkdownPlainText {

    fun convert(markdown: String): String = blocksToPlain(MarkdownParser.parse(markdown))

    private fun blocksToPlain(blocks: List<MdBlock>): String =
        blocks.joinToString("\n\n") { blockToPlain(it) }.trim()

    private fun blockToPlain(block: MdBlock): String = when (block) {
        MdBlock.Rule -> "---"
        is MdBlock.Heading -> MarkdownInline.plainText(block.text)
        is MdBlock.Paragraph -> MarkdownInline.plainText(block.text)
        is MdBlock.CodeBlock -> block.code
        is MdBlock.Quote -> block.blocks.joinToString("\n\n") { blockToPlain(it) }
            .lines()
            .joinToString("\n") { line -> if (line.isEmpty()) line else "> $line" }
        is MdBlock.ListBlock -> listToPlain(block, indent = "")
        is MdBlock.Table -> (listOf(block.header) + block.rows)
            .joinToString("\n") { row -> row.joinToString(" | ") { cell -> MarkdownInline.plainText(cell) } }
    }

    private fun listToPlain(list: MdBlock.ListBlock, indent: String): String {
        val ordered = list is MdBlock.OrderedList
        val start = (list as? MdBlock.OrderedList)?.start ?: 1
        return list.items.mapIndexed { index, item ->
            val marker = when {
                item.checked == true -> "- [x] "
                item.checked == false -> "- [ ] "
                ordered -> "${start + index}. "
                else -> "- "
            }
            val sub = item.subList?.let { nested -> "\n" + listToPlain(nested, indent + "  ") }.orEmpty()
            indent + marker + MarkdownInline.plainText(item.text) + sub
        }.joinToString("\n")
    }
}
```

- [ ] **Step 4: 运行确认全绿**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.ai.MarkdownPlainTextTest"`
Expected: PASS（12 条）

- [ ] **Step 5: 提交**

```powershell
git add app/src/main/java/com/mynote/app/ui/ai/MarkdownPlainText.kt app/src/test/java/com/mynote/app/ui/ai/MarkdownPlainTextTest.kt
git commit -m "feat: 新增 Markdown 转纯文本（插入正文/存笔记用）"
```

---

### Task 4: 渲染层 `MarkdownContent.kt`

**Files:**
- Create: `app/src/main/java/com/mynote/app/ui/ai/MarkdownContent.kt`

无 UI 单测（项目无 Compose UI 测试约定），以编译 + Task 6 回归验证。

- [ ] **Step 1: 创建渲染层**

创建 `app/src/main/java/com/mynote/app/ui/ai/MarkdownContent.kt`：

```kotlin
package com.mynote.app.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 助手回答的 Markdown 渲染：按块解析后逐块输出，样式跟随 MaterialTheme。 */
@Composable
fun MarkdownContent(content: String, modifier: Modifier = Modifier) {
    val blocks = remember(content) { MarkdownParser.parse(content) }
    MarkdownBlocks(blocks, modifier)
}

@Composable
private fun MarkdownBlocks(blocks: List<MarkdownParser.MdBlock>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block -> MarkdownBlock(block) }
    }
}

@Composable
private fun MarkdownBlock(block: MarkdownParser.MdBlock) {
    when (block) {
        is MarkdownParser.MdBlock.Heading -> MarkdownText(
            text = block.text,
            style = headingStyle(block.level)
        )
        is MarkdownParser.MdBlock.Paragraph -> MarkdownText(
            text = block.text,
            style = MaterialTheme.typography.bodyMedium
        )
        MarkdownParser.MdBlock.Rule -> HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant
        )
        is MarkdownParser.MdBlock.CodeBlock -> CodeBlockView(block.language, block.code)
        is MarkdownParser.MdBlock.Quote -> QuoteView(block.blocks)
        is MarkdownParser.MdBlock.ListBlock -> MarkdownList(block)
        is MarkdownParser.MdBlock.Table -> TableView(block)
    }
}

@Composable
private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.titleMedium
    2 -> MaterialTheme.typography.titleSmall
    3 -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
    else -> MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
}

@Composable
private fun MarkdownText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start
) {
    Text(
        text = inlineText(text),
        modifier = modifier,
        style = style,
        textAlign = textAlign
    )
}

@Composable
private fun inlineText(text: String): AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.surface
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(text, codeBackground, linkColor) {
        MarkdownInline.build(text, codeBackground, linkColor)
    }
}

@Composable
private fun QuoteView(blocks: List<MarkdownParser.MdBlock>) {
    val barColor = MaterialTheme.colorScheme.outlineVariant
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(color = barColor, size = Size(3.dp.toPx(), size.height))
            }
            .padding(start = 10.dp)
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            MarkdownBlocks(blocks)
        }
    }
}

@Composable
private fun MarkdownList(list: MarkdownParser.MdBlock.ListBlock, modifier: Modifier = Modifier) {
    val ordered = list is MarkdownParser.MdBlock.OrderedList
    val start = (list as? MarkdownParser.MdBlock.OrderedList)?.start ?: 1
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        list.items.forEachIndexed { index, item ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = itemMarker(item, ordered, start + index),
                    modifier = Modifier.width(24.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
                Column(modifier = Modifier.weight(1f)) {
                    MarkdownText(item.text, MaterialTheme.typography.bodyMedium)
                    item.subList?.let { sub ->
                        MarkdownList(sub, Modifier.padding(start = 12.dp, top = 2.dp))
                    }
                }
            }
        }
    }
}

private fun itemMarker(item: MarkdownParser.MdListItem, ordered: Boolean, number: Int): String = when {
    item.checked == true -> "☑"
    item.checked == false -> "☐"
    ordered -> "$number."
    else -> "•"
}

@Composable
private fun TableView(table: MarkdownParser.MdBlock.Table) {
    val columns = table.header.size
    if (columns == 0) return
    val scrollState = rememberScrollState()
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columnWidth = maxOf(maxWidth / columns, 80.dp)
        Column(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .width(columnWidth * columns)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            TableRow(table.header, table.alignments, columnWidth, header = true)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            table.rows.forEach { row ->
                TableRow(row, table.alignments, columnWidth, header = false)
            }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    alignments: List<MarkdownParser.MdAlign>,
    columnWidth: Dp,
    header: Boolean
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        cells.forEachIndexed { index, cell ->
            MarkdownText(
                text = cell,
                style = if (header) {
                    MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                modifier = Modifier.width(columnWidth).padding(horizontal = 8.dp, vertical = 6.dp),
                textAlign = alignments.getOrNull(index).toTextAlign()
            )
        }
    }
}

private fun MarkdownParser.MdAlign?.toTextAlign(): TextAlign = when (this) {
    MarkdownParser.MdAlign.CENTER -> TextAlign.Center
    MarkdownParser.MdAlign.END -> TextAlign.End
    else -> TextAlign.Start
}

/** 围栏代码块：语言标签 + 等宽字体 + 横向滚动（自 AiChatScreen 迁入，视觉不变）。 */
@Composable
private fun CodeBlockView(language: String?, code: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        if (!language.isNullOrBlank()) {
            Text(
                language,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            Text(
                code.trimEnd('\n'),
                modifier = Modifier.padding(10.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
        }
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `.\gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（此时尚无调用方，未使用私有 composable 不报错；若 `width(columnWidth * columns)` 报 Dp 运算错误，改为 `columnWidth * columns.toFloat()`）

- [ ] **Step 3: 提交**

```powershell
git add app/src/main/java/com/mynote/app/ui/ai/MarkdownContent.kt
git commit -m "feat: 新增 Markdown 渲染层（标题/列表/引用/表格/代码块）"
```

---

### Task 5: 接线 `AiChatScreen` 并移除旧解析器

**Files:**
- Modify: `app/src/main/java/com/mynote/app/ui/ai/AiChatScreen.kt`
- Delete: `app/src/main/java/com/mynote/app/ui/ai/CodeBlockParser.kt`
- Delete: `app/src/test/java/com/mynote/app/ui/ai/CodeBlockParserTest.kt`

- [ ] **Step 1: 助手气泡与流式气泡改用 `MarkdownContent`**

`AiChatScreen.kt` 修改点：

1. `MessageBubble` 中助手正文（原 `AssistantContent(message.content.ifEmpty { "（没有内容）" })`）：

```kotlin
                if (isUser) {
                    Text(
                        message.content.ifEmpty { "（没有内容）" },
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    MarkdownContent(message.content.ifEmpty { "（没有内容）" })
                }
```

2. 「插入正文 / 替换选中 / 存为新笔记」改为传纯文本（复制仍传原文）。整体替换 `if (!isUser && message.status == AiMessageEntity.STATUS_DONE) { ... }` 段：

```kotlin
        if (!isUser && message.status == AiMessageEntity.STATUS_DONE) {
            val plainContent = remember(message.content) {
                MarkdownPlainText.convert(message.content)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onInsert(plainContent) }) {
                    Text("插入正文", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { onReplace(plainContent) }, enabled = hasSelection) {
                    Text("替换选中", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { onCopy(message.content) }) {
                    Text("复制", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { onSaveAsNote(plainContent) }) {
                    Text("存为新笔记", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
```

3. `StreamingBubble` 正文改用 `MarkdownContent`：

```kotlin
            Text(
                text.ifEmpty { if (sending) "正在等待回答…" else "" },
                style = MaterialTheme.typography.bodyMedium
            )
```
替换为：
```kotlin
            MarkdownContent(text.ifEmpty { if (sending) "正在等待回答…" else "" })
```

4. 删除文件内的 `AssistantContent` 与 `CodeBlockView` 两个 composable（原第 475–523 行整段）。

5. 清理不再使用的 import：`androidx.compose.foundation.background`、`androidx.compose.foundation.horizontalScroll`、`androidx.compose.foundation.rememberScrollState`、`androidx.compose.ui.draw.clip`、`androidx.compose.ui.text.font.FontFamily`（`Arrangement`、`Box`、`widthIn`、`AnnotatedString` 等仍在使用，勿删）。

- [ ] **Step 2: 删除旧解析器及其测试**

```powershell
git rm app/src/main/java/com/mynote/app/ui/ai/CodeBlockParser.kt app/src/test/java/com/mynote/app/ui/ai/CodeBlockParserTest.kt
```

确认无残留引用：

```powershell
Select-String -Path "app\src\**\*.kt" -Pattern "CodeBlockParser" -SimpleMatch | Select-Object Path, LineNumber
```
Expected: 无输出

- [ ] **Step 3: 全量单测**

Run: `.\gradlew :app:testDebugUnitTest`
Expected: PASS（463 - 12 旧 + 62 新 ≈ 513 条，以实际为准）

- [ ] **Step 4: debug 构建**

Run: `.\gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```powershell
git add app/src/main/java/com/mynote/app/ui/ai/AiChatScreen.kt
git commit -m "feat: AI 助手气泡改用 Markdown 渲染并移除旧代码块解析器"
```

---

### Task 6: 收尾验证与文档

**Files:**
- Modify: `docs/backlog.md`（记录条目 61 升级为完整 Markdown）

- [ ] **Step 1: release 构建（可选，本地有 `keystore.properties` 时）**

Run: `.\gradlew :app:assembleRelease`
Expected: BUILD SUCCESSFUL；若因缺少签名配置失败，跳过并记录

- [ ] **Step 2: 更新 backlog 完成记录**

在 `docs/backlog.md` 的「完成记录」表格末尾追加一行（沿用现有列格式）：

```markdown
| 2026-09-15 | **AI 回答 Markdown 渲染**：新增 `MarkdownParser`（块级：标题/列表/引用/分隔线/表格/代码块）、`MarkdownInline`（行内：粗斜删/行内代码/链接/图片降级）、`MarkdownPlainText`（插入正文与存笔记转纯文本）、`MarkdownContent`（Compose 渲染，流式实时渲染）；移除 `CodeBlockParser`；零新增依赖、VM/数据库零改动。 |
```

- [ ] **Step 3: 提交**

```powershell
git add docs/backlog.md
git commit -m "docs: 记录 AI 回答 Markdown 渲染落地"
```

---

## 修订记录

- 2026-09-15：初版（用户确认设计后自行编写）。解析器契约、测试用例、渲染样式与接线细节均取自设计文档 `2026-09-15-ai-markdown-rendering-design.md`。
