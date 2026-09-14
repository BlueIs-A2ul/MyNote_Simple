package com.mynote.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.mynote.app.data.db.NoteEntity
import com.mynote.app.ui.notes.NoteContentParser
import com.mynote.app.util.TimeFormat

/** 纸感列表行：行首 3dp 分类色条 + 衬线标题 + 摘要 + 相对日期；可选显示分类名与搜索高亮。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteRow(
    note: NoteEntity,
    categoryColor: Color?,
    onClick: () -> Unit,
    now: Long = System.currentTimeMillis(),
    categoryName: String? = null,
    highlightQuery: String? = null,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // 高亮仅在查询词非空白时生效；背景色在此取主题色，保持纯函数可单测。
    val query = highlightQuery?.takeIf { it.isNotBlank() }
    val highlightBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val title = note.title.ifBlank { "无标题" }
    // 解析与高亮结果按输入缓存，避免每分钟 tick/滚动重组时重复全量正则与构造
    val plainContent = remember(note.content) { NoteContentParser.plainText(note.content) }
    val summary = remember(plainContent, query) {
        // 搜索态：命中在深处时展示命中位置附近的窗口（带省略号），而非盲目取开头
        if (query != null) snippetForHighlight(plainContent, query) else plainContent
    }
    val highlightedTitle = remember(title, query, highlightBackground) {
        if (query != null) buildHighlighted(title, query, highlightBackground) else AnnotatedString(title)
    }
    val highlightedSummary = remember(summary, query, highlightBackground) {
        if (query != null) buildHighlighted(summary, query, highlightBackground) else AnnotatedString(summary)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = onClick)
                }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.5.dp))
                .background(categoryColor ?: Color.Transparent)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (note.pinned) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = "置顶",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = highlightedTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (note.content.isNotBlank()) {
                Text(
                    text = highlightedSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = TimeFormat.relativeDate(note.updatedAt, now),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (categoryName != null) {
                Text(
                    text = categoryName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/**
 * 搜索摘要窗口：命中在前 [maxChars] 内取前缀；命中在深处时取命中位置附近的窗口，
 * 首尾用省略号标记截断。query 为空白或无命中时取前缀。
 * 让「正文深处命中」的笔记在行内也能看到命中上下文与高亮线索。
 */
internal fun snippetForHighlight(text: String, query: String, maxChars: Int = 80): String {
    if (query.isBlank()) return text.take(maxChars)
    val idx = text.indexOf(query, ignoreCase = true)
    if (idx < 0 || idx < maxChars) return text.take(maxChars)
    val start = (idx - maxChars / 3).coerceAtLeast(0)
    val end = (start + maxChars).coerceAtMost(text.length)
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < text.length) "…" else ""
    return prefix + text.substring(start, end) + suffix
}

/**
 * 把 text 中所有忽略大小写的 query 命中片段用指定背景色高亮。
 * 仅构造 AnnotatedString，不改变原文本内容。query 为空白时返回纯文本。
 * 背景色由调用方传入（如 MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)），
 * 使本函数不依赖 Composable 上下文，可脱离 MaterialTheme 单测。
 */
internal fun buildHighlighted(text: String, query: String, highlightBackground: Color): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val hit = text.indexOf(query, startIndex = i, ignoreCase = true)
            if (hit < 0) {
                // 剩余部分无命中，原样追加后结束。
                append(text.substring(i))
                break
            }
            if (hit > i) append(text.substring(i, hit))
            withStyle(SpanStyle(background = highlightBackground)) {
                append(text.substring(hit, hit + query.length))
            }
            // 起点推进到命中之后，保证顺序推进且不死循环。
            i = hit + query.length
        }
    }
}
