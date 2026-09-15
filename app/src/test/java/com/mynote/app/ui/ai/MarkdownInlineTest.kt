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
