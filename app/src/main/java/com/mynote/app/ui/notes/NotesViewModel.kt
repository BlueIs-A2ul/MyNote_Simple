package com.mynote.app.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.net.Uri
import com.mynote.app.data.backup.BackupFormatException
import com.mynote.app.data.backup.BackupManager
import com.mynote.app.data.db.CategoryEntity
import com.mynote.app.data.db.NoteEntity
import com.mynote.app.data.db.NoteListItem
import com.mynote.app.data.db.NoteSortMode
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.data.settings.NoteFilterStore
import com.mynote.app.data.settings.NoteSortStore
import com.mynote.app.ui.common.launchSafely
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class NotesViewModel(
    private val repository: NoteRepository,
    private val sortStore: NoteSortStore,
    private val filterStore: NoteFilterStore,
    private val backupManager: BackupManager
) : ViewModel() {

    /** 条目 78：备份导出/导入在 ViewModel 里跑，旋转不会取消协程（否则会留下半截 zip 且无提示）。 */
    data class BackupUiState(
        val exporting: Boolean = false,
        val importing: Boolean = false,
        val message: String? = null
    )

    private val _backupUi = MutableStateFlow(BackupUiState())
    val backupUi: StateFlow<BackupUiState> = _backupUi

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    // 恢复上次退出时的筛选（冷启动不再一律回「全部」）；脏值由解码兜底为「全部」
    private val _selectedFilter = MutableStateFlow(decodeCategoryFilter(filterStore.read()))
    val selectedFilter: StateFlow<CategoryFilter> = _selectedFilter.asStateFlow()

    val sortMode: StateFlow<NoteSortMode> = sortStore.mode

    val categories: StateFlow<List<CategoryEntity>> =
        repository.observeCategories()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 分类 id → 可见笔记数：分类抽屉行尾计数（与分类管理页共用同一投影）。 */
    val categoryCounts: StateFlow<Map<Long, Int>> =
        repository.observeCategoryNoteCounts()
            .map { counts -> counts.associate { it.categoryId to it.count } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 笔记列表；null 表示首帧加载中（Room 首个真实结果到达前），避免冷启动闪「还没有笔记」空态。 */
    val notes: StateFlow<List<NoteListItem>?> =
        // 搜索输入防抖 200ms + 去重，避免每敲一个键重启一次 LIKE 全表扫描；
        // 空查询（含冷启动首帧）不延迟，否则主页会先闪 >=200ms 的「加载中…」
        combine(
            _query.debounce(::searchDebounceMillis).distinctUntilChanged(),
            _selectedFilter,
            sortMode
        ) { q, filter, sort -> q to Pair(filter, sort) }
            .flatMapLatest { (q, filterSort) ->
                val (filter, sort) = filterSort
                when {
                    q.isNotBlank() -> repository.search(q, sort)
                    filter is CategoryFilter.Single -> repository.observeByCategory(filter.categoryId, sort)
                    filter is CategoryFilter.Uncategorized -> repository.observeUncategorized(sort)
                    else -> repository.observeNotes(sort)
                }
            }
            // 条目 91：可见集合变化时收敛多选，避免「选中项已不在列表里」却仍被批量操作
            .onEach { visible -> pruneSelection(visible) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 新建笔记的预选分类：仅「某个分类」筛选下有值，其余为 null（未分类）。 */
    val newNoteCategoryId: Long?
        get() = (_selectedFilter.value as? CategoryFilter.Single)?.categoryId

    /**
     * 记住的分类可能已在别处删除，启动时用一次性查询校验。
     *
     * 不能只靠 NotesScreen 里那段 LaunchedEffect：它以「分类列表非空」作为已加载信号，
     * 若分类被删到一个不剩，列表恒为空、校验永远不成立，界面会卡在空的分类页。
     * 查询失败时保持原值——校验失败不该改掉用户的选择。
     */
    init {
        viewModelScope.launch {
            if (_selectedFilter.value !is CategoryFilter.Single) return@launch
            val categories = runCatching { repository.observeCategories().first() }
                .getOrNull() ?: return@launch
            if (_selectedFilter.value.isMissingFrom(categories)) onFilterSelect(CategoryFilter.All)
        }
    }

    fun exportBackup(uri: Uri) {
        if (_backupUi.value.exporting) return
        _backupUi.update { it.copy(exporting = true) }
        viewModelScope.launch {
            val message = try {
                "已导出 " + backupManager.exportZip(uri) + " 条笔记"
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                "导出失败"
            }
            _backupUi.update { it.copy(exporting = false, message = message) }
        }
    }

    fun importBackup(uri: Uri) {
        if (_backupUi.value.importing) return
        _backupUi.update { it.copy(importing = true) }
        viewModelScope.launch {
            val message = try {
                val r = backupManager.importZip(uri)
                buildString {
                    append("导入完成：新增 " + r.inserted + " · 更新 " + r.updated + " · 跳过 " + r.skipped)
                    if (r.trashed > 0) append(" · 回收站 " + r.trashed)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (t is BackupFormatException) "不是有效的备份文件" else "导入失败"
            }
            _backupUi.update { it.copy(importing = false, message = message) }
        }
    }

    /** 条目 48：删除后的「撤销」——把刚移入回收站的笔记恢复回来。 */
    fun restoreDeleted(noteId: Long) {
        launchSafely {
            repository.getNote(noteId)?.let { repository.restoreNote(it) }
        }
    }

    /** 批量删除后的「撤销」：逐条恢复刚移入回收站的笔记（已被彻底删除的跳过）。 */
    fun restoreDeleted(noteIds: List<Long>) {
        launchSafely {
            noteIds.forEach { id ->
                repository.getNote(id)?.let { repository.restoreNote(it) }
            }
        }
    }

    fun consumeBackupMessage() {
        _backupUi.update { it.copy(message = null) }
    }

    fun onQueryChange(value: String) {
        if (value == _query.value) return
        // 条目 91：查询词变化后可见集合随之变化，多选必须清零，
        // 否则「搜 a 选中 A → 改搜 b → 删除」会删掉用户根本看不见的 A
        if (_selectedIds.value.isNotEmpty()) exitSelection()
        _query.value = value
    }

    fun onFilterSelect(filter: CategoryFilter) {
        // 切换筛选时清空多选，避免选中集跨 tab 残留（含分类被删除后的自动回落）
        exitSelection()
        _selectedFilter.value = filter
        // 记忆：每次切换都落盘（含回落「全部」），下次冷启动据此恢复
        filterStore.write(filter.encodeToken())
    }

    fun onSortSelect(mode: NoteSortMode) {
        sortStore.setMode(mode)
    }

    // ---------- 多选批量操作 ----------

    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    /** 长按进入多选，并选中该行。 */
    fun enterSelection(noteId: Long) {
        _selectionMode.value = true
        _selectedIds.value = setOf(noteId)
    }

    fun toggleSelect(noteId: Long) {
        val next = _selectedIds.value.let { if (noteId in it) it - noteId else it + noteId }
        _selectedIds.value = next
        // 取消最后一个选中项后自动退出多选，避免停留在「已选 0 项」的死状态
        if (next.isEmpty()) _selectionMode.value = false
    }

    /** 全选当前列表可见笔记；列表仍在加载或为空时不动作。 */
    fun selectAll() {
        val visible = notes.value
        if (visible.isNullOrEmpty()) return
        _selectedIds.value = visible.map { it.id }.toSet()
    }

    fun exitSelection() {
        _selectionMode.value = false
        _selectedIds.value = emptySet()
    }

    /** 条目 91：从选中集里剔除已不可见的笔记（被别处删除、移出当前筛选或搜索结果变化）。 */
    private fun pruneSelection(visible: List<NoteListItem>) {
        val selected = _selectedIds.value
        if (selected.isEmpty()) return
        val visibleIds = visible.mapTo(HashSet()) { it.id }
        val kept = selected.filterTo(HashSet()) { it in visibleIds }
        if (kept.size == selected.size) return
        _selectedIds.value = kept
        if (kept.isEmpty()) _selectionMode.value = false
    }

    /**
     * 批量删除：回调被删笔记的 id 列表，供列表页给出「撤销」。
     * 不复用 [runBatch]（它的完成回调只有条数），但保持同样的失败语义：
     * 失败时保留选中态便于重试，取消照常向上抛。
     */
    fun batchDelete(onDone: (List<Long>) -> Unit, onFailed: () -> Unit) {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                val notes = repository.getNotesByIds(ids.toList())
                repository.deleteNotes(notes)
                exitSelection()
                onDone(notes.map { it.id })
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onFailed()
            }
        }
    }

    fun batchSetCategory(categoryId: Long?, onDone: (Int) -> Unit, onFailed: () -> Unit) =
        runBatch(onDone, onFailed) { repository.moveNotesToCategory(it, categoryId) }

    fun batchSetPinned(pinned: Boolean, onDone: (Int) -> Unit, onFailed: () -> Unit) =
        runBatch(onDone, onFailed) { repository.setNotesPinned(it, pinned) }

    /**
     * 批量操作（条目 76）：失败时回调 [onFailed] 并**保留选中态**，让用户可以直接重试；
     * 原先无 try/catch，DB 异常会沿 viewModelScope 抛出直接崩溃。
     */
    private fun runBatch(
        onDone: (Int) -> Unit,
        onFailed: () -> Unit,
        op: suspend (List<NoteEntity>) -> Unit
    ) {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                val notes = repository.getNotesByIds(ids.toList())
                op(notes)
                exitSelection()
                onDone(notes.size)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onFailed()
            }
        }
    }

    companion object {
        /**
         * 条目 92：空查询（冷启动首帧）不延迟，非空输入才防抖 200ms，
         * 否则主页冷启动必然先闪一次「加载中…」。纯函数便于单测。
         */
        internal fun searchDebounceMillis(query: String): Long = if (query.isEmpty()) 0L else SEARCH_DEBOUNCE_MS

        private const val SEARCH_DEBOUNCE_MS = 200L

        fun factory(
            repository: NoteRepository,
            sortStore: NoteSortStore,
            filterStore: NoteFilterStore,
            backupManager: BackupManager
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { NotesViewModel(repository, sortStore, filterStore, backupManager) }
        }
    }
}