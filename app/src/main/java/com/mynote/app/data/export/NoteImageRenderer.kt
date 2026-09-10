package com.mynote.app.data.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.mynote.app.data.image.ImageStore
import com.mynote.app.ui.notes.NoteContentParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 把笔记渲染成纯白底 PNG 页面。
 *
 * - [PageMode.PAGED]：按可用页高自动分页，分页只发生在文本行边界；
 * - [PageMode.SINGLE]：一张位图，高度 = 内容总高。
 *
 * 预览与导出共用同一引擎，仅 [scale] 不同。
 */
class NoteImageRenderer(private val imageStore: ImageStore) {

    companion object {
        const val BASE_WIDTH_DP = 360f
        const val EXPORT_SCALE = 3f
        const val PREVIEW_SCALE = 1f

        /** 输出宽 1080px（scale=3）时每页高上限 4096px。 */
        const val PAGE_HEIGHT_DP = 4096f / 3f
        const val PADDING_DP = 24f
        const val TITLE_SIZE_SP = 22f
        const val DATE_SIZE_SP = 12f
        const val BODY_SIZE_SP = 16f
        const val BODY_LINE_HEIGHT = 1.7f
        const val IMAGE_CORNER_DP = 8f
        const val SPACE_TITLE_DATE_DP = 6f
        const val SPACE_DATE_BODY_DP = 16f
        const val SPACE_BLOCK_DP = 12f

        /** 单张模式预计导出高度超过该值（导出像素）时提示生成较慢。 */
        const val SINGLE_WARN_HEIGHT_PX = 12000

        /** 预览长图总高上限（预览像素），超出则继续缩小预览比例。 */
        const val PREVIEW_MAX_HEIGHT_PX = 8000

        /** 分页预览页数上限；超过则按比例缩小预览比例（控制预览峰值内存）。 */
        const val PREVIEW_MAX_PAGES = 16

        private val COLOR_BACKGROUND = Color.White
        private val COLOR_TITLE = Color(0xFF111111)
        private val COLOR_BODY = Color(0xFF333333)
        private val COLOR_DATE = Color(0xFF9A9A9A)

        private fun pixelWidth(scale: Float): Int = (BASE_WIDTH_DP * scale).roundToInt()

        private fun pageHeightPx(scale: Float): Int = (PAGE_HEIGHT_DP * scale).roundToInt()
    }

    data class NoteData(val title: String, val content: String, val dateText: String)

    data class Measurement(val totalHeightPx: Int, val pageCount: Int)

    internal sealed interface Block {
        val spaceBefore: Float

        data class TextBlock(
            override val spaceBefore: Float,
            val layout: TextLayoutResult
        ) : Block

        data class ImageBlock(
            override val spaceBefore: Float,
            val file: File,
            val width: Float,
            val height: Float,
            val centered: Boolean
        ) : Block
    }

    internal sealed interface PlacedItem {
        val y: Float
        val height: Float

        data class TextPiece(
            val layout: TextLayoutResult,
            val startLine: Int,
            val endLine: Int,
            override val y: Float,
            override val height: Float
        ) : PlacedItem

        data class ImagePiece(
            val block: Block.ImageBlock,
            override val y: Float,
            override val height: Float
        ) : PlacedItem
    }

    internal data class PagePlan(val items: List<PlacedItem>, val contentHeight: Float)

    /** 只测量不绘制：用于预览比例决策与页数展示。 */
    suspend fun measure(note: NoteData, scale: Float, measurer: TextMeasurer): Measurement =
        withContext(Dispatchers.Default) {
            val density = Density(scale, 1f)
            val width = pixelWidth(scale)
            val padding = PADDING_DP * scale
            val contentWidth = width - 2 * padding
            val usableHeight = pageHeightPx(scale) - 2 * padding
            val blocks = buildBlocks(note, scale, contentWidth, usableHeight, measurer, density)
            coroutineContext.ensureActive()
            Measurement(
                totalHeightPx = ceil(plan(blocks, Float.MAX_VALUE).first().contentHeight + 2 * padding).toInt(),
                pageCount = plan(blocks, usableHeight).size
            )
        }

    /** 渲染全部页面并保留在内存（预览等小尺寸场景用）。 */
    suspend fun render(
        note: NoteData,
        mode: PageMode,
        scale: Float,
        measurer: TextMeasurer,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<RenderedPage> = withContext(Dispatchers.Default) {
        val render = planRender(note, mode, scale, measurer)
        render.plans.mapIndexed { index, page ->
            coroutineContext.ensureActive()
            onProgress(index, render.plans.size)
            drawPage(render, page, index, mode, scale)
        }
    }

    /**
     * 逐页渲染并交给 [onPage]（携带当前页与总页数）；渲染器不保留页面引用，
     * 调用方可在回调中写盘并立即回收位图，峰值内存 ≈ 一页。返回总页数。
     */
    suspend fun renderPages(
        note: NoteData,
        mode: PageMode,
        scale: Float,
        measurer: TextMeasurer,
        onPage: suspend (page: RenderedPage, total: Int) -> Unit
    ): Int = withContext(Dispatchers.Default) {
        val render = planRender(note, mode, scale, measurer)
        render.plans.forEachIndexed { index, page ->
            coroutineContext.ensureActive()
            onPage(drawPage(render, page, index, mode, scale), render.plans.size)
        }
        render.plans.size
    }

    private data class RenderPlan(
        val plans: List<PagePlan>,
        val width: Int,
        val padding: Float,
        val contentWidth: Float,
        val pageHeight: Float
    )

    private fun planRender(note: NoteData, mode: PageMode, scale: Float, measurer: TextMeasurer): RenderPlan {
        val density = Density(scale, 1f)
        val width = pixelWidth(scale)
        val padding = PADDING_DP * scale
        val contentWidth = width - 2 * padding
        val pageHeight = pageHeightPx(scale).toFloat()
        val usableHeight = pageHeight - 2 * padding
        val blocks = buildBlocks(note, scale, contentWidth, usableHeight, measurer, density)
        val plans = if (mode == PageMode.SINGLE) {
            listOf(plan(blocks, Float.MAX_VALUE).first())
        } else {
            plan(blocks, usableHeight)
        }
        return RenderPlan(plans, width, padding, contentWidth, pageHeight)
    }

    internal fun buildBlocks(
        note: NoteData,
        scale: Float,
        contentWidth: Float,
        usableHeight: Float,
        measurer: TextMeasurer,
        density: Density
    ): List<Block> {
        val blocks = mutableListOf<Block>()
        if (note.title.isNotBlank()) {
            blocks += Block.TextBlock(
                spaceBefore = 0f,
                layout = measureText(measurer, note.title, titleStyle(), contentWidth, density)
            )
        }
        blocks += Block.TextBlock(
            spaceBefore = if (blocks.isEmpty()) 0f else SPACE_TITLE_DATE_DP * scale,
            layout = measureText(measurer, note.dateText, dateStyle(), contentWidth, density)
        )

        var firstContent = true
        NoteContentParser.parse(note.content).forEach { contentBlock ->
            when (contentBlock) {
                is NoteContentParser.ContentBlock.Text -> {
                    if (contentBlock.text.isBlank()) return@forEach
                    blocks += Block.TextBlock(
                        spaceBefore = contentSpace(firstContent, scale),
                        layout = measureText(measurer, contentBlock.text, bodyStyle(), contentWidth, density)
                    )
                    firstContent = false
                }

                is NoteContentParser.ContentBlock.Image -> {
                    val file = imageStore.physicalFile(contentBlock.name)
                    val dimensions = imageDimensions(file) ?: return@forEach
                    val fit = fitImage(dimensions.first, dimensions.second, contentWidth, usableHeight)
                    blocks += Block.ImageBlock(
                        spaceBefore = contentSpace(firstContent, scale),
                        file = file,
                        width = fit.width,
                        height = fit.height,
                        centered = fit.centered
                    )
                    firstContent = false
                }
            }
        }
        return blocks
    }

    /**
     * 把块列表装进页；[usableHeight] 为每页可用内容高，`Float.MAX_VALUE` 表示不分页。
     * 文本按行拆分，绝不裁断一行；图片放不下整张移到下一页。
     */
    internal fun plan(blocks: List<Block>, usableHeight: Float): List<PagePlan> {
        val pages = mutableListOf<PagePlan>()
        var items = mutableListOf<PlacedItem>()
        var cursor = 0f
        var remaining = usableHeight

        fun flush() {
            pages += PagePlan(items, cursor)
            items = mutableListOf()
            cursor = 0f
            remaining = usableHeight
        }

        blocks.forEach { block ->
            when (block) {
                is Block.TextBlock -> {
                    var space = block.spaceBefore
                    var startLine = 0
                    val lineCount = block.layout.lineCount
                    while (startLine < lineCount) {
                        if (items.isNotEmpty() && space + lineHeight(block.layout, startLine) > remaining) {
                            flush()
                            space = 0f
                        }
                        val available = remaining - space
                        var endLine = startLine
                        while (
                            endLine < lineCount &&
                            block.layout.getLineBottom(endLine) - block.layout.getLineTop(startLine) <= available
                        ) {
                            endLine++
                        }
                        if (endLine == startLine) endLine++
                        val height = block.layout.getLineBottom(endLine - 1) - block.layout.getLineTop(startLine)
                        items += PlacedItem.TextPiece(block.layout, startLine, endLine, cursor + space, height)
                        cursor += space + height
                        remaining -= space + height
                        startLine = endLine
                        space = 0f
                    }
                }

                is Block.ImageBlock -> {
                    var space = block.spaceBefore
                    if (items.isNotEmpty() && space + block.height > remaining) {
                        flush()
                        space = 0f
                    }
                    items += PlacedItem.ImagePiece(block, cursor + space, block.height)
                    cursor += space + block.height
                    remaining -= space + block.height
                }
            }
        }
        if (items.isNotEmpty()) flush()
        return pages.ifEmpty { listOf(PagePlan(emptyList(), 0f)) }
    }

    private fun drawPage(render: RenderPlan, page: PagePlan, index: Int, mode: PageMode, scale: Float): RenderedPage {
        val isLast = index == render.plans.size - 1
        val height = if (mode == PageMode.PAGED && !isLast) {
            render.pageHeight
        } else {
            page.contentHeight + 2 * render.padding
        }
        val heightPx = ceil(height).toInt()
        val bitmap = Bitmap.createBitmap(render.width, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap.asImageBitmap())
        CanvasDrawScope().draw(
            Density(scale, 1f),
            LayoutDirection.Ltr,
            canvas,
            Size(render.width.toFloat(), heightPx.toFloat())
        ) {
            drawRect(COLOR_BACKGROUND, size = size)
            page.items.forEach { item ->
                when (item) {
                    is PlacedItem.TextPiece -> {
                        val top = item.layout.getLineTop(item.startLine)
                        val bottom = item.layout.getLineBottom(item.endLine - 1)
                        val itemY = render.padding + item.y
                        clipRect(left = 0f, top = itemY, right = render.width.toFloat(), bottom = itemY + bottom - top) {
                            drawText(item.layout, topLeft = Offset(render.padding, itemY - top))
                        }
                    }

                    is PlacedItem.ImagePiece -> {
                        val block = item.block
                        if (block.width < 1f || block.height < 1f) return@forEach
                        val decoded = decodeImage(block.file, block.width.roundToInt()) ?: return@forEach
                        val x = if (block.centered) {
                            render.padding + (render.contentWidth - block.width) / 2f
                        } else {
                            render.padding
                        }
                        val itemY = render.padding + item.y
                        val path = Path().apply {
                            addRoundRect(
                                RoundRect(
                                    Rect(x, itemY, x + block.width, itemY + block.height),
                                    CornerRadius(IMAGE_CORNER_DP * scale)
                                )
                            )
                        }
                        clipPath(path) {
                            drawImage(
                                image = decoded.asImageBitmap(),
                                dstOffset = IntOffset(x.roundToInt(), itemY.roundToInt()),
                                dstSize = IntSize(block.width.roundToInt(), block.height.roundToInt())
                            )
                        }
                        decoded.recycle()
                    }
                }
            }
        }
        return RenderedPage(bitmap, index)
    }

    private fun contentSpace(firstContent: Boolean, scale: Float): Float =
        if (firstContent) SPACE_DATE_BODY_DP * scale else SPACE_BLOCK_DP * scale

    private fun titleStyle() = TextStyle(
        fontSize = TITLE_SIZE_SP.sp,
        fontWeight = FontWeight.Bold,
        color = COLOR_TITLE
    )

    private fun dateStyle() = TextStyle(
        fontSize = DATE_SIZE_SP.sp,
        color = COLOR_DATE
    )

    private fun bodyStyle() = TextStyle(
        fontSize = BODY_SIZE_SP.sp,
        lineHeight = (BODY_SIZE_SP * BODY_LINE_HEIGHT).sp,
        color = COLOR_BODY
    )

    private fun measureText(
        measurer: TextMeasurer,
        text: String,
        style: TextStyle,
        widthPx: Float,
        density: Density
    ): TextLayoutResult = measurer.measure(
        text = text,
        style = style,
        overflow = TextOverflow.Clip,
        softWrap = true,
        constraints = Constraints(maxWidth = widthPx.roundToInt()),
        density = density,
        layoutDirection = LayoutDirection.Ltr
    )

    private fun lineHeight(layout: TextLayoutResult, line: Int): Float =
        layout.getLineBottom(line) - layout.getLineTop(line)

    private data class ImageFit(val width: Float, val height: Float, val centered: Boolean)

    private fun fitImage(
        imageWidth: Int,
        imageHeight: Int,
        contentWidth: Float,
        usableHeight: Float
    ): ImageFit {
        val fullHeight = contentWidth * imageHeight / imageWidth
        return if (fullHeight <= usableHeight) {
            ImageFit(contentWidth, fullHeight, centered = false)
        } else {
            ImageFit(contentWidth * usableHeight / fullHeight, usableHeight, centered = true)
        }
    }

    private fun imageDimensions(file: File): Pair<Int, Int>? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            bounds.outWidth to bounds.outHeight
        } else {
            null
        }
    }

    private fun decodeImage(file: File, targetWidth: Int): Bitmap? {
        if (!file.exists()) return null
        val target = targetWidth.coerceAtLeast(1)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }
}
