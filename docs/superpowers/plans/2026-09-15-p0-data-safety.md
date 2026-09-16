# 批次 E · 数据安全与一致性 实施计划（条目 71–76）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修掉 6 条 P0 数据安全缺陷（GC 误删新图、保留期静默删除、草稿 id 丢失、导入非原子、分类冲突致整体失败、批量操作崩溃/无反馈）。

**Architecture:** 集中在 `ImageStore`（写入与回收策略）、`BackupManager`（导入两阶段：暂存 → 提升）、`NoteRepository`/`NoteDao`（批量查询切块、超期计数）、`SettingsViewModel/Screen`（缩短保留期确认 + 立即清理）、`NoteEditViewModel`（草稿 id 落 SavedStateHandle）、`NotesViewModel`/`TrashViewModel`（异常处理与失败反馈）。

**Tech Stack:** Kotlin 2.0.20 / Room 2.6.1（KSP）/ Compose UI 1.7.3 / JUnit4 + Robolectric `@Config(sdk = [34])`；VM 测试用 `Dispatchers.setMain(StandardTestDispatcher())` + `runTest`，真实异步结果用 `CompletableDeferred`/`Flow.first`。

**设计文档：** `docs/superpowers/specs/2026-09-15-p0-data-safety-design.md`

**证据行号基准：** `fa21e96`（v1.10.1 + 首批修复 + backlog 入库后）

---

## Task 1: ImageStore —— GC 宽限期 + 原子写入 + 暂存目录（条目 71、74a）

**Files:** `app/src/main/java/com/mynote/app/data/image/ImageStore.kt`、`app/src/test/java/com/mynote/app/data/image/ImageStoreTest.kt`

- [ ] **Step 1: 写失败用例**
  - `collectGarbageKeepsFilesWithinGracePeriod`：写 `fresh.webp` 后立即 `collectGarbage(emptySet(), now = 文件 mtime + 1) ` → 文件仍在；`collectGarbage(emptySet(), now = mtime + GC_GRACE_MS + 1)` → 被删。
  - `writeFileDoesNotTruncateExistingOnFailure`：先写入 `a.webp` = "good"；再用一个读到一半抛错的 `InputStream` 调 `writeFile("a.webp", stream)` → 抛异常且原内容仍是 "good"，目录里无 `.tmp` 残留。
  - `writePendingWritesIntoPendingDir`：`writePending("p.webp", bytes)` → 落在 `pendingDir()` 且正式目录没有它。
- [ ] **Step 2: 跑测试确认失败**：`.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.image.ImageStoreTest"`
- [ ] **Step 3: 实现**：`GC_GRACE_MS = 10 * 60 * 1000L`、`PENDING_DIR_NAME = "notes_images_pending"`；`collectGarbage(referenced, now)` 跳过宽限期内文件并清理暂存目录过期残留；`writeFile`/`writePending` 走私有 `writeAtomically(dir, name, write)`（临时文件 → 删旧 → rename，失败 `copyTo(overwrite)` 兜底，异常时删临时文件）。
- [ ] **Step 4: 跑测试确认全绿**。

## Task 2: BackupManager —— 两阶段导入 + 失败回滚 + 分类冲突复用（条目 74b、75）

**Files:** `app/src/main/java/com/mynote/app/data/backup/BackupManager.kt`、`app/src/test/java/com/mynote/app/data/backup/BackupManagerTest.kt`

- [ ] **Step 1: 新增/改写用例**
  - `importPromotesStagedImagesAndClearsPendingDir`（成功路径：图片就位、暂存目录空）。
  - `importFailureKeepsExistingImagesIntact`：预置 `notes_images/keep.png` = A；zip 先给 `img/keep.png` = B，再给非法条目名 → 导入抛 `BackupFormatException`，`keep.png` 仍为 A，正式目录无新增文件，暂存目录为空。
  - `importReusesCategoryWhenSameIdRenamesIntoExistingName`（改写原 `importRollsBackPartialWritesWhenLaterEntryFails`）：本地 `{1:A, 2:B}` + 备份 `[{id:1,name:B}]` + 一条引用分类 1 的笔记 → 导入**成功**、分类仍 2 条、笔记 categoryId == 2。
  - `importReusesCategoryWithSameNameAndRemapsNote` 保持通过（同名不同 id 仍复用）。
- [ ] **Step 2: 跑测试确认失败**。
- [ ] **Step 3: 实现**：解压阶段 `img/*` 写 `imageStore.writePending`；`decode` 之后调 `promotePendingImages()`（返回 `List<Promotion(name, backupFile?)>`，失败时 `rollbackPromotion` + 抛 `IOException`）；事务包 try/catch，失败时 `rollbackPromotion(promoted)` 后重抛；成功则 `discardStagedBackups()`；分类重映射按设计文档 §2 的四分支。
- [ ] **Step 4: 跑测试确认全绿**。

## Task 3: NoteDao/NoteRepository —— 超期计数 + IN 切块（条目 72a、76a）

**Files:** `app/src/main/java/com/mynote/app/data/db/NoteDao.kt`、`app/src/main/java/com/mynote/app/data/repository/NoteRepository.kt`、测试 `NoteDaoTest.kt` / `NoteRepositoryTest.kt`

- [ ] **Step 1: 用例**：`getNotesByIdsChunksLargeIdLists`（1000 个 id 含 1 个真实 id → 返回该条、不抛异常；空列表 → 空结果）；`countExpiredDeletedNotesCountsOnlyBeyondTtl`。
- [ ] **Step 2: 实现**：`NoteDao.countDeletedBefore(cutoff)`；`NoteRepository.getNotesByIds` 空列表早返回 + `chunked(IN_QUERY_CHUNK = 900)`；新增 `countExpiredDeletedNotes(now, ttlMs)`。
- [ ] **Step 3: 跑测试确认全绿**。

## Task 4: 缩短保留期确认 + 立即清理（条目 72b）

**Files:** `ui/settings/SettingsViewModel.kt`、`ui/settings/SettingsScreen.kt`、`ui/navigation/AppNavHost.kt`、测试 `SettingsViewModelTest.kt`

- [ ] **Step 1: 用例**：`expiringCountReflectsNewRetention`（3 条回收站笔记：20 天前/3 天前/40 天前 → `expiringCount(7) == 2`、`expiringCount(90) == 0`）；`applyRetentionDaysPurgesExpiredAndPersists`（`apply(7)` 返回 2、回收站剩 1 条、store 值 7）。
- [ ] **Step 2: 实现**：`SettingsViewModel` 增加 `repository: NoteRepository` 依赖（factory 同步加参数，`AppNavHost` 传 `container.noteRepository`）；新增 `suspend fun expiringCount(days)` 与 `suspend fun applyRetentionDays(days): Int`（先写 store 再按新 TTL 清理，返回清理条数）。
- [ ] **Step 3: UI**：设置页 `TextTabRow` 的 `onSelect` 改为——新值 < 当前值则先 `expiringCount`，有条数则弹 `PaperAlertDialog`（「改为 N 天后，回收站中已有 M 条笔记会立即被永久删除…」）确认后 `applyRetentionDays` 并 snackbar「已清理 M 条」；否则直接 `applyRetentionDays`。
- [ ] **Step 4: 跑测试 + 编译确认**。

## Task 5: 草稿目标 id 落 SavedStateHandle（条目 73）

**Files:** `ui/notes/NoteEditScreen.kt`、`ui/navigation/AppNavHost.kt`、测试 `NoteEditViewModelTest.kt`

- [ ] **Step 1: 用例**：`draftIdSurvivesViewModelRecreation`——VM#1（空 handle）`saveDraft("标题","正文",…)` → draftId 非空；用**同一个 handle**、`noteId = null` 新建 VM#2 → `draftId` 已恢复；VM#2 `save(...)` → 仍只有 1 条笔记且内容更新。
- [ ] **Step 2: 实现**：`NoteEditViewModel(repository, imageStore, noteId, savedState: SavedStateHandle = SavedStateHandle())`，init 时 `_draftId.value = savedState[KEY_DRAFT_ID]` 并（非空时）`observeNote(it)`；草稿插入分支写 `_draftId.value = id` 同时 `savedState[KEY_DRAFT_ID] = id`。
- [ ] **Step 3: 接线**：`NoteEditScreen` 增加 `draftIdHandle: SavedStateHandle` 参数并传给 VM 工厂；`AppNavHost` 编辑页传 `backStack.savedStateHandle`。
- [ ] **Step 4: 跑测试确认全绿**。

## Task 6: 批量/回收站操作的异常处理与失败反馈（条目 76b）

**Files:** `ui/notes/NotesViewModel.kt`、`ui/notes/NotesScreen.kt`、`ui/trash/TrashViewModel.kt`、`ui/trash/TrashScreen.kt`、测试 `NotesViewModelTest.kt` / `TrashViewModelTest.kt`

- [ ] **Step 1: 用例**：`batchFailureReportsErrorAndKeepsSelection`（`db.close()` 制造失败 → `onFailed` 被调用、`selectionMode`/`selectedIds` 保留）；`purgeAllOnEmptyTrashDoesNotThrow`（空回收站 → `onDone(0)`，不抛异常）。
- [ ] **Step 2: 实现**：`runBatch(onDone, onFailed, op)` 包 try/catch，失败时 `onFailed()` 且不清选中；`TrashViewModel.restore/purge/purgeAll` 加 try/catch 与空列表早返回，新增 `onFailed` 回调。
- [ ] **Step 3: 界面**：5 处批量调用 + 3 处回收站调用补失败分支 snackbar「操作失败，请重试」。
- [ ] **Step 4: 跑测试确认全绿**。

## Task 7: 收尾

- [ ] **Step 1:** 全量 `:app:testDebugUnitTest` + `:app:assembleDebug`。
- [ ] **Step 2:** backlog 条目 71–76 状态回写为「已完成」并补修订记录；AGENTS.md / build-guide 单测数同步。
- [ ] **Step 3:** 提交（`fix:`），主树 `git merge --ff-only p0-data-safety-0915`，移除 worktree 与分支；不 push。

---

## 修订记录

| 日期 | 内容 |
|---|---|
| 2026-09-15 | 初版：按 backlog 批次 E 拆 6 个修复任务 + 收尾；导入采用「暂存 → 提升 → 失败回滚」，并明确不做批次 F/H 的取消反馈与文案细化。 |
| 2026-09-15 | 执行完成：6 项全部落地，全量单测 593 → 610 全绿（新增 17 个用例）。四处偏离：① **GC 宽限期改变了既有 GC 语义**，`ImageStoreTest` 2 条与 `NoteRepositoryTest` 4 条断言孤儿回收的用例补了 `ageImageFiles()`（把文件 mtime 调到宽限期之外），否则「新文件受保护」会让这些用例假通过；② `BackupManagerTest.importRollsBackPartialWritesWhenLaterEntryFails` 断言的「分类撞名 → 整体回滚」正是条目 75 要消除的行为，改写为 `importResolvesCategoryNameConflictByReuse`（导入成功、分类复用、笔记重映射）；③ 失败分支测试原打算用 `db.close()` 制造 DB 异常，实测 Robolectric 下关闭后查询仍能成功（前置断言直接失败），改用 `DROP TABLE notes` 制造确定性 SQL 错误；④ 导入的 `promotePendingImages` 回滚路径（写库失败时还原被覆盖的旧图）无法在现有测试栈中确定性触发，属**防御性未覆盖代码**，已在设计文档与代码注释中写明前提。 |
