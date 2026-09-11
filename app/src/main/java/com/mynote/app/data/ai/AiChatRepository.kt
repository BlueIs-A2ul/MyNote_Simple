package com.mynote.app.data.ai

import com.mynote.app.data.db.AiMessageDao
import com.mynote.app.data.db.AiMessageEntity
import com.mynote.app.data.db.AiSessionDao
import com.mynote.app.data.db.AiSessionEntity
import kotlinx.coroutines.flow.Flow

class AiChatRepository(
    private val sessionDao: AiSessionDao,
    private val messageDao: AiMessageDao
) {

    fun observeSessions(noteId: Long): Flow<List<AiSessionEntity>> = sessionDao.observeByNote(noteId)

    fun observeMessages(sessionId: Long): Flow<List<AiMessageEntity>> = messageDao.observeBySession(sessionId)

    suspend fun getSession(id: Long): AiSessionEntity? = sessionDao.getById(id)

    suspend fun createSession(noteId: Long, serviceId: String, title: String, now: Long): Long =
        sessionDao.insert(AiSessionEntity(0, noteId, serviceId, title, null, now, now))

    suspend fun updateRemoteChatId(sessionId: Long, remoteChatId: String) =
        sessionDao.updateRemoteChatId(sessionId, remoteChatId)

    suspend fun touch(sessionId: Long, now: Long) = sessionDao.touch(sessionId, now)

    suspend fun appendMessage(
        sessionId: Long,
        role: String,
        content: String,
        status: String,
        now: Long
    ): Long = messageDao.insert(AiMessageEntity(0, sessionId, role, content, status, now))

    suspend fun deleteSession(sessionId: Long) = sessionDao.deleteById(sessionId)

    suspend fun getMessages(sessionId: Long): List<AiMessageEntity> = messageDao.getBySession(sessionId)
}
