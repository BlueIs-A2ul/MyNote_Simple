package com.mynote.app.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mynote.app.data.db.CategoryEntity
import com.mynote.app.data.db.NoteEntity
import com.mynote.app.data.db.NoteSortMode
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.data.settings.NoteSortStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModel(
    private val repository: NoteRepository,
    private val sortStore: NoteSortStore
) : ViewModel() {

    val query = MutableStateFlow("")
    val selectedFilter = MutableStateFlow<CategoryFilter>(CategoryFilter.All)

    val sortMode: StateFlow<NoteSortMode> = sortStore.mode

    val categories: StateFlow<List<CategoryEntity>> =
        repository.observeCategories()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 笔记列表；null 表示首帧加载中（Room 首个真实结果到达前），避免冷启动闪「还没有笔记」空态。 */
    val notes: StateFlow<List<NoteEntity>?> =
        // 搜索输入防抖 200ms + 去重，避免每敲一个键重启一次 LIKE 全表扫描
        combine(query.debounce(200).distinctUntilChanged(), selectedFilter, sortMode) { q, filter, sort -> q to Pair(filter, sort) }
            .flatMapLatest { (q, filterSort) ->
                val (filter, sort) = filterSort
                when {
                    q.isNotBlank() -> repository.search(q, sort)
                    filter is CategoryFilter.Single -> repository.observeByCategory(filter.categoryId, sort)
                    filter is CategoryFilter.Uncategorized -> repository.observeUncategorized(sort)
                    else -> repository.observeNotes(sort)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 新建笔记的预选分类：仅「某个分类」筛选下有值，其余为 null（未分类）。 */
    val newNoteCategoryId: Long?
        get() = (selectedFilter.value as? CategoryFilter.Single)?.categoryId

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onFilterSelect(filter: CategoryFilter) {
        selectedFilter.value = filter
    }

    fun onSortSelect(mode: NoteSortMode) {
        sortStore.setMode(mode)
    }

    // ---------- 多选批量操作 ----------

    val selectionMode = MutableStateFlow(false)
    val selectedIds = MutableStateFlow<Set<Long>>(emptySet())

    /** 长按进入多选，并选中该行。 */
    fun enterSelection(noteId: Long) {
        selectionMode.value = true
        selectedIds.value = setOf(noteId)
    }

    fun toggleSelect(noteId: Long) {
        selectedIds.value = selectedIds.value.let { if (noteId in it) it - noteId else it + noteId }
    }

    /** 全选当前列表可见笔记。 */
    fun selectAll() {
        selectedIds.value = notes.value.orEmpty().map { it.id }.toSet()
    }

    fun exitSelection() {
        selectionMode.value = false
        selectedIds.value = emptySet()
    }

    fun batchDelete(onDone: (Int) -> Unit) = runBatch(onDone) { repository.deleteNotes(it) }

    fun batchSetCategory(categoryId: Long?, onDone: (Int) -> Unit) =
        runBatch(onDone) { repository.moveNotesToCategory(it, categoryId) }

    fun batchSetPinned(pinned: Boolean, onDone: (Int) -> Unit) =
        runBatch(onDone) { repository.setNotesPinned(it, pinned) }

    private fun runBatch(onDone: (Int) -> Unit, op: suspend (List<NoteEntity>) -> Unit) {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val notes = repository.getNotesByIds(ids.toList())
            op(notes)
            exitSelection()
            onDone(notes.size)
        }
    }

    companion object {
        fun factory(repository: NoteRepository, sortStore: NoteSortStore): ViewModelProvider.Factory = viewModelFactory {
            initializer { NotesViewModel(repository, sortStore) }
        }
    }
}
