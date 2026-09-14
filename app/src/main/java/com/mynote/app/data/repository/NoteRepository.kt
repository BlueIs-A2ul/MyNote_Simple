package com.mynote.app.data.repository

import androidx.room.withTransaction
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.db.CategoryDao
import com.mynote.app.data.db.CategoryEntity
import com.mynote.app.data.db.NoteDao
import com.mynote.app.data.db.NoteEntity
import com.mynote.app.data.db.NoteSortMode
import com.mynote.app.data.db.NoteRevisionDao
import com.mynote.app.data.db.NoteRevisionEntity
import com.mynote.app.data.image.ImageStore
import com.mynote.app.ui.notes.NoteContentParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class NoteRepository(
    private val noteDao: NoteDao,
    private val categoryDao: CategoryDao,
    private val revisionDao: NoteRevisionDao,
    private val imageStore: ImageStore,
    private val database: AppDatabase
) {

    fun observeNotes(): Flow<List<NoteEntity>> = noteDao.observeAll()

    fun observeNotes(sort: NoteSortMode): Flow<List<NoteEntity>> = noteDao.observeAllBySort(sort)

    fun observeNote(id: Long): Flow<NoteEntity?> = noteDao.observeById(id)

    fun search(query: String): Flow<List<NoteEntity>> =
        if (query.isBlank()) noteDao.observeAll() else noteDao.search(query.trim())

    fun observeByCategory(categoryId: Long): Flow<List<NoteEntity>> = noteDao.observeByCategory(categoryId)

    /** 未分类笔记（categoryId 为空）。 */
    fun observeUncategorized(): Flow<List<NoteEntity>> = noteDao.observeUncategorized()

    /** 回收站列表（已软删除的笔记）。 */
    fun observeDeletedNotes(): Flow<List<NoteEntity>> = noteDao.observeDeleted()

    fun observeCategories(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

    fun observeRevisions(noteId: Long): Flow<List<NoteRevisionEntity>> = revisionDao.observeByNote(noteId)

    suspend fun getNote(id: Long): NoteEntity? = noteDao.getById(id)

    suspend fun getNotesByIds(ids: List<Long>): List<NoteEntity> = noteDao.getByIds(ids)

    suspend fun countRevisions(noteId: Long): Int = revisionDao.countByNote(noteId)

    suspend fun saveNote(
        id: Long?,
        title: String,
        content: String,
        categoryId: Long?,
        pinned: Boolean,
        color: Int?
    ): Long {
        val now = System.currentTimeMillis()
        var trimmed = false
        val resultId = database.withTransaction {
            if (id == null || id == 0L) {
                val newId = noteDao.insert(NoteEntity(0, title, content, now, now, categoryId, pinned, color))
                revisionDao.insert(NoteRevisionEntity(0, newId, title, content, categoryId, pinned, color, now))
                newId
            } else {
                val existing = noteDao.getById(id)
                if (existing == null) {
                    id
                } else {
                    // 基线兜底：功能上线前的老笔记/备份导入的笔记首次保存时补一条老状态
                    if (revisionDao.countByNote(id) == 0) {
                        revisionDao.insert(existing.toRevision(existing.updatedAt))
                    }
                    val changed = existing.title != title || existing.content != content ||
                        existing.categoryId != categoryId || existing.pinned != pinned || existing.color != color
                    if (changed) {
                        noteDao.update(NoteEntity(id, title, content, existing.createdAt, now, categoryId, pinned, color))
                        revisionDao.insert(NoteRevisionEntity(0, id, title, content, categoryId, pinned, color, now))
                        trimmed = revisionDao.trimTo(id, NoteRevisionDao.MAX_PER_NOTE) > 0
                    }
                    id
                }
            }
        }
        if (trimmed) collectImageGarbage()
        return resultId
    }

    suspend fun restoreRevision(noteId: Long, revisionId: Long): Boolean {
        val revision = revisionDao.getById(revisionId) ?: return false
        if (revision.noteId != noteId) return false
        val categoryId = revision.categoryId?.takeIf { categoryDao.getById(it) != null }
        saveNote(noteId, revision.title, revision.content, categoryId, revision.pinned, revision.color)
        return true
    }

    /** 删除笔记 = 软删除（移入回收站）：保留历史快照与图片，30 天后自动清理。 */
    suspend fun deleteNote(note: NoteEntity) {
        noteDao.update(note.copy(deletedAt = System.currentTimeMillis()))
    }

    /** 从回收站恢复：清除软删除标记，分类/置顶/历史全部还原。 */
    suspend fun restoreNote(note: NoteEntity) {
        noteDao.update(note.copy(deletedAt = null))
    }

    /** 批量删除 = 批量软删除（进回收站），不触发图片 GC。 */
    suspend fun deleteNotes(notes: List<NoteEntity>) {
        if (notes.isEmpty()) return
        val now = System.currentTimeMillis()
        noteDao.updateAll(notes.map { it.copy(deletedAt = now) })
    }

    /** 批量移动分类（null = 未分类）。 */
    suspend fun moveNotesToCategory(notes: List<NoteEntity>, categoryId: Long?) {
        if (notes.isEmpty()) return
        noteDao.updateAll(notes.map { it.copy(categoryId = categoryId) })
    }

    /** 批量置顶/取消置顶。 */
    suspend fun setNotesPinned(notes: List<NoteEntity>, pinned: Boolean) {
        if (notes.isEmpty()) return
        noteDao.updateAll(notes.map { it.copy(pinned = pinned) })
    }

    /** 彻底删除：物理删除并级联清历史，再回收孤儿图片。 */
    suspend fun purgeNote(note: NoteEntity) {
        noteDao.delete(note)
        collectImageGarbage()
    }

    /** 清理回收站中超过 ttlMs 的笔记；返回清理条数。 */
    suspend fun purgeExpiredDeletedNotes(
        now: Long = System.currentTimeMillis(),
        ttlMs: Long = TRASH_TTL_MS
    ): Int {
        val expired = noteDao.getAll().filter { note ->
            val deletedAt = note.deletedAt ?: return@filter false
            now - deletedAt > ttlMs
        }
        if (expired.isNotEmpty()) {
            expired.forEach { noteDao.delete(it) }
            collectImageGarbage()
        }
        return expired.size
    }

    suspend fun addCategory(name: String, color: Int): Long =
        categoryDao.getByName(name)?.id ?: categoryDao.insert(CategoryEntity(0, name, color))

    /** 重命名分类；撞名返回 false 且不更新，重命名为自身原名视为成功。 */
    suspend fun renameCategory(category: CategoryEntity, newName: String): Boolean {
        val trimmed = newName.trim()
        val existing = categoryDao.getByName(trimmed)
        return if (existing != null && existing.id != category.id) {
            false
        } else {
            categoryDao.update(category.copy(name = trimmed))
            true
        }
    }

    suspend fun deleteCategory(category: CategoryEntity) {
        noteDao.clearCategory(category.id)
        categoryDao.delete(category)
    }

    private fun NoteEntity.toRevision(savedAt: Long): NoteRevisionEntity =
        NoteRevisionEntity(0, id, title, content, categoryId, pinned, color, savedAt)

    private suspend fun collectImageGarbage() = withContext(Dispatchers.IO) {
        val referenced = noteDao.getAll()
            .flatMap { NoteContentParser.extractImageNames(it.content) }
            .toMutableSet()
        referenced += revisionDao.getContentsWithImageMarkup()
            .flatMap { NoteContentParser.extractImageNames(it) }
        imageStore.collectGarbage(referenced)
    }

    companion object {
        /** 回收站保留时长：30 天。 */
        const val TRASH_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
