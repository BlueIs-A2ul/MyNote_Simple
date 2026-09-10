package com.mynote.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteRevisionDaoTest {

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

    private fun revision(noteId: Long, savedAt: Long, content: String = "c") =
        NoteRevisionEntity(
            noteId = noteId, title = "t", content = content,
            categoryId = null, pinned = false, color = null, savedAt = savedAt
        )

    @Test
    fun insertAndObserveOrderedBySavedAtDesc() = runTest {
        val noteId = db.noteDao().insert(NoteEntity(0, "t", "c", 1, 1, null, false, null))
        db.noteRevisionDao().insert(revision(noteId, 100, "old"))
        db.noteRevisionDao().insert(revision(noteId, 200, "new"))
        val list = db.noteRevisionDao().observeByNote(noteId).first()
        assertEquals(listOf("new", "old"), list.map { it.content })
    }

    @Test
    fun countByNoteCountsOnlyThatNote() = runTest {
        val a = db.noteDao().insert(NoteEntity(0, "a", "", 1, 1, null, false, null))
        val b = db.noteDao().insert(NoteEntity(0, "b", "", 1, 1, null, false, null))
        db.noteRevisionDao().insert(revision(a, 1))
        db.noteRevisionDao().insert(revision(a, 2))
        db.noteRevisionDao().insert(revision(b, 3))
        assertEquals(2, db.noteRevisionDao().countByNote(a))
    }

    @Test
    fun trimToKeepsNewestAndReturnsDeletedCount() = runTest {
        val noteId = db.noteDao().insert(NoteEntity(0, "t", "", 1, 1, null, false, null))
        for (i in 1..55) db.noteRevisionDao().insert(revision(noteId, i.toLong(), "v$i"))
        val deleted = db.noteRevisionDao().trimTo(noteId, 50)
        assertEquals(5, deleted)
        val remain = db.noteRevisionDao().getByNote(noteId)
        assertEquals(50, remain.size)
        assertEquals("v55", remain.first().content)
        assertEquals("v6", remain.last().content)
    }

    @Test
    fun deletingNoteCascadesRevisions() = runTest {
        val noteId = db.noteDao().insert(NoteEntity(0, "t", "c", 1, 1, null, false, null))
        db.noteRevisionDao().insert(revision(noteId, 1))
        db.noteDao().delete(db.noteDao().getById(noteId)!!)
        assertEquals(0, db.noteRevisionDao().countByNote(noteId))
    }

    @Test
    fun getAllContentsReturnsEverySnapshot() = runTest {
        val noteId = db.noteDao().insert(NoteEntity(0, "t", "", 1, 1, null, false, null))
        db.noteRevisionDao().insert(revision(noteId, 1, "a"))
        db.noteRevisionDao().insert(revision(noteId, 2, "b"))
        assertTrue(db.noteRevisionDao().getAllContents().containsAll(listOf("a", "b")))
    }
}
