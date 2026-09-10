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
