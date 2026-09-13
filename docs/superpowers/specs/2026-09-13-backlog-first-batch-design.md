# MyNote Backlog 第一批（导入确认 + 快速体验优化）设计文档

> 对应 `docs/backlog.md` 条目 1、3–11。条目 2（回收站）、12（多选）、13（高亮）另行单独出文档；14/15 待决策。

## 背景与目标

2026-09-13 全量体验复查产出了 15 条待办（见 `docs/backlog.md`）。本批先做「P0 数据安全 #1」与「P1 快速体验 #3–#11」共 10 条：全部为零新增三方依赖、零新增权限，目标版本 **1.2.0**（新增功能按惯例递增 minor）。

## 范围

| # | 条目 | 涉及现有文件 | 备注 |
|---|---|---|---|
| 1 | 导入备份前确认与策略说明 | `ui/notes/NotesScreen.kt` | 仅 UI 层 |
| 3 | 编辑正文撤销/重做 | 新增 `ui/notes/NoteUndoController.kt`；`NoteEditScreen.kt`（集成） | 逻辑与 UI 分离，逻辑可先行 |
| 4 | 插图后恢复输入焦点 | `NoteEditScreen.kt` | |
| 5 | 列表相对时间自动刷新 | `NotesScreen.kt` | |
| 6 | 列表行显示分类名 | `ui/components/NoteRow.kt`；`NotesScreen.kt`（传参） | 组件加可选参数 |
| 7 | txt 导出文件名清洗 | 新增 `util/FileNameSanitizer.kt`；`NoteEditScreen.kt`（接入） | 纯函数 |
| 8 | 系统分享（ACTION_SEND） | `NoteEditScreen.kt` | |
| 9 | 预览模式点开大图 | 新增 `ui/notes/NoteImagePreviewDialog.kt`；`NoteEditScreen.kt`（接入） | 组件可先行 |
| 10 | 编辑页字数统计 | `NoteEditScreen.kt` | |
| 11 | 列表排序选项 | `NoteDao.kt`、`NoteRepository.kt`、`NotesViewModel.kt`、新增 `data/settings/NoteSortStore.kt`、`AppContainer.kt`、`AppNavHost.kt`（工厂调用行）、`NotesScreen.kt`（入口） | DAO/VM 层可先行 |

## 逐条设计

### 1. 导入备份前确认与策略说明
- 现状：`NotesScreen.kt` 导入 launcher 回调直接执行 `backupManager.importZip(it)`，合并策略（备份较新覆盖本地、本地较新跳过、本地缺失新增）对用户不可见。
- 方案：回调里只暂存 `pendingImportUri: Uri?`，弹出 `PaperAlertDialog` 说明三条合并规则，点「导入」才执行；点「取消」清空。执行结果仍走既有 Snackbar。
- 导出不动。不做笔记数量预览（zip 解析需另读文件，收益低）。

### 3. 撤销/重做（NoteUndoController）
- 纯 Kotlin 类（仅依赖 `androidx.compose.ui.text.input.TextFieldValue`），与 UI 解耦以便单测。
- 接口（最终版）：
```kotlin
class NoteUndoController(private val maxEntries: Int = 100) {
    fun record(before: TextFieldValue)          // 编辑前快照入 undo 栈；新编辑清空 redo 栈
    fun undo(current: TextFieldValue): TextFieldValue?   // 弹出最近快照，current 压入 redo；空栈返回 null
    fun redo(current: TextFieldValue): TextFieldValue?   // 对称
    val canUndo: Boolean
    val canRedo: Boolean
    fun reset()                                  // 换笔记/回填时清空
}
```
- 语义：`record` 在 `onValueChange` 前用旧值调用；undo/redo 用当前值压栈后返回目标值；栈上限 100，超出丢最旧。连续输入每帧各记一条（100 条上限兜底内存，不做防抖合并——简单优先，YAGNI）。
- 编辑页集成：正文 `BasicTextField.onValueChange` 前 `record(content)`；顶栏或底部栏加撤销/重做 `IconButton`（`Icons.AutoMirrored.Filled.Undo/Redo`），`enabled = canUndo/canRedo`；`initialized` 回填后 `reset()`；进程重建后栈空（正文由 `rememberSaveable` 兜底，可接受）。

### 4. 插图后焦点
- 现状：`pickImage` 回调插入标记后焦点丢失、键盘收起（`NoteEditScreen.kt:264-268`）。
- 方案：正文 `BasicTextField` 挂 `FocusRequester`；回调中 `insertImage` 完成后 `focusRequester.requestFocus()`（键盘随焦点弹出）。`AiResultApplier.INSERT` 已把光标定位到插入点之后，无需改逻辑。

### 5. 相对时间刷新
- 方案：`NotesScreen` 中 `var now by remember { mutableLongStateOf(System.currentTimeMillis()) }`，`LaunchedEffect(Unit)` 内 `while (true) { delay(60_000); now = System.currentTimeMillis() }`。仅影响 `NoteRow(now = now)`。

### 6. 列表行分类名
- 方案：`NoteRow` 增加 `categoryName: String? = null`、`highlightQuery: String? = null` 两个可选参数（默认 null，现有调用不受影响）。`categoryName` 非空时在时间下方追加一行 `labelSmall` 小字；`highlightQuery` 非空时标题与摘要用 `buildHighlighted()`（见 #13）着色。
- 列表页：仅在「全部」tab（`selectedCategoryId == null`）且非搜索态传 `categories.firstOrNull{...}?.name`，避免与顶部 tab 重复。

### 7. 文件名清洗
```kotlin
object FileNameSanitizer {
    /** 替换 SAF 文件名非法字符（\ / : * ? " < > | 与控制符），去首尾空白与点，超 80 字符截断，空则回退 fallback。 */
    fun sanitize(raw: String, fallback: String = "note"): String
}
```
- 接入：`NoteEditScreen.kt:574` 的 `exportTxtLauncher.launch(...)` 文件名改为 `FileNameSanitizer.sanitize(note?.title ?: "note") + ".txt"`。

### 8. 系统分享
- 编辑页溢出菜单（`PaperOverflowMenu`）新增「分享」项（放在「导出」之后）：`ACTION_SEND` + `text/plain`，`EXTRA_TEXT = 标题\n\n正文`（正文为纯文本时直接原文；含图片标记时用 `NoteContentParser.plainText` 口径与列表一致）。空笔记（标题与正文皆空）禁用该项。
- 启动用 `context.startActivity(Intent.createChooser(...))`，无权限、无依赖。

### 9. 预览大图
```kotlin
@Composable
fun NoteImagePreviewDialog(file: File, onDismiss: () -> Unit)
```
- 全屏 `Dialog`：黑背景（固定 `Color.Black`，与深色模式无关），`AsyncImage` `contentScale = ContentScale.Fit`，点击任意处 / 系统返回（内嵌 `BackHandler`）关闭。
- 接入：预览模式里 `AsyncImage` 加 `Modifier.clickable { previewFile = imageStore.physicalFile(block.name) }`；`previewFile` 非空时弹 Dialog。复用 Coil，零依赖。

### 10. 字数统计
- 底部栏（图片/分类/预览 行）增加右侧「字数 N」小字：`N = NoteContentParser.plainText(content.text).length`（图片标记不计入，与列表摘要口径一致）。编辑态与预览态都显示。

### 11. 排序选项
```kotlin
enum class NoteSortMode { UPDATED_DESC, CREATED_DESC, TITLE_ASC }
```
- `NoteDao` 新增 `observeAllBySort(mode)`（`@Query` 三选一：`ORDER BY pinned DESC, updatedAt DESC` / `pinned DESC, createdAt DESC` / `pinned DESC, title COLLATE NOCASE ASC`）；`NoteRepository.observeNotes(sort)`；`NotesViewModel` 暴露 `sortMode: StateFlow<NoteSortMode>` + `onSortSelect(mode)`。
- 持久化：新增 `data/settings/NoteSortStore`（SharedPreferences + StateFlow，模式仿 `ThemeSettingsStore`，key `note_sort`，默认 `UPDATED_DESC`），注入 `AppContainer`（`by lazy`），`AppNavHost` 工厂调用行同步。
- 列表页入口：溢出菜单加「排序」子项组（`DropdownMenuItem` ×3，当前项打勾/高亮），位于「分类管理」之后；搜索态不显示排序项（搜索本身有排序语义）。

## 并行轨道与冲突规避

- 轨道 A（新文件+纯逻辑，先行）：#3 控制器、#7 清洗函数、#9 大图组件 → 子代理并行，**不改任何现有文件**。
- 轨道 B（DAO/VM 层）：#11 → 子代理，独占 `NoteDao/NoteRepository/NotesViewModel/AppContainer/AppNavHost` + 新增 `NoteSortStore` + DAO 测试。
- 轨道 C（组件层）：#6/#13 的 `NoteRow` 部分 → 子代理，独占 `NoteRow.kt`。
- 集成（主线程串行）：`NotesScreen`（#1 → #5 → #6 传参 → #11 入口）与 `NoteEditScreen`（#3 集成 → #4 → #7 接入 → #8 → #9 接入 → #10）互不交叉，可在 A/B/C 落地后统一集成，避免同文件并发编辑。

## 测试策略

- #3：`NoteUndoControllerTest`（纯 JUnit：undo/redo 往返、新编辑清 redo、栈上限、reset、空栈返回 null）。
- #7：`FileNameSanitizerTest`（非法字符、首尾空白/点、超长截断、空回退、中文标题）。
- #11：`NoteDaoTest` 增补三种排序用例（置顶优先在各排序下成立）。
- #6/#13：`buildHighlighted` 纯函数测试（命中范围、大小写、多命中、空 query）。
- #1/#4/#5/#8/#9/#10：纯 UI 行为，不新增 Robolectric UI 测试，随全量单测 + 真机清单验证。

## 验收（真机清单）

1. 导入备份必先确认，取消零写入，确认后 Snackbar 报导入数量。
2. 正文连续输入/删除/插图/贴 AI 结果可撤销与重做；切换笔记后栈重置。
3. 插图后键盘弹出、光标在插入点后。
4. 列表停留 1 分钟相对时间自动更新。
5. 「全部」tab 列表行显示分类名，其他 tab 不显示。
6. 标题含 `/ :` 等字符导出 txt 成功。
7. 分享面板唤起、内容完整；空笔记分享禁用。
8. 预览图点开全屏、点击/返回关闭。
9. 字数实时更新且与列表摘要口径一致。
10. 三种排序即时生效、重启保留；搜索态不显示排序项。

## 版本

1.1.2 → **1.2.0**（新增功能 minor；`versionCode` 1_01_02 → 1_02_00），README 版本行、产物名、测试数同步。
