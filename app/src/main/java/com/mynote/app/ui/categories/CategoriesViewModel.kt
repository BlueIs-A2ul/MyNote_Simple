package com.mynote.app.ui.categories

import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mynote.app.data.db.CategoryEntity
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.ui.theme.NoteColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CategoriesViewModel(private val repository: NoteRepository) : ViewModel() {

    val categories: StateFlow<List<CategoryEntity>> = repository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(name: String, onDone: () -> Unit) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.addCategory(name.trim(), NoteColors.random().toArgb())
            onDone()
        }
    }

    fun rename(category: CategoryEntity, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch { repository.renameCategory(category, newName.trim()) }
    }

    fun delete(category: CategoryEntity) {
        viewModelScope.launch { repository.deleteCategory(category) }
    }

    companion object {
        fun factory(repository: NoteRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { CategoriesViewModel(repository) } }
    }
}
