package com.mynote.app.data.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 导出文件命名、SAF 保存、FileProvider 分享与缓存清理。 */
class ImageExportManager(private val context: Context) {

    private val exportsDir: File
        get() = File(context.cacheDir, "exports")

    /** 去掉文件名非法字符并截断；空标题回退「笔记」。 */
    fun sanitizeName(title: String): String {
        val cleaned = title
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .take(40)
            .trim()
            .trim('.')
        return cleaned.ifBlank { "笔记" }
    }

    /** 文件名基名：`标题-yyyyMMdd`。 */
    fun baseName(title: String, timestamp: Long = System.currentTimeMillis()): String =
        "${sanitizeName(title)}-${dateStamp(timestamp)}"

    /** 单页 `base.png`；多页 `base-1.png`、`base-2.png`… */
    fun pageFileName(base: String, index: Int, total: Int): String =
        if (total <= 1) "$base.png" else "$base-${index + 1}.png"

    /** 写入单个 SAF 文档 Uri（另存为，只接受一页）。 */
    suspend fun writeToUri(uri: Uri, pages: List<RenderedPage>): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val page = pages.singleOrNull() ?: error("单页保存只接受一页图片")
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    compressPng(page, out)
                } ?: error("无法写入所选位置")
                Result.success(1)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** 写入 SAF 目录（多页逐张创建文档）。 */
    suspend fun writeToTree(treeUri: Uri, pages: List<RenderedPage>, base: String): Result<Int> =
        withContext(Dispatchers.IO) {
            writePages(pages, base) { name ->
                DocumentsContract.createDocument(context.contentResolver, treeUri, "image/png", name)
            }
        }

    /**
     * 逐页写入；[createDocument] 负责为每页创建目标 Uri（生产用 DocumentsContract，测试可注入）。
     * 中途失败时错误信息带上已写入张数。
     */
    internal fun writePages(
        pages: List<RenderedPage>,
        base: String,
        createDocument: (displayName: String) -> Uri?
    ): Result<Int> {
        if (pages.isEmpty()) return Result.failure(IllegalArgumentException("没有可写入的页面"))
        var done = 0
        try {
            pages.forEachIndexed { index, page ->
                val name = pageFileName(base, index, pages.size)
                val docUri = createDocument(name) ?: error("无法创建文件：$name")
                context.contentResolver.openOutputStream(docUri)?.use { out ->
                    compressPng(page, out)
                } ?: error("无法写入文件：$name")
                done++
            }
            return Result.success(done)
        } catch (e: Exception) {
            return Result.failure(IllegalStateException("已保存 $done 张，写入中断：${e.message}", e))
        }
    }

    /** 写入缓存目录并返回 FileProvider content Uri（分享用）。 */
    fun writeCacheFiles(pages: List<RenderedPage>, base: String): List<Uri> {
        clearCache()
        exportsDir.mkdirs()
        return pages.mapIndexed { index, page ->
            val file = File(exportsDir, pageFileName(base, index, pages.size))
            FileOutputStream(file).use { out -> compressPng(page, out) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
    }

    /** 单页 ACTION_SEND；多页 ACTION_SEND_MULTIPLE。 */
    fun buildShareIntent(uris: List<Uri>): Intent {
        check(uris.isNotEmpty()) { "没有可分享的图片" }
        return if (uris.size == 1) {
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
    }

    fun clearCache() {
        exportsDir.listFiles()?.forEach { it.delete() }
    }

    private fun compressPng(page: RenderedPage, out: OutputStream) {
        check(page.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG 编码失败" }
    }

    /** 文件名用公历/西文数字，避免非公历 Locale 产生 2569 之类的年份。 */
    private fun dateStamp(timestamp: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(timestamp))
}
