package com.mynote.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiMessageDao {

    @Query("SELECT * FROM ai_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    fun observeBySession(sessionId: Long): Flow<List<AiMessageEntity>>

    @Query("SELECT * FROM ai_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun getBySession(sessionId: Long): List<AiMessageEntity>

    @Insert
    suspend fun insert(message: AiMessageEntity): Long

    @Query("UPDATE ai_messages SET content = :content, status = :status WHERE id = :id")
    suspend fun updateContentAndStatus(id: Long, content: String, status: String)
}
