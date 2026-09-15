package com.mynote.app.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.db.CategoryEntity
import com.mynote.app.data.db.NoteEntity
import com.mynote.app.data.image.ImageStore
import com.mynote.app.ui.notes.NoteContentParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 备份文件格式错误（非 zip / 缺少 notes.json / 条目名非法）。 */
class BackupFormatException(message: String) : Exception(message)

/** 导入结果计数（条目 44）：回收站条目单独计数，不计入新增/更新。 */
data class BackupImportResult(
    val inserted: Int,
    val updated: Int,
    val skipped: Int,
    val trashed: Int
)

class BackupManager(
    private val context: Context,
    private val imageStore: ImageStore,
    private val database: AppDatabase? = null,
    private val openOutput: (Uri) -> OutputStream? = { context.contentResolver.openOutputStream(it) },
    private val openInput: (Uri) -> InputStream? = { context.contentResolver.openInputStream(it) }
) {

    companion object {
        /** 格式错误统一提示文案。 */
        private const val FORMAT_ERROR = "不是有效的备份文件"

        /** zip 内图片条目名校验（剥离 img/ 前缀后）：仅允许字母数字与 . _ -，防路径穿越。 */
        private val IMAGE_NAME_REGEX = Regex("^[A-Za-z0-9._-]+$")
    }

    @Serializable
    data class BackupNote(
        val id: Long,
        val title: String,
        val content: String,
        val createdAt: Long,
        val updatedAt: Long,
        val categoryId: Long?,
        val pinned: Boolean,
        val color: Int?,
        /** 软删除时间戳；默认 null 保证旧备份 JSON 缺失该字段时仍可解码。 */
        val deletedAt: Long? = null,
        /** 归属日期（本地零点毫秒）；默认 null 保证旧备份 JSON 缺失该字段时仍可解码。 */
        val noteDate: Long? = null
    )

    @Serializable
    data class BackupCategory(val id: Long, val name: String, val color: Int)

    @Serializable
    data class BackupData(
        val notes: List<BackupNote>,
        val categories: List<BackupCategory>
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(data: BackupData): String = json.encodeToString(data)

    fun decode(text: String): BackupData = json.decodeFromString(text)

    /** 冲突时保留更新者：本地不存在或备份较新时返回 true（修订记录第 4 条）。 */
    fun incomingWins(localUpdatedAt: Long?, incomingUpdatedAt: Long): Boolean =
        localUpdatedAt == null || incomingUpdatedAt > localUpdatedAt

    fun NoteEntity.toBackup() =
        BackupNote(id, title, content, createdAt, updatedAt, categoryId, pinned, color, deletedAt, noteDate)

    fun BackupNote.toEntity() =
        NoteEntity(id, title, content, createdAt, updatedAt, categoryId, pinned, color, deletedAt, noteDate)

    fun CategoryEntity.toBackup() = BackupCategory(id, name, color)

    fun BackupCategory.toEntity() = CategoryEntity(id, name, color)

    /** 导出全量备份为 zip（notes.json + img/），返回笔记数；回收站中的笔记不导出。 */
    suspend fun exportZip(uri: Uri): Int = withContext(Dispatchers.IO) {
        val db = requireNotNull(database) { "导出需要数据库实例" }
        val notes = db.noteDao().getAll().filter { it.deletedAt == null }.map { it.toBackup() }
        val categories = db.categoryDao().getAll().map { it.toBackup() }
        val data = BackupData(notes, categories)
        val jsonText = encode(data)

        // 条目 17：拿不到输出流说明所选文件不可写，必须抛错而非「假成功」
        val output = openOutput(uri) ?: error("无法写入所选文件")
        output.use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("notes.json"))
                zip.write(jsonText.toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                val refs = notes.flatMap { NoteContentParser.extractImageNames(it.content) }.toSet()
                refs.forEach { name ->
                    val file = imageStore.physicalFile(name)
                    if (file.exists()) {
                        zip.putNextEntry(ZipEntry("img/$name"))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        }
        notes.size
    }

    /**
     * 导入 zip 备份（条目 43/44）：条目名校验 + 图片流式落盘 + 分类/笔记单事务写入。
     * 格式问题抛 [BackupFormatException]；读取 IO 异常原样上抛。返回四类计数。
     */
    suspend fun importZip(uri: Uri): BackupImportResult = withContext(Dispatchers.IO) {
        val db = requireNotNull(database) { "导入需要数据库实例" }

        var jsonText: String? = null
        try {
            openInput(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        when {
                            entry.isDirectory -> Unit
                            entry.name == "notes.json" ->
                                jsonText = zip.readBytes().toString(Charsets.UTF_8)
                            entry.name.startsWith("img/") -> {
                                val name = entry.name.removePrefix("img/")
                                // 先校验再落盘：非法条目不得触碰文件系统
                                if (!IMAGE_NAME_REGEX.matches(name)) throw BackupFormatException(FORMAT_ERROR)
                                imageStore.writeFile(name, zip)
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
        } catch (e: ZipException) {
            // 非 zip 或损坏的压缩包统一视为格式错误
            throw BackupFormatException(FORMAT_ERROR)
        }
        val data = decode(jsonText ?: throw BackupFormatException(FORMAT_ERROR))

        val noteDao = db.noteDao()
        val categoryDao = db.categoryDao()
        var inserted = 0
        var updated = 0
        var skipped = 0
        var trashed = 0

        db.withTransaction {
            // 分类 id 重映射：备份 id → 库内真实 id
            val idRemap = mutableMapOf<Long, Long>()
            data.categories.forEach { cat ->
                val byId = categoryDao.getById(cat.id)
                if (byId != null) {
                    // 同 id 视为同一分类：备份的名称与颜色覆盖本地
                    categoryDao.update(cat.toEntity())
                    idRemap[cat.id] = cat.id
                } else {
                    val byName = categoryDao.getByName(cat.name)
                    if (byName != null) {
                        // 名称唯一索引冲突：复用本地分类 id，颜色以备份为准
                        categoryDao.update(CategoryEntity(byName.id, cat.name, cat.color))
                        idRemap[cat.id] = byName.id
                    } else {
                        categoryDao.insert(cat.toEntity())
                        idRemap[cat.id] = cat.id
                    }
                }
            }
            val knownDbCategoryIds = categoryDao.getAll().map { it.id }.toSet()
            data.notes.forEach { note ->
                // 备份分类 id 经重映射；映射不到且库中也不存在的野 id 置 null
                val categoryId = note.categoryId?.let { cid ->
                    idRemap[cid] ?: cid.takeIf { it in knownDbCategoryIds }
                }
                val entity = note.toEntity().copy(categoryId = categoryId)
                val existing = noteDao.getById(note.id)
                val inTrash = note.deletedAt != null
                when {
                    existing == null -> {
                        noteDao.insert(entity)
                        if (inTrash) trashed++ else inserted++
                    }
                    incomingWins(existing.updatedAt, note.updatedAt) -> {
                        noteDao.update(entity)
                        if (inTrash) trashed++ else updated++
                    }
                    // 否则保留本地较新版本，跳过
                    else -> skipped++
                }
            }
        }
        BackupImportResult(inserted, updated, skipped, trashed)
    }

    /** 单条笔记导出为 txt（标记原样保留）：标题非空时写「标题 + 空行 + 正文」，否则仅正文。 */
    suspend fun exportNoteAsTxt(uri: Uri, title: String, content: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val output = openOutput(uri) ?: return@withContext false
                output.use { out ->
                    if (title.isNotBlank()) {
                        out.write(title.toByteArray(Charsets.UTF_8))
                        out.write("\n\n".toByteArray(Charsets.UTF_8))
                    }
                    out.write(content.toByteArray(Charsets.UTF_8))
                }
                true
            } catch (e: Exception) {
                false
            }
        }
}
