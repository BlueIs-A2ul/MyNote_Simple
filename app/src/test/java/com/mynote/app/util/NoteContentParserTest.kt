package com.mynote.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteContentParserTest {

    /** 条目 97：按名字移除标记时只删那一张，其余内容原样保留。 */
    @Test
    fun removeImageMarkupDropsOnlyTargetImage() {
        val content = "a ![](img/x.webp) b ![](img/y.webp)"

        assertEquals("a  b ![](img/y.webp)", NoteContentParser.removeImageMarkup(content, "x.webp"))
        assertEquals(content, NoteContentParser.removeImageMarkup(content, "missing.webp"))
    }

    /** 条目 97：半截标记（缺右括号）按普通文本处理，不能被当成图片吞掉。 */
    @Test
    fun halfWrittenMarkupIsPlainText() {
        val content = "残缺 ![](img/x.webp 与 ![](img/"

        assertEquals(listOf(NoteContentParser.ContentBlock.Text(content)), NoteContentParser.parse(content))
        assertEquals(content, NoteContentParser.plainText(content))
        assertEquals(0, NoteContentParser.extractImageNames(content).size)
    }

    @Test
    fun pureTextYieldsSingleTextBlock() {
        val blocks = NoteContentParser.parse("hello world")
        assertEquals(listOf(NoteContentParser.ContentBlock.Text("hello world")), blocks)
    }

    @Test
    fun splitsTextAndImageBlocks() {
        val blocks = NoteContentParser.parse("前文 ![](img/abc.jpg) 后文")
        assertEquals(
            listOf(
                NoteContentParser.ContentBlock.Text("前文 "),
                NoteContentParser.ContentBlock.Image("abc.jpg"),
                NoteContentParser.ContentBlock.Text(" 后文")
            ),
            blocks
        )
    }

    @Test
    fun consecutiveImagesYieldNoEmptyText() {
        val blocks = NoteContentParser.parse("![](img/a.png)![](img/b.png)")
        assertEquals(
            listOf(
                NoteContentParser.ContentBlock.Image("a.png"),
                NoteContentParser.ContentBlock.Image("b.png")
            ),
            blocks
        )
    }

    @Test
    fun extractImageNamesCollectsAll() {
        val names = NoteContentParser.extractImageNames("x ![](img/1.jpg) y ![](img/2.webp)")
        assertEquals(listOf("1.jpg", "2.webp"), names)
    }

    @Test
    fun markupRoundTripsThroughParser() {
        val name = "uuid123.webp"
        val markup = NoteContentParser.makeImageMarkup(name)
        val blocks = NoteContentParser.parse("a$markup b")
        assertEquals(
            listOf(
                NoteContentParser.ContentBlock.Text("a"),
                NoteContentParser.ContentBlock.Image(name),
                NoteContentParser.ContentBlock.Text(" b")
            ),
            blocks
        )
    }

    @Test
    fun plainTextStripsImageMarkup() {
        assertEquals(
            "前文后文",
            NoteContentParser.plainText("前文![](img/a.jpg)后文")
        )
    }
}
