# 批次 F/G/H 快修（条目 77 / 82 / 83 / 89 / 91）— 实施计划

- 日期：2026-11-08
- 分支：`bugfix-safe-1108`（worktree `.worktrees/bugfix-safe-1108`）
- 设计：`docs/superpowers/specs/2026-11-08-backlog-fgh-quick-fixes-design.md`

## 步骤

1. **写失败测试**（TDD 红灯）：在 `NoteRepositoryTest`、`AiChatViewModelTest`、`NotesViewModelTest`、`AiApiSessionTest` 中先加 7 个用例，运行确认「失败原因是缺功能」而不是编译错误。
2. **条目 89**：`NoteDao` 三处 `LIKE` 补 `ESCAPE '\\'`；`NoteRepository` 新增 `escapeLikePattern` 并在两个 `search` 重载里使用；`NotesScreen` 高亮改用 `query.trim()`。
3. **条目 91**：`NotesViewModel.onQueryChange` 值变化时 `exitSelection()`；`notes` 流水线加 `onEach { pruneSelection(it) }`。
4. **条目 82**：`AiChatViewModel` 看门狗超时分支补 `session.stop()`；`AiApiSession.send()` 在旧 job 活跃时先 `streamer.cancel()`。
5. **条目 83**：`AiChatViewModel` 的 `Done` 分支判空，抽出 `finishWithoutMessage(reason, settingsHint)` 与 `EMPTY_ANSWER_REASON`，`Failed` 空分支复用同一收尾函数。
6. **条目 77**：`SettingsScreen.apiKeyInput` 由 `rememberSaveable` 改 `remember`（附注释说明为何不进 Bundle）。
7. **全量回归**：`testDebugUnitTest` 全绿 + `assembleDebug` 通过；更新 `docs/backlog.md` 状态与修订记录。

## 验证记录

| 步骤 | 结果 |
|---|---|
| 红灯（新增 7 个用例） | 7 个失败：`resendCancelsPreviousStream`、`searchTreatsPercentAsLiteral`、`searchTreatsUnderscoreAsLiteral`、`watchdogTimeoutStopsStream`、`emptyDoneIsTreatedAsFailureWithoutStoringMessage`、`onQueryChangeClearsSelection`、`selectionIsPrunedWhenNoteLeavesList` |
| 实现后定向测试 | 全绿（`AiApiSessionTest` 首轮因 `send` 无条件 `cancel` 与既有 `stopCancelsStreamingWithoutDone` 计数冲突，改为「旧 job 活跃才断连」后通过） |
| 全量单测 | 见修订记录 |

## 修订记录

| 日期 | 内容 |
|---|---|
| 2026-11-08 | 初稿 + 实现：条目 77、82、83、89、91 落地；红灯 7 例 → 全绿。 |
