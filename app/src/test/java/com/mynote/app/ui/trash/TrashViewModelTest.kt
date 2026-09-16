package com.mynote.app.ui.trash

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.image.ImageStore
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.data.settings.TrashRetentionStore
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
        vm = TrashViewModel(repo, TrashRetentionStore(context))
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
        vm.restore(inTrash.first(), onDone = { done.complete(Unit) }, onFailed = { done.complete(Unit) })
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
        vm.purge(inTrash.first(), onDone = { done.complete(Unit) }, onFailed = { done.complete(Unit) })
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
        vm.purgeAll(onDone = { done.complete(it) }, onFailed = { done.complete(-1) })
        assertEquals(2, done.await())
        assertEquals(0, db.noteDao().getAll().size)
    }

    /** 条目 76：回收站为空时「清空」不得抛异常（旧实现的 IN () 会直接崩）。 */
    @Test
    fun purgeAllOnEmptyTrashReportsZero() = runTest(dispatcher) {
        val done = CompletableDeferred<Int>()
        vm.purgeAll(onDone = { done.complete(it) }, onFailed = { done.complete(-1) })
        assertEquals(0, done.await())
    }

    /** 条目 76：DB 失败走 onFailed 而不是让异常逃出 viewModelScope 崩溃。 */
    @Test
    fun purgeAllFailureInvokesOnFailed() = runTest(dispatcher) {
        val id = repo.saveNote(null, "t", "c", null, false, null)
        repo.deleteNote(repo.getNote(id)!!)
        vm.notes.first { it.isNotEmpty() }
        // 制造确定性的 DB 失败：删掉 notes 表（Robolectric 下 db.close() 之后查询仍能成功，不可靠）
        db.openHelper.writableDatabase.execSQL("DROP TABLE notes")
        assertTrue(runCatching { repo.getNotesByIds(listOf(id)) }.isFailure)

        val outcome = CompletableDeferred<String>()
        vm.purgeAll(onDone = { outcome.complete("done") }, onFailed = { outcome.complete("failed") })

        assertEquals("failed", outcome.await())
        // 在途标志必须在失败后复位，否则「清空」按钮永久禁用
        assertFalse(vm.purging.value)
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
        vm = TrashViewModel(repo, TrashRetentionStore(ApplicationProvider.getApplicationContext()))
        vm.notes.first { it.size == 1 && it.first().title == "fresh" }

        assertNull(db.noteDao().getById(expired))
        assertNotNull(db.noteDao().getById(fresh))
    }

    @Test
    fun retentionDaysComesFromStore() {
        // VM 暴露的保留期与新建 store 的默认值一致（30 天）
        val store = TrashRetentionStore(ApplicationProvider.getApplicationContext())
        assertEquals(store.retentionDays.value, vm.retentionDays.value)
        assertEquals(30, vm.retentionDays.value)
    }

    @Test
    fun purgeAllResetsPurgingState() = runTest(dispatcher) {
        val a = repo.saveNote(null, "a", "c", null, false, null)
        val b = repo.saveNote(null, "b", "c", null, false, null)
        repo.deleteNote(repo.getNote(a)!!)
        repo.deleteNote(repo.getNote(b)!!)
        vm.notes.first { it.size == 2 }
        assertFalse(vm.purging.value)

        val done = CompletableDeferred<Int>()
        vm.purgeAll(onDone = { done.complete(it) }, onFailed = { done.complete(-1) })
        assertEquals(2, done.await())
        // 清空完成后在途标志复位（界面按钮恢复可用）
        assertFalse(vm.purging.value)
        assertEquals(0, db.noteDao().getAll().size)
    }
}
