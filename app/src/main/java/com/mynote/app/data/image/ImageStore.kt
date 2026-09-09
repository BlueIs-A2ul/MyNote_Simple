package com.mynote.app.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class ImageStore(private val context: Context) {

    companion object {
        const val MAX_DIMENSION = 1600
        const val THUMB_DIMENSION = 300
    }

    private val imageDir: File
        get() = File(context.filesDir, "notes_images").apply { mkdirs() }

    fun dir(): File = File(context.filesDir, "notes_images")

    fun physicalFile(name: String): File = File(imageDir, name)

    fun thumbFileName(name: String): String = "thumb_$name"

    fun thumbFile(name: String): File = File(imageDir, thumbFileName(name))

    fun newImageFile(extension: String): File =
        File(imageDir, "${UUID.randomUUID()}.${extension.removePrefix(".")}")

    /** 采样压缩源图并写入目标文件，返回是否成功。 */
    fun importAndCompress(source: Uri, target: File): Boolean {
        return try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }

            var sample = 1
            while (bounds.outWidth / sample > MAX_DIMENSION || bounds.outHeight / sample > MAX_DIMENSION) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return false

            FileOutputStream(target).use { out ->
                bitmap.compress(Bitmap.CompressFormat.WEBP, 85, out)
            }
            bitmap.recycle()
            generateThumbnail(target.name)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun generateThumbnail(name: String) {
        try {
            val src = physicalFile(name)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > THUMB_DIMENSION || bounds.outHeight / sample > THUMB_DIMENSION) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeFile(src.absolutePath, opts) ?: return
            FileOutputStream(thumbFile(name)).use { out ->
                bitmap.compress(Bitmap.CompressFormat.WEBP, 80, out)
            }
            bitmap.recycle()
        } catch (e: Exception) {
            // 缩略图失败不阻塞主图保存
        }
    }

    fun writeFile(name: String, bytes: ByteArray) {
        FileOutputStream(physicalFile(name)).use { it.write(bytes) }
        generateThumbnail(name)
    }

    /** 删除未被任何笔记引用的图片文件（含缩略图）。 */
    fun collectGarbage(referenced: Set<String>) {
        imageDir.listFiles()?.forEach { file ->
            val name = file.name
            val base = if (name.startsWith("thumb_")) name.removePrefix("thumb_") else name
            if (base !in referenced) {
                file.delete()
            }
        }
    }
}
