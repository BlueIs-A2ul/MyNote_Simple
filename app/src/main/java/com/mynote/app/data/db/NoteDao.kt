package com.mynote.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 笔记列表排序方式。 */
enum class NoteSortMode { UPDATED_DESC, CREATED_DESC, TITLE_ASC }

@Dao
interface NoteDao {

    @Query("SELECT * FROM notes WHERE deletedAt IS NULL ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    /** 按指定排序方式观察全部笔记。 */
    fun observeAllBySort(mode: NoteSortMode): Flow<List<NoteEntity>> = when (mode) {
        NoteSortMode.UPDATED_DESC -> observeAll()
        NoteSortMode.CREATED_DESC -> observeAllByCreated()
        NoteSortMode.TITLE_ASC -> observeAllByTitle()
    }

    @Query("SELECT * FROM notes WHERE deletedAt IS NULL ORDER BY pinned DESC, createdAt DESC")
    fun observeAllByCreated(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE deletedAt IS NULL ORDER BY pinned DESC, title COLLATE NOCASE ASC")
    fun observeAllByTitle(): Flow<List<NoteEntity>>

    /** 回收站列表：已软删除的笔记，按删除时间倒序。 */
    @Query("SELECT * FROM notes WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeDeleted(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observeById(id: Long): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE deletedAt IS NULL AND (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') ORDER BY pinned DESC, updatedAt DESC")
    fun search(query: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE deletedAt IS NULL AND categoryId = :categoryId ORDER BY pinned DESC, updatedAt DESC")
    fun observeByCategory(categoryId: Long): Flow<List<NoteEntity>>

    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    @Delete
    suspend fun delete(note: NoteEntity)

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: Long): NoteEntity?

    /** 按 id 集合取笔记（无 deletedAt 过滤，批量操作只对可见笔记调用）。 */
    @Query("SELECT * FROM notes WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<NoteEntity>

    @Update
    suspend fun updateAll(notes: List<NoteEntity>)

    @Query("SELECT * FROM notes")
    suspend fun getAll(): List<NoteEntity>

    @Query("UPDATE notes SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long)
}
