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
class AiSessionDaoTest {

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

    private fun session(noteId: Long, title: String = "会话", updatedAt: Long = 1) =
        AiSessionEntity(
            noteId = noteId, serviceId = "deepseek", title = title,
            remoteChatId = null, createdAt = 1, updatedAt = updatedAt
        )

    @Test
    fun insertAndObserveOrderedByUpdatedAtDesc() = runTest {
        val noteId = db.noteDao().insert(NoteEntity(0, "t", "c", 1, 1, null, false, null))
        db.aiSessionDao().insert(session(noteId, "old", 100))
        db.aiSessionDao().insert(session(noteId, "new", 200))
        val list = db.aiSessionDao().observeByNote(noteId).first()
        assertEquals(listOf("new", "old"), list.map { it.title })
    }

    @Test
    fun observeByNoteOnlyReturnsThatNote() = runTest {
        val a = db.noteDao().insert(NoteEntity(0, "a", "", 1, 1, null, false, null))
        val b = db.noteDao().insert(NoteEntity(0, "b", "", 1, 1, null, false, null))
        db.aiSessionDao().insert(session(a, "a1"))
        db.aiSessionDao().insert(session(b, "b1"))
        assertEquals(listOf("a1"), db.aiSessionDao().observeByNote(a).first().map { it.title })
    }

    @Test
    fun updateRemoteChatIdAndTouch() = runTest {
        val noteId = db.noteDao().insert(NoteEntity(0, "t", "c", 1, 1, null, false, null))
        val id = db.aiSessionDao().insert(session(noteId))
        db.aiSessionDao().updateRemoteChatId(id, "abc-123")
        db.aiSessionDao().touch(id, 999)
        val row = db.aiSessionDao().getById(id)!!
        assertEquals("abc-123", row.remoteChatId)
        assertEquals(999, row.updatedAt)
    }

    @Test
    fun deletingNoteCascadesSessions() = runTest {
        val noteId = db.noteDao().insert(NoteEntity(0, "t", "c", 1, 1, null, false, null))
        db.aiSessionDao().insert(session(noteId))
        db.noteDao().delete(db.noteDao().getById(noteId)!!)
        assertEquals(0, db.aiSessionDao().observeByNote(noteId).first().size)
    }
}
