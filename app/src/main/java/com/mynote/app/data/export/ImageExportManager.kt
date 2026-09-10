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

    /** 清空并准备分享缓存目录（每次导出开始时调用）。 */
    fun prepareCache() {
        clearCache()
        exportsDir.mkdirs()
    }

    /** 缓存目录中的一页文件路径。 */
    fun cacheFile(base: String, index: Int, total: Int): File =
        File(exportsDir, pageFileName(base, index, total))

    /** 把一页写入缓存文件（调用方随后回收位图以控制峰值内存）。 */
    fun writePageFile(file: File, page: RenderedPage) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out -> compressPng(page, out) }
    }

    /** 缓存文件的 FileProvider 分享 Uri。 */
    fun cachePageUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** 把缓存中的一页复制到 SAF 文档（另存为，单页）。 */
    suspend fun copyPageToUri(uri: Uri, source: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { it.copyTo(out) }
                } ?: error("无法写入所选位置")
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** 把缓存中的多页复制到 SAF 目录，返回写入张数。 */
    suspend fun copyCacheToTree(treeUri: Uri, pages: List<File>): Result<Int> =
        withContext(Dispatchers.IO) {
            copyFiles(pages) { name ->
                DocumentsContract.createDocument(context.contentResolver, treeUri, "image/png", name)
            }
        }

    /**
     * 逐页复制；[createDocument] 负责创建目标 Uri（生产用 DocumentsContract，测试可注入）。
     * 中途失败时错误信息带上已写入张数。
     */
    internal fun copyFiles(
        files: List<File>,
        createDocument: (displayName: String) -> Uri?
    ): Result<Int> {
        if (files.isEmpty()) return Result.failure(IllegalArgumentException("没有可写入的页面"))
        var done = 0
        try {
            files.forEach { file ->
                val docUri = createDocument(file.name) ?: error("无法创建文件：${file.name}")
                context.contentResolver.openOutputStream(docUri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: error("无法写入文件：${file.name}")
                done++
            }
            return Result.success(done)
        } catch (e: Exception) {
            return Result.failure(IllegalStateException("已保存 $done 张，写入中断：${e.message}", e))
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
