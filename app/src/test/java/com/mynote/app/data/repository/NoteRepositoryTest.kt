package com.mynote.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.image.ImageStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: NoteRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = NoteRepository(db.noteDao(), db.categoryDao(), ImageStore(context))
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun saveNewNoteInsertsAndReturnsId() = runTest {
        val id = repo.saveNote(null, "t", "c", null, false, null)
        assertNotNull(id)
        assertEquals("t", repo.getNote(id)?.title)
    }

    @Test
    fun saveExistingNoteUpdatesContentAndKeepsCreatedAt() = runTest {
        val id = repo.saveNote(null, "t", "v1", null, false, null)
        val created = repo.getNote(id)!!.createdAt
        repo.saveNote(id, "t2", "v2", null, false, null)
        val updated = repo.getNote(id)!!
        assertEquals("v2", updated.content)
        assertEquals(created, updated.createdAt)
    }

    @Test
    fun deleteCategoryMovesNotesToUncategorized() = runTest {
        val catId = repo.addCategory("工作", 0)
        val noteId = repo.saveNote(null, "n", "c", catId, false, null)
        repo.deleteCategory(db.categoryDao().getById(catId)!!)
        assertNull(repo.getNote(noteId)?.categoryId)
    }

    @Test
    fun searchBlankReturnsAll() = runTest {
        repo.saveNote(null, "a", "x", null, false, null)
        repo.saveNote(null, "b", "y", null, false, null)
        assertEquals(2, repo.search("   ").first().size)
    }

    @Test
    fun saveExistingNoteAppliesPinToggle() = runTest {
        val id = repo.saveNote(null, "t", "c", null, true, null)
        repo.saveNote(id, "t", "c", null, false, null)
        assertEquals(false, repo.getNote(id)?.pinned)
    }

    @Test
    fun addCategoryIsIdempotentForSameName() = runTest {
        val first = repo.addCategory("工作", 0)
        val second = repo.addCategory("工作", 1)
        assertEquals(first, second)
        assertEquals(1, db.categoryDao().getAll().size)
    }
}
