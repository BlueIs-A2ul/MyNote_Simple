package com.mynote.app.data.repository

import com.mynote.app.data.db.CategoryDao
import com.mynote.app.data.db.CategoryEntity
import com.mynote.app.data.db.NoteDao
import com.mynote.app.data.db.NoteEntity
import com.mynote.app.data.image.ImageStore
import com.mynote.app.ui.notes.NoteContentParser
import kotlinx.coroutines.flow.Flow

class NoteRepository(
    private val noteDao: NoteDao,
    private val categoryDao: CategoryDao,
    private val imageStore: ImageStore
) {

    fun observeNotes(): Flow<List<NoteEntity>> = noteDao.observeAll()

    fun observeNote(id: Long): Flow<NoteEntity?> = noteDao.observeById(id)

    fun search(query: String): Flow<List<NoteEntity>> =
        if (query.isBlank()) noteDao.observeAll() else noteDao.search(query.trim())

    fun observeByCategory(categoryId: Long): Flow<List<NoteEntity>> = noteDao.observeByCategory(categoryId)

    fun observeCategories(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

    suspend fun getNote(id: Long): NoteEntity? = noteDao.getById(id)

    suspend fun saveNote(
        id: Long?,
        title: String,
        content: String,
        categoryId: Long?,
        pinned: Boolean,
        color: Int?
    ): Long {
        val now = System.currentTimeMillis()
        return if (id == null || id == 0L) {
            noteDao.insert(NoteEntity(0, title, content, now, now, categoryId, pinned, color))
        } else {
            val existing = noteDao.getById(id)
            noteDao.update(
                NoteEntity(
                    id = id,
                    title = title,
                    content = content,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                    categoryId = categoryId,
                    pinned = pinned,
                    color = color
                )
            )
            id
        }
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

    private suspend fun collectImageGarbage() {
        val referenced = noteDao.getAll()
            .flatMap { NoteContentParser.extractImageNames(it.content) }
            .toSet()
        imageStore.collectGarbage(referenced)
    }
}
