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
