# MyNote Backlog 第一批实施计划（导入确认 + 快速体验优化）

> **For agentic workers:** 本计划配套设计文档 `docs/superpowers/specs/2026-09-13-backlog-first-batch-design.md`。并行轨道 A/B/C 由独立子代理执行，集成任务由主线程串行完成。步骤使用 `- [ ]` 跟踪。

**Goal:** 完成 backlog 条目 1、3–11（导入确认、撤销/重做、焦点恢复、时间刷新、分类名、文件名清洗、分享、大图预览、字数、排序），发布 1.2.0。

**Architecture:** 零冲突部分拆为三条并行轨道（纯逻辑新文件 / DAO-VM 层 / 组件层），全部落地后由主线程在 `NotesScreen` 与 `NoteEditScreen` 串行集成，最后全量单测 + 版本递增。

**Tech Stack:** Kotlin、Jetpack Compose Material3、Room(KSP)、Robolectric/JUnit4。无新依赖、无新权限。

---

## 轨道 A（子代理并行，新文件+测试，不改现有文件）

### Task A1: NoteUndoController（#3 逻辑）

**Files:**
- Create: `app/src/main/java/com/mynote/app/ui/notes/NoteUndoController.kt`
- Test: `app/src/test/java/com/mynote/app/ui/notes/NoteUndoControllerTest.kt`

- [ ] 实现 `NoteUndoController(maxEntries: Int = 100)`：`record(before: TextFieldValue)`、`undo(current): TextFieldValue?`、`redo(current): TextFieldValue?`、`canUndo/canRedo`、`reset()`。
- [ ] 语义：record 压 undo 栈并清空 redo 栈；undo 弹栈、把 current 压 redo；redo 对称；超 maxEntries 丢最旧；空栈返回 null；reset 清两栈。
- [ ] 测试覆盖：往返一致、新编辑清 redo、栈上限、reset、空栈 null、连续 undo 后 redo 顺序。
- [ ] 注意：`TextFieldValue` 来自 `androidx.compose.ui.text.input`，纯 JVM 可测；不动 `NoteEditScreen.kt`。

### Task A2: FileNameSanitizer（#7 逻辑）

**Files:**
- Create: `app/src/main/java/com/mynote/app/util/FileNameSanitizer.kt`
- Test: `app/src/test/java/com/mynote/app/util/FileNameSanitizerTest.kt`

- [ ] 实现 `FileNameSanitizer.sanitize(raw: String, fallback: String = "note"): String`：将 `\ / : * ? " < > |` 与控制符替换为 `_`，去首尾空白与 `.`/`_`，截断至 80 字符（按字符），空结果回退 fallback。
- [ ] 测试覆盖：各非法字符、首尾空白/点、超长截断、空串回退、纯非法字符回退、中文标题原样保留。
- [ ] 不动任何现有文件。

### Task A3: NoteImagePreviewDialog（#9 组件）

**Files:**
- Create: `app/src/main/java/com/mynote/app/ui/notes/NoteImagePreviewDialog.kt`

- [ ] 实现 `@Composable fun NoteImagePreviewDialog(file: File, onDismiss: () -> Unit)`：`Dialog`（`DialogProperties(usePlatformDefaultWidth = false)`）全屏黑色（固定 `Color.Black`），内容 `AsyncImage(model = file, contentDescription = "图片预览", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().clickable { onDismiss() })`；内嵌 `BackHandler { onDismiss() }`。
- [ ] import 参照 `NoteEditScreen.kt` 的 Coil 用法（`coil.compose.AsyncImage`、`androidx.compose.ui.window.Dialog`、`androidx.compose.ui.window.DialogProperties`）。
- [ ] 不动任何现有文件；无单测（纯 UI 组件）。

## 轨道 B（子代理，DAO/VM 层 #11，不含任何 UI 入口）

### Task B1: 排序数据层

**Files:**
- Modify: `app/src/main/java/com/mynote/app/data/db/NoteDao.kt`
- Modify: `app/src/main/java/com/mynote/app/data/repository/NoteRepository.kt`
- Create: `app/src/main/java/com/mynote/app/data/settings/NoteSortStore.kt`
- Modify: `app/src/main/java/com/mynote/app/ui/notes/NotesViewModel.kt`
- Modify: `app/src/main/java/com/mynote/app/di/AppContainer.kt`
- Modify: `app/src/main/java/com/mynote/app/ui/navigation/AppNavHost.kt`（仅工厂调用行）
- Test: `app/src/test/java/com/mynote/app/data/db/NoteDaoTest.kt`（增补排序用例）

- [ ] 在 `NoteDao.kt` 定义 `enum class NoteSortMode { UPDATED_DESC, CREATED_DESC, TITLE_ASC }` 与 `@Query` 方法 `observeAllBySort(mode)`：三分支 `ORDER BY pinned DESC, updatedAt DESC` / `pinned DESC, createdAt DESC` / `pinned DESC, title COLLATE NOCASE ASC`。Room 不支持动态 ORDER BY，用 `when(mode)` 选择三个独立 `@Query` 方法之一返回 `Flow<List<NoteEntity>>`，对外方法名统一为 `observeAllBySort(mode: NoteSortMode)`。
- [ ] `NoteRepository.observeNotes(sort: NoteSortMode) = noteDao.observeAllBySort(sort)`（保留原无参 `observeNotes()` 不动，避免破坏现有测试）。
- [ ] `NoteSortStore(context)`：仿 `ThemeSettingsStore`（SharedPreferences `note_sort_settings`，key `note_sort`，`StateFlow<NoteSortMode>` + `setMode(mode)`，默认 `UPDATED_DESC`，非法值回退默认）。
- [ ] `NotesViewModel`：构造加 `sortStore: NoteSortStore`；`val sortMode: StateFlow<NoteSortMode>`（由 store 的 StateFlow 直供）；`fun onSortSelect(mode)` → `sortStore.setMode(mode)`；`notes` 流的 flatMapLatest 分支改为 `else -> repository.observeNotes(sortMode.value)`（搜索/分类视图仍走原查询；排序只作用于「全部」）。工厂签名改为 `factory(repository, sortStore)`。
- [ ] `AppContainer`：`val noteSortStore: NoteSortStore by lazy { NoteSortStore(context) }`。
- [ ] `AppNavHost.kt` 仅改一行：`NotesViewModel.factory(container.noteRepository, container.noteSortStore)`。
- [ ] `NoteDaoTest` 增补：三种排序各自成立 + 每种排序下 pinned 优先（沿用现有测试的 `@Config(sdk=[34])` 与构造方式）。
- [ ] 不动 `NotesScreen.kt`、`NoteEditScreen.kt`、`NoteRow.kt`。

## 轨道 C（子代理，组件层 #6/#13 的 NoteRow 部分）

### Task C1: NoteRow 分类名 + 高亮

**Files:**
- Modify: `app/src/main/java/com/mynote/app/ui/components/NoteRow.kt`
- Create: `app/src/test/java/com/mynote/app/ui/components/HighlightTextTest.kt`（纯函数测试）

- [ ] `NoteRow` 增加可选参数：`categoryName: String? = null`、`highlightQuery: String? = null`（默认 null，现有调用零改动）。
- [ ] `categoryName` 非空时：日期 `Text` 下方追加一行 `labelSmall`、`onSurfaceVariant` 小字（`Text(categoryName, ..., modifier = Modifier.padding(top = 2.dp))`，位于行右侧时间列下、与右侧对齐）。
- [ ] 新增 `internal fun buildHighlighted(text: String, query: String): AnnotatedString`（同文件或同包新文件均可，需可被单测引用）：query 为空白返回纯文本；否则对 text 中所有忽略大小写的命中片段加 `SpanStyle(background = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))`；不修改文本本身。
- [ ] `highlightQuery` 非空时：标题与摘要 `Text` 改用 `buildHighlighted(..., query)`（`AnnotatedString` 直接传给 `Text`，仍保持 `maxLines/overflow`）。
- [ ] 测试：大小写不敏感命中、多命中、无命中返回原文本、空 query 返回原文本、AnnotatedString 的 text 与原串一致。
- [ ] 不动 `NotesScreen.kt` 等任何其他文件。

---

## 集成任务（主线程串行）

### Task D1: NotesScreen —— #1 导入确认 + #5 时间刷新

**Files:** Modify: `app/src/main/java/com/mynote/app/ui/notes/NotesScreen.kt`

- [ ] #1：`importLauncher` 回调改为只 `pendingImportUri = it`；新增 `var pendingImportUri by remember { mutableStateOf<Uri?>(null) }`；`pendingImportUri != null` 时弹 `PaperAlertDialog`（title「导入备份？」、text 说明三条合并规则：「备份较新的笔记覆盖本地，本地较新的保留，本地没有的将新增」；确认「导入」→ 执行 `backupManager.importZip(uri)` + Snackbar + 清空；「取消」清空）。
- [ ] #5：`now` 改为 `remember { mutableLongStateOf(System.currentTimeMillis()) }` + `LaunchedEffect(Unit) { while (true) { delay(60_000); now = ... } }`；`NoteRow(now = now.value)`。
- [ ] 全量单测通过。

### Task D2: NoteEditScreen —— #3 集成、#4、#7 接入、#8、#9 接入、#10

**Files:** Modify: `app/src/main/java/com/mynote/app/ui/notes/NoteEditScreen.kt`

- [ ] #3：`val undoController = remember { NoteUndoController() }`；正文 `onValueChange` 改为 `{ undoController.record(content); content = it }`；标题同理；回填 `initialized` 分支里 `undoController.reset()`；正文底部栏加撤销/重做 `IconButton`（`Icons.AutoMirrored.Filled.Undo` / `Redo`，`enabled = undoController.canUndo/canRedo`，onClick 用当前值调 undo/redo 并写回）。
- [ ] #4：正文 `BasicTextField` 挂 `focusRequester`；`pickImage` 回调 `vm.insertImage(...) { ...; focusRequester.requestFocus() }`。
- [ ] #7：导出文件名改 `FileNameSanitizer.sanitize(note?.title ?: "note") + ".txt"`。
- [ ] #8：溢出菜单「导出」后加「分享」项（`hasContent` 为启用条件）：`Intent(ACTION_SEND).apply { type = "text/plain"; putExtra(EXTRA_TEXT, title.ifBlank{"无标题"} + "\n\n" + NoteContentParser.plainText(content.text)) }` + `context.startActivity(Intent.createChooser(...))`；`LocalContext.current` 取 context。
- [ ] #9：预览 `AsyncImage` 加 `Modifier.clickable { previewFile = imageStore.physicalFile(block.name) }`；`var previewFile by remember { mutableStateOf<File?>(null) }`；非空时 `NoteImagePreviewDialog(previewFile!!, onDismiss = { previewFile = null })`。
- [ ] #10：底部栏「预览」右侧加 `Text("字数 ${NoteContentParser.plainText(content.text).length}")`（`labelSmall`、`onSurfaceVariant`），编辑/预览态都显示。
- [ ] 全量单测通过。

### Task D3: NotesScreen —— #6 传参 + #11 入口 + #13 传 query

**Files:** Modify: `app/src/main/java/com/mynote/app/ui/notes/NotesScreen.kt`

- [ ] #6：`NoteRow(..., categoryName = if (selectedCategoryId == null && query.isBlank()) categories.firstOrNull { it.id == note.categoryId }?.name else null)`。
- [ ] #11：溢出菜单「分类管理」后加「排序」三个 `DropdownMenuItem`（最近更新/最早创建/按标题），当前项 `text` 颜色用 `primary`；`onClick = { menuOpen = false; viewModel.onSortSelect(NoteSortMode.X) }`；`selectedCategoryId == null && query.isBlank()` 时可用，否则禁用（`enabled = false`）。
- [ ] #13：`NoteRow(..., highlightQuery = query.takeIf { query.isNotBlank() })`。
- [ ] 全量单测通过。

### Task D4: 收尾

- [ ] `app/build.gradle.kts`：`appVersionName = "1.2.0"`、`appVersionCode = 1_02_00`。
- [ ] `README.md`：版本行、`MyNote-1.2.0-release.apk`、测试数更新（以实际为准）。
- [ ] `docs/backlog.md`：条目 1、3–11 状态改「已完成」并填日期。
- [ ] 全量 `.\gradlew :app:testDebugUnitTest` 通过 + `.\gradlew :app:assembleDebug :app:assembleRelease` 成功。
- [ ] 提交（中文，`feat:` 前缀，不 push）。

## 修订记录

| 日期 | 内容 |
|---|---|
| 2026-09-13 | 初稿：三条并行轨道 + 四项集成任务。 |
| 2026-09-13 | 执行修订：① 撤销/重做只作用于正文，标题不纳入（标题行短、误删风险低，混用 String/TextFieldValue 栈收益低）；② 字数统计移到标题行右侧（底部栏 48dp 高度放不下撤销/重做 + 三个文字按钮 + 字数）；③ 条目 13（搜索高亮）随 #6 同批完成（同文件、同参数通道，拆开反而增加一次集成）；④ 导入确认对话框为纯 UI 行为，未新增 Robolectric UI 测试（与设计文档一致）。 |
