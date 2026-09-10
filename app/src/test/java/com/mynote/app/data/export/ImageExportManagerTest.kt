package com.mynote.app.data.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageExportManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = ImageExportManager(context)

    private fun page() = RenderedPage(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888), 0)

    private fun pngBytes(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

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
    fun sanitizeNameTrimsAfterTruncation() {
        assertEquals("a", manager.sanitizeName("a" + " ".repeat(100) + "b"))
    }

    @Test
    fun baseNameCombinesTitleAndDate() {
        val timestamp = Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, 10) }.timeInMillis
        assertEquals("旅行-20260910", manager.baseName("旅行", timestamp))
    }

    @Test
    fun pageFileNameAddsIndexOnlyForMultiplePages() {
        assertEquals("标题-20260910.png", manager.pageFileName("标题-20260910", 0, 1))
        assertEquals("标题-20260910-1.png", manager.pageFileName("标题-20260910", 0, 3))
        assertEquals("标题-20260910-3.png", manager.pageFileName("标题-20260910", 2, 3))
    }

    @Test
    fun writePageFileWritesPngBytes() {
        val file = File(context.cacheDir, "exports-test/page.png")
        manager.writePageFile(file, page())
        assertTrue(file.exists())
        val header = file.readBytes().take(4).map { it.toInt() and 0xFF }
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), header)
    }

    @Test
    fun copyPageToUriCopiesBytes() = runTest {
        val source = File(context.cacheDir, "copy-source.png")
            .apply { writeBytes(pngBytes(android.graphics.Color.RED)) }
        val target = File(context.cacheDir, "copy-target.png")
        val result = manager.copyPageToUri(Uri.fromFile(target), source)
        assertTrue(result.isSuccess)
        assertArrayEquals(source.readBytes(), target.readBytes())
    }

    @Test
    fun prepareCacheClearsOldFiles() {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stale = File(dir, "old.png").apply { writeText("x") }
        manager.prepareCache()
        assertFalse(stale.exists())
        assertTrue(dir.exists())
    }

    @Test
    fun copyFilesWritesEachPageWithSequentialNames() {
        val sourceDir = File(context.cacheDir, "copy-source").apply { mkdirs() }
        val targetDir = File(context.cacheDir, "copy-target").apply { mkdirs() }
        val files = listOf(
            File(sourceDir, "标题-20260910-1.png").apply { writeBytes(pngBytes(android.graphics.Color.RED)) },
            File(sourceDir, "标题-20260910-2.png").apply { writeBytes(pngBytes(android.graphics.Color.BLUE)) }
        )
        val created = mutableListOf<String>()

        val result = manager.copyFiles(files) { name ->
            created += name
            Uri.fromFile(File(targetDir, name))
        }

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull())
        assertEquals(listOf("标题-20260910-1.png", "标题-20260910-2.png"), created)
        assertArrayEquals(files[0].readBytes(), File(targetDir, "标题-20260910-1.png").readBytes())
    }

    @Test
    fun copyFilesReportsPartialCountOnFailure() {
        val sourceDir = File(context.cacheDir, "copy-fail").apply { mkdirs() }
        val targetDir = File(context.cacheDir, "copy-fail-target").apply { mkdirs() }
        val files = listOf(
            File(sourceDir, "a-1.png").apply { writeBytes(pngBytes(android.graphics.Color.RED)) },
            File(sourceDir, "a-2.png").apply { writeBytes(pngBytes(android.graphics.Color.RED)) }
        )
        var calls = 0

        val result = manager.copyFiles(files) { name ->
            calls++
            if (calls == 1) Uri.fromFile(File(targetDir, name)) else null
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("已保存 1 张"))
    }

    @Test
    fun copyFilesRejectsEmptyList() {
        val result = manager.copyFiles(emptyList()) { null }
        assertTrue(result.isFailure)
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

    @Test(expected = IllegalStateException::class)
    fun buildShareIntentRejectsEmptyList() {
        manager.buildShareIntent(emptyList())
    }
}
