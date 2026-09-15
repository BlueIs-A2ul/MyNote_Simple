# P0 批次设计：导入/导出可靠性 + 草稿兜底 + 多选/历史修正（1.5.1）

> 日期：2026-09-15｜范围：backlog 条目 17、18、19、20、24、43、44（全部 P0）｜版本：patch → 1.6.1
> 来源：2026-09-14 六域体验审计（`docs/backlog.md` 批次 A/B/D 中的 P0 条目）。
> 已确认产品决策：#24 采用「ON_STOP 静默保存」（存量笔记只更新数据行不写历史；新笔记标题/正文非空才静默插入；空白新笔记禁用保存）。
> 状态：已实现（2026-09-15，v1.6.1；全量单测 376 绿）。

## 条目 17 · 导出防「假成功」（zip/txt 静默失败）

- **现状**：`BackupManager.exportZip` 在 `openOutputStream` 返回 null 时一行未写仍返回条数（UI 报「已导出 N 条」）；`exportNoteAsTxt` 同样返回 true；调用方丢弃返回值。
- **方案**：
  - `exportZip(uri): Int`：`openOutputStream(uri) ?: error("无法写入所选文件")`，异常沿 UI 既有 `runCatching` 走「导出失败」。
  - `exportNoteAsTxt(uri, title: String, content: String): Boolean`：null 流返回 false；标题非空时写入「标题 + 空行 + 正文」（纯正文否则）。
  - `NotesScreen` zip 导出加 `exporting` 状态：导出在途时导出菜单项 `enabled = false`，完成/失败后恢复；导入同理加 `importing`，确认按钮在途禁用，防重复提交。
- **测试**：`BackupManagerTest` 用可注入流（见条目 43 的构造参数约定）断言 zip 导出 null 流抛错、txt null 流返回 false、txt 含标题。

## 条目 18 · txt 导出当前编辑内容并补反馈

- **现状**：`NoteEditScreen` 导出的是 Room 已保存实体（未保存修改不导出）；返回值被丢弃；文件名用已保存标题。
- **方案**：
  - 调用改为编辑态现值：`exportNoteAsTxt(uri, title, content.text)`。
  - 结果 snackbar：「已导出」/「导出失败」；导出在途禁用导出入口（对话框内 txt 项 + 溢出菜单入口，`exporting` 状态）。
  - 导出对话框 txt 项 `enabled = 标题或正文非空`（未保存的新笔记也能导出）；文件名 `FileNameSanitizer.sanitize(title.ifBlank { "note" }) + ".txt"`；移除「保存后可导出 txt」提示。
- **测试**：纯 UI 接线（项目无 Compose UI 测试约定），补 BackupManager 侧 txt 用例（条目 17）。

## 条目 19 · 历史恢复不静默丢未保存草稿 + 恢复成功反馈

- **现状**：`AppNavHost` 恢复成功后直接 `popBackStack("notes")`，绕过编辑页 dirty 拦截，草稿无声丢失；成功也无提示。
- **方案**（沿用 AI 结果的 savedStateHandle 通道先例）：
  - 编辑页：`onOpenHistory: (dirty: Boolean) -> Unit`，调用点传当前 `dirty`。
  - 路由：`note_history/{noteId}?dirty={dirty}`（`NavType.BoolType`，默认 false），历史页新增参数 `hadUnsavedDraft`；恢复确认框文案在 dirty 时追加「编辑页未保存的修改将一并丢弃」。
  - 恢复成功：`AppNavHost.onRestored` 先向 `navController.getBackStackEntry("notes").savedStateHandle` 写 `NavResults.RESTORE_MESSAGE = "已恢复历史版本"`，再 pop 回 notes；`NotesScreen` 新增 `restoreMessage: String?` + `onRestoreMessageConsumed`，非空弹 snackbar 后消费。
- **测试**：无 nav/Compose 测试约定，不加自动化测试；`NoteHistoryViewModelTest` 既有用例不受影响。

## 条目 20 · 多选跨 tab 残留与空选死按钮

- **现状**：多选态下分类 tab/搜索仍可点且不清选择集；取消最后一个选中不退出多选；批量菜单无 enabled 守卫，空选可弹「0 条」确认。
- **方案**（`NotesViewModel` + `NotesScreen`）：
  - `onFilterSelect` 内先 `exitSelection()`；进入搜索（`searchActive = true`）与 `closeSearch()` 调 `exitSelection()`。
  - `toggleSelect` 移除最后一项后自动 `selectionMode = false`。
  - `selectAll()` 仅当前可见列表，空列表直接返回。
  - 批量菜单各项 `enabled = selectedIds.isNotEmpty()`；批量删除确认按钮同样守卫（防御性）。
- **测试**：`NotesViewModelTest` 补：切筛选清选择、取消最后一个退出多选、空列表全选不炸、空选批量无回调（已有）。

## 条目 24 · 编辑页未保存草稿兜底（ON_STOP 静默保存）

- **现状**：唯一落盘入口 `saveAndExit` 且保存即退出；正文只在 `rememberSaveable`，退后台被系统杀进程即全丢；空白新笔记可保存出空行。
- **方案**：
  - `NoteRepository.updateDraft(id, title, content, categoryId, pinned, color)`：只更新 notes 行（刷新 `updatedAt`），**不写 revision、不跑图片 GC**。
  - `NoteEditViewModel` 新增 `draftId: StateFlow<Long?>`（新笔记静默入库后的 id，并把 `observeNote(id)` 接入 `_note`）与 `saveDraft(...)`：单飞（复用 `saving` 守卫）；已有笔记（nav id 或 draftId）→ `updateDraft`；新笔记且标题/正文均空 → 不动作；新笔记非空 → `saveNote(null, ...)` 插入并记录 `draftId`。
  - `save()` 目标 id 改为 `noteId ?: draftId`（静默入库后再保存只更新、不重复插入）；历史预警判定沿用目标 id。
  - 编辑页：
    - `isNewNote` 改为「nav id 为空且 draftId 为空」；`dirty` 在静默入库后按已保存值比较（回填与草稿内容一致 → 不再弹未保存对话框）。
    - `hasUnsavedChanges`：新建分支改为「标题/正文非空才把分类/置顶计入脏」（避免空笔记仅切置顶就产生脏）。
    - `LifecycleEventEffect(ON_STOP)`：`dirty && !isSaving` 时调 `saveDraft(...)`。
    - 「保存」与「保存并退出」按钮在「新建且标题/正文皆空」时禁用（不再插入空笔记）。
  - 历史入口/AI 入口可见性维持原判据（`noteId != null`），本次不扩散。
- **测试**：`NoteRepositoryTest` 补「updateDraft 更新字段且不增 revision」；`NoteEditViewModelTest` 补 saveDraft 三分支（存量更新、新笔记非空插入并暴露 draftId、空白不动作）与「draft 后 save 不重复插入」；`HasUnsavedChangesTest` 适配新建分支语义。

## 条目 43 · 导入事务 + 条目名校验 + 流式写盘

- **现状**：`importZip` 无事务（半导入）；zip 条目名 `img/../../x` 可越目录；图片全量 `readBytes()` 进内存。
- **方案**：
  - 可注入流：`BackupManager` 构造参数新增 `openOutput: (Uri) -> OutputStream?`、`openInput: (Uri) -> InputStream?`，默认取 `context.contentResolver`（保持调用点不变；测试传 ByteArray 流，不依赖 ShadowContentResolver）。
  - zip 条目白名单 `^[A-Za-z0-9._-]+$`（含 `img/` 前缀剥离后的名字）；非法条目 / 非 zip / 缺 `notes.json` → 抛 `BackupFormatException("不是有效的备份文件")`；读取 IO 异常原样上抛（UI 报「导入失败」）。
  - 图片写入改流式：`zip.getInputStream(entry).copyTo(FileOutputStream(imageStore.physicalFile(name)))`（`ImageStore` 增 `writeFile(name, input: InputStream)` 或等价物）；先写图片后写库（失败留孤儿文件由既有 GC 兜底）。
  - 所有分类 + 笔记写入包 `database.withTransaction {}`。
  - 分类名唯一索引冲突处理：按 id 未命中时按 `name` 查重，命中则复用其 id（颜色以备份为准更新），并把备份 id → 现存 id 记入 `idRemap`；笔记 `categoryId` 经 `idRemap` 映射，映射后不在库中的野 id 置 null。
- **测试**：`BackupManagerTest`（注入内存 Room + 内存流）补：导入为单事务（构造一条非法笔记使事务回滚，断言库无写入——或等价断言）、路径穿越条目被拒、流式导入（图字节写入且内容一致）、分类名冲突不抛约束异常且笔记指向复用分类。

## 条目 44 · 导入结果真实汇报

- **现状**：无论新增/更新/跳过一律「已导入 N 条」；带 `deletedAt` 的条目落回收站无说明。
- **方案**：
  - `importZip(uri): BackupImportResult`，`data class BackupImportResult(inserted: Int, updated: Int, skipped: Int, trashed: Int)`（`trashed` 为导入到回收站的条数，不计入前两项；`incomingWins` 语义不变）。
  - `NotesScreen.runImport` 文案：「导入完成：新增 X · 更新 Y · 跳过 Z」，`trashed > 0` 追加「· 回收站 W」；`BackupFormatException` → 「不是有效的备份文件」。
  - 导入确认框补说明：分类名称与颜色以备份为准；备份中在回收站的条目会导入回收站。
- **测试**：`BackupManagerTest` 断言插入/更新/跳过/回收站四类计数。

## 接口冻结（供并行实现对齐）

```kotlin
// BackupManager.kt
class BackupManager(
    context: Context,
    imageStore: ImageStore,
    database: AppDatabase? = null,
    openOutput: (Uri) -> OutputStream? = { context.contentResolver.openOutputStream(it) },
    openInput: (Uri) -> InputStream? = { context.contentResolver.openInputStream(it) },
)
class BackupFormatException(message: String) : Exception(message)
data class BackupImportResult(val inserted: Int, val updated: Int, val skipped: Int, val trashed: Int)

suspend fun exportZip(uri: Uri): Int // null 流抛错
suspend fun exportNoteAsTxt(uri: Uri, title: String, content: String): Boolean // null 流 false
suspend fun importZip(uri: Uri): BackupImportResult // 事务 + 白名单 + 流式

// AppNavHost.kt
object NavResults { const val RESTORE_MESSAGE = "restore_message" }

// NotesScreen.kt
fun NotesScreen(
    viewModel, backupManager,
    restoreMessage: String?,
    onRestoreMessageConsumed: () -> Unit,
    onOpenNote, onNewNote, onManageCategories, onOpenTrash, onOpenSettings,
)

// NoteEditScreen.kt
onOpenHistory: (Boolean) -> Unit // dirty

// NoteEditViewModel.kt
val draftId: StateFlow<Long?>
fun saveDraft(title: String, content: String, categoryId: Long?, pinned: Boolean, color: Int?)

// NoteRepository.kt
suspend fun updateDraft(id: Long, title: String, content: String, categoryId: Long?, pinned: Boolean, color: Int?)
```

## 收尾

全量单测通过 → 1.6.1 / 1_06_01 → README（版本、测试数、产物名）、backlog 条目状态与修订记录、AGENTS.md（测试数与草稿兜底约定）同步 → 本地提交（不 push）。
