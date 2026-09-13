# 搜索返回行为与备份反馈 修复设计

- 日期：2026-09-13
- 状态：已实现（2026-09-13，224 个单测全绿 + assembleDebug 通过；真机 UI 手工验证待做）
- 关联：`docs/superpowers/specs/2026-09-13-edit-ux-fixes-design.md`（同批体验排查的中等项）

## 1. 背景与问题

体验排查（2026-09-13）遗留的两个中等体验问题：

1. **首页搜索时按返回键直接退出应用**：`NotesScreen` 没有 `BackHandler`。搜索激活时（`searchActive = true`），系统返回事件直接落到返回栈——首页是 startDestination，pop 即 finish Activity，整个应用退出。用户预期是退出搜索，而不是退出应用。
2. **备份导入/导出没有任何结果反馈**：`NotesScreen` 的 `exportLauncher` / `importLauncher` 回调里 `scope.launch { backupManager.exportZip(it) }`，返回值被丢弃，页面无 Snackbar。用户选完文件后不知道操作是否成功、导入了多少条；选中损坏文件（`decode` 抛异常）时更是毫无反应。

## 2. 目标与非目标

**目标**

- 搜索激活时按系统返回键：关闭搜索（清空关键词、隐藏键盘），停留在首页；与搜索框右侧「关闭」按钮行为完全一致。
- 导入/导出完成或失败后 Snackbar 反馈：成功显示条数，失败显示失败提示。

**非目标（本次不做）**

- AI 会话删除确认（中等项 #4）、分类行内重命名键盘体验（轻微项 #7）——按用户要求留待后续。
- 导入前确认、导入预览、备份文件校验 UI——超出本次范围。

## 3. 交互设计

### 3.1 搜索返回

- `BackHandler(enabled = searchActive)`：关闭搜索 = 清空关键词 + `searchActive = false` + 隐藏键盘（复用 X 按钮的同一逻辑，抽出小函数避免两处重复）。
- 键盘弹出时：第一次按返回由输入法消费（收起键盘），第二次触发 BackHandler 关闭搜索——标准 Android 行为，不做特殊处理。
- 搜索未激活时 BackHandler 不启用，返回行为不变（退出应用）。

### 3.2 备份反馈

- `NotesScreen` 的 `Scaffold` 增加 `snackbarHost`（与编辑页、历史页一致）。
- 导出成功：`已导出 N 条笔记`（N = `exportZip` 返回的笔记数）；失败（异常）：`导出失败`。
- 导入成功：`已导入 N 条笔记`（N = `importZip` 返回的导入条数）；失败（损坏 zip、非法 JSON 等）：`导入失败`。
- 实现用 `runCatching` 包裹 suspend 调用，`fold` 映射文案，失败场景吞掉异常细节（避免把堆栈级信息暴露给用户，也不做日志基建）。

## 4. 技术设计

`NotesScreen.kt`：

```kotlin
// 顶部
val snackbarHostState = remember { SnackbarHostState() }

// 关闭搜索（X 按钮与 BackHandler 共用）
fun closeSearch() {
    viewModel.onQueryChange("")
    searchActive = false
    keyboard?.hide()
}

BackHandler(enabled = searchActive) { closeSearch() }

Scaffold(
    snackbarHost = { SnackbarHost(snackbarHostState) },
    // ...其余不变
) { ... }

val exportLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument("application/zip")
) { uri ->
    uri?.let {
        scope.launch {
            val message = runCatching { backupManager.exportZip(it) }
                .fold(onSuccess = { "已导出 $it 条笔记" }, onFailure = { "导出失败" })
            snackbarHostState.showSnackbar(message)
        }
    }
}
val importLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument()
) { uri ->
    uri?.let {
        scope.launch {
            val message = runCatching { backupManager.importZip(it) }
                .fold(onSuccess = { "已导入 $it 条笔记" }, onFailure = { "导入失败" })
            snackbarHostState.showSnackbar(message)
        }
    }
}
```

- 仅改 `NotesScreen`，无数据层/导航/依赖变化。`importZip`/`exportZip` 均已是 `Dispatchers.IO` 的 suspend 函数，`rememberCoroutineScope` 下调用安全。
- import `androidx.activity.compose.BackHandler`、`androidx.compose.material3.SnackbarHost`、`androidx.compose.material3.SnackbarHostState`。

## 5. 边界与错误处理

| 场景 | 行为 |
|---|---|
| 搜索激活 + 键盘弹出按返回 | 第一次收键盘，第二次关闭搜索 |
| 搜索未激活按返回 | BackHandler 不启用，维持原行为（退出应用） |
| 导出时用户取消文件选择 | launcher 回调 `uri == null`，不提示（用户主动取消，非错误） |
| 导出/导入抛异常（损坏文件等） | Snackbar「导出失败/导入失败」，不崩溃 |
| 连续导入导出 | 各次 Snackbar 依次排队显示，无竞态（SnackbarHostState 自带队列） |

## 6. 测试策略

- 均为 UI 行为（BackHandler 拦截、Snackbar 文案），项目无 Compose UI 测试设施，不新增单测；`BackupManagerTest` 已覆盖导入/导出数据逻辑。
- 验证：全量单测回归 + `assembleDebug` + 真机手工清单（见计划 Task 3）。

## 7. 影响面

- 仅 `NotesScreen.kt` 一处文件；无数据层改动、无新依赖、不递增版本号（不发布 release）。
