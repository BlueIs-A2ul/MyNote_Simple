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
