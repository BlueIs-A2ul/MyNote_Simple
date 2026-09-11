package com.mynote.app.data.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.db.AiMessageEntity
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.db.NoteEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiChatRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AiChatRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = AiChatRepository(db.aiSessionDao(), db.aiMessageDao())
    }

    @After
    fun teardown() {
        db.close()
    }

    private suspend fun note(): Long =
        db.noteDao().insert(NoteEntity(0, "t", "c", 1, 1, null, false, null))

    @Test
    fun createSessionAppendMessagesAndObserve() = runTest {
        val noteId = note()
        val sessionId = repo.createSession(noteId, "deepseek", "帮我总结", 100)
        repo.appendMessage(sessionId, AiMessageEntity.ROLE_USER, "帮我总结", AiMessageEntity.STATUS_DONE, 100)
        repo.appendMessage(sessionId, AiMessageEntity.ROLE_ASSISTANT, "好的", AiMessageEntity.STATUS_DONE, 200)

        val session = repo.observeSessions(noteId).first().single()
        assertEquals("deepseek", session.serviceId)
        assertEquals(null, session.remoteChatId)
        assertEquals(
            listOf("帮我总结", "好的"),
            repo.observeMessages(sessionId).first().map { it.content }
        )
    }

    @Test
    fun remoteChatIdAndTouchUpdateSession() = runTest {
        val noteId = note()
        val sessionId = repo.createSession(noteId, "deepseek", "t", 100)
        repo.updateRemoteChatId(sessionId, "chat-1")
        repo.touch(sessionId, 300)
        val session = repo.getSession(sessionId)!!
        assertEquals("chat-1", session.remoteChatId)
        assertEquals(300, session.updatedAt)
    }

    @Test
    fun touchReordersSessions() = runTest {
        val noteId = note()
        val first = repo.createSession(noteId, "deepseek", "first", 100)
        repo.createSession(noteId, "deepseek", "second", 200)
        repo.touch(first, 300)
        assertEquals(
            listOf("first", "second"),
            repo.observeSessions(noteId).first().map { it.title }
        )
    }

    @Test
    fun deleteSessionCascadesMessages() = runTest {
        val noteId = note()
        val sessionId = repo.createSession(noteId, "deepseek", "t", 100)
        repo.appendMessage(sessionId, AiMessageEntity.ROLE_USER, "q", AiMessageEntity.STATUS_DONE, 100)
        repo.deleteSession(sessionId)
        assertEquals(0, repo.observeSessions(noteId).first().size)
        assertEquals(0, repo.getMessages(sessionId).size)
    }
}
