package com.mynote.app.data.image

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageStoreTest {

    private val store = ImageStore(ApplicationProvider.getApplicationContext())

    @Test
    fun collectGarbageDeletesUnreferencedButKeepsReferenced() {
        val dir = store.dir()
        dir.mkdirs()
        val keep = File(dir, "keep.jpg")
        val drop = File(dir, "drop.jpg")
        val dropThumb = File(dir, "thumb_drop.jpg")
        keep.writeText("x")
        drop.writeText("x")
        dropThumb.writeText("x")

        store.collectGarbage(setOf("keep.jpg"))

        assertTrue(keep.exists())
        assertFalse(drop.exists())
        assertFalse(dropThumb.exists())
    }

    @Test
    fun collectGarbageDeletesLegacyThumbOfReferencedImage() {
        // 缩略图机制已移除：即使原图仍被引用，遗留的 thumb_* 文件也应一并清理
        val dir = store.dir()
        dir.mkdirs()
        val keep = File(dir, "keep.jpg")
        val keepThumb = File(dir, "thumb_keep.jpg")
        keep.writeText("x")
        keepThumb.writeText("x")

        store.collectGarbage(setOf("keep.jpg"))

        assertTrue(keep.exists())
        assertFalse(keepThumb.exists())
    }

    @Test
    fun importDoesNotCreateThumbnail() = runTest {
        val dir = store.dir()
        dir.mkdirs()
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.RED)
        val source = File(dir, "source.png")
        FileOutputStream(source).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val target = store.newImageFile("webp")
        val ok = store.importAndCompress(Uri.fromFile(source), target)

        assertTrue(ok)
        assertTrue(target.exists())
        assertFalse(File(dir, "thumb_${target.name}").exists())
    }

    @Test
    fun writeFileDoesNotCreateThumbnail() {
        val dir = store.dir()
        dir.mkdirs()
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)

        store.writeFile("plain.webp", bos.toByteArray())

        assertFalse(File(dir, "thumb_plain.webp").exists())
    }

    @Test
    fun failedImportDeletesHalfWrittenTarget() = runTest {
        val dir = store.dir()
        dir.mkdirs()
        // 源文件不存在：读取阶段即失败（确定性失败路径）
        val missingSource = File(dir, "missing-source.webp")
        val target = store.newImageFile("webp")
        target.writeText("半成品")

        val ok = store.importAndCompress(Uri.fromFile(missingSource), target)

        assertFalse(ok)
        assertFalse(target.exists())
    }

    @Test
    fun writeFileFromStreamWritesExactBytes() {
        // 备份导入的流式写盘路径：字节必须原样落盘
        val dir = store.dir()
        dir.mkdirs()
        val payload = "流式写入内容".toByteArray(Charsets.UTF_8)

        store.writeFile("stream.bin", ByteArrayInputStream(payload))

        val file = File(dir, "stream.bin")
        assertTrue(file.exists())
        assertTrue(file.readBytes().contentEquals(payload))
    }
}
