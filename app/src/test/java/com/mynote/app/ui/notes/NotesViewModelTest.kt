package com.mynote.app.ui.notes

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.image.ImageStore
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.data.settings.NoteSortStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class NotesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var repo: NoteRepository
    private lateinit var vm: NotesViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = NoteRepository(db.noteDao(), db.categoryDao(), db.noteRevisionDao(), ImageStore(context), db)
        vm = NotesViewModel(repo, NoteSortStore(context))
    }

    @After
    fun teardown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun enterSelectionSelectsNoteAndEnablesMode() {
        vm.enterSelection(1L)
        assertTrue(vm.selectionMode.value)
        assertEquals(setOf(1L), vm.selectedIds.value)
    }

    @Test
    fun toggleSelectAddsAndRemoves() {
        vm.enterSelection(1L)
        vm.toggleSelect(2L)
        assertEquals(setOf(1L, 2L), vm.selectedIds.value)
        vm.toggleSelect(1L)
        assertEquals(setOf(2L), vm.selectedIds.value)
    }

    @Test
    fun selectAllSelectsVisibleNotes() = runTest(dispatcher) {
        val a = repo.saveNote(null, "a", "c", null, false, null)
        val b = repo.saveNote(null, "b", "c", null, false, null)
        vm.notes.first { it.isNotEmpty() }
        vm.selectAll()
        assertEquals(setOf(a, b), vm.selectedIds.value)
    }

    @Test
    fun exitSelectionClearsState() {
        vm.enterSelection(1L)
        vm.exitSelection()
        assertFalse(vm.selectionMode.value)
        assertTrue(vm.selectedIds.value.isEmpty())
    }

    @Test
    fun batchDeleteSoftDeletesSelectedNotesAndExits() = runTest(dispatcher) {
        val a = repo.saveNote(null, "a", "c", null, false, null)
        val b = repo.saveNote(null, "b", "c", null, false, null)
        vm.enterSelection(a)
        vm.toggleSelect(b)

        val done = CompletableDeferred<Int>()
        vm.batchDelete { done.complete(it) }
        assertEquals(2, done.await())

        assertNotNull(db.noteDao().getById(a)?.deletedAt)
        assertNotNull(db.noteDao().getById(b)?.deletedAt)
        assertFalse(vm.selectionMode.value)
        assertTrue(vm.selectedIds.value.isEmpty())
    }

    @Test
    fun batchSetCategoryMovesSelectedNotes() = runTest(dispatcher) {
        val a = repo.saveNote(null, "a", "c", null, false, null)
        val b = repo.saveNote(null, "b", "c", null, false, null)
        val catId = repo.addCategory("工作", 0)
        vm.enterSelection(a)
        vm.toggleSelect(b)

        val done = CompletableDeferred<Int>()
        vm.batchSetCategory(catId) { done.complete(it) }
        assertEquals(2, done.await())
        assertEquals(catId, db.noteDao().getById(a)?.categoryId)
        assertEquals(catId, db.noteDao().getById(b)?.categoryId)
    }

    @Test
    fun batchSetCategoryNullMovesToUncategorized() = runTest(dispatcher) {
        val catId = repo.addCategory("工作", 0)
        val a = repo.saveNote(null, "a", "c", catId, false, null)
        vm.enterSelection(a)

        val done = CompletableDeferred<Int>()
        vm.batchSetCategory(null) { done.complete(it) }
        assertEquals(1, done.await())
        assertNull(db.noteDao().getById(a)?.categoryId)
    }

    @Test
    fun batchSetPinnedUpdatesSelectedNotes() = runTest(dispatcher) {
        val a = repo.saveNote(null, "a", "c", null, false, null)
        val b = repo.saveNote(null, "b", "c", null, false, null)
        vm.enterSelection(a)
        vm.toggleSelect(b)

        val done = CompletableDeferred<Int>()
        vm.batchSetPinned(true) { done.complete(it) }
        assertEquals(2, done.await())
        assertTrue(db.noteDao().getById(a)?.pinned == true)
        assertTrue(db.noteDao().getById(b)?.pinned == true)
    }

    @Test
    fun batchOpWithEmptySelectionDoesNotCallback() = runTest(dispatcher) {
        var called = false
        vm.batchDelete { called = true }
        vm.batchSetPinned(true) { called = true }
        vm.batchSetCategory(null) { called = true }
        assertFalse(called)
    }
}
