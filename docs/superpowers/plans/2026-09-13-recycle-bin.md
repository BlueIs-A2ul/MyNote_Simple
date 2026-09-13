# MyNote 回收站实施计划

> **For agentic workers:** 配套设计文档 `docs/superpowers/specs/2026-09-13-recycle-bin-design.md`。本计划按数据层 → 备份 → UI → 测试 → 收尾串行推进（层次耦合紧密，不拆并行子代理）。步骤使用 `- [ ]` 跟踪。

**Goal:** 实现回收站：软删除 + 30 天自动清理 + 回收站页面（恢复/彻底删除/清空），发布 1.3.0。

**Architecture:** `NoteEntity.deletedAt` 标记软删除；所有列表/搜索查询过滤已删；`NoteRepository` 提供恢复/彻底删除/到期清理；备份只在未删笔记上工作；新增 `ui/trash/` 页面与 VM；Room v4 + `MIGRATION_3_4` + 迁移测试。

**Tech Stack:** Kotlin、Compose Material3、Room(KSP)、Robolectric/JUnit4。无新依赖、无新权限。

---

### Task 1: 数据层（Entity / DB v4 / DAO / 仓库）

**Files:**
- Modify: `app/src/main/java/com/mynote/app/data/db/NoteEntity.kt`
- Modify: `app/src/main/java/com/mynote/app/data/db/AppDatabase.kt`
- Modify: `app/src/main/java/com/mynote/app/data/db/NoteDao.kt`
- Modify: `app/src/main/java/com/mynote/app/data/repository/NoteRepository.kt`

- [ ] `NoteEntity` 末尾加 `val deletedAt: Long? = null`。
- [ ] `AppDatabase`：`version = 4`；新增 `MIGRATION_3_4`：`db.execSQL("ALTER TABLE notes ADD COLUMN deletedAt INTEGER")`；`AppContainer` 无需改（`addMigrations` 列表追加 `MIGRATION_3_4`）。
- [ ] `NoteDao`：
  - `observeAll()` / `observeAllByCreated()` / `observeAllByTitle()` / `search()` / `observeByCategory()` 的 SQL 全部加 `WHERE deletedAt IS NULL`（搜索为 `WHERE deletedAt IS NULL AND (...)`）。
  - 新增 `@Query("SELECT * FROM notes WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC") fun observeDeleted(): Flow<List<NoteEntity>>`。
  - `getById` / `getAll` 保持无过滤（备份合并与图片 GC 需要全量）。
- [ ] `NoteRepository`：
  - `deleteNote(note)` 改为软删除：`noteDao.update(note.copy(deletedAt = System.currentTimeMillis()))`；**不再**触发 `collectImageGarbage()`（回收站期间图片必须保留）。
  - 新增 `restoreNote(note)`：`noteDao.update(note.copy(deletedAt = null))`。
  - 新增 `purgeNote(note)`：`noteDao.delete(note)` + `collectImageGarbage()`。
  - 新增 `purgeExpiredDeletedNotes(now: Long = System.currentTimeMillis(), ttlMs: Long = TRASH_TTL_MS)`：`noteDao.getAll().filter { it.deletedAt != null && now - it.deletedAt!! > ttlMs }` 逐个 `delete`，最后若删了任一条则 `collectImageGarbage()`。伴生常量 `const val TRASH_TTL_MS = 30L * 24 * 60 * 60 * 1000`。
  - 新增 `observeDeletedNotes(): Flow<List<NoteEntity>> = noteDao.observeDeleted()`。

### Task 2: 备份（deletedAt 兼容）

**Files:** Modify: `app/src/main/java/com/mynote/app/data/backup/BackupManager.kt`

- [ ] `BackupNote` 加 `val deletedAt: Long? = null`（旧 JSON 缺字段解码为 null）。
- [ ] `toBackup()` / `toEntity()` 带上 `deletedAt`。
- [ ] `exportZip`：`db.noteDao().getAll().filter { it.deletedAt == null }`。
- [ ] `importZip` 不改合并逻辑（`toEntity()` 已带 deletedAt，天然兼容旧备份）。

### Task 3: 回收站页面

**Files:**
- Create: `app/src/main/java/com/mynote/app/ui/trash/TrashViewModel.kt`
- Create: `app/src/main/java/com/mynote/app/ui/trash/TrashScreen.kt`
- Modify: `app/src/main/java/com/mynote/app/ui/navigation/AppNavHost.kt`
- Modify: `app/src/main/java/com/mynote/app/ui/notes/NotesScreen.kt`
- Modify: `app/src/main/java/com/mynote/app/ui/notes/NoteEditScreen.kt`
- Modify: `app/src/main/java/com/mynote/app/MainActivity.kt`

- [ ] `TrashViewModel(repository)`：
  - `val notes: StateFlow<List<NoteEntity>>` = `repository.observeDeletedNotes().stateIn(...)`；
  - `init` 里 `viewModelScope.launch { repository.purgeExpiredDeletedNotes() }`；
  - `fun restore(note, onDone: () -> Unit)`、`fun purge(note, onDone: () -> Unit)`、`fun purgeAll(onDone: () -> Unit)`（`viewModelScope.launch` + `onDone` 回调，仿 `NoteEditViewModel`）；
  - `companion object { fun factory(repository): ViewModelProvider.Factory = ... }`。
- [ ] `TrashScreen(repository, onBack)`：`PaperTopBar(title = "回收站", actions = 清空 TextButton)`；说明条 `state.bannerText` 风格同 `NoteHistoryScreen`（surfaceVariant 背景）；`LazyColumn` 行 = 标题 + 摘要（`NoteContentParser.plainText`）+ 「删除于 `TimeFormat.dateTime(note.deletedAt!!)`」+ 行尾「恢复」「删除」两个 TextButton；「删除」「清空」弹 `PaperAlertDialog` 确认（文案「彻底删除后不可恢复」）；空态 Text「回收站是空的」；SnackbarHost 报结果。
- [ ] `AppNavHost`：`composable("trash") { TrashScreen(container.noteRepository, onBack = { navController.popBackStack() }) }`；`NotesScreen` 增加 `onOpenTrash: () -> Unit` 并在溢出菜单「分类管理」后加 `DropdownMenuItem("回收站")`。
- [ ] `NoteEditScreen` 删除确认文案：「删除后不可恢复。」→「删除后将移入回收站，30 天后自动清理，期间可恢复。」（按钮仍为「删除」）。
- [ ] `MainActivity`：`LaunchedEffect(Unit) { container.noteRepository.purgeExpiredDeletedNotes() }`（启动清理，setContent 内、`container` 已可用）。

### Task 4: 测试

**Files:**
- Modify: `app/src/test/java/com/mynote/app/data/db/NoteDaoTest.kt`
- Modify: `app/src/test/java/com/mynote/app/data/repository/NoteRepositoryTest.kt`
- Modify: `app/src/test/java/com/mynote/app/data/backup/BackupManagerTest.kt`
- Modify: `app/src/test/java/com/mynote/app/data/db/AppDatabaseMigrationTest.kt`
- Create: `app/src/test/java/com/mynote/app/ui/trash/TrashViewModelTest.kt`

- [ ] `NoteDaoTest` 增补：软删除笔记不出现在 observeAll/observeByCategory/search；observeDeleted 只含已删且 deletedAt 降序。
- [ ] `NoteRepositoryTest`：原 `deleteNoteCascadesRevisions` 改为 `purgeNoteCascadesRevisions`（软删除保留历史是预期行为）；`garbageCollectionKeepsImagesReferencedByHistory` 触发 GC 的方式改为 `purgeNote`；新增：软删除进回收站且保留历史与图片、restoreNote 清标记、purgeNote 回收图片、purgeExpired 只清超期（用显式 now/ttl 参数构造时间）。
- [ ] `BackupManagerTest`：新增 `backupNoteJsonRoundTripsWithDeletedAt` 与 `oldBackupJsonWithoutDeletedAtDecodesAsNull`。
- [ ] `AppDatabaseMigrationTest`：新增 `createV3Database()`（含 ai_sessions/ai_messages 的 v3 全 schema）+ `migrate3To4AddsDeletedAtColumnKeepsData`（旧数据 deletedAt 为 null，写入软删除值后可读回）。
- [ ] `TrashViewModelTest`（参照 `NoteEditViewModelTest` 的 Dispatchers.setMain + CompletableDeferred）：restore 后 observeDeletedNotes 不含该笔记；purge 后列表为空；purgeAll 清空；init 触发到期清理（构造超期笔记，VM 创建后其物理行消失）。

### Task 5: 收尾

- [ ] 全量 `.\gradlew :app:testDebugUnitTest` 通过；`assembleDebug` + `assembleRelease` 成功。
- [ ] 版本 1.2.0 → 1.3.0（`versionCode` 1_03_00）；README 版本行、产物名、测试数同步。
- [ ] `docs/backlog.md` #2 → 已完成；本计划补「修订记录」。
- [ ] 本地提交（中文 `feat:`，不 push）。

## 修订记录

| 日期 | 内容 |
|---|---|
| 2026-09-13 | 初稿：数据层 → 备份 → 回收站 UI → 测试 → 收尾。 |
| 2026-09-13 | 执行完成（v1.3.0）。无结构性偏离；补充两点实现细节：① 启动清理放在 `MainActivity.setContent` 内的 `LaunchedEffect(Unit)`（应用冷启动即清到期笔记）；② `purgeAll` 逐条调用 `purgeNote`（回收站容量有限，单条 GC 开销可忽略，换取逻辑复用）。单测 251 → 265 全绿。 |
