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
