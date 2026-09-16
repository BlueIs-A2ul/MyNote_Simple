package com.mynote.app.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class ImageStore(private val context: Context) {

    companion object {
        const val MAX_DIMENSION = 1600

        /**
         * GC 宽限期（条目 71）：图片文件先于引用它的 DB 行落盘（插图后草稿尚未保存、备份导入尚未提交），
         * 刚落盘的文件在宽限期内一律不回收，避免「启动清理恰好在保存前跑」把活图删掉。
         */
        const val GC_GRACE_MS = 10 * 60 * 1000L

        /** 备份导入的图片暂存目录（与 notes_images 同级）：事务提交后再提升，失败即整包丢弃。 */
        const val PENDING_DIR_NAME = "notes_images_pending"
    }

    private val imageDir: File
        get() = File(context.filesDir, "notes_images").apply { mkdirs() }

    fun dir(): File = File(context.filesDir, "notes_images")

    /** 导入暂存目录：整包图片先落这里，写库成功后再提升到正式目录。 */
    fun pendingDir(): File = File(context.filesDir, PENDING_DIR_NAME).apply { mkdirs() }

    fun physicalFile(name: String): File = File(imageDir, name)

    /** 暂存文件路径（备份导入用）。 */
    fun pendingFile(name: String): File = File(pendingDir(), name)

    fun newImageFile(extension: String): File =
        File(imageDir, "${UUID.randomUUID()}.${extension.removePrefix(".")}")

    /** 采样压缩源图并写入目标文件，返回是否成功。解码/压缩全部在 IO 线程执行。 */
    suspend fun importAndCompress(source: Uri, target: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }

            var sample = 1
            while (bounds.outWidth / sample > MAX_DIMENSION || bounds.outHeight / sample > MAX_DIMENSION) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: throw java.io.IOException("图片解码失败")

            FileOutputStream(target).use { out ->
                bitmap.compress(Bitmap.CompressFormat.WEBP, 85, out)
            }
            bitmap.recycle()
            true
        } catch (e: Exception) {
            // 失败时清理半成品文件，避免残留占用空间
            target.delete()
            false
        }
    }

    fun writeFile(name: String, bytes: ByteArray) {
        writeAtomically(imageDir, name) { it.write(bytes) }
    }

    /** 流式写入图片文件（供备份导入，避免整图读入内存）；不关闭传入的输入流。 */
    fun writeFile(name: String, input: InputStream) {
        writeAtomically(imageDir, name) { input.copyTo(it) }
    }

    /** 流式写入暂存目录（备份导入第一阶段），不关闭传入的输入流。 */
    fun writePending(name: String, input: InputStream) {
        writeAtomically(pendingDir(), name) { input.copyTo(it) }
    }

    /**
     * 删除未被任何笔记引用的图片文件，并清理历史版本的缩略图遗留文件（缩略图机制已移除）。
     *
     * @param now 当前时刻，便于单测注入；宽限期内的文件（[GC_GRACE_MS]）与暂存目录中的新鲜文件一律跳过。
     */
    fun collectGarbage(referenced: Set<String>, now: Long = System.currentTimeMillis()) {
        imageDir.listFiles()?.forEach { file ->
            val name = file.name
            // 刚落盘但引用尚未提交（插图/导入中途）→ 宽限期内不回收
            if (now - file.lastModified() < GC_GRACE_MS) return@forEach
            // thumb_* 一律删除：仅清理历史遗留，不再生成
            if (name.startsWith("thumb_") || name !in referenced) {
                file.delete()
            }
        }
        // 暂存目录的残留（导入被取消/进程被杀）同样按宽限期回收
        pendingDir().listFiles()?.forEach { file ->
            if (now - file.lastModified() >= GC_GRACE_MS) file.delete()
        }
    }

    /**
     * 原子写入：先写同目录临时文件再改名。失败时删除临时文件，
     * 既不留下半成品，也不会像「直接写目标文件」那样把已有的同名图片截断成坏图。
     */
    private fun writeAtomically(dir: File, name: String, write: (java.io.OutputStream) -> Unit) {
        val dest = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        try {
            FileOutputStream(tmp).use { write(it) }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                // 少数文件系统 rename 不覆盖：退化为拷贝 + 删除
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }
}
