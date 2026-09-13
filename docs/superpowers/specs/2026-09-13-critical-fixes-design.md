# 三个严重缺陷修复（分类重命名崩溃 / 连点重复保存 / 旋转后内容复活）— 设计文档

- 日期：2026-09-13
- 状态：已实现（2026-09-13，224 个单测全绿 + assembleDebug 通过；真机 UI 手工验证待做）
- 关联：`docs/superpowers/specs/2026-09-13-edit-ux-fixes-design.md`（同批体验排查的严重项）、`docs/superpowers/specs/2026-09-10-edit-page-add-category-design.md`（分类幂等行为）

## 1. 背景与问题

对编辑页体验排查（2026-09-13）发现三个严重缺陷，按危害排序：

1. **分类重命名撞名 → 应用崩溃**：`categories.name` 有唯一索引（`CategoryEntity.kt:7`）。新建分类在 `NoteRepository.addCategory` 有 `getByName` 查重（幂等），但重命名路径 `CategoriesViewModel.rename` → `NoteRepository.renameCategory` 直接 `categoryDao.update(...)`，无查重。把分类 A 重命名为已存在的分类名 → SQLite 唯一约束违反 → `SQLiteConstraintException` 在协程内未捕获 → 崩溃。
2. **编辑页「保存」连点 → 重复笔记 + 双重退出**：`saveAndExit`（`NoteEditScreen.kt`）无防重入。`vm.save` 异步，第一次插入尚未完成时第二次点击仍以 `noteId=null` 走 insert 分支 → 两条完全相同的笔记；两次 `onBack()` 连续弹出两层返回栈（编辑页 + 列表页），可能直接退出应用。同类问题：首页 FAB / 笔记行连点会叠两层编辑页；编辑页「删除」确认框连点也会触发两次 `onBack`。
3. **清空内容后旋转屏幕 → 刚删的内容复活**：`NoteEditScreen` 的 `LaunchedEffect(note)` 用「标题与正文是否为空」判断是否已初始化（`NoteEditScreen.kt:219`）。用户把已有笔记的标题和正文全部删光后旋转屏幕，`rememberSaveable` 恢复的是"空"值，VM 重建后 note 重新加载，条件成立 → 把已保存的旧内容覆盖回输入框，用户以为删干净的内容又出现。

## 2. 目标与非目标

**目标**

- 重命名撞名不再崩溃：撞名时保留原分类名并提示「该分类已存在」；重命名为全新名成功。
- 保存/删除防重入：数据层保证一次保存只产生一条记录、一次删除只回调一次退出；UI 保存期间禁用按钮；导航侧 FAB/列表行连点不叠层。
- 编辑页初始化判定独立于内容是否为空：清空内容后旋转/进程重建不再被旧内容覆盖。

**非目标（本次不做）**

- 其余已报告的体验项（AI 会话删除确认、搜索返回退出、备份无反馈、分类重命名键盘体验）按用户要求留待后续。
- 自动保存、乐观并发等更大范围的编辑一致性改造。

## 3. 技术设计

### 3.1 重命名查重（Repository + ViewModel + UI）

- `NoteRepository.renameCategory` 改为返回 `Boolean`：

```kotlin
suspend fun renameCategory(category: CategoryEntity, newName: String): Boolean {
    val trimmed = newName.trim()
    val existing = categoryDao.getByName(trimmed)
    return if (existing != null && existing.id != category.id) {
        false // 撞名：不更新，交由上层提示
    } else {
        categoryDao.update(category.copy(name = trimmed))
        true
    }
}
```

- 重命名为自身原名：`existing.id == category.id`，允许（等价于 trim 后原地更新），返回 true。
- 说明：唯一索引为大小写敏感精确匹配，`getByName` 与之同语义，故撞名检测按精确匹配即可。
- `CategoriesViewModel.rename` 改为回调式：

```kotlin
fun rename(category: CategoryEntity, newName: String, onDone: (Boolean) -> Unit) {
    if (newName.isBlank()) return
    viewModelScope.launch { onDone(repository.renameCategory(category, newName)) }
}
```

- UI（`CategoriesScreen`）：`Scaffold` 增加 `SnackbarHost`；`CategoryRow` 的保存回调：
  - 名称空白 → 直接退出编辑态（沿用旧行为：VM 忽略、行回到原名）。
  - 成功（true）→ 退出编辑态。
  - 失败（false）→ 保持编辑态（用户输入还在），Snackbar 提示「该分类已存在」。

### 3.2 防重入（ViewModel 数据层 + UI 禁用 + 导航 singleTop）

- `NoteEditViewModel` 增加在途标志：

```kotlin
private var saving = false
private var deleting = false

fun save(...) {
    if (saving) return
    saving = true
    viewModelScope.launch {
        try {
            // 原有保存逻辑不变
        } finally {
            saving = false
        }
    }
}

fun delete(onDone: () -> Unit) {
    if (deleting) return
    val n = _note.value ?: return
    deleting = true
    viewModelScope.launch {
        try { repository.deleteNote(n); onDone() }
        finally { deleting = false }
    }
}
```

- 数据层保证：连点第二次 `save` 直接 return（不插入第二条、不回调第二次 `onBack`），从根上消除重复笔记与双重弹栈。
- UI 反馈（`NoteEditScreen`）：`isSaving` 状态，保存按钮与「保存并退出」按钮 `enabled = !isSaving`；`saveAndExit` 入口同时判 `isSaving`（UI 层第一道闸，VM 层第二道闸）。
- 导航（`AppNavHost`）：`onOpenNote`、`onNewNote` 的 `navigate` 加 `launchSingleTop = true`，FAB/列表行连点不再叠两层编辑页。仅影响"栈顶重复目标"，不影响正常返回栈行为。

### 3.3 编辑页初始化标志（旋转复活修复）

- 新增 `var initialized by rememberSaveable(noteId) { mutableStateOf(false) }`。
- 回填逻辑改为：

```kotlin
LaunchedEffect(note) {
    val n = note ?: return@LaunchedEffect
    if (!initialized) {
        initialized = true
        if (title.isEmpty() && content.text.isEmpty()) {
            title = n.title
            content = TextFieldValue(n.content)
            selectedCategoryId = n.categoryId
            pinned = n.pinned
        }
    }
}
```

- 语义：仅第一次拿到已保存笔记时回填，且仍保留"未输入才回填"的防打字竞态保护；此后（含旋转/进程重建后 `initialized=true` 恢复）note 重新加载**不再回填**，用户清空的内容保持为空。
- 新建笔记 `note` 恒为 null，不受影响。

## 4. 边界与错误处理

| 场景 | 行为 |
|---|---|
| 重命名撞名 | 不更新，Snackbar「该分类已存在」，保持编辑态 |
| 重命名为自身原名 | 允许（trim 后原地更新） |
| 重命名空白 | UI 退出编辑态、VM 忽略（沿用旧行为） |
| 保存连点 | 第二次 `save` 被忽略；仅插入一条、仅退出一次 |
| 删除确认连点 | 第二次 `delete` 被忽略；仅回调一次 `onBack` |
| FAB / 列表行连点 | `launchSingleTop` 保证栈顶只有一个同目标实例 |
| 清空内容后旋转 | `initialized=true` 已保存恢复，不再回填旧内容 |
| 打开笔记立即打字（note 未加载完） | 回填条件含 `title.isEmpty() && content.text.isEmpty()`，不覆盖用户输入 |

## 5. 测试策略

- `NoteRepositoryTest` 新增：
  - 重命名为全新名 → 返回 true，库中名称更新。
  - 重命名为已存在分类名 → 返回 false，原分类名不变、库中无重复。
  - 重命名为自身原名 → 返回 true。
- 新增 `CategoriesViewModelTest`（Robolectric + 内存 Room + `Dispatchers.setMain(StandardTestDispatcher())`，仿 `NoteEditViewModelTest`）：
  - 空白名不回调。
  - 撞名回调 false。
  - 成功回调 true。
- `NoteEditViewModelTest` 新增：
  - 连续两次 `save`（新建笔记）→ 库中仅 1 条笔记、`onDone` 仅回调 1 次。
  - 连续两次 `delete` → `onDone` 仅回调 1 次。
- 旋转复活与导航 singleTop 属 UI 行为，无 Compose UI 测试设施，靠真机手工验证（见计划）。

## 6. 影响面

- `renameCategory` 返回类型变化：仅 `CategoriesViewModel` 一处调用，同步修改。
- `NoteEditViewModel.save/delete` 增加在途标志：现有调用点（`NoteEditScreen`）回调语义不变。
- 不涉及数据库结构/迁移，不新增依赖，不递增版本号（不发布 release）。
