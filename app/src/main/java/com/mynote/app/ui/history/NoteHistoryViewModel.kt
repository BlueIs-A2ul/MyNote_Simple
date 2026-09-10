package com.mynote.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mynote.app.data.db.NoteRevisionDao
import com.mynote.app.data.db.NoteRevisionEntity
import com.mynote.app.data.repository.NoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NoteHistoryViewModel(
    private val repository: NoteRepository,
    private val noteId: Long
) : ViewModel() {

    data class RevisionItem(
        val revision: NoteRevisionEntity,
        val isCurrent: Boolean,
        val labels: List<String>
    )

    data class DetailState(
        val revision: NoteRevisionEntity,
        val isFirst: Boolean,
        val isCurrent: Boolean,
        val labels: List<String>,
        val diffLines: List<NoteDiff.Line> = emptyList(),
        val loadingDiff: Boolean = false,
        val showFullText: Boolean = false
    )

    data class UiState(
        val revisions: List<RevisionItem> = emptyList(),
        val count: Int = 0,
        val bannerText: String? = null,
        val detail: DetailState? = null,
        val restoring: Boolean = false,
        val restoreSucceeded: Boolean = false,
        val message: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            repository.observeRevisions(noteId).collectLatest { raw ->
                _state.update { current ->
                    current.copy(
                        revisions = raw.mapIndexed { index, rev ->
                            RevisionItem(
                                revision = rev,
                                isCurrent = index == 0,
                                labels = if (index == raw.lastIndex) listOf("初始版本")
                                else changeLabels(rev, raw[index + 1])
                            )
                        },
                        count = raw.size,
                        bannerText = bannerFor(raw.size),
                        detail = refreshDetail(current.detail, raw)
                    )
                }
            }
        }
    }

    fun selectRevision(revisionId: Long) {
        val list = _state.value.revisions
        val index = list.indexOfFirst { it.revision.id == revisionId }
        if (index < 0) return
        val item = list[index]
        val older = list.getOrNull(index + 1)
        _state.update {
            it.copy(
                detail = DetailState(
                    revision = item.revision,
                    isFirst = older == null,
                    isCurrent = item.isCurrent,
                    labels = item.labels,
                    loadingDiff = older != null
                )
            )
        }
        if (older != null) {
            viewModelScope.launch {
                val lines = withContext(Dispatchers.Default) {
                    NoteDiff.diff(older.revision.content, item.revision.content)
                }
                _state.update { s ->
                    val detail = s.detail
                    if (detail != null && detail.revision.id == revisionId) {
                        s.copy(detail = detail.copy(diffLines = lines, loadingDiff = false))
                    } else {
                        s
                    }
                }
            }
        }
    }

    fun closeDetail() {
        _state.update { it.copy(detail = null) }
    }

    fun toggleFullText() {
        _state.update { s ->
            val detail = s.detail ?: return@update s
            s.copy(detail = detail.copy(showFullText = !detail.showFullText))
        }
    }

    fun restore() {
        val detail = _state.value.detail ?: return
        if (_state.value.restoring) return
        _state.update { it.copy(restoring = true) }
        val revisionId = detail.revision.id
        viewModelScope.launch {
            val ok = repository.restoreRevision(noteId, revisionId)
            _state.update {
                it.copy(restoring = false, restoreSucceeded = ok, message = if (ok) null else "恢复失败")
            }
        }
    }

    fun consumeRestoreSuccess() {
        _state.update { it.copy(restoreSucceeded = false) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun refreshDetail(detail: DetailState?, raw: List<NoteRevisionEntity>): DetailState? {
        if (detail == null) return null
        val index = raw.indexOfFirst { it.id == detail.revision.id }
        if (index < 0) return null
        val rev = raw[index]
        val older = raw.getOrNull(index + 1)
        return detail.copy(
            revision = rev,
            isCurrent = index == 0,
            isFirst = older == null,
            labels = if (older == null) listOf("初始版本") else changeLabels(rev, older)
        )
    }

    private fun changeLabels(newer: NoteRevisionEntity, older: NoteRevisionEntity): List<String> = buildList {
        if (newer.title != older.title) add("标题已修改")
        if (newer.content != older.content) add("正文已修改")
        if (newer.categoryId != older.categoryId) add("分类已修改")
        if (newer.pinned != older.pinned) add("置顶已修改")
        if (newer.color != older.color) add("颜色已修改")
        if (isEmpty()) add("已修改")
    }

    private fun bannerFor(count: Int): String? = when {
        count >= NoteRevisionDao.MAX_PER_NOTE ->
            "历史已满 ${NoteRevisionDao.MAX_PER_NOTE} 条，最旧记录将随新记录自动清理"
        count >= NoteRevisionDao.WARN_AT ->
            "历史已达 $count/${NoteRevisionDao.MAX_PER_NOTE} 条，满 ${NoteRevisionDao.MAX_PER_NOTE} 条后最旧记录会自动清理"
        else -> null
    }

    companion object {
        fun factory(repo: NoteRepository, noteId: Long): ViewModelProvider.Factory =
            viewModelFactory { initializer { NoteHistoryViewModel(repo, noteId) } }
    }
}
