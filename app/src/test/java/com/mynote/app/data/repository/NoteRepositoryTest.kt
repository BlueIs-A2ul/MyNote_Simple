package com.mynote.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.db.NoteEntity
import com.mynote.app.data.image.ImageStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    private lateinit var imageStore: ImageStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        imageStore = ImageStore(context)
        repo = NoteRepository(db.noteDao(), db.categoryDao(), db.noteRevisionDao(), imageStore, db)
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

    @Test
    fun newNoteCreatesFirstRevision() = runTest {
        val id = repo.saveNote(null, "t", "c", null, false, null)
        val revisions = db.noteRevisionDao().getByNote(id)
        assertEquals(1, revisions.size)
        assertEquals("c", revisions[0].content)
        assertEquals(id, revisions[0].noteId)
    }

    @Test
    fun saveWithoutChangesDoesNotAddRevision() = runTest {
        val id = repo.saveNote(null, "t", "c", null, false, null)
        repo.saveNote(id, "t", "c", null, false, null)
        assertEquals(1, repo.countRevisions(id))
    }

    @Test
    fun firstSaveOfLegacyNoteCreatesBaselineAndNewRevision() = runTest {
        val legacyId = db.noteDao().insert(NoteEntity(0, "old", "old content", 111, 222, null, false, null))
        repo.saveNote(legacyId, "new", "new content", null, false, null)
        val revisions = db.noteRevisionDao().getByNote(legacyId)
        assertEquals(2, revisions.size)
        assertEquals("new content", revisions[0].content)
        assertEquals("old content", revisions[1].content)
        assertEquals(222L, revisions[1].savedAt)
        assertTrue(revisions[0].savedAt > 222L)
    }

    @Test
    fun trimKeepsAtMost50RevisionsPerNote() = runTest {
        val id = repo.saveNote(null, "t", "v0", null, false, null)
        for (i in 1..55) repo.saveNote(id, "t", "v$i", null, false, null)
        assertEquals(50, repo.countRevisions(id))
    }

    @Test
    fun deleteNoteCascadesRevisions() = runTest {
        val id = repo.saveNote(null, "t", "c", null, false, null)
        repo.deleteNote(repo.getNote(id)!!)
        assertEquals(0, repo.countRevisions(id))
    }

    @Test
    fun restoreRevisionWritesBackAndAddsRevision() = runTest {
        val id = repo.saveNote(null, "t", "v1", null, false, null)
        repo.saveNote(id, "t", "v2", null, false, null)
        val firstRevision = db.noteRevisionDao().getByNote(id).last()
        assertTrue(repo.restoreRevision(id, firstRevision.id))
        assertEquals("v1", repo.getNote(id)?.content)
        assertEquals(3, repo.countRevisions(id))
    }

    @Test
    fun restoreRevisionWithDeletedCategoryFallsBackToNull() = runTest {
        val catId = repo.addCategory("工作", 0)
        val id = repo.saveNote(null, "t", "v1", catId, false, null)
        repo.saveNote(id, "t", "v2", catId, false, null)
        val firstRevision = db.noteRevisionDao().getByNote(id).last()
        repo.deleteCategory(db.categoryDao().getById(catId)!!)
        assertTrue(repo.restoreRevision(id, firstRevision.id))
        assertNull(repo.getNote(id)?.categoryId)
    }

    @Test
    fun restoreMissingRevisionReturnsFalse() = runTest {
        val id = repo.saveNote(null, "t", "c", null, false, null)
        assertEquals(false, repo.restoreRevision(id, 99999L))
    }

    @Test
    fun garbageCollectionKeepsImagesReferencedByHistory() = runTest {
        imageStore.writeFile("a.webp", byteArrayOf(1))
        val id = repo.saveNote(null, "t", "![](img/a.webp)", null, false, null)
        repo.saveNote(id, "t", "no image", null, false, null)
        // 通过删除另一篇笔记触发 GC（GC 只在删除/裁剪后执行）
        val other = repo.saveNote(null, "other", "x", null, false, null)
        repo.deleteNote(repo.getNote(other)!!)
        assertTrue(imageStore.physicalFile("a.webp").exists())
    }

    @Test
    fun garbageCollectionReclaimsImagesAfterTrim() = runTest {
        imageStore.writeFile("b.webp", byteArrayOf(1))
        val id = repo.saveNote(null, "t", "![](img/b.webp)", null, false, null)
        for (i in 1..50) repo.saveNote(id, "t", "v$i", null, false, null)
        assertEquals(false, imageStore.physicalFile("b.webp").exists())
    }
}
