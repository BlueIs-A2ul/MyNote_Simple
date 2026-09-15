package com.mynote.app.ui.notes

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.db.NoteSortMode
import com.mynote.app.data.image.ImageStore
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.data.settings.NoteSortStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
        // 排序偏好跨测试共享同一 SharedPreferences，显式复位避免顺序依赖
        NoteSortStore(context).setMode(NoteSortMode.UPDATED_DESC)
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
        vm.notes.first { it != null && it.isNotEmpty() }
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
    fun toggleLastSelectedExitsSelectionMode() {
        vm.enterSelection(1L)
        vm.toggleSelect(1L)
        assertFalse(vm.selectionMode.value)
        assertTrue(vm.selectedIds.value.isEmpty())
    }

    @Test
    fun selectAllOnEmptyListDoesNothing() = runTest(dispatcher) {
        // 首帧加载中（notes == null）时全选不动作也不崩溃
        assertNull(vm.notes.value)
        vm.selectAll()
        assertTrue(vm.selectedIds.value.isEmpty())

        // 列表为空时全选同样不动作
        vm.notes.first { it != null }
        assertTrue(vm.notes.value.orEmpty().isEmpty())
        vm.selectAll()
        assertTrue(vm.selectedIds.value.isEmpty())
    }

    @Test
    fun onFilterSelectClearsSelection() {
        vm.enterSelection(1L)
        assertTrue(vm.selectionMode.value)

        vm.onFilterSelect(CategoryFilter.Uncategorized)

        assertFalse(vm.selectionMode.value)
        assertTrue(vm.selectedIds.value.isEmpty())
        assertEquals(CategoryFilter.Uncategorized, vm.selectedFilter.value)
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

    // ---------- 分类筛选（全部 / 未分类 / 某个分类） ----------

    @Test
    fun filterAllObservesAllNotes() = runTest(dispatcher) {
        val catId = repo.addCategory("工作", 0)
        repo.saveNote(null, "a", "c", null, false, null)
        repo.saveNote(null, "b", "c", catId, false, null)
        vm.notes.first { it != null && it.isNotEmpty() }
        assertEquals(setOf("a", "b"), vm.notes.value.orEmpty().map { it.title }.toSet())
    }

    @Test
    fun filterUncategorizedObservesOnlyUncategorizedNotes() = runTest(dispatcher) {
        val catId = repo.addCategory("工作", 0)
        repo.saveNote(null, "有分类", "c", catId, false, null)
        repo.saveNote(null, "未分类", "c", null, false, null)
        vm.onFilterSelect(CategoryFilter.Uncategorized)
        vm.notes.first { it != null && it.isNotEmpty() }
        assertEquals(listOf("未分类"), vm.notes.value.orEmpty().map { it.title })
    }

    @Test
    fun filterSingleObservesOnlyThatCategoryNotes() = runTest(dispatcher) {
        val catA = repo.addCategory("工作", 0)
        val catB = repo.addCategory("生活", 0)
        repo.saveNote(null, "工作笔记", "c", catA, false, null)
        repo.saveNote(null, "生活笔记", "c", catB, false, null)
        vm.onFilterSelect(CategoryFilter.Single(catA))
        vm.notes.first { it != null && it.isNotEmpty() }
        assertEquals(listOf("工作笔记"), vm.notes.value.orEmpty().map { it.title })
    }

    @Test
    fun filterUncategorizedIgnoresDeletedNotes() = runTest(dispatcher) {
        val keep = repo.saveNote(null, "未分类", "c", null, false, null)
        val trash = repo.saveNote(null, "待删", "c", null, false, null)
        vm.onFilterSelect(CategoryFilter.Uncategorized)
        vm.notes.first { it != null && it.any { n -> n.id == trash } }

        db.noteDao().update(db.noteDao().getById(trash)!!.copy(deletedAt = 9L))

        vm.notes.first { it != null && it.none { n -> n.id == trash } }
        assertEquals(listOf(keep), vm.notes.value.orEmpty().map { it.id })
    }

    @Test
    fun newNoteCategoryIdFollowsFilter() {
        vm.onFilterSelect(CategoryFilter.All)
        assertNull(vm.newNoteCategoryId)
        vm.onFilterSelect(CategoryFilter.Uncategorized)
        assertNull(vm.newNoteCategoryId)
        vm.onFilterSelect(CategoryFilter.Single(7L))
        assertEquals(7L, vm.newNoteCategoryId)
    }

    @Test
    fun notesIsNullBeforeFirstEmission() {
        // 冷启动首帧（尚未订阅）时 notes 初始为 null，供页面渲染「加载中」占位而非空态
        assertNull(vm.notes.value)
    }

    @Test
    fun queryDebounceDelaysSearchResults() = runTest(dispatcher) {
        repo.saveNote(null, "abc", "c", null, false, null)
        repo.saveNote(null, "xyz", "c", null, false, null)
        vm.notes.first { it != null && it.size == 2 }

        vm.onQueryChange("abc")
        // 防抖窗口（200ms）内：搜索结果尚未触发，列表仍是旧全量值
        assertEquals(2, vm.notes.value.orEmpty().size)

        advanceTimeBy(210)
        runCurrent()
        vm.notes.first { it != null && it.size == 1 && it[0].title == "abc" }
        assertEquals(1, vm.notes.value.orEmpty().size)
    }

    @Test
    fun sortModeAppliesToUncategorizedFilter() = runTest(dispatcher) {
        repo.saveNote(null, "banana", "c", null, false, null)
        repo.saveNote(null, "Apple", "c", null, false, null)
        vm.onFilterSelect(CategoryFilter.Uncategorized)
        vm.notes.first { it != null && it.isNotEmpty() }

        vm.onSortSelect(NoteSortMode.TITLE_ASC)
        vm.notes.first { it != null && it.size == 2 && it[0].title == "Apple" }
        assertEquals(listOf("Apple", "banana"), vm.notes.value.orEmpty().map { it.title })
    }

    @Test
    fun sortModeAppliesToCategoryFilter() = runTest(dispatcher) {
        val catId = repo.addCategory("工作", 0)
        repo.saveNote(null, "banana", "c", catId, false, null)
        repo.saveNote(null, "Apple", "c", catId, false, null)
        vm.onFilterSelect(CategoryFilter.Single(catId))
        vm.notes.first { it != null && it.isNotEmpty() }

        vm.onSortSelect(NoteSortMode.TITLE_ASC)
        vm.notes.first { it != null && it.size == 2 && it[0].title == "Apple" }
        assertEquals(listOf("Apple", "banana"), vm.notes.value.orEmpty().map { it.title })
    }
}
