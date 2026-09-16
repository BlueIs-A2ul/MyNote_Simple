# 首批缺陷修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修掉 5 个用户可感知的缺陷（预览崩溃、AI 流式与上下文、保存卡死、撤销合并），全部可单测、零新增依赖、无 DB 迁移。

**Architecture:** 集中在 5 个文件：`NoteEditScreen.kt`（焦点门控 + 保存/草稿作业分离）、`NoteUndoController.kt`（合并锚点）、`AiChatViewModel.kt`（分片追加）、`AiApiMessageBuilder.kt` + `AiPromptBuilder.kt`（首条正文幂等重包）。新增一个纯 Kotlin 小状态机 `BodyFocusGate.kt` 使「不在组合中不许 requestFocus」的判定可测。

**Tech Stack:** Kotlin 2.0.20 / Compose UI 1.7.3（BOM 2024.09.03）/ Room 2.6.1 / JUnit4 + Robolectric `@Config(sdk = [34])`；ViewModer 测试用 `Dispatchers.setMain(StandardTestDispatcher())` + `runTest`。

**设计文档：** `docs/superpowers/specs/2026-09-15-first-batch-bugfixes-design.md`

---

## Task 1: 撤销合并保留整段起点（纯逻辑，先测）

**Files:** `app/src/main/java/com/mynote/app/ui/notes/NoteUndoController.kt`、`app/src/test/java/com/mynote/app/ui/notes/NoteUndoControllerTest.kt`

- [ ] **Step 1: 改 3 条既有用例的期望值（红）**：`typingChainMergesIntoSingleUndoStep` 期望 `value("")`（整段起点）、`deletionChainMerges` 期望 `value("abc")`、`middleInsertionMerges` 期望 `value("ac")`、`forceBreaksMerge` 期望 `value("ab")`（force 断点）+ `value("a")`。
- [ ] **Step 2: 新增 `recordAfterUndoStartsNewStep`（红）**：`record("ab",0)` → `record("abc",1000)`（超窗口，独立格）→ `undo("abcd") == "abc"` → `record("abc",1100)`（与上次记录仅差一字符且在窗口内）→ `undo("abcd")` 必须再得到 `"abc"`（若沿用撤销前的 `lastBefore`，会被错误合并而返回 `"ab"`）。
- [ ] **Step 3: 跑测试确认失败**：`.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.notes.NoteUndoControllerTest"`。
- [ ] **Step 4: 实现**：新增 `private var lastBefore: TextFieldValue?`；`record()` 中合并判定改用 `lastBefore`，合并时不改栈；`undo()/redo()/reset()` 清空 `lastBefore`。
- [ ] **Step 5: 跑测试确认全绿**。

## Task 2: AI 分片改为追加

**Files:** `app/src/main/java/com/mynote/app/ui/ai/AiChatViewModel.kt:339`、`app/src/test/java/com/mynote/app/ui/ai/AiChatViewModelTest.kt`

- [ ] **Step 1: 用例改增量语义（红）**：`idleWatchdogKeepsAliveWhileStreamEventsArrive` 的第二片由 `Chunk("第一段第二段")` 改为 `Chunk("第二段")`，仍断言 `streamingText == "第一段第二段"`。
- [ ] **Step 2: 新增 `stopStoresConcatenatedPartial`（红）**：发送后依次 `Chunk("半截")`、`Chunk("回答")` → `vm.stop()` → 落库 content 为 `"半截回答"`、status `interrupted`。
- [ ] **Step 3: 跑测试确认失败**。
- [ ] **Step 4: 实现**：`streamingText = it.streamingText + event.text`，并补注释说明 `Chunk` 是增量、`Done` 才带全文。
- [ ] **Step 5: 跑测试确认全绿**。

## Task 3: 首条 user 消息始终带笔记正文

**Files:** `app/src/main/java/com/mynote/app/data/ai/AiPromptBuilder.kt`、`app/src/main/java/com/mynote/app/data/ai/AiApiMessageBuilder.kt`、`app/src/test/java/com/mynote/app/data/ai/AiApiMessageBuilderTest.kt`

- [ ] **Step 1:** `AiPromptBuilder` 暴露 `const val NOTE_CONTEXT_HEADER = "【笔记正文】"` 与 `internal fun hasNoteContext(text: String)`。
- [ ] **Step 2: 新增用例（红）**：历史为**裸输入**的两轮会话（`user("帮我总结")` + `assistant("好的")`）→ `build("标题","正文",history,"再短一点")` 的结果中 `messages[0].content` 含 `【笔记正文】` 与 `正文`；首条已含表头时**幂等**（内容不变）。
- [ ] **Step 3: 改既有用例**：`blankHistoryContentIsFiltered` 的首条期望由裸 `"问"` 改为含正文表头（语义变更）；`laterMessageOmitsNoteContextAndKeepsHistory` 保持（首条已带表头 → 幂等）。
- [ ] **Step 4: 实现**：`build()` 中当 `messages[0].role == USER` 时，用 `AiPromptBuilder.build(noteTitle, noteContent, 首条原文, includeNoteContext = true, MAX_NOTE_CHARS)` 重包（`hasNoteContext` 为真则跳过）。
- [ ] **Step 5: 跑测试确认全绿**。

## Task 4: 保存与草稿作业分离

**Files:** `app/src/main/java/com/mynote/app/ui/notes/NoteEditScreen.kt`（`NoteEditViewModel` 部分）、`app/src/test/java/com/mynote/app/ui/notes/NoteEditViewModelTest.kt`

- [ ] **Step 1: 新增用例（红）**：`saveWhileDraftInFlightStillCallsBack` —— `saveDraft(...)`（不推进虚拟时间）后立刻 `save(...)`，`advanceUntilIdle()` 后断言 `onDone` 被调用且目标行只有一条（不重复插入）。
- [ ] **Step 2: 实现**：`private var saveJob: Job?` / `private var draftJob: Job?`；`save()` 只在 `saveJob?.isActive == true` 时 return，否则 `draftJob?.join()` 后再保存，`finally { saveJob = null }`；`saveDraft()` 在 `saveJob?.isActive == true` 或 `draftJob?.isActive == true` 时 return。`saving` 布尔字段移除，屏幕侧 `isSaving` 不再被 VM 状态影响。
- [ ] **Step 3: 跑测试确认全绿**（含既有 `saveAfterSilentDraftUpdatesSameNoteWithoutDuplicateInsert`）。

## Task 5: 预览模式焦点门控（修崩溃）

**Files:** 新增 `app/src/main/java/com/mynote/app/ui/notes/BodyFocusGate.kt`、新增 `app/src/test/java/com/mynote/app/ui/notes/BodyFocusGateTest.kt`、修改 `app/src/main/java/com/mynote/app/ui/notes/NoteEditScreen.kt`

- [ ] **Step 1: 写测试（红，编译失败即红）**：预览中请求 → 返回 false 且登记 pending；回到编辑态 → 返回 true 并清 pending；非预览请求 → 返回 true；重新进入预览后 pending 不残留。
- [ ] **Step 2: 实现 `BodyFocusGate`**（纯 Kotlin，无 Android 依赖）：
  ```kotlin
  class BodyFocusGate {
      private var pending = false
      /** 请求正文焦点；预览中正文输入框不在组合中，只能延后（返回 false）。 */
      fun requestBodyFocus(previewing: Boolean): Boolean {
          if (previewing) { pending = true; return false }
          pending = false
          return true
      }
      /** 预览开关变化时调用；由预览切回编辑且有待聚焦请求时返回 true。 */
      fun onPreviewModeChanged(previewing: Boolean): Boolean {
          if (previewing || !pending) return false
          pending = false
          return true
      }
  }
  ```
- [ ] **Step 3: 接线**：`NoteEditScreen.kt` 中 `val bodyFocusGate = remember { BodyFocusGate() }`；三处调用点改为
  `if (bodyFocusGate.requestBodyFocus(previewMode)) contentFocusRequester.requestFocus()`（`:356` 贴 AI 结果、`:374` 插图成功、`:531` 标题 IME Next）；另加
  `LaunchedEffect(previewMode) { if (bodyFocusGate.onPreviewModeChanged(previewMode)) contentFocusRequester.requestFocus() }`。
- [ ] **Step 4: 跑测试确认全绿** + `.\gradlew :app:assembleDebug` 确认 Compose 编译通过。

## Task 6: 文档同步与收尾

- [ ] **Step 1:** 全量 `.\gradlew :app:testDebugUnitTest`（期望全绿，测试数 = 582 + 新增）。
- [ ] **Step 2:** 同步 `AGENTS.md` 与 `docs/build-guide.md` 的单测数；`docs/backlog.md` 追加修订记录（记录本批 5 项与根因）。
- [ ] **Step 3:** 提交（`fix:` 前缀），主树 `git merge --ff-only first-batch-0915`，移除 worktree 与分支；不 push。

---

## 修订记录

| 日期 | 内容 |
|---|---|
| 2026-09-15 | 初版：按审计结论拆 5 个修复任务 + 文档收尾；明确不引入 Compose UI 测试依赖，屏幕级行为用 `BodyFocusGate` 纯状态机覆盖。 |
| 2026-09-15 | 执行完成：5 项全部落地，全量单测 582 → 593 全绿 + `assembleDebug` 通过。执行中的三处偏离：① 保存/草稿用例改用 `done.await()`（Room 事务在真实线程上完成，`advanceUntilIdle()` 之后 `isCompleted` 仍可能为 false，与 AGENTS.md 的「用 CompletableDeferred 等真实异步」约定一致）；② 连带修正 4 处把错误语义固化的旧断言（`selectionOnlyChangeCollapses`、`historyAtBudgetIsKeptIntact`、`typingChainMergesIntoSingleUndoStep`、`middleInsertionMerges`/`forceBreaksMerge`）；③ 三处 `requestFocus()` 改为「门控 + 预览切回编辑时补聚焦」，而非简单丢弃焦点请求。 |
