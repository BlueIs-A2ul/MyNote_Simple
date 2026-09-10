package com.mynote.app.data.repository

import androidx.room.withTransaction
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.db.CategoryDao
import com.mynote.app.data.db.CategoryEntity
import com.mynote.app.data.db.NoteDao
import com.mynote.app.data.db.NoteEntity
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

    fun observeNote(id: Long): Flow<NoteEntity?> = noteDao.observeById(id)

    fun search(query: String): Flow<List<NoteEntity>> =
        if (query.isBlank()) noteDao.observeAll() else noteDao.search(query.trim())

    fun observeByCategory(categoryId: Long): Flow<List<NoteEntity>> = noteDao.observeByCategory(categoryId)

    fun observeCategories(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

    fun observeRevisions(noteId: Long): Flow<List<NoteRevisionEntity>> = revisionDao.observeByNote(noteId)

    suspend fun getNote(id: Long): NoteEntity? = noteDao.getById(id)

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

    suspend fun deleteNote(note: NoteEntity) {
        noteDao.delete(note)
        collectImageGarbage()
    }

    suspend fun addCategory(name: String, color: Int): Long =
        categoryDao.getByName(name)?.id ?: categoryDao.insert(CategoryEntity(0, name, color))

    suspend fun renameCategory(category: CategoryEntity, newName: String) =
        categoryDao.update(category.copy(name = newName))

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
}
