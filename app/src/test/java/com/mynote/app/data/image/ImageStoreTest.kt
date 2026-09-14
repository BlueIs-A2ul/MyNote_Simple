package com.mynote.app.data.image

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageStoreTest {

    private val store = ImageStore(ApplicationProvider.getApplicationContext())

    @Test
    fun thumbNamePrefixesOriginal() {
        assertEquals("thumb_abc.jpg", store.thumbFileName("abc.jpg"))
    }

    @Test
    fun collectGarbageDeletesUnreferencedButKeepsReferenced() {
        val dir = store.dir()
        dir.mkdirs()
        val keep = java.io.File(dir, "keep.jpg")
        val drop = java.io.File(dir, "drop.jpg")
        val dropThumb = java.io.File(dir, "thumb_drop.jpg")
        keep.writeText("x")
        drop.writeText("x")
        dropThumb.writeText("x")

        store.collectGarbage(setOf("keep.jpg"))

        assertTrue(keep.exists())
        assertFalse(drop.exists())
        assertFalse(dropThumb.exists())
    }

    @Test
    fun failedImportDeletesHalfWrittenTarget() {
        val dir = store.dir()
        dir.mkdirs()
        // 源文件不存在：读取阶段即失败（确定性失败路径）
        val missingSource = java.io.File(dir, "missing-source.webp")
        val target = store.newImageFile("webp")
        target.writeText("半成品")

        val ok = store.importAndCompress(Uri.fromFile(missingSource), target)

        assertFalse(ok)
        assertFalse(target.exists())
    }
}
