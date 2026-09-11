package com.mynote.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
class AiMessageDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun teardown() {
        db.close()
    }

    private suspend fun newSession(): Long {
        val noteId = db.noteDao().insert(NoteEntity(0, "t", "c", 1, 1, null, false, null))
        return db.aiSessionDao().insert(
            AiSessionEntity(
                noteId = noteId, serviceId = "deepseek", title = "会话",
                remoteChatId = null, createdAt = 1, updatedAt = 1
            )
        )
    }

    private fun message(sessionId: Long, role: String, content: String, createdAt: Long, status: String = AiMessageEntity.STATUS_DONE) =
        AiMessageEntity(sessionId = sessionId, role = role, content = content, status = status, createdAt = createdAt)

    @Test
    fun insertAndObserveOrderedByCreatedAtAsc() = runTest {
        val sessionId = newSession()
        db.aiMessageDao().insert(message(sessionId, AiMessageEntity.ROLE_USER, "q1", 100))
        db.aiMessageDao().insert(message(sessionId, AiMessageEntity.ROLE_ASSISTANT, "a1", 200))
        val list = db.aiMessageDao().observeBySession(sessionId).first()
        assertEquals(listOf("q1", "a1"), list.map { it.content })
    }

    @Test
    fun updateContentAndStatusForInterrupted() = runTest {
        val sessionId = newSession()
        val id = db.aiMessageDao().insert(
            message(sessionId, AiMessageEntity.ROLE_ASSISTANT, "半截", 100, AiMessageEntity.STATUS_FAILED)
        )
        db.aiMessageDao().updateContentAndStatus(id, "半截完整一点", AiMessageEntity.STATUS_INTERRUPTED)
        val row = db.aiMessageDao().getBySession(sessionId).single()
        assertEquals("半截完整一点", row.content)
        assertEquals(AiMessageEntity.STATUS_INTERRUPTED, row.status)
    }

    @Test
    fun equalCreatedAtBreaksTieByIdAsc() = runTest {
        val sessionId = newSession()
        db.aiMessageDao().insert(message(sessionId, AiMessageEntity.ROLE_USER, "m1", 100))
        db.aiMessageDao().insert(message(sessionId, AiMessageEntity.ROLE_ASSISTANT, "m2", 100))
        val list = db.aiMessageDao().observeBySession(sessionId).first()
        assertEquals(listOf("m1", "m2"), list.map { it.content })
    }

    @Test
    fun deletingSessionCascadesMessages() = runTest {
        val sessionId = newSession()
        db.aiMessageDao().insert(message(sessionId, AiMessageEntity.ROLE_USER, "q", 1))
        db.aiSessionDao().deleteById(sessionId)
        assertEquals(0, db.aiMessageDao().getBySession(sessionId).size)
    }
}
