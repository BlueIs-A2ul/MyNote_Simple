# MyNote 多选批量操作实施计划

> **For agentic workers:** 配套设计文档 `docs/superpowers/specs/2026-09-13-multi-select-design.md`。层次耦合（VM 状态 ↔ 列表 UI ↔ DAO），由主线程串行实现。步骤使用 `- [ ]` 跟踪。

**Goal:** 列表页长按进入多选，支持全选、批量置顶/取消置顶、批量改分类、批量删除（进回收站），发布 1.4.0。

**Architecture:** 选择状态放 `NotesViewModel`（StateFlow）；`NoteRow` 加 `selected`/`onLongClick` 可选参数（默认值保持现有调用零改动）；批量操作为 DAO 的 `getByIds` + `updateAll` 组合；`NotesScreen` 多选模式切换顶栏/行为。

**Tech Stack:** Kotlin、Compose Material3、Room(KSP)、Robolectric/JUnit4。无新依赖、无新权限。

---

### Task 1: 数据层

**Files:** Modify `app/src/main/java/com/mynote/app/data/db/NoteDao.kt`、`app/src/main/java/com/mynote/app/data/repository/NoteRepository.kt`

- [ ] `NoteDao` 增加：
```kotlin
@Query("SELECT * FROM notes WHERE id IN (:ids)")
suspend fun getByIds(ids: List<Long>): List<NoteEntity>

@Update
suspend fun updateAll(notes: List<NoteEntity>)
```
- [ ] `NoteRepository` 增加：
```kotlin
suspend fun getNotesByIds(ids: List<Long>): List<NoteEntity> = noteDao.getByIds(ids)

/** 批量删除 = 批量软删除（进回收站），不触发图片 GC。 */
suspend fun deleteNotes(notes: List<NoteEntity>) {
    if (notes.isEmpty()) return
    val now = System.currentTimeMillis()
    noteDao.updateAll(notes.map { it.copy(deletedAt = now) })
}

suspend fun moveNotesToCategory(notes: List<NoteEntity>, categoryId: Long?) {
    if (notes.isEmpty()) return
    noteDao.updateAll(notes.map { it.copy(categoryId = categoryId) })
}

suspend fun setNotesPinned(notes: List<NoteEntity>, pinned: Boolean) {
    if (notes.isEmpty()) return
    noteDao.updateAll(notes.map { it.copy(pinned = pinned) })
}
```

### Task 2: NotesViewModel 选择状态与批量操作

**Files:** Modify `app/src/main/java/com/mynote/app/ui/notes/NotesViewModel.kt`

- [ ] 增加：
```kotlin
val selectionMode = MutableStateFlow(false)
val selectedIds = MutableStateFlow<Set<Long>>(emptySet())

fun enterSelection(noteId: Long) {
    selectionMode.value = true
    selectedIds.value = setOf(noteId)
}

fun toggleSelect(noteId: Long) {
    selectedIds.value = selectedIds.value.let { if (noteId in it) it - noteId else it + noteId }
}

fun selectAll() {
    selectedIds.value = notes.value.map { it.id }.toSet()
}

fun exitSelection() {
    selectionMode.value = false
    selectedIds.value = emptySet()
}

fun batchDelete(onDone: (Int) -> Unit) = batch(emptyList(), onDone) { notes ->
    repository.deleteNotes(notes)
}

fun batchSetCategory(categoryId: Long?, onDone: (Int) -> Unit) = batch(emptyList(), onDone) { notes ->
    repository.moveNotesToCategory(notes, categoryId)
}

fun batchSetPinned(pinned: Boolean, onDone: (Int) -> Unit) = batch(emptyList(), onDone) { notes ->
    repository.setNotesPinned(notes, pinned)
}

private fun batch(fallback: List<NoteEntity>, onDone: (Int) -> Unit, op: suspend (List<NoteEntity>) -> Unit) {
    val ids = selectedIds.value
    if (ids.isEmpty()) return
    viewModelScope.launch {
        val notes = repository.getNotesByIds(ids.toList())
        op(notes)
        exitSelection()
        onDone(notes.size)
    }
}
```
（`batch` 的 fallback 参数弃用——直接用 `onDone`；保持简单：空选择直接 return 不回调。）
- [ ] 注意：`selectionMode`/`selectedIds` 需要 `MutableStateFlow`（已 import）；其余现有 API 不动。

### Task 3: NoteRow 扩展

**Files:** Modify `app/src/main/java/com/mynote/app/ui/components/NoteRow.kt`

- [ ] 签名增加 `selected: Boolean = false`、`onLongClick: (() -> Unit)? = null`（插在 `highlightQuery` 之后、`modifier` 之前）。
- [ ] 行 `Modifier`：`selected` 时加 `.background(MaterialTheme.colorScheme.surfaceVariant)`（放在 clickable 之前）；点击改：
```kotlin
val clickModifier = if (onLongClick != null) {
    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
} else {
    Modifier.clickable(onClick = onClick)
}
```
- [ ] 文件加 `@OptIn(ExperimentalFoundationApi::class)` 到 NoteRow 函数，import `androidx.compose.foundation.ExperimentalFoundationApi`、`androidx.compose.foundation.combinedClickable`。

### Task 4: NotesScreen 多选 UI

**Files:** Modify `app/src/main/java/com/mynote/app/ui/notes/NotesScreen.kt`

- [ ] collect：`val selectionMode by viewModel.selectionMode.collectAsState()`、`val selectedIds by viewModel.selectedIds.collectAsState()`。
- [ ] `BackHandler(enabled = selectionMode && !searchActive) { viewModel.exitSelection() }`（放在现有搜索 BackHandler 之后）。
- [ ] 顶栏：`PaperTopBar(title = if (selectionMode) "已选 ${selectedIds.size} 项" else "备忘录", onBack = if (selectionMode) {{ viewModel.exitSelection() }} else null, actions = ...)`。注意 `onBack` 是 `(() -> Unit)?` 类型：多选时传退出，否则传 null（保持现状无返回键）。
- [ ] actions 内容：多选模式下仅 `PaperOverflowMenu`（全选 / 置顶 / 取消置顶 / 移动到分类 / 删除）；非多选保持现有搜索 + 三点菜单。移动分类用 `showBatchCategorySheet` 状态 + `ModalBottomSheet`（行样式同编辑页：未分类 + categories + 取消）。
- [ ] 行接线：
```kotlin
NoteRow(
    ...,
    selected = note.id in selectedIds,
    onLongClick = { viewModel.enterSelection(note.id) },
    onClick = { if (selectionMode) viewModel.toggleSelect(note.id) else onOpenNote(note.id) },
    ...
)
```
- [ ] FAB：`if (!selectionMode) SmallFloatingActionButton(...)`。
- [ ] 批量删除确认框：`showBatchDeleteDialog` + `PaperAlertDialog`（「将把所选 N 条笔记移入回收站，30 天后自动清理。」），确认 → `viewModel.batchDelete { n -> scope.launch { snackbarHostState.showSnackbar("已删除 $n 条") } }`。
- [ ] 批量置顶/取消置顶：`viewModel.batchSetPinned(true/false) { n -> snackbar("已置顶/已取消置顶 $n 条") }`（保留多选模式）。
- [ ] 批量改分类：选择后 `viewModel.batchSetCategory(catId) { n -> snackbar("已移动 $n 条") }`（退出多选）。
- [ ] 全选：`viewModel.selectAll()`。

### Task 5: 测试

**Files:** Modify `app/src/test/java/com/mynote/app/data/db/NoteDaoTest.kt`；Create `app/src/test/java/com/mynote/app/ui/notes/NotesViewModelTest.kt`

- [ ] `NoteDaoTest`：`getByIdsReturnsOnlyRequestedRows`（3 条取 2 个 id）。
- [ ] `NotesViewModelTest`（`Dispatchers.setMain(StandardTestDispatcher())` + 真实仓库 + in-memory Room，CompletableDeferred 模式）：
  - `enterSelectionSelectsNoteAndEnablesMode`
  - `toggleSelectAddsAndRemoves`
  - `selectAllSelectsVisibleNotes`
  - `exitSelectionClearsState`
  - `batchDeleteSoftDeletesSelectedNotesAndExits`（回调数量=2，deletedAt 非空，selectionMode=false）
  - `batchSetCategoryMovesSelectedNotes`（含 null → 未分类）
  - `batchSetPinnedUpdatesSelectedNotes`
  - `batchOpWithEmptySelectionDoesNotCallback`
- [ ] 注意 VM 构造：`NotesViewModel(repo, NoteSortStore(context))`——用真实 `NoteSortStore`（SharedPreferences，Robolectric 支持）或改工厂签名？直接用 `NoteSortStore(ApplicationProvider.getApplicationContext())`。

### Task 6: 收尾

- [ ] 全量 `.\gradlew :app:testDebugUnitTest` 通过；`assembleDebug` + `assembleRelease` 成功。
- [ ] 版本 1.3.0 → 1.4.0（`versionCode` 1_04_00）；README 版本行、产物名、测试数同步。
- [ ] `docs/backlog.md` #12 → 已完成；本计划补「修订记录」。
- [ ] 本地提交（中文 `feat:`，不 push）。

## 修订记录

| 日期 | 内容 |
|---|---|
| 2026-09-13 | 初稿：数据层 → VM → NoteRow → NotesScreen → 测试 → 收尾。 |
| 2026-09-13 | 执行完成（v1.4.0）。两处实现简化：① 计划中 `runBatch` 的 fallback 参数弃用，空选择直接 return 不回调（防呆由 `batchOpWithEmptySelectionDoesNotCallback` 覆盖）；② `selectAll` 读取 `notes.value`（界面订阅下必为当前值，测试中先 `notes.first{}` 订阅）。另补充 `NoteDaoTest.updateAllUpdatesBatch` 用例。单测 265 → 276 全绿。 |
