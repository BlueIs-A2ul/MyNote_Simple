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

        // 越过 GC 宽限期（新鲜文件按条目 71 受保护）；以三个文件里最晚的时间为基准，
        // 避免跨毫秒创建导致某个文件仍落在宽限期内
        val base = maxOf(keep.lastModified(), drop.lastModified(), dropThumb.lastModified())
        store.collectGarbage(setOf("keep.jpg"), now = base + ImageStore.GC_GRACE_MS + 1)

        assertTrue(keep.exists())
        assertFalse(drop.exists())
        assertFalse(dropThumb.exists())
    }

    /** 条目 80：旧备份正文可能仍引用 thumb_* 文件，只要还有引用就不能删（否则永久裂图）。 */
    @Test
    fun collectGarbageKeepsThumbnailStillReferenced() {
        val dir = store.dir()
        dir.mkdirs()
        val keep = File(dir, "keep.jpg")
        val keepThumb = File(dir, "thumb_keep.jpg")
        keep.writeText("x")
        keepThumb.writeText("x")

        store.collectGarbage(
            setOf("keep.jpg", "thumb_keep.jpg"),
            now = keep.lastModified() + ImageStore.GC_GRACE_MS + 1
        )

        assertTrue(keep.exists())
        assertTrue(keepThumb.exists())
    }

    @Test
    fun collectGarbageDeletesLegacyThumbWhenUnreferenced() {
        val dir = store.dir()
        dir.mkdirs()
        val keep = File(dir, "keep.jpg")
        val orphanThumb = File(dir, "thumb_keep.jpg")
        keep.writeText("x")
        orphanThumb.writeText("x")

        store.collectGarbage(setOf("keep.jpg"), now = keep.lastModified() + ImageStore.GC_GRACE_MS + 1)

        assertTrue(keep.exists())
        assertFalse(orphanThumb.exists())
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

    // ---------- 条目 71：GC 宽限期 ----------

    @Test
    fun collectGarbageKeepsFilesWithinGracePeriod() {
        // 刚插图的文件：引用还没落库（草稿尚未保存），此时 GC 不能删
        val dir = store.dir()
        dir.mkdirs()
        val fresh = File(dir, "fresh.webp")
        fresh.writeText("x")

        store.collectGarbage(emptySet(), now = fresh.lastModified() + 1)

        assertTrue(fresh.exists())
    }

    @Test
    fun collectGarbageDeletesUnreferencedAfterGracePeriod() {
        val dir = store.dir()
        dir.mkdirs()
        val old = File(dir, "old.webp")
        old.writeText("x")

        store.collectGarbage(emptySet(), now = old.lastModified() + ImageStore.GC_GRACE_MS + 1)

        assertFalse(old.exists())
    }

    @Test
    fun collectGarbageClearsStalePendingFiles() {
        // 导入中途崩溃留下的暂存残留：超过宽限期后由 GC 回收
        val staged = store.pendingFile("stale.webp")
        staged.writeText("x")

        store.collectGarbage(emptySet(), now = staged.lastModified() + 1)
        assertTrue(staged.exists())

        store.collectGarbage(emptySet(), now = staged.lastModified() + ImageStore.GC_GRACE_MS + 1)
        assertFalse(staged.exists())
    }

    // ---------- 条目 74：原子写入 ----------

    @Test
    fun writeFileDoesNotTruncateExistingFileWhenStreamFails() {
        val dir = store.dir()
        dir.mkdirs()
        val target = File(dir, "existing.webp")
        target.writeText("good")

        val failing = object : ByteArrayInputStream("bad".toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw java.io.IOException("读取失败")
        }
        val threw = runCatching { store.writeFile("existing.webp", failing) }.isFailure

        assertTrue(threw)
        assertTrue(target.exists())
        assertTrue(target.readText() == "good")
        assertFalse(File(dir, "existing.webp.tmp").exists())
    }

    @Test
    fun writePendingWritesIntoPendingDirNotImageDir() {
        store.writePending("pending.webp", ByteArrayInputStream("p".toByteArray()))

        assertTrue(store.pendingFile("pending.webp").exists())
        assertFalse(store.physicalFile("pending.webp").exists())
    }
}
