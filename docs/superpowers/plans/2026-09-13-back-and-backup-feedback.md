# 搜索返回行为与备份反馈 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复两个中等体验问题：搜索激活时按返回键关闭搜索而非退出应用；备份导入/导出完成或失败后 Snackbar 反馈。

**Architecture:** 全部改动集中在 `NotesScreen.kt`：`BackHandler(enabled = searchActive)` + 抽出的 `closeSearch()`；`Scaffold` 增加 `SnackbarHost`，两个 launcher 回调用 `runCatching` 映射文案。

**Tech Stack:** Kotlin + Compose(Material3)；零新增依赖。

**Spec:** `docs/superpowers/specs/2026-09-13-back-and-backup-feedback-design.md`

**执行前提：** 当前工作目录即仓库 `master`（D:\desktop\myNote）；命令统一 `.\gradlew`（Windows PowerShell）。不创建分支。

---

### Task 1: 搜索返回键关闭搜索

**Files:**
- Modify: `app/src/main/java/com/mynote/app/ui/notes/NotesScreen.kt`

- [ ] **Step 1: 抽出 closeSearch 并接入 BackHandler**

1. import `androidx.activity.compose.BackHandler`。
2. 在 `searchActive`/`focusRequester`/`keyboard` 声明之后加：

```kotlin
fun closeSearch() {
    viewModel.onQueryChange("")
    searchActive = false
    keyboard?.hide()
}

BackHandler(enabled = searchActive) { closeSearch() }
```

3. X 按钮 onClick 改为 `closeSearch()`（去掉重复的三行）。

- [ ] **Step 2: 编译验证**（无独立单测；真机验证见 Task 3）。

### Task 2: 备份导入/导出 Snackbar 反馈

**Files:**
- Modify: `app/src/main/java/com/mynote/app/ui/notes/NotesScreen.kt`

- [ ] **Step 1: Scaffold 加 SnackbarHost**

import `androidx.compose.material3.SnackbarHost`、`androidx.compose.material3.SnackbarHostState`；`val snackbarHostState = remember { SnackbarHostState() }`；`Scaffold` 加 `snackbarHost = { SnackbarHost(snackbarHostState) }`。

- [ ] **Step 2: 两个 launcher 回调接入文案**

```kotlin
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

- [ ] **Step 3: 编译验证**。

### Task 3: 全量验证与收尾

- [ ] 运行 `.\gradlew :app:testDebugUnitTest` 全绿（预计 224 个，无新增/修改单测）。
- [ ] 运行 `.\gradlew :app:assembleDebug` 产出 APK。
- [ ] 真机手工验证清单：
  1. 首页点搜索输入关键词 → 按系统返回键两次（第一次收键盘）→ 回到列表且停留在应用内；搜索关键词已清空。
  2. 搜索未激活时按返回 → 应用退出（行为不变）。
  3. 导出备份：选好保存位置后出现「已导出 N 条笔记」。
  4. 导入备份：选有效 zip 后出现「已导入 N 条笔记」；选一个非 zip 文件（如照片）后出现「导入失败」，应用不崩溃。
  5. 导出对话框点取消 → 无任何 Snackbar（不打扰）。
- [ ] 按需递增版本号（仅发布 release 时），本次不主动 push。

## 修订记录

- 2026-09-13：初稿（跟随设计文档 `2026-09-13-back-and-backup-feedback-design.md`）。
