package com.mynote.app.data.export

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.image.ImageStore
import com.mynote.app.ui.notes.NoteContentParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteImageRendererTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val imageStore = ImageStore(context)
    private val renderer = NoteImageRenderer(imageStore)
    private val measurer = TextMeasurer(
        defaultFontFamilyResolver = createFontFamilyResolver(context),
        defaultDensity = Density(1f, 1f),
        defaultLayoutDirection = LayoutDirection.Ltr,
        cacheSize = 0
    )

    private fun note(title: String = "标题", content: String = "正文") =
        NoteImageRenderer.NoteData(title, content, "2026-09-10")

    private fun blocksOf(note: NoteImageRenderer.NoteData, scale: Float = 1f): List<NoteImageRenderer.Block> {
        val width = 360f * scale
        val padding = 24f * scale
        val contentWidth = width - 2 * padding
        val usable = 1365f * scale - 2 * padding
        return renderer.buildBlocks(note, scale, contentWidth, usable, measurer, Density(scale, 1f))
    }

    @Test
    fun shortNoteRendersOneWhitePage() = runTest {
        val pages = renderer.render(note(), PageMode.PAGED, 1f, measurer)
        assertEquals(1, pages.size)
        val bitmap = pages.first().bitmap
        assertEquals(360, bitmap.width)
        assertTrue(bitmap.height in 49..1365)
        assertEquals(android.graphics.Color.WHITE, bitmap.getPixel(0, 0))
    }

    @Test
    fun pagedModeKeepsEveryTextLine() = runTest {
        val longText = "这是一段用于分页测试的较长文字。".repeat(200)
        val note = note(content = longText)
        val blocks = blocksOf(note)
        val usable = 1365f - 48f
        val plans = renderer.plan(blocks, usable)

        assertTrue(plans.size > 1)
        plans.forEach { page ->
            assertTrue(page.contentHeight <= usable + 0.5f)
        }

        val textBlocks = blocks.filterIsInstance<NoteImageRenderer.Block.TextBlock>()
        val covered = mutableMapOf<Int, MutableList<IntRange>>()
        plans.forEach { page ->
            page.items.filterIsInstance<NoteImageRenderer.PlacedItem.TextPiece>().forEach { piece ->
                val index = textBlocks.indexOfFirst { it.layout === piece.layout }
                covered.getOrPut(index) { mutableListOf() } += piece.startLine until piece.endLine
            }
        }
        textBlocks.forEachIndexed { index, block ->
            val ranges = covered[index].orEmpty().sortedBy { it.first }
            assertEquals(0, ranges.first().first)
            assertEquals(block.layout.lineCount - 1, ranges.last().last)
            ranges.zipWithNext().forEach { (a, b) ->
                assertEquals(a.last + 1, b.first)
            }
        }
    }

    @Test
    fun imageTooLargeForRemainingSpaceStartsNewPage() = runTest {
        writeImage("tall.webp", 200, 900)
        val text = "行文本。".repeat(120)
        val note = note(content = text + NoteContentParser.makeImageMarkup("tall.webp"))
        val blocks = blocksOf(note)
        val plans = renderer.plan(blocks, 1317f)

        assertTrue(plans.size > 1)
        val imagePages = plans.mapIndexedNotNull { index, page ->
            if (page.items.any { it is NoteImageRenderer.PlacedItem.ImagePiece }) index else null
        }
        assertTrue(imagePages.isNotEmpty())
        imagePages.forEach { index ->
            val first = plans[index].items.first()
            assertTrue(first is NoteImageRenderer.PlacedItem.ImagePiece)
            assertEquals(0f, first.y)
        }
    }

    @Test
    fun oversizedImageIsScaledAndCentered() = runTest {
        writeImage("huge.webp", 400, 6000)
        val blocks = blocksOf(note(content = NoteContentParser.makeImageMarkup("huge.webp")))
        val image = blocks.filterIsInstance<NoteImageRenderer.Block.ImageBlock>().single()
        assertEquals(1317f, image.height, 1f)
        assertTrue(image.width < 312f)
        assertTrue(image.centered)
    }

    @Test
    fun blankTitleOmitsTitleBlock() = runTest {
        val blocks = blocksOf(note(title = ""))
        val first = blocks.first()
        assertTrue(first is NoteImageRenderer.Block.TextBlock)
        assertEquals("2026-09-10", (first as NoteImageRenderer.Block.TextBlock).layout.layoutInput.text.text)
    }

    @Test
    fun singleModeHeightMatchesMeasurement() = runTest {
        val note = note(content = "正文\n" + "更多正文。".repeat(50))
        val measurement = renderer.measure(note, 1f, measurer)
        val pages = renderer.render(note, PageMode.SINGLE, 1f, measurer)
        assertEquals(1, pages.size)
        assertEquals(measurement.totalHeightPx, pages.first().bitmap.height)
    }

    @Test
    fun extremeAspectImageDoesNotCrash() = runTest {
        writeImage("sliver.webp", 1, 4000)
        val pages = renderer.render(
            note(content = NoteContentParser.makeImageMarkup("sliver.webp")),
            PageMode.SINGLE,
            1f,
            measurer
        )
        assertTrue(pages.isNotEmpty())
    }

    @Test
    fun exportScaleUsesExpectedDimensions() = runTest {
        val pages = renderer.render(note(content = "分页文字。".repeat(300)), PageMode.PAGED, 3f, measurer)
        assertTrue(pages.size > 1)
        pages.dropLast(1).forEach { page ->
            assertEquals(1080, page.bitmap.width)
            assertEquals(4096, page.bitmap.height)
        }
    }

    @Test
    fun bodyTextIsDrawnOnPage() = runTest {
        val bitmap = renderer.render(note(content = "一段正文"), PageMode.PAGED, 1f, measurer).first().bitmap
        var nonWhite = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) != android.graphics.Color.WHITE) nonWhite++
            }
        }
        assertTrue(nonWhite > 0)
    }

    @Test
    fun missingImageIsSkipped() = runTest {
        val blocks = blocksOf(note(content = "正文" + NoteContentParser.makeImageMarkup("missing.webp")))
        assertTrue(blocks.none { it is NoteImageRenderer.Block.ImageBlock })
        assertTrue(blocks.any { it is NoteImageRenderer.Block.TextBlock })
    }

    @Test
    fun imageIsDrawnWithRoundedCorners() = runTest {
        writeSolidImage("solid.webp", 200, 200)
        val bitmap = renderer.render(
            note(content = NoteContentParser.makeImageMarkup("solid.webp")),
            PageMode.PAGED,
            1f,
            measurer
        ).first().bitmap

        var minX = bitmap.width
        var minY = bitmap.height
        var found = false
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) == android.graphics.Color.RED) {
                    found = true
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                }
            }
        }
        assertTrue(found)
        assertEquals(android.graphics.Color.WHITE, bitmap.getPixel(minX, minY))
    }

    @Test
    fun renderPagesStreamsAllPagesInOrderWithTotal() = runTest {
        val note = note(content = "分页文字。".repeat(300))
        val seen = mutableListOf<Pair<Int, Int>>()
        val count = renderer.renderPages(note, PageMode.PAGED, 1f, measurer) { page, total ->
            seen += page.index to total
            page.bitmap.recycle()
        }
        assertEquals(count, seen.size)
        assertEquals(seen.indices.toList(), seen.map { it.first })
        assertTrue(seen.all { it.second == count })
    }

    private fun writeImage(name: String, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.WEBP, 80, out)
            out.toByteArray()
        }
        imageStore.writeFile(name, bytes)
    }

    private fun writeSolidImage(name: String, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.RED)
        }
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        imageStore.writeFile(name, bytes)
    }
}
