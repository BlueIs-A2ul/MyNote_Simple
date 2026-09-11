package com.mynote.app.ui.notes

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.db.NoteRevisionDao
import com.mynote.app.data.image.ImageStore
import com.mynote.app.data.repository.NoteRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
        val warning = CompletableDeferred<String?>()
        vm.save("t", "v39", null, false, null) { warning.complete(it) }
        assertEquals(NoteEditViewModel.HISTORY_WARNING, warning.await())
        assertEquals(40, repo.countRevisions(id))
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
}
