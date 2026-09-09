package com.mynote.app.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteContentParserTest {

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
