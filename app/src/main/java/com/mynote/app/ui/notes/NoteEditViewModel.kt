package com.mynote.app.ui.notes

import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mynote.app.data.backup.BackupManager
import com.mynote.app.data.db.CategoryEntity
import com.mynote.app.data.db.NoteEntity
import com.mynote.app.data.db.NoteRevisionDao
import com.mynote.app.data.image.ImageStore
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.ui.common.launchSafely
import com.mynote.app.ui.theme.NoteColors
import com.mynote.app.util.NoteContentParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class NoteEditViewModel(
    private val repository: NoteRepository,
    private val imageStore: ImageStore,
    private val noteId: Long?,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    // 条目 78：txt 导出搬进 VM 作用域；测试可省略（不导出即可）
    private val backupManager: BackupManager? = null
) : ViewModel() {

    /** 条目 78：txt 导出在 VM 作用域执行，旋转不会留下半截文件且提示不丢。 */
    data class ExportState(val exporting: Boolean = false, val message: String? = null)

    private val _export = MutableStateFlow(ExportState())
    val exportState: StateFlow<ExportState> = _export

    fun exportTxt(uri: Uri, title: String, content: String) {
        if (_export.value.exporting) return
        _export.update { it.copy(exporting = true) }
        viewModelScope.launch {
            val ok = backupManager?.exportNoteAsTxt(uri, title, content) ?: false
            _export.update { it.copy(exporting = false, message = if (ok) "已导出" else "导出失败") }
        }
    }

    fun consumeExportMessage() {
        _export.update { it.copy(message = null) }
    }

    private val _categories = MutableStateFlow<List<CategoryEntity>>(emptyList())
    val categories: StateFlow<List<CategoryEntity>> = _categories

    private val _note = MutableStateFlow<NoteEntity?>(null)
    val note: StateFlow<NoteEntity?> = _note

    // 目标笔记在库中不存在（深链失效 / 别处彻底删除）：界面区分「加载中」与「已不存在」
    private val _noteMissing = MutableStateFlow(false)
    val noteMissing: StateFlow<Boolean> = _noteMissing

    // 历史恢复后重新读到的数据行：界面据此覆盖本地旧编辑态（常规回填只跑一次，等不到这次变更）
    private val _restoredNote = MutableStateFlow<NoteEntity?>(null)
    val restoredNote: StateFlow<NoteEntity?> = _restoredNote

    // 新笔记被静默保存（草稿兜底）后的 id：后续保存/静默保存都以它为目标，避免重复插入。
    // 条目 73：同时落在 SavedStateHandle 里，进程被杀后重建的 VM 也能取回同一个目标 id。
    private val _draftId = MutableStateFlow(savedState.get<Long>(KEY_DRAFT_ID))
    val draftId: StateFlow<Long?> = _draftId

    // 保存/草稿/删除在途作业：连点第二次直接忽略，防止重复插入与双重退出回调
    private var saveJob: Job? = null
    private var draftJob: Job? = null
    private var deleting = false

    init {
        viewModelScope.launch {
            repository.observeCategories().collectLatest { _categories.value = it }
        }
        val restoredDraftId = _draftId.value
        if (noteId != null && noteId != 0L) {
            observeNote(noteId)
        } else if (restoredDraftId != null) {
            // 进程重建后继续盯着这条草稿行，字段比较/脏值判断都以它为准
            observeNote(restoredDraftId)
        }
    }

    private fun observeNote(id: Long) {
        viewModelScope.launch {
            repository.observeNote(id).collectLatest { note ->
                _note.value = note
                // Room 的首次结果即当前库状态：null 代表确实不存在，而不是还在加载
                _noteMissing.value = note == null
            }
        }
    }

    // 条目 97：本次编辑会话里插入的图片名（用于撤销/放弃时清理孤儿文件）
    private val insertedImages = mutableSetOf<String>()

    fun insertImage(uri: Uri, onInserted: (markup: String) -> Unit, onFailed: () -> Unit) {
        launchSafely(onError = { onFailed() }) {
            val target = imageStore.newImageFile("webp")
            if (imageStore.importAndCompress(uri, target)) {
                insertedImages += target.name
                onInserted(NoteContentParser.makeImageMarkup(target.name))
            } else {
                onFailed()
            }
        }
    }

    fun addCategory(name: String, onCreated: (Long) -> Unit) {
        if (name.isBlank()) return
        launchSafely {
            val id = repository.addCategory(name.trim(), NoteColors.random().toArgb())
            onCreated(id)
        }
    }

    fun save(
        title: String,
        content: String,
        categoryId: Long?,
        pinned: Boolean,
        color: Int?,
        noteDate: Long?,
        // 条目 94：失败回调放在末尾参数之前，保持既有「尾随 lambda = onDone」的调用方式不变
        onError: (String) -> Unit = {},
        onDone: (String?) -> Unit
    ) {
        // 只有「正式保存」在途时才拦截连点；退后台草稿写入在途不拦截——否则 onDone 永不回调，
        // 界面 isSaving 会永久卡在 true（保存键变灰、退后台兜底也一并失效）。
        if (saveJob?.isActive == true) return
        val pendingDraft = draftJob
        saveJob = viewModelScope.launch {
            try {
                // 先等草稿落库：新笔记的 draftId 由草稿写入设置，抢跑会重复插入一条
                pendingDraft?.join()
                val targetId = noteId?.takeIf { it != 0L } ?: _draftId.value
                // 条目 94：目标行已不存在（别处彻底删除/到期清理）时不能假装保存成功
                if (targetId != null && repository.getNote(targetId) == null) {
                    onError(SAVE_TARGET_GONE)
                    return@launch
                }
                val before = if (targetId != null) repository.countRevisions(targetId) else 0
                val id = repository.saveNote(targetId, title, content, categoryId, pinned, color, noteDate)
                val warning = if (targetId != null &&
                    before < NoteRevisionDao.WARN_AT &&
                    repository.countRevisions(id) == NoteRevisionDao.WARN_AT
                ) {
                    HISTORY_WARNING
                } else {
                    null
                }
                // 保存后这些图片已经进入历史快照引用集，后续清理交给 saveNote 的 GC
                insertedImages.clear()
                onDone(warning)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                // 条目 94：DB 失败要给「保存失败」而不是崩在 viewModelScope
                onError(SAVE_FAILED)
            } finally {
                saveJob = null
            }
        }
    }

    /**
     * 静默保存草稿（退后台兜底）：存量笔记只更新数据行；新笔记标题/正文均空则不落库；
     * 新笔记非空则插入并记录 [draftId]，后续保存只更新同一条。
     */
    fun saveDraft(
        title: String,
        content: String,
        categoryId: Long?,
        pinned: Boolean,
        color: Int?,
        noteDate: Long?
    ) {
        // 正式保存在途时无需再写草稿；草稿自身重复触发也直接忽略
        if (saveJob?.isActive == true || draftJob?.isActive == true) return
        draftJob = launchSafely {
            try {
                val targetId = noteId?.takeIf { it != 0L } ?: _draftId.value
                when {
                    targetId != null -> repository.updateDraft(targetId, title, content, categoryId, pinned, color, noteDate)
                    title.isBlank() && content.isBlank() -> Unit
                    else -> {
                        val id = repository.saveNote(null, title, content, categoryId, pinned, color, noteDate)
                        _draftId.value = id
                        savedState[KEY_DRAFT_ID] = id
                        observeNote(id)
                    }
                }
            } finally {
                draftJob = null
            }
        }
    }

    /**
     * 条目 97：删除「本次会话插入、但当前正文已不再引用」的图片文件。
     * 只处理本次会话插入的（历史快照引用由 saveNote 的 GC 负责），调用方在撤销/放弃时调用。
     */
    fun cleanupUnusedInsertedImages(currentContent: String) {
        if (insertedImages.isEmpty()) return
        val orphans = orphanImages(insertedImages, currentContent)
        if (orphans.isEmpty()) return
        insertedImages -= orphans
        launchSafely {
            orphans.forEach { imageStore.physicalFile(it).delete() }
        }
    }

    /** 条目 48：当前笔记 id（未保存的新笔记返回 null）。 */
    fun currentNoteId(): Long? = _note.value?.id ?: draftId.value

    /**
     * 历史页恢复成功后调用：重新读库，把恢复后的行交给界面回填。
     * 不能复用 [observeNote] 的常规回填——那里有 initialized 一次性守卫，
     * 从历史页返回时它已是 true，会用恢复前的旧内容继续编辑并在下次保存时覆盖恢复结果。
     */
    fun refreshAfterRestore() {
        val targetId = noteId?.takeIf { it != 0L } ?: _draftId.value ?: return
        launchSafely { _restoredNote.value = repository.getNote(targetId) }
    }

    fun consumeRestoredNote() {
        _restoredNote.value = null
    }

    fun delete(onError: () -> Unit = {}, onDone: () -> Unit) {
        if (deleting) return
        val n = _note.value ?: return
        deleting = true
        launchSafely(onError = { onError() }) {
            try {
                repository.deleteNote(n)
                onDone()
            } finally {
                deleting = false
            }
        }
    }

    companion object {
        /** 条目 97：本次插入、但正文已不再引用的图片名（纯函数，便于单测）。 */
        internal fun orphanImages(inserted: Set<String>, currentContent: String): Set<String> =
            inserted - NoteContentParser.extractImageNames(currentContent).toSet()

        /** 条目 94：目标笔记已被别处删除时的保存失败文案。 */
        const val SAVE_TARGET_GONE = "笔记已不存在，无法保存"

        /** 条目 94：写库失败文案。 */
        const val SAVE_FAILED = "保存失败，请重试"

        const val HISTORY_WARNING =
            "该笔记历史已 ${NoteRevisionDao.WARN_AT} 条，满 ${NoteRevisionDao.MAX_PER_NOTE} 条后最旧记录会自动清理"

        /** 草稿目标 id 在 [SavedStateHandle] 中的键（条目 73：进程被杀后重建仍指向同一条笔记）。 */
        private const val KEY_DRAFT_ID = "note_edit_draft_id"

        fun factory(
            repo: NoteRepository,
            imageStore: ImageStore,
            noteId: Long?,
            savedState: SavedStateHandle = SavedStateHandle(),
            backupManager: BackupManager? = null
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    NoteEditViewModel(repo, imageStore, noteId, savedState, backupManager)
                }
            }
    }
}

/**
 * 判断编辑页是否存在未保存变更。
 * - 新建笔记：仅标题/正文非空才算变更；空笔记只改分类/置顶/日期不视为变更（不会落库）。
 * - 已有笔记未加载完（saved 为 null）：标题/正文非空即视为有变更（保守防丢）。
 * - 已有笔记已加载：标题/正文/分类/置顶/归属日期与已保存值逐项比较。
 */
internal fun hasUnsavedChanges(
    isNew: Boolean,
    saved: NoteEntity?,
    title: String,
    content: String,
    categoryId: Long?,
    pinned: Boolean,
    noteDate: Long?
): Boolean = when {
    isNew -> title.isNotBlank() || content.isNotBlank()
    saved == null -> title.isNotBlank() || content.isNotBlank()
    else -> title != saved.title || content != saved.content ||
        categoryId != saved.categoryId || pinned != saved.pinned || noteDate != saved.noteDate
}
