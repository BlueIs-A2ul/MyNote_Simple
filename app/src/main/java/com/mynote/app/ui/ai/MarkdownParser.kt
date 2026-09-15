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
