package com.mynote.app.ui.notes

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.image.ImageStore
import com.mynote.app.data.repository.NoteRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteEditViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var repo: NoteRepository
    private lateinit var vm: NoteEditViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = NoteRepository(db.noteDao(), db.categoryDao(), db.noteRevisionDao(), ImageStore(context), db)
        vm = NoteEditViewModel(repo, ImageStore(context), noteId = null)
    }

    @After
    fun teardown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun addCategoryCreatesCategoryAndReturnsId() = runTest(dispatcher) {
        val createdId = CompletableDeferred<Long>()
        vm.addCategory("工作") { createdId.complete(it) }
        val id = createdId.await()
        assertEquals("工作", db.categoryDao().getById(id)?.name)
    }

    @Test
    fun addCategoryBlankNameDoesNothing() = runTest(dispatcher) {
        var called = false
        vm.addCategory("   ") { called = true }
        assertFalse(called)
        assertEquals(0, db.categoryDao().getAll().size)
    }

    @Test
    fun addCategoryExistingNameReturnsExistingId() = runTest(dispatcher) {
        val existing = repo.addCategory("工作", 0)
        val createdId = CompletableDeferred<Long>()
        vm.addCategory("工作") { createdId.complete(it) }
        assertEquals(existing, createdId.await())
        assertEquals(1, db.categoryDao().getAll().size)
    }

    @Test
    fun addCategoryTrimsName() = runTest(dispatcher) {
        val createdId = CompletableDeferred<Long>()
        vm.addCategory("  工作  ") { createdId.complete(it) }
        assertEquals("工作", db.categoryDao().getById(createdId.await())?.name)
    }

    @Test
    fun saveAt40RevisionsReportsWarningOnce() = runTest(dispatcher) {
        val id = repo.saveNote(null, "t", "v0", null, false, null)
        for (i in 1..38) repo.saveNote(id, "t", "v$i", null, false, null)
        assertEquals(39, repo.countRevisions(id))

        vm.viewModelScope.cancel()
        vm = NoteEditViewModel(repo, ImageStore(ApplicationProvider.getApplicationContext()), noteId = id)

        val crossing = CompletableDeferred<String?>()
        vm.save("t", "v39", null, false, null) { crossing.complete(it) }
        assertEquals(NoteEditViewModel.HISTORY_WARNING, crossing.await())
        assertEquals(40, repo.countRevisions(id))

        val noop = CompletableDeferred<String?>()
        vm.save("t", "v39", null, false, null) { noop.complete(it) }
        assertEquals(null, noop.await())

        val beyond = CompletableDeferred<String?>()
        vm.save("t", "v40", null, false, null) { beyond.complete(it) }
        assertEquals(null, beyond.await())
        assertEquals(41, repo.countRevisions(id))
    }

    @Test
    fun saveBelowWarningThresholdDoesNotReport() = runTest(dispatcher) {
        val id = repo.saveNote(null, "t", "v0", null, false, null)
        for (i in 1..36) repo.saveNote(id, "t", "v$i", null, false, null)
        assertEquals(37, repo.countRevisions(id))

        vm.viewModelScope.cancel()
        vm = NoteEditViewModel(repo, ImageStore(ApplicationProvider.getApplicationContext()), noteId = id)
        val warning = CompletableDeferred<String?>()
        vm.save("t", "v38", null, false, null) { warning.complete(it) }
        assertEquals(null, warning.await())
    }

    @Test
    fun doubleSaveCreatesOnlyOneNoteAndReportsOnce() = runTest(dispatcher) {
        var doneCount = 0
        val first = CompletableDeferred<Unit>()
        vm.save("t", "c", null, false, null) { doneCount++; first.complete(Unit) }
        vm.save("t", "c", null, false, null) { doneCount++ }
        first.await()
        assertEquals(1, db.noteDao().getAll().size)
        assertEquals(1, doneCount)
    }

    @Test
    fun saveDraftExistingNoteUpdatesWithoutAddingRevision() = runTest(dispatcher) {
        val id = repo.saveNote(null, "t", "c", null, false, null)
        vm.viewModelScope.cancel()
        vm = NoteEditViewModel(repo, ImageStore(ApplicationProvider.getApplicationContext()), noteId = id)
        vm.note.first { it != null }

        vm.saveDraft("t2", "c2", null, true, null)

        val updated = vm.note.first { it?.title == "t2" }!!
        assertEquals("c2", updated.content)
        assertTrue(updated.pinned)
        assertEquals(1, repo.countRevisions(id))
    }

    @Test
    fun saveDraftBlankNewNoteDoesNotInsert() = runTest(dispatcher) {
        vm.saveDraft("   ", "", null, true, null)
        advanceUntilIdle()
        assertNull(vm.draftId.value)

        // 用一次正式保存同步数据库：空白草稿若误插入，这次断言会看到两条
        val done = CompletableDeferred<Unit>()
        vm.save("t", "c", null, false, null) { done.complete(Unit) }
        done.await()
        assertEquals(1, db.noteDao().getAll().size)
        assertEquals("t", db.noteDao().getAll().single().title)
    }

    @Test
    fun saveDraftNewNonBlankInsertsAndExposesDraftId() = runTest(dispatcher) {
        vm.saveDraft("标题", "正文", null, false, null)

        val id = vm.draftId.first { it != null }!!
        assertEquals("标题", db.noteDao().getById(id)?.title)
        assertEquals(1, db.noteDao().getAll().size)
        // 静默入库走 saveNote：新笔记的首次快照照常写入
        assertEquals(1, repo.countRevisions(id))
    }

    @Test
    fun saveAfterSilentDraftUpdatesSameNoteWithoutDuplicateInsert() = runTest(dispatcher) {
        vm.saveDraft("标题", "正文", null, false, null)
        val id = vm.draftId.first { it != null }!!

        val done = CompletableDeferred<Unit>()
        vm.save("标题", "改后正文", null, false, null) { done.complete(Unit) }
        done.await()

        assertEquals(1, db.noteDao().getAll().size)
        assertEquals("改后正文", db.noteDao().getById(id)?.content)
        assertEquals(2, repo.countRevisions(id))
    }

    @Test
    fun doubleDeleteReportsOnce() = runTest(dispatcher) {
        val id = repo.saveNote(null, "t", "c", null, false, null)
        vm.viewModelScope.cancel()
        vm = NoteEditViewModel(repo, ImageStore(ApplicationProvider.getApplicationContext()), noteId = id)
        vm.note.first { it != null }

        var doneCount = 0
        val first = CompletableDeferred<Unit>()
        vm.delete { doneCount++; first.complete(Unit) }
        vm.delete { doneCount++ }
        first.await()
        assertEquals(1, doneCount)
        // 删除 = 软删除：仍在库中但带删除标记
        assertEquals(1, db.noteDao().getAll().size)
        assertNotNull(db.noteDao().getById(id)?.deletedAt)
    }
}
