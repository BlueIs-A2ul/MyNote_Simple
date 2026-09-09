package com.mynote.app.data.backup

import android.content.Context
import android.net.Uri
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
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupManager(
    private val context: Context,
    private val imageStore: ImageStore,
    private val database: AppDatabase? = null
) {

    @Serializable
    data class BackupNote(
        val id: Long,
        val title: String,
        val content: String,
        val createdAt: Long,
        val updatedAt: Long,
        val categoryId: Long?,
        val pinned: Boolean,
        val color: Int?
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

    fun NoteEntity.toBackup() = BackupNote(id, title, content, createdAt, updatedAt, categoryId, pinned, color)

    fun BackupNote.toEntity() = NoteEntity(id, title, content, createdAt, updatedAt, categoryId, pinned, color)

    fun CategoryEntity.toBackup() = BackupCategory(id, name, color)

    fun BackupCategory.toEntity() = CategoryEntity(id, name, color)

    /** 导出全量备份为 zip（notes.json + img/），返回笔记数。 */
    suspend fun exportZip(uri: Uri): Int = withContext(Dispatchers.IO) {
        val db = requireNotNull(database) { "导出需要数据库实例" }
        val notes = db.noteDao().getAll().map { it.toBackup() }
        val categories = db.categoryDao().getAll().map { it.toBackup() }
        val data = BackupData(notes, categories)
        val jsonText = encode(data)

        context.contentResolver.openOutputStream(uri)?.use { output ->
            ZipOutputStream(output).use { zip ->
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

    /** 导入 zip 备份，返回导入的笔记数。 */
    suspend fun importZip(uri: Uri): Int = withContext(Dispatchers.IO) {
        val db = requireNotNull(database) { "导入需要数据库实例" }

        var jsonText = ""
        val images = mutableMapOf<String, ByteArray>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    when {
                        entry.name == "notes.json" -> jsonText = zip.readBytes().toString(Charsets.UTF_8)
                        entry.name.startsWith("img/") ->
                            images[entry.name.removePrefix("img/")] = zip.readBytes()
                    }
                    entry = zip.nextEntry
                }
            }
        }

        val data = decode(jsonText)

        images.forEach { (name, bytes) -> imageStore.writeFile(name, bytes) }

        val noteDao = db.noteDao()
        val categoryDao = db.categoryDao()

        data.categories.forEach { cat ->
            val existing = categoryDao.getById(cat.id)
            if (existing == null) categoryDao.insert(cat.toEntity()) else categoryDao.update(cat.toEntity())
        }
        data.notes.forEach { note ->
            val existing = noteDao.getById(note.id)
            when {
                existing == null -> noteDao.insert(note.toEntity())
                incomingWins(existing.updatedAt, note.updatedAt) -> noteDao.update(note.toEntity())
                // 否则保留本地较新版本，跳过
            }
        }
        data.notes.size
    }

    /** 单条笔记导出为 txt（标记原样保留）。 */
    suspend fun exportNoteAsTxt(uri: Uri, note: NoteEntity): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(note.content.toByteArray(Charsets.UTF_8))
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
