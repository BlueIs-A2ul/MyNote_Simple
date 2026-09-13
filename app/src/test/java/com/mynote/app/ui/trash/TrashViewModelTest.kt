package com.mynote.app.ui.trash

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.image.ImageStore
import com.mynote.app.data.repository.NoteRepository
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrashViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var repo: NoteRepository
    private lateinit var vm: TrashViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = NoteRepository(db.noteDao(), db.categoryDao(), db.noteRevisionDao(), ImageStore(context), db)
        vm = TrashViewModel(repo)
    }

    @After
    fun teardown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun restoreRemovesNoteFromTrash() = runTest(dispatcher) {
        val id = repo.saveNote(null, "t", "c", null, false, null)
        repo.deleteNote(repo.getNote(id)!!)
        val inTrash = vm.notes.first { it.isNotEmpty() }

        val done = CompletableDeferred<Unit>()
        vm.restore(inTrash.first()) { done.complete(Unit) }
        done.await()

        assertNull(db.noteDao().getById(id)?.deletedAt)
        assertEquals(0, db.noteDao().getAll().filter { it.deletedAt != null }.size)
    }

    @Test
    fun purgeRemovesNotePermanently() = runTest(dispatcher) {
        val id = repo.saveNote(null, "t", "c", null, false, null)
        repo.deleteNote(repo.getNote(id)!!)
        val inTrash = vm.notes.first { it.isNotEmpty() }

        val done = CompletableDeferred<Unit>()
        vm.purge(inTrash.first()) { done.complete(Unit) }
        done.await()

        assertNull(db.noteDao().getById(id))
    }

    @Test
    fun purgeAllClearsTrash() = runTest(dispatcher) {
        val a = repo.saveNote(null, "a", "c", null, false, null)
        val b = repo.saveNote(null, "b", "c", null, false, null)
        repo.deleteNote(repo.getNote(a)!!)
        repo.deleteNote(repo.getNote(b)!!)
        vm.notes.first { it.size == 2 }

        val done = CompletableDeferred<Int>()
        vm.purgeAll { done.complete(it) }
        assertEquals(2, done.await())
        assertEquals(0, db.noteDao().getAll().size)
    }

    @Test
    fun initPurgesExpiredDeletedNotes() = runTest(dispatcher) {
        val expired = repo.saveNote(null, "expired", "c", null, false, null)
        val fresh = repo.saveNote(null, "fresh", "c", null, false, null)
        repo.deleteNote(repo.getNote(expired)!!)
        repo.deleteNote(repo.getNote(fresh)!!)
        val now = System.currentTimeMillis()
        db.noteDao().update(db.noteDao().getById(expired)!!.copy(deletedAt = now - 31L * 24 * 3600 * 1000))
        db.noteDao().update(db.noteDao().getById(fresh)!!.copy(deletedAt = now - 3600_000))

        // 新 VM 的 init 触发到期清理；等待真实 DB 流收敛到只剩 fresh
        vm.viewModelScope.cancel()
        vm = TrashViewModel(repo)
        vm.notes.first { it.size == 1 && it.first().title == "fresh" }

        assertNull(db.noteDao().getById(expired))
        assertNotNull(db.noteDao().getById(fresh))
    }
}
