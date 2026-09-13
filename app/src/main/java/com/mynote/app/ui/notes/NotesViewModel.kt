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

    companion object {
        fun factory(repository: NoteRepository, sortStore: NoteSortStore): ViewModelProvider.Factory = viewModelFactory {
            initializer { NotesViewModel(repository, sortStore) }
        }
    }
}
