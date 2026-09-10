package com.mynote.app.ui.history

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.image.ImageStore
import com.mynote.app.data.repository.NoteRepository
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteHistoryViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var repo: NoteRepository
    private val vms = mutableListOf<NoteHistoryViewModel>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = NoteRepository(db.noteDao(), db.categoryDao(), db.noteRevisionDao(), ImageStore(context), db)
    }

    @After
    fun teardown() {
        vms.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
        db.close()
    }

    private fun createVm(id: Long): NoteHistoryViewModel =
        NoteHistoryViewModel(repo, id).also { vms += it }

    @Test
    fun listShowsCurrentMarkerAndChangeLabels() = runTest(dispatcher) {
        val id = repo.saveNote(null, "t1", "c1", null, false, null)
        repo.saveNote(id, "t2", "c1", null, false, null)
        val vm = createVm(id)

        val state = vm.state.first { it.count == 2 }
        assertTrue(state.revisions[0].isCurrent)
        assertEquals(listOf("标题已修改"), state.revisions[0].labels)
        assertEquals(listOf("初始版本"), state.revisions[1].labels)
    }

    @Test
    fun bannerAppearsAt40Revisions() = runTest(dispatcher) {
        val id = repo.saveNote(null, "t", "v0", null, false, null)
        for (i in 1..39) repo.saveNote(id, "t", "v$i", null, false, null)
        val vm = createVm(id)
        val state = vm.state.first { it.count == 40 }
        assertNotNull(state.bannerText)
    }

    @Test
    fun firstRevisionHasNoDiff() = runTest(dispatcher) {
        val id = repo.saveNote(null, "t1", "c1", null, false, null)
        repo.saveNote(id, "t2", "c2", null, false, null)
        val vm = createVm(id)
        val state = vm.state.first { it.count == 2 }
        vm.selectRevision(state.revisions[1].revision.id)

        val detail = vm.state.value.detail!!
        assertTrue(detail.isFirst)
        assertTrue(detail.diffLines.isEmpty())
    }

    @Test
    fun selectingRevisionComputesDiff() = runTest(dispatcher) {
        val id = repo.saveNote(null, "t", "line1", null, false, null)
        repo.saveNote(id, "t", "line1\nline2", null, false, null)
        val vm = createVm(id)
        val state = vm.state.first { it.count == 2 }
        vm.selectRevision(state.revisions[0].revision.id)

        val detail = vm.state.first { it.detail?.diffLines?.isNotEmpty() == true }.detail!!
        assertEquals(
            listOf(NoteDiff.Type.UNCHANGED, NoteDiff.Type.ADDED),
            detail.diffLines.map { it.type }
        )
    }

    @Test
    fun restoreIsSingleFlightAndWritesBack() = runTest(dispatcher) {
        val id = repo.saveNote(null, "t", "v1", null, false, null)
        repo.saveNote(id, "t", "v2", null, false, null)
        val vm = createVm(id)
        val state = vm.state.first { it.count == 2 }
        val oldId = state.revisions[1].revision.id

        vm.selectRevision(oldId)
        vm.restore()
        vm.restore() // 第二次应被防重入拦截

        vm.state.first { it.restoreSucceeded }
        assertEquals("v1", repo.getNote(id)?.content)
        assertEquals(3, repo.countRevisions(id))
    }

    @Test
    fun closeDetailResetsDetailState() = runTest(dispatcher) {
        val id = repo.saveNote(null, "t1", "c1", null, false, null)
        repo.saveNote(id, "t2", "c2", null, false, null)
        val vm = createVm(id)
        val state = vm.state.first { it.count == 2 }
        vm.selectRevision(state.revisions[0].revision.id)
        assertNotNull(vm.state.value.detail)
        vm.closeDetail()
        assertEquals(null, vm.state.value.detail)
    }
}
