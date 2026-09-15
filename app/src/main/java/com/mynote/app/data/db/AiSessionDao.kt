package com.mynote.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiSessionDao {

    @Query("SELECT * FROM ai_sessions WHERE noteId = :noteId ORDER BY updatedAt DESC, id DESC")
    fun observeByNote(noteId: Long): Flow<List<AiSessionEntity>>

    @Query("SELECT * FROM ai_sessions WHERE id = :id")
    suspend fun getById(id: Long): AiSessionEntity?

    @Insert
    suspend fun insert(session: AiSessionEntity): Long

    @Query("UPDATE ai_sessions SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    @Query("DELETE FROM ai_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)
}
