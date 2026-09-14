package com.mynote.app.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mynote.app.data.db.NoteListItem
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.data.settings.TrashRetentionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 回收站：恢复 / 彻底删除 / 清空 / 打开时清理到期笔记。 */
class TrashViewModel(
    private val repository: NoteRepository,
    private val retentionStore: TrashRetentionStore
) : ViewModel() {

    val notes: StateFlow<List<NoteListItem>> =
        repository.observeDeletedNotes()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前保留期（天），随设置即时生效。 */
    val retentionDays: StateFlow<Int> = retentionStore.retentionDays

    // 清空回收站在途标志：防止重复触发，界面据此禁用按钮
    private val _purging = MutableStateFlow(false)
    val purging: StateFlow<Boolean> = _purging.asStateFlow()

    init {
        // 打开回收站即清理已到期（超过设置保留期）的笔记
        viewModelScope.launch {
            repository.purgeExpiredDeletedNotes(ttlMs = TrashRetentionStore.ttlMs(retentionStore.retentionDays.value))
        }
    }

    fun restore(item: NoteListItem, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.getNote(item.id)?.let { repository.restoreNote(it) }
            onDone()
        }
    }

    fun purge(item: NoteListItem, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.getNote(item.id)?.let { repository.purgeNote(it) }
            onDone()
        }
    }

    fun purgeAll(onDone: (Int) -> Unit) {
        if (_purging.value) return
        viewModelScope.launch {
            _purging.value = true
            try {
                val list = repository.getNotesByIds(notes.value.map { it.id })
                repository.purgeNotes(list)
                onDone(list.size)
            } finally {
                _purging.value = false
            }
        }
    }

    companion object {
        fun factory(repository: NoteRepository, retentionStore: TrashRetentionStore): ViewModelProvider.Factory =
            viewModelFactory { initializer { TrashViewModel(repository, retentionStore) } }
    }
}
