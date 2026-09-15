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
                        val close = findClosingDelimiter(
                            text,
                            delimiter.marker,
                            index + delimiter.marker.length,
                            end
                        )
                        if (close >= 0) {
                            builder.withStyle(delimiter.style) {
                                appendInline(
                                    this,
                                    text,
                                    index + delimiter.marker.length,
                                    close,
                                    codeBackground,
                                    linkColor
                                )
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
