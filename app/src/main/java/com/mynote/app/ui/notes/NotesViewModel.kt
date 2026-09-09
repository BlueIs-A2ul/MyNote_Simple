package com.mynote.app.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mynote.app.data.db.CategoryEntity
import com.mynote.app.data.db.NoteEntity
import com.mynote.app.data.repository.NoteRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModel(
    private val repository: NoteRepository
) : ViewModel() {

    val query = MutableStateFlow("")
    val selectedCategoryId = MutableStateFlow<Long?>(null)

    val categories: StateFlow<List<CategoryEntity>> =
        repository.observeCategories()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val notes: StateFlow<List<NoteEntity>> =
        combine(query, selectedCategoryId) { q, cat -> q to cat }
            .flatMapLatest { (q, cat) ->
                when {
                    q.isNotBlank() -> repository.search(q)
                    cat != null -> repository.observeByCategory(cat)
                    else -> repository.observeNotes()
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onCategorySelect(id: Long?) {
        selectedCategoryId.value = id
    }

    companion object {
        fun factory(repository: NoteRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { NotesViewModel(repository) }
        }
    }
}
