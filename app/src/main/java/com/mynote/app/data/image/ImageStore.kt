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
    }

    private val imageDir: File
        get() = File(context.filesDir, "notes_images").apply { mkdirs() }

    fun dir(): File = File(context.filesDir, "notes_images")

    fun physicalFile(name: String): File = File(imageDir, name)

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
        FileOutputStream(physicalFile(name)).use { it.write(bytes) }
    }

    /** 流式写入图片文件（供备份导入，避免整图读入内存）；不关闭传入的输入流。 */
    fun writeFile(name: String, input: InputStream) {
        FileOutputStream(physicalFile(name)).use { input.copyTo(it) }
    }

    /** 删除未被任何笔记引用的图片文件，并清理历史版本的缩略图遗留文件（缩略图机制已移除）。 */
    fun collectGarbage(referenced: Set<String>) {
        imageDir.listFiles()?.forEach { file ->
            val name = file.name
            // thumb_* 一律删除：仅清理历史遗留，不再生成
            if (name.startsWith("thumb_") || name !in referenced) {
                file.delete()
            }
        }
    }
}
