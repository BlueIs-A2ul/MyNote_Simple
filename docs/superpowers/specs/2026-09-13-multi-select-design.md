# MyNote 多选批量操作设计文档

> 对应 `docs/backlog.md` 条目 12。目标版本 **1.4.0**（新功能，minor 递增）。

## 背景与目标

列表页目前只能逐条操作（点击进入编辑）。本设计引入多选模式：长按列表行进入，支持全选、批量置顶/取消置顶、批量改分类、批量删除（移入回收站，沿用 #2 软删除语义）。

## 交互设计

- **进入**：长按任意列表行（搜索态也可）进入多选模式，该行自动选中；顶栏变为「已选 N 项」，返回键变为退出多选（`BackHandler` 同样拦截，先于搜索返回逻辑）。
- **选中/取消**：多选模式下点击行切换选中；选中行以 `surfaceVariant` 底色高亮。
- **顶栏**：`PaperTopBar(title = "已选 N 项", onBack = 退出多选)`，actions 为溢出菜单：全选、置顶、取消置顶、移动到分类、删除（error 色）。
- **批量删除**：`PaperAlertDialog` 确认（「将把所选 N 条笔记移入回收站…」），执行后退出多选 + Snackbar 反馈。
- **批量改分类**：底部弹出分类选择 `ModalBottomSheet`（未分类 + 现有分类，样式与编辑页分类面板一致），选择后执行并退出多选。
- **批量置顶/取消置顶**：直接执行（幂等），保留多选模式并 Snackbar 反馈。
- **FAB**：多选模式下隐藏；搜索与多选可共存（搜索过滤后再多选是合理组合），返回键优先级：搜索收起 > 退出多选。
- **选择状态**：放在 `NotesViewModel`（`StateFlow`，旋转/重建不丢；进程死亡后清空可接受）。

## 数据层

- `NoteDao` 新增：
  - `@Query("SELECT * FROM notes WHERE id IN (:ids)") suspend fun getByIds(ids: List<Long>): List<NoteEntity>`（无 deletedAt 过滤，批量操作只对可见笔记调用）。
  - `@Update suspend fun updateAll(notes: List<NoteEntity>)`（Room 支持 List 批量更新）。
- `NoteRepository` 新增：
  - `suspend fun getNotesByIds(ids: List<Long>)`
  - `suspend fun deleteNotes(notes: List<NoteEntity>)`（软删除批量：copy(deletedAt = now) + updateAll，不触发 GC）
  - `suspend fun moveNotesToCategory(notes: List<NoteEntity>, categoryId: Long?)`（updateAll）
  - `suspend fun setNotesPinned(notes: List<NoteEntity>, pinned: Boolean)`（updateAll）

## ViewModel（NotesViewModel 扩展）

```kotlin
val selectionMode = MutableStateFlow(false)
val selectedIds = MutableStateFlow<Set<Long>>(emptySet())
fun enterSelection(noteId: Long)      // mode=true 且选中该行
fun toggleSelect(noteId: Long)
fun selectAll()                        // 选中当前 notes 全部
fun exitSelection()                    // mode=false 且清空选择
fun batchDelete(onDone: (Int) -> Unit)
fun batchSetCategory(categoryId: Long?, onDone: (Int) -> Unit)
fun batchSetPinned(pinned: Boolean, onDone: (Int) -> Unit)
```

批量方法读取 `selectedIds` → `repository.getNotesByIds` → 执行 → `exitSelection()` → 回调数量。

## 组件（NoteRow 扩展）

- 新增可选参数：`selected: Boolean = false`、`onLongClick: (() -> Unit)? = null`（默认 null 时行为与现在完全一致）。
- `selected` 为 true 时行背景 `MaterialTheme.colorScheme.surfaceVariant`。
- `onLongClick` 非空时用 `combinedClickable(onClick, onLongClick)` 替换 `clickable`（`@OptIn(ExperimentalFoundationApi::class)`）。

## 涉及文件

- 改：`data/db/NoteDao.kt`、`data/repository/NoteRepository.kt`、`ui/notes/NotesViewModel.kt`、`ui/components/NoteRow.kt`、`ui/notes/NotesScreen.kt`。
- 测试：`NoteDaoTest`（getByIds）、新增 `NotesViewModelTest`（选择状态 + 三个批量操作，CompletableDeferred 模式）。

## 测试要点

1. DAO：getByIds 只返回给定 id 集合的行。
2. VM：enterSelection/toggle/selectAll/exit 状态迁移正确；batchDelete 软删除所选且回调数量、退出选择；batchSetCategory（含 null=未分类）与 batchSetPinned 生效；空选择时批量操作不回调（防呆）。
3. UI 为纯组合逻辑，随全量单测 + 真机清单验证。

## 版本

1.3.0 → **1.4.0**（`versionCode` 1_04_00），README 版本行、产物名、测试数同步；`docs/backlog.md` #12 置为已完成。
