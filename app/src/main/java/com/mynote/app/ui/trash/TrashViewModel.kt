package com.mynote.app.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mynote.app.data.db.NoteListItem
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.data.settings.TrashRetentionStore
import kotlinx.coroutines.CancellationException
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

    /** 回收站列表；null 表示首帧加载中（条目 92），避免进页面先闪「回收站是空的」。 */
    val notes: StateFlow<List<NoteListItem>?> =
        repository.observeDeletedNotes()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    /** 恢复（条目 76：失败回调 onFailed；条目 90：回调是否真的恢复了，避免 no-op 也报成功）。 */
    fun restore(item: NoteListItem, onDone: (Boolean) -> Unit, onFailed: () -> Unit) {
        viewModelScope.launch {
            try {
                val note = repository.getNote(item.id)
                if (note != null) repository.restoreNote(note)
                onDone(note != null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onFailed()
            }
        }
    }

    /** 彻底删除单条（条目 76；条目 90：回调是否真的删掉了）。 */
    fun purge(item: NoteListItem, onDone: (Boolean) -> Unit, onFailed: () -> Unit) {
        viewModelScope.launch {
            try {
                val note = repository.getNote(item.id)
                if (note != null) repository.purgeNote(note)
                onDone(note != null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onFailed()
            }
        }
    }

    /** 清空回收站（条目 76）：空列表直接返回 0，异常走 onFailed。 */
    fun purgeAll(onDone: (Int) -> Unit, onFailed: () -> Unit) {
        if (_purging.value) return
        // 条目 90：置位必须在 launch 之前，否则同一帧内的两次点击都能穿过检查
        _purging.value = true
        val ids = notes.value.orEmpty().map { it.id }
        viewModelScope.launch {
            try {
                val list = repository.getNotesByIds(ids)
                repository.purgeNotes(list)
                onDone(list.size)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onFailed()
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
