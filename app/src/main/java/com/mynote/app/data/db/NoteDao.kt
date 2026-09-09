package com.mynote.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Query("SELECT * FROM notes ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observeById(id: Long): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') ORDER BY pinned DESC, updatedAt DESC")
    fun search(query: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE categoryId = :categoryId ORDER BY pinned DESC, updatedAt DESC")
    fun observeByCategory(categoryId: Long): Flow<List<NoteEntity>>

    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    @Delete
    suspend fun delete(note: NoteEntity)

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: Long): NoteEntity?

    @Query("SELECT * FROM notes")
    suspend fun getAll(): List<NoteEntity>

    @Query("UPDATE notes SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long)
}
