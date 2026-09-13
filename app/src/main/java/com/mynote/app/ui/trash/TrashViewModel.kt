package com.mynote.app.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mynote.app.data.db.NoteEntity
import com.mynote.app.data.repository.NoteRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 回收站：恢复 / 彻底删除 / 清空 / 打开时清理到期笔记。 */
class TrashViewModel(
    private val repository: NoteRepository
) : ViewModel() {

    val notes: StateFlow<List<NoteEntity>> =
        repository.observeDeletedNotes()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // 打开回收站即清理已到期（超过 30 天）的笔记
        viewModelScope.launch { repository.purgeExpiredDeletedNotes() }
    }

    fun restore(note: NoteEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.restoreNote(note)
            onDone()
        }
    }

    fun purge(note: NoteEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.purgeNote(note)
            onDone()
        }
    }

    fun purgeAll(onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val count = notes.value.size
            notes.value.forEach { repository.purgeNote(it) }
            onDone(count)
        }
    }

    companion object {
        fun factory(repository: NoteRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { TrashViewModel(repository) }
        }
    }
}
