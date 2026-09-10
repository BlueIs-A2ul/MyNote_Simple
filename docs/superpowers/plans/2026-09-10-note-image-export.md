# 笔记导出为图片 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让编辑页能把当前笔记导出为纯白底 PNG（无任何品牌元素），长笔记支持自动分页与单张长图两种输出，并提供预览、保存与分享。

**Architecture:** 自绘渲染引擎（Compose `TextMeasurer` + `CanvasDrawScope`）负责测量、分页与逐页位图绘制；预览与导出共用同一引擎，仅 scale 不同（预览 360px 宽 / 导出 1080px 宽）。`ImageExportManager` 负责文件名清理、SAF 保存与 FileProvider 分享。UI 层用全屏 Dialog 承载预览与操作，ViewModel 调度后台渲染。

**Tech Stack:** Kotlin · Jetpack Compose 1.7（TextMeasurer / CanvasDrawScope）· kotlinx.coroutines · SAF（CreateDocument / OpenDocumentTree）· FileProvider（androidx.core）· Robolectric 4.13 单测

**设计文档:** `docs/superpowers/specs/2026-09-10-note-image-export-design.md`

---

## 文件结构

| 文件 | 职责 |
|---|---|
| `app/src/main/java/com/mynote/app/data/export/ExportModels.kt` | `PageMode`、`RenderedPage` |
| `app/src/main/java/com/mynote/app/data/export/NoteImageRenderer.kt` | 测量 + 分页 + 逐页绘制（核心引擎） |
| `app/src/main/java/com/mynote/app/data/export/ImageExportManager.kt` | 文件名清理 / SAF 写入 / FileProvider 分享 / 缓存清理 |
| `app/src/main/java/com/mynote/app/ui/export/NoteExportViewModel.kt` | 预览状态、模式切换、全清渲染调度 |
| `app/src/main/java/com/mynote/app/ui/export/NoteExportDialog.kt` | 全屏预览 UI + ActivityResult + Snackbar |
| `app/src/main/res/xml/file_paths.xml` | FileProvider 缓存目录映射 |
| `app/src/main/AndroidManifest.xml` | FileProvider 声明（修改） |
| `app/src/main/java/com/mynote/app/ui/notes/NoteEditScreen.kt` | 分享图标改格式选择 + 挂预览（修改） |
| `app/src/main/java/com/mynote/app/di/AppContainer.kt` | 注册 renderer / exportManager（修改） |
| `app/src/main/java/com/mynote/app/ui/navigation/AppNavHost.kt` | 传参（修改） |
| `app/src/test/java/com/mynote/app/data/export/ImageExportManagerTest.kt` | 新增测试 |
| `app/src/test/java/com/mynote/app/data/export/NoteImageRendererTest.kt` | 新增测试 |
| `app/src/test/java/com/mynote/app/ui/export/NoteExportViewModelTest.kt` | 新增测试 |
| `README.md`、设计文档 | 文档更新（修改） |

---

### Task 1: 数据模型 ExportModels.kt

**Files:**
- Create: `app/src/main/java/com/mynote/app/data/export/ExportModels.kt`

- [ ] **Step 1: 跑基线测试确认 39 个全绿**

Run: `.\gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL（39 个测试通过）

- [ ] **Step 2: 创建 ExportModels.kt**

```kotlin
package com.mynote.app.data.export

import android.graphics.Bitmap

/** 导出模式：自动分页 / 单张长图。 */
enum class PageMode { PAGED, SINGLE }

/** 一页渲染结果；index 从 0 开始。 */
data class RenderedPage(val bitmap: Bitmap, val index: Int)
```

- [ ] **Step 3: 编译确认**

Run: `.\gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mynote/app/data/export/ExportModels.kt
git commit -m "feat: 图片导出数据模型（PageMode / RenderedPage）"
```

---

### Task 2: ImageExportManager（TDD）

**Files:**
- Create: `app/src/test/java/com/mynote/app/data/export/ImageExportManagerTest.kt`
- Create: `app/src/main/java/com/mynote/app/data/export/ImageExportManager.kt`

- [ ] **Step 1: 先写失败测试**

创建 `app/src/test/java/com/mynote/app/data/export/ImageExportManagerTest.kt`：

```kotlin
package com.mynote.app.data.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.util.TimeFormat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageExportManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = ImageExportManager(context)

    @Test
    fun sanitizeNameReplacesIllegalCharacters() {
        assertEquals("a_b_c__", manager.sanitizeName("""a/b:c*?"""))
    }

    @Test
    fun sanitizeNameFallsBackForBlankTitle() {
        assertEquals("笔记", manager.sanitizeName("   "))
        assertEquals("笔记", manager.sanitizeName(".."))
    }

    @Test
    fun baseNameCombinesTitleAndDate() {
        val timestamp = Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, 10) }.timeInMillis
        val expectedDate = TimeFormat.date(timestamp).replace("-", "")
        assertEquals("旅行-$expectedDate", manager.baseName("旅行", timestamp))
    }

    @Test
    fun pageFileNameAddsIndexOnlyForMultiplePages() {
        assertEquals("标题-20260910.png", manager.pageFileName("标题-20260910", 0, 1))
        assertEquals("标题-20260910-1.png", manager.pageFileName("标题-20260910", 0, 3))
        assertEquals("标题-20260910-3.png", manager.pageFileName("标题-20260910", 2, 3))
    }

    @Test
    fun writeToUriWritesPngFile() = runTest {
        val file = File(context.cacheDir, "single-test.png")
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val result = manager.writeToUri(Uri.fromFile(file), listOf(RenderedPage(bitmap, 0)))
        assertTrue(result.isSuccess)
        assertTrue(file.exists())
        val header = file.readBytes().take(4).map { it.toInt() and 0xFF }
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), header)
    }

    @Test
    fun clearCacheDeletesOldExportFiles() {
        val dir = File(context.cacheDir, "exports")
        dir.mkdirs()
        val stale = File(dir, "old.png")
        stale.writeText("x")

        manager.clearCache()

        assertFalse(stale.exists())
    }

    @Test
    fun buildShareIntentUsesSendForSinglePage() {
        val uri = Uri.parse("content://com.mynote.app.fileprovider/exports/a.png")
        val intent = manager.buildShareIntent(listOf(uri))
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/png", intent.type)
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun buildShareIntentUsesSendMultipleForPages() {
        val uris = listOf(
            Uri.parse("content://com.mynote.app.fileprovider/exports/a-1.png"),
            Uri.parse("content://com.mynote.app.fileprovider/exports/a-2.png")
        )
        val intent = manager.buildShareIntent(uris)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        val extra = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        assertEquals(2, extra?.size)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.export.ImageExportManagerTest"`
Expected: BUILD FAILED（`unresolved reference: ImageExportManager`）

- [ ] **Step 3: 实现 ImageExportManager.kt**

创建 `app/src/main/java/com/mynote/app/data/export/ImageExportManager.kt`：

```kotlin
package com.mynote.app.data.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import com.mynote.app.util.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** 导出文件命名、SAF 保存、FileProvider 分享与缓存清理。 */
class ImageExportManager(private val context: Context) {

    private val exportsDir: File
        get() = File(context.cacheDir, "exports")

    /** 去掉文件名非法字符；空标题回退「笔记」。 */
    fun sanitizeName(title: String): String {
        val cleaned = title
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .trim()
            .trim('.')
        return cleaned.take(40).ifBlank { "笔记" }
    }

    /** 文件名基名：`标题-yyyyMMdd`。 */
    fun baseName(title: String, timestamp: Long = System.currentTimeMillis()): String =
        "${sanitizeName(title)}-${TimeFormat.date(timestamp).replace("-", "")}"

    /** 单页 `base.png`；多页 `base-1.png`、`base-2.png`… */
    fun pageFileName(base: String, index: Int, total: Int): String =
        if (total <= 1) "$base.png" else "$base-${index + 1}.png"

    /** 写入单个 SAF 文档 Uri（另存为）。 */
    suspend fun writeToUri(uri: Uri, pages: List<RenderedPage>): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val page = pages.first()
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    check(page.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG 编码失败" }
                } ?: error("无法写入所选位置")
                Result.success(1)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** 写入 SAF 目录（多页逐张创建文档）。 */
    suspend fun writeToTree(treeUri: Uri, pages: List<RenderedPage>, base: String): Result<Int> =
        withContext(Dispatchers.IO) {
            var done = 0
            try {
                val resolver = context.contentResolver
                pages.forEachIndexed { index, page ->
                    val name = pageFileName(base, index, pages.size)
                    val docUri = DocumentsContract.createDocument(resolver, treeUri, "image/png", name)
                        ?: error("无法创建文件：$name")
                    resolver.openOutputStream(docUri)?.use { out ->
                        check(page.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG 编码失败" }
                    } ?: error("无法写入文件：$name")
                    done++
                }
                Result.success(done)
            } catch (e: Exception) {
                Result.failure(IllegalStateException("已保存 $done 张，写入中断：${e.message}", e))
            }
        }

    /** 写入缓存目录并返回 FileProvider content Uri（分享用）。 */
    fun writeCacheFiles(pages: List<RenderedPage>, base: String): List<Uri> {
        clearCache()
        exportsDir.mkdirs()
        return pages.mapIndexed { index, page ->
            val file = File(exportsDir, pageFileName(base, index, pages.size))
            FileOutputStream(file).use { out ->
                page.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
    }

    /** 单页 ACTION_SEND；多页 ACTION_SEND_MULTIPLE。 */
    fun buildShareIntent(uris: List<Uri>): Intent =
        if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/png"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

    fun clearCache() {
        exportsDir.listFiles()?.forEach { it.delete() }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.export.ImageExportManagerTest"`
Expected: BUILD SUCCESSFUL（8 个测试通过）

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/mynote/app/data/export/ImageExportManagerTest.kt app/src/main/java/com/mynote/app/data/export/ImageExportManager.kt
git commit -m "feat: ImageExportManager（文件名清理 / SAF 保存 / 分享 Intent / 缓存清理）"
```

---

### Task 3: NoteImageRenderer 渲染引擎（TDD）

**Files:**
- Create: `app/src/test/java/com/mynote/app/data/export/NoteImageRendererTest.kt`
- Create: `app/src/main/java/com/mynote/app/data/export/NoteImageRenderer.kt`

- [ ] **Step 1: 先写失败测试**

创建 `app/src/test/java/com/mynote/app/data/export/NoteImageRendererTest.kt`：

```kotlin
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
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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

    private fun writeImage(name: String, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.WEBP, 80, out)
            out.toByteArray()
        }
        imageStore.writeFile(name, bytes)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.export.NoteImageRendererTest"`
Expected: BUILD FAILED（`unresolved reference: NoteImageRenderer`）

- [ ] **Step 3: 实现 NoteImageRenderer.kt**

创建 `app/src/main/java/com/mynote/app/data/export/NoteImageRenderer.kt`：

```kotlin
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
            val block: ImageBlock,
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
            Measurement(
                totalHeightPx = ceil(plan(blocks, Float.MAX_VALUE).first().contentHeight + 2 * padding).toInt(),
                pageCount = plan(blocks, usableHeight).size
            )
        }

    /** 渲染全部页面；调用方负责在合适线程收集结果。 */
    suspend fun render(
        note: NoteData,
        mode: PageMode,
        scale: Float,
        measurer: TextMeasurer,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<RenderedPage> = withContext(Dispatchers.Default) {
        val density = Density(scale, 1f)
        val width = pixelWidth(scale)
        val padding = PADDING_DP * scale
        val contentWidth = width - 2 * padding
        val pageHeight = pageHeightPx(scale)
        val usableHeight = pageHeight - 2 * padding
        val blocks = buildBlocks(note, scale, contentWidth, usableHeight, measurer, density)
        val plans = if (mode == PageMode.SINGLE) {
            listOf(plan(blocks, Float.MAX_VALUE).first())
        } else {
            plan(blocks, usableHeight)
        }
        plans.mapIndexed { index, page ->
            coroutineContext.ensureActive()
            onProgress(index, plans.size)
            drawPage(page, index, mode, plans.size, scale, width, padding, contentWidth, pageHeight)
        }
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

    private fun drawPage(
        page: PagePlan,
        index: Int,
        mode: PageMode,
        pageCount: Int,
        scale: Float,
        width: Int,
        padding: Float,
        contentWidth: Float,
        pageHeight: Float
    ): RenderedPage {
        val isLast = index == pageCount - 1
        val height = if (mode == PageMode.PAGED && !isLast) pageHeight else page.contentHeight + 2 * padding
        val bitmap = Bitmap.createBitmap(width, ceil(height).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap.asImageBitmap())
        CanvasDrawScope().draw(Density(scale, 1f), LayoutDirection.Ltr, canvas, Size(width.toFloat(), height)) {
            drawRect(COLOR_BACKGROUND, size = size)
            page.items.forEach { item ->
                when (item) {
                    is PlacedItem.TextPiece -> {
                        val top = item.layout.getLineTop(item.startLine)
                        val bottom = item.layout.getLineBottom(item.endLine - 1)
                        val itemY = padding + item.y
                        clipRect(left = 0f, top = itemY, right = width.toFloat(), bottom = itemY + bottom - top) {
                            drawText(item.layout, topLeft = Offset(padding, itemY - top))
                        }
                    }

                    is PlacedItem.ImagePiece -> {
                        val decoded = decodeImage(item.block.file, item.block.width.roundToInt()) ?: return@forEach
                        val x = if (item.block.centered) padding + (contentWidth - item.block.width) / 2f else padding
                        val itemY = padding + item.y
                        val path = Path().apply {
                            addRoundRect(
                                RoundRect(
                                    Rect(x, itemY, x + item.block.width, itemY + item.block.height),
                                    CornerRadius(IMAGE_CORNER_DP * scale)
                                )
                            )
                        }
                        clipPath(path) {
                            drawImage(
                                image = decoded.asImageBitmap(),
                                dstOffset = IntOffset(x.roundToInt(), itemY.roundToInt()),
                                dstSize = IntSize(item.block.width.roundToInt(), item.block.height.roundToInt())
                            )
                        }
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
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.export.NoteImageRendererTest"`
Expected: BUILD SUCCESSFUL（6 个测试通过）

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/mynote/app/data/export/NoteImageRendererTest.kt app/src/main/java/com/mynote/app/data/export/NoteImageRenderer.kt
git commit -m "feat: NoteImageRenderer 自绘渲染引擎（测量/分页/逐页绘制）"
```

---

### Task 4: NoteExportViewModel（TDD）

**Files:**
- Create: `app/src/test/java/com/mynote/app/ui/export/NoteExportViewModelTest.kt`
- Create: `app/src/main/java/com/mynote/app/ui/export/NoteExportViewModel.kt`

- [ ] **Step 1: 先写失败测试**

创建 `app/src/test/java/com/mynote/app/ui/export/NoteExportViewModelTest.kt`：

```kotlin
package com.mynote.app.ui.export

import android.content.Context
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.export.ImageExportManager
import com.mynote.app.data.export.NoteImageRenderer
import com.mynote.app.data.export.PageMode
import com.mynote.app.data.image.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteExportViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var vm: NoteExportViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val measurer = TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(context),
            defaultDensity = Density(1f, 1f),
            defaultLayoutDirection = LayoutDirection.Ltr,
            cacheSize = 0
        )
        vm = NoteExportViewModel(
            renderer = NoteImageRenderer(ImageStore(context)),
            exportManager = ImageExportManager(context),
            measurer = measurer,
            note = NoteImageRenderer.NoteData("标题", "正文内容", "2026-09-10")
        )
    }

    @After
    fun teardown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun initLoadsPagedPreview() = runTest(dispatcher) {
        val state = vm.state.filterIsInstance<NoteExportViewModel.State.Ready>().first()
        assertEquals(PageMode.PAGED, state.mode)
        assertTrue(state.pageCount >= 1)
        assertTrue(state.pages.isNotEmpty())
    }

    @Test
    fun setModeSingleRendersSinglePage() = runTest(dispatcher) {
        vm.state.filterIsInstance<NoteExportViewModel.State.Ready>().first()
        vm.setMode(PageMode.SINGLE)
        val state = vm.state.filterIsInstance<NoteExportViewModel.State.Ready>()
            .first { it.mode == PageMode.SINGLE && it.pages.size == 1 }
        assertEquals(PageMode.SINGLE, state.mode)
        assertTrue(state.pageCount >= 1)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.export.NoteExportViewModelTest"`
Expected: BUILD FAILED（`unresolved reference: NoteExportViewModel`）

- [ ] **Step 3: 实现 NoteExportViewModel.kt**

创建 `app/src/main/java/com/mynote/app/ui/export/NoteExportViewModel.kt`：

```kotlin
package com.mynote.app.ui.export

import android.content.Intent
import androidx.compose.ui.text.TextMeasurer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mynote.app.data.export.ImageExportManager
import com.mynote.app.data.export.NoteImageRenderer
import com.mynote.app.data.export.PageMode
import com.mynote.app.data.export.RenderedPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NoteExportViewModel(
    private val renderer: NoteImageRenderer,
    private val exportManager: ImageExportManager,
    private val measurer: TextMeasurer,
    private val note: NoteImageRenderer.NoteData
) : ViewModel() {

    sealed interface State {
        data object Loading : State

        data class Ready(
            val pages: List<RenderedPage>,
            val pageCount: Int,
            val mode: PageMode,
            val longWarning: Boolean
        ) : State

        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting

    private var mode = PageMode.PAGED

    init {
        refreshPreview()
    }

    fun setMode(newMode: PageMode) {
        if (newMode == mode) return
        mode = newMode
        refreshPreview()
    }

    fun retry() {
        refreshPreview()
    }

    /** 全清渲染（1080px 宽）后回调页面；失败时给出提示。 */
    fun renderForExport(onReady: (List<RenderedPage>) -> Unit, onError: (String) -> Unit) {
        if (_exporting.value) return
        viewModelScope.launch {
            _exporting.value = true
            try {
                val pages = renderer.render(note, mode, NoteImageRenderer.EXPORT_SCALE, measurer)
                onReady(pages)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (mode == PageMode.SINGLE) {
                    mode = PageMode.PAGED
                    refreshPreview()
                    onError("内容太长，已切换为分页模式，请重试")
                } else {
                    onError("生成图片失败")
                }
            } finally {
                _exporting.value = false
            }
        }
    }

    fun suggestedBaseName(): String = exportManager.baseName(note.title)

    fun suggestedFileName(): String = "${suggestedBaseName()}.png"

    suspend fun shareIntentFor(pages: List<RenderedPage>): Result<Intent> =
        withContext(Dispatchers.IO) {
            runCatching {
                val uris = exportManager.writeCacheFiles(pages, exportManager.baseName(note.title))
                exportManager.buildShareIntent(uris)
            }
        }

    private fun refreshPreview() {
        viewModelScope.launch {
            _state.value = State.Loading
            try {
                val measurement = renderer.measure(note, NoteImageRenderer.PREVIEW_SCALE, measurer)
                val previewScale = previewScaleFor(mode, measurement)
                val pages = renderer.render(note, mode, previewScale, measurer)
                val exportHeight = measurement.totalHeightPx.toFloat() *
                    NoteImageRenderer.EXPORT_SCALE / NoteImageRenderer.PREVIEW_SCALE
                _state.value = State.Ready(
                    pages = pages,
                    pageCount = if (mode == PageMode.PAGED) pages.size else measurement.pageCount,
                    mode = mode,
                    longWarning = mode == PageMode.SINGLE &&
                        exportHeight >= NoteImageRenderer.SINGLE_WARN_HEIGHT_PX
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (mode == PageMode.SINGLE) {
                    mode = PageMode.PAGED
                    refreshPreview()
                } else {
                    _state.value = State.Error("预览生成失败")
                }
            }
        }
    }

    private fun previewScaleFor(
        mode: PageMode,
        measurement: NoteImageRenderer.Measurement
    ): Float {
        if (mode != PageMode.SINGLE) return NoteImageRenderer.PREVIEW_SCALE
        if (measurement.totalHeightPx <= NoteImageRenderer.PREVIEW_MAX_HEIGHT_PX) {
            return NoteImageRenderer.PREVIEW_SCALE
        }
        return NoteImageRenderer.PREVIEW_SCALE * NoteImageRenderer.PREVIEW_MAX_HEIGHT_PX /
            measurement.totalHeightPx
    }

    companion object {
        fun factory(
            renderer: NoteImageRenderer,
            exportManager: ImageExportManager,
            measurer: TextMeasurer,
            note: NoteImageRenderer.NoteData
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { NoteExportViewModel(renderer, exportManager, measurer, note) }
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.export.NoteExportViewModelTest"`
Expected: BUILD SUCCESSFUL（2 个测试通过）

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/mynote/app/ui/export/NoteExportViewModelTest.kt app/src/main/java/com/mynote/app/ui/export/NoteExportViewModel.kt
git commit -m "feat: NoteExportViewModel（预览状态 / 模式切换 / 全清渲染调度）"
```

---

### Task 5: UI 接线（预览 Dialog + 编辑页入口 + FileProvider）

**Files:**
- Create: `app/src/main/res/xml/file_paths.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/mynote/app/di/AppContainer.kt`
- Modify: `app/src/main/java/com/mynote/app/ui/navigation/AppNavHost.kt`
- Create: `app/src/main/java/com/mynote/app/ui/export/NoteExportDialog.kt`
- Modify: `app/src/main/java/com/mynote/app/ui/notes/NoteEditScreen.kt`

- [ ] **Step 1: 创建 file_paths.xml 并在 Manifest 声明 FileProvider**

创建 `app/src/main/res/xml/file_paths.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="exports" path="exports/" />
</paths>
```

在 `AndroidManifest.xml` 的 `</activity>` 之后、`</application>` 之前插入：

```xml
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
```

- [ ] **Step 2: AppContainer 注册 + NavHost 传参**

`AppContainer.kt` 增加 import：

```kotlin
import com.mynote.app.data.export.ImageExportManager
import com.mynote.app.data.export.NoteImageRenderer
```

在 `backupManager` 之后增加：

```kotlin
    val noteImageRenderer: NoteImageRenderer by lazy { NoteImageRenderer(imageStore) }

    val imageExportManager: ImageExportManager by lazy { ImageExportManager(context) }
```

`AppNavHost.kt` 中 `NoteEditScreen(...)` 调用改为：

```kotlin
            NoteEditScreen(
                noteId = id,
                repository = container.noteRepository,
                imageStore = container.imageStore,
                backupManager = container.backupManager,
                imageRenderer = container.noteImageRenderer,
                exportManager = container.imageExportManager,
                onBack = { navController.popBackStack() }
            )
```

- [ ] **Step 3: 创建 NoteExportDialog.kt**

创建 `app/src/main/java/com/mynote/app/ui/export/NoteExportDialog.kt`：

```kotlin
package com.mynote.app.ui.export

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mynote.app.data.export.ImageExportManager
import com.mynote.app.data.export.NoteImageRenderer
import com.mynote.app.data.export.PageMode
import com.mynote.app.data.export.RenderedPage
import com.mynote.app.util.TimeFormat
import kotlinx.coroutines.launch

/** 让预览 Dialog 拥有独立 ViewModel 生命周期，关闭时取消渲染。 */
private class DialogViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteExportDialog(
    title: String,
    content: String,
    updatedAt: Long,
    renderer: NoteImageRenderer,
    exportManager: ImageExportManager,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val measurer = rememberTextMeasurer()
    val owner = remember { DialogViewModelStoreOwner() }
    val note = remember(title, content, updatedAt) {
        NoteImageRenderer.NoteData(title, content, TimeFormat.date(updatedAt))
    }
    val vm: NoteExportViewModel = viewModel(
        viewModelStoreOwner = owner,
        factory = NoteExportViewModel.factory(renderer, exportManager, measurer, note)
    )
    DisposableEffect(Unit) {
        onDispose { owner.viewModelStore.clear() }
    }

    val state by vm.state.collectAsState()
    val exporting by vm.exporting.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    var pendingPages by remember { mutableStateOf<List<RenderedPage>?>(null) }
    var pendingBase by remember { mutableStateOf("") }

    fun showMessage(message: String) {
        scope.launch { snackbarHost.showSnackbar(message) }
    }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        val pages = pendingPages
        if (uri != null && pages != null) {
            scope.launch {
                exportManager.writeToUri(uri, pages)
                    .onSuccess { showMessage("已保存") }
                    .onFailure { showMessage("保存失败") }
            }
        }
        pendingPages = null
    }

    val chooseTree = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val pages = pendingPages
        if (uri != null && pages != null) {
            scope.launch {
                exportManager.writeToTree(uri, pages, pendingBase)
                    .onSuccess { showMessage("已保存 $it 张") }
                    .onFailure { showMessage(it.message ?: "保存失败") }
            }
        }
        pendingPages = null
    }

    fun save() {
        vm.renderForExport(
            onReady = { pages ->
                pendingPages = pages
                pendingBase = vm.suggestedBaseName()
                if (pages.size == 1) {
                    createDocument.launch(vm.suggestedFileName())
                } else {
                    chooseTree.launch(null)
                }
            },
            onError = { showMessage(it) }
        )
    }

    fun share() {
        vm.renderForExport(
            onReady = { pages ->
                scope.launch {
                    vm.shareIntentFor(pages)
                        .onSuccess { intent ->
                            try {
                                context.startActivity(Intent.createChooser(intent, "分享图片"))
                            } catch (e: ActivityNotFoundException) {
                                showMessage("没有可分享的应用")
                            }
                        }
                        .onFailure { showMessage(it.message ?: "分享失败") }
                }
            },
            onError = { showMessage(it) }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("导出图片") },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                            }
                        }
                    )
                },
                snackbarHost = { SnackbarHost(snackbarHost) },
                bottomBar = {
                    if (state is NoteExportViewModel.State.Ready) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (exporting) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .navigationBarsPadding()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { save() },
                                    enabled = !exporting,
                                    modifier = Modifier.weight(1f)
                                ) { Text("保存") }
                                Button(
                                    onClick = { share() },
                                    enabled = !exporting,
                                    modifier = Modifier.weight(1f)
                                ) { Text("分享") }
                            }
                        }
                    }
                }
            ) { padding ->
                Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                    when (val current = state) {
                        is NoteExportViewModel.State.Loading -> {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        }

                        is NoteExportViewModel.State.Error -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(current.message)
                                TextButton(onClick = vm::retry) { Text("重试") }
                            }
                        }

                        is NoteExportViewModel.State.Ready -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp)
                            ) {
                                if (current.pageCount > 1) {
                                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                        SegmentedButton(
                                            selected = current.mode == PageMode.PAGED,
                                            onClick = { vm.setMode(PageMode.PAGED) },
                                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                            label = { Text("分页 ${current.pageCount} 张") }
                                        )
                                        SegmentedButton(
                                            selected = current.mode == PageMode.SINGLE,
                                            onClick = { vm.setMode(PageMode.SINGLE) },
                                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                            label = { Text("单张长图") }
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }
                                if (current.longWarning) {
                                    Text(
                                        "长图较大，生成可能需要几秒",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }
                                current.pages.forEach { page ->
                                    Image(
                                        bitmap = page.bitmap.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                    )
                                    Spacer(Modifier.height(12.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: 修改 NoteEditScreen.kt**

增加 import：

```kotlin
import com.mynote.app.data.export.ImageExportManager
import com.mynote.app.data.export.NoteImageRenderer
import com.mynote.app.ui.export.NoteExportDialog
```

函数签名在 `backupManager: BackupManager,` 之后插入两个参数：

```kotlin
    imageRenderer: NoteImageRenderer,
    exportManager: ImageExportManager,
```

在 `var showDeleteDialog by remember { mutableStateOf(false) }` 之后增加：

```kotlin
    var showExportDialog by remember { mutableStateOf(false) }
    var showImageExport by remember { mutableStateOf(false) }
```

把现有分享按钮块（原本直接 launch 导出 txt）：

```kotlin
                    if (noteId != null) {
                        IconButton(onClick = { exportTxtLauncher.launch((note?.title ?: "note") + ".txt") }) {
                            Icon(Icons.Default.Share, contentDescription = "导出为 txt")
                        }
                    }
```

替换为：

```kotlin
                    if (noteId != null) {
                        IconButton(onClick = { showExportDialog = true }) {
                            Icon(Icons.Default.Share, contentDescription = "导出")
                        }
                    }
```

在文件末尾 `if (showDeleteDialog) { ... }` 块之后追加：

```kotlin
    if (showExportDialog) {
        val hasContent = title.isNotBlank() || content.isNotBlank()
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("导出为…") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showExportDialog = false
                            exportTxtLauncher.launch((note?.title ?: "note") + ".txt")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("文本文档 (txt)") }
                    TextButton(
                        onClick = {
                            showExportDialog = false
                            showImageExport = true
                        },
                        enabled = hasContent,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("图片 (PNG)") }
                    if (!hasContent) {
                        Text("还没有内容", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("取消") }
            }
        )
    }

    if (showImageExport) {
        NoteExportDialog(
            title = title,
            content = content,
            updatedAt = note?.updatedAt ?: System.currentTimeMillis(),
            renderer = imageRenderer,
            exportManager = exportManager,
            onDismiss = { showImageExport = false }
        )
    }
```

- [ ] **Step 5: 编译并构建 debug APK**

Run: `.\gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/xml/file_paths.xml app/src/main/AndroidManifest.xml app/src/main/java/com/mynote/app/di/AppContainer.kt app/src/main/java/com/mynote/app/ui/navigation/AppNavHost.kt app/src/main/java/com/mynote/app/ui/export/NoteExportDialog.kt app/src/main/java/com/mynote/app/ui/notes/NoteEditScreen.kt
git commit -m "feat: 导出图片入口与预览页（格式选择 / 分页切换 / 保存分享 / FileProvider）"
```

---

### Task 6: 文档与全量验证

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-09-10-note-image-export-design.md`

- [ ] **Step 1: 跑全量单测并记录数量**

Run: `.\gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL；在构建输出或 `app/build/test-results/testDebugUnitTest/` 中确认总数（原 39 + 新增约 16）

- [ ] **Step 2: 更新 README**

功能列表：把

```markdown
- 备份：zip 全量备份 / 导入（按 id 去重、冲突保留更新者）+ 单条笔记导出 txt
```

替换为

```markdown
- 备份：zip 全量备份 / 导入（按 id 去重、冲突保留更新者）
- 导出：单条笔记导出 txt 或图片（纯白 PNG、无任何品牌元素；长笔记可选自动分页或单张长图，含内嵌图片）
```

项目结构中 `│   └── backup/               # BackupManager（zip 备份导入导出 / txt 导出）` 替换为

```markdown
│   ├── backup/               # BackupManager（zip 备份导入导出 / txt 导出）
│   └── export/               # NoteImageRenderer / ImageExportManager（图片导出）
```

项目结构中 `│   ├── settings/             # 主题设置页 / ViewModel` 之后（`│   └── navigation/` 之前）插入

```markdown
│   ├── export/               # 导出图片预览页 / ViewModel
```

构建命令注释里的测试数量（`rem 39 个单元测试`）改为实际数量（Step 1 记录值）。

- [ ] **Step 3: 更新设计文档状态**

`docs/superpowers/specs/2026-09-10-note-image-export-design.md` 头部：

```markdown
- 状态：设计已确认（待实现）
```

替换为（填入实际测试数与日期）：

```markdown
- 状态：已实现（2026-09-10，N 个单测全绿 + debug/release 构建通过；真机 UI 手工验证待做）
```

- [ ] **Step 4: 构建 debug + release**

Run: `.\gradlew :app:assembleDebug :app:assembleRelease`
Expected: BUILD SUCCESSFUL（release 为 R8 + 资源压缩 + 签名）

- [ ] **Step 5: Commit**

```bash
git add README.md docs/superpowers/specs/2026-09-10-note-image-export-design.md
git commit -m "docs: 图片导出功能落地（README + 设计文档状态）"
```

---

## 已知风险与备选（执行时若命中，按 systematic-debugging 处理）

1. **Robolectric 文本测量不可用**（`createFontFamilyResolver` 报错或布局结果为 0 行）：Robolectric 4.13 默认 NATIVE 图形，可先给测试类加 `@GraphicsMode(GraphicsMode.Mode.NATIVE)`；仍失败则改为在测试中构造最小 `TextMeasurer` 的包装接口（把测量抽象成 `(String, TextStyle, Float) -> TextLayoutResult` 注入），实现代码不变。
2. **Robolectric 下 `Uri.fromFile` 写入失败**：`writeToUriWritesPngFile` 可改为使用 `DocumentsContract` 的测试替身或在临时目录直接断言 `Bitmap.compress` 输出；主实现逻辑不变。
3. **VM 测试挂起**（真实 `Dispatchers.Default` 与测试调度器交互）：把 `NoteExportViewModelTest` 的 Main 换成 `UnconfinedTestDispatcher()`，或让 `NoteImageRenderer` 的调度器可注入。

## 手工验证清单（真机，实现者随构建产物执行）

1. 新建一条含标题、正文、2 张图片的笔记；编辑页分享 → 图片 → 预览应纯白、无图标/应用名/页码。
2. 深色模式 + 深色主题色下导出，图片仍为纯白。
3. 保存单页 PNG，相册/文件管理器打开检查清晰度（1080px 宽）。
4. 构造超长笔记（几百行文字 + 多图）：预览出现「分页 N 张 / 单张长图」切换；分别保存与分享；分页保存用文件夹选择器一次写入多张。
5. 分享到微信（或任一聊天应用）验证单张与多张。
6. 编辑页有未保存修改时导出，图片应包含未保存内容。
7. 空笔记（无标题无正文）时「图片 (PNG)」置灰并显示「还没有内容」。
