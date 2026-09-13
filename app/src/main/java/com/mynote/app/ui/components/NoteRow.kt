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
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = if (query != null) {
                        buildHighlighted(title, query, highlightBackground)
                    } else {
                        AnnotatedString(title)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (note.content.isNotBlank()) {
                val plainContent = NoteContentParser.plainText(note.content)
                Text(
                    text = if (query != null) {
                        buildHighlighted(plainContent, query, highlightBackground)
                    } else {
                        AnnotatedString(plainContent)
                    },
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
