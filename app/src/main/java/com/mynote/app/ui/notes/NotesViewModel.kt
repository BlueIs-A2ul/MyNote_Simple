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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModel(
    private val repository: NoteRepository,
    private val sortStore: NoteSortStore
) : ViewModel() {

    val query = MutableStateFlow("")
    val selectedCategoryId = MutableStateFlow<Long?>(null)

    val sortMode: StateFlow<NoteSortMode> = sortStore.mode

    val categories: StateFlow<List<CategoryEntity>> =
        repository.observeCategories()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val notes: StateFlow<List<NoteEntity>> =
        combine(query, selectedCategoryId, sortMode) { q, cat, _ -> q to cat }
            .flatMapLatest { (q, cat) ->
                when {
                    q.isNotBlank() -> repository.search(q)
                    cat != null -> repository.observeByCategory(cat)
                    else -> repository.observeNotes(sortMode.value)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onCategorySelect(id: Long?) {
        selectedCategoryId.value = id
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
        selectedIds.value = notes.value.map { it.id }.toSet()
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
