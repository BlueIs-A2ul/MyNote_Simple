# MyNote 笔记日期与日历视图实施计划

> **For agentic workers:** 配套设计文档 `docs/superpowers/specs/2026-09-15-note-calendar-design.md`。本计划按数据层 → 工具 → 编辑页 → 日历页 → 测试 → 收尾串行推进（层次耦合紧密，不拆并行子代理）。步骤使用 `- [ ]` 跟踪。

**Goal:** 笔记支持手动标记归属日期，新增只读日历页按日查看笔记，发布 1.8.0。

**Architecture:** `NoteEntity.noteDate`（本地零点毫秒，可空）走 Room v6 + `MIGRATION_5_6`；DAO 新增日期范围/每日计数查询；编辑页底栏接 Material3 `DatePickerDialog`；新增 `ui/calendar/` 页面（月网格 + 当日笔记列表，单 LazyColumn）与 `util/CalendarDates.kt` 纯函数；溢出菜单进入，不新增依赖与权限。

**Tech Stack:** Kotlin、Compose Material3（DatePicker 属现有依赖）、Room(KSP)、Robolectric/JUnit4、java.util.Calendar（minSdk 24，无 desugaring）。

---

### Task 1: 数据层（Entity / DB v6 / DAO / 仓库 / 备份）

**Files:**
- Modify: `app/src/main/java/com/mynote/app/data/db/NoteEntity.kt`
- Modify: `app/src/main/java/com/mynote/app/data/db/AppDatabase.kt`
- Modify: `app/src/main/java/com/mynote/app/data/db/NoteDao.kt`
- Modify: `app/src/main/java/com/mynote/app/data/repository/NoteRepository.kt`
- Modify: `app/src/main/java/com/mynote/app/data/backup/BackupManager.kt`
- Modify: `app/src/main/java/com/mynote/app/di/AppContainer.kt`
- Create: `app/src/main/java/com/mynote/app/data/db/DateMark.kt`

- [ ] `NoteEntity` 末尾追加 `val noteDate: Long? = null`；`indices` 增加 `Index("noteDate")`；索引注释改「Room v6」并补 `noteDate` 说明。
- [ ] `AppDatabase`：`version = 6`；新增 `MIGRATION_5_6`：`ALTER TABLE notes ADD COLUMN noteDate INTEGER` + `CREATE INDEX IF NOT EXISTS index_notes_noteDate ON notes(noteDate)`；`AppContainer.database` 的 `addMigrations` 追加 `MIGRATION_5_6`。
- [ ] 新建 `DateMark(noteDate: Long, count: Int)`（日历圆点用）。
- [ ] `NoteDao` 新增两条投影查询（均 `deletedAt IS NULL`）：
  - `observeByDateRange(startInclusive, endExclusive): Flow<List<NoteListItem>>`，`noteDate >= :start AND noteDate < :end`，`ORDER BY pinned DESC, updatedAt DESC`（月/日视图共用）。
  - `observeDateMarks(startInclusive, endExclusive): Flow<List<DateMark>>`，`SELECT noteDate, COUNT(*) AS count ... GROUP BY noteDate`。
- [ ] `NoteRepository.saveNote` / `updateDraft` 末尾追加 `noteDate: Long?` 参数（给 `= null` 默认值：测试与 AI/欢迎笔记等既有无日期调用点不受影响，App 侧调用点必须显式传值）；`saveNote` 的 `changed` 判定、新建实体与更新实体两处位置参数都补 `noteDate`；`updateDraft` 的 `copy` 补 `noteDate`。
- [ ] `restoreRevision` 调 `saveNote` 时显式传当前笔记的 `noteDate`（历史快照不含日期，恢复旧版不动日期）。
- [ ] 新增 `observeNotesByDateRange(...)` / `observeDateMarks(...)` 仓库透传。
- [ ] `BackupManager.BackupNote` 增加 `val noteDate: Long? = null`（旧备份兼容）；`toBackup()` / `toEntity()` 位置参数补 `noteDate`。

### Task 2: 工具纯函数（日历计算 + 文案）

**Files:**
- Create: `app/src/main/java/com/mynote/app/util/CalendarDates.kt`
- Modify: `app/src/main/java/com/mynote/app/util/TimeFormat.kt`

- [ ] `CalendarDates`（全部 `java.util.Calendar`，纯函数）：
  - `dayStart(millis)`、`dayStart(year, month, day)`（month 1–12）、`nextDay(dayStartMillis)`；
  - `monthStart(year, month)`、`nextMonthStart(year, month)`；
  - `addMonths(year, month, delta): Pair<Int, Int>`（跨年）；
  - `daysInMonth(year, month)`、`firstDayOffset(year, month)`（周一为 0）、`monthGrid(year, month): List<Long?>`（前导 null + 日零点，整周对齐）；
  - `pickerMillisToLocalDay(utcMillis)` / `localDayToPickerMillis(localMillis)`（DatePicker 用 UTC 零点，必须换算，防 UTC+8 偏移一天）。
- [ ] `TimeFormat` 新增 `monthLabel(year, month)` = `2026年9月`、`dayLabel(millis)` = `9月10日`、`dateLabel(millis, now)` = 同年 `M月d日` / 跨年 `yyyy年M月d日`。

### Task 3: 编辑页日期入口

**Files:** Modify: `app/src/main/java/com/mynote/app/ui/notes/NoteEditScreen.kt`

- [ ] `hasUnsavedChanges` 末尾追加 `noteDate: Long?` 参数：存量笔记逐项比较补 `noteDate != saved.noteDate`；新建笔记规则不变（日期不单独构成变更）。
- [ ] `NoteEditViewModel.save` / `saveDraft` 追加 `noteDate: Long?` 并透传给仓库。
- [ ] 编辑页新增 `noteDate` 状态（`rememberSaveable`），`!initialized` 回填时同步 `noteDate = n.noteDate`。
- [ ] 底栏「图片」旁新增日期按钮：未设置显示「日期」，已设置显示 `TimeFormat.dateLabel(noteDate, now)`；点击弹 `DatePickerDialog`（`rememberDatePickerState(initialDateMillis = noteDate?.let(CalendarDates::localDayToPickerMillis))`），确定写回、取消不写、已设置时 dismiss 区额外给「清除」。
- [ ] `dirty` 判定、`ON_STOP` 静默保存、`saveAndExit` 全部带上 `noteDate`。

### Task 4: 日历页

**Files:**
- Create: `app/src/main/java/com/mynote/app/ui/calendar/CalendarViewModel.kt`
- Create: `app/src/main/java/com/mynote/app/ui/calendar/CalendarScreen.kt`
- Modify: `app/src/main/java/com/mynote/app/ui/navigation/AppNavHost.kt`
- Modify: `app/src/main/java/com/mynote/app/ui/notes/NotesScreen.kt`
- Modify: `app/src/main/java/com/mynote/app/ui/components/EmptyState.kt`

- [ ] `CalendarViewModel(repository)`：`year` / `month` / `selectedDay`（默认今天）、`prevMonth()`（月份±1，选中日回到该月 1 号）、`nextMonth()`、`goToToday()`、`selectDay(day)`；`marks: StateFlow<Map<Long, Int>>`（当月每日计数）、`dayNotes: StateFlow<List<NoteListItem>?>`（选中日，null=加载中）、`categories`（行内分类色条/名称）；`factory(repository)`。
- [ ] `CalendarScreen(repository, onOpenNote, onBack)`：`PaperTopBar` 标题 `TimeFormat.monthLabel`、返回、「今天」、`‹`/`›`；单个 `LazyColumn`：周标题（一…日）→ 5–6 行月网格（今天描边、选中填充 primary、有笔记带 4dp 圆点、非本月留白）→ `HairlineDivider` + 区段头「9月10日 · N 篇」→ `NoteRow` 列表（点击进编辑页）；`dayNotes == null` 显示加载占位、空列表显示空态（「这天还没有笔记」+ 提示）。
- [ ] `EmptyState` 增加可选 `hint: String? = null`（正文下方小字提示，默认 null 不影响现有调用）。
- [ ] `AppNavHost` 注册 `composable("calendar")`（`onOpenNote` → `edit/{id}`），`NotesScreen` 增加 `onOpenCalendar` 参数并在溢出菜单「回收站」后加「日历」项。

### Task 5: 测试

**Files:**
- Modify: `app/src/test/java/com/mynote/app/data/db/AppDatabaseMigrationTest.kt`
- Modify: `app/src/test/java/com/mynote/app/data/db/NoteDaoTest.kt`
- Modify: `app/src/test/java/com/mynote/app/data/repository/NoteRepositoryTest.kt`
- Modify: `app/src/test/java/com/mynote/app/data/backup/BackupManagerTest.kt`
- Modify: `app/src/test/java/com/mynote/app/ui/notes/HasUnsavedChangesTest.kt`
- Modify: `app/src/test/java/com/mynote/app/ui/notes/NoteEditViewModelTest.kt`
- Create: `app/src/test/java/com/mynote/app/util/CalendarDatesTest.kt`
- Create: `app/src/test/java/com/mynote/app/ui/calendar/CalendarViewModelTest.kt`

- [ ] 迁移：新增 `createV5Database()`（notes 含 deletedAt + 三索引）+ `migrate5To6AddsNoteDateColumnKeepsData`（旧数据 `noteDate` 为 null、索引存在、可写入并读回）。
- [ ] `NoteDaoTest`：范围查询（含起不含止、排除软删与未标记、置顶优先）、`observeDateMarks` 按日计数。
- [ ] `CalendarDatesTest`：周一对齐（2026-09 → 偏移 1）、闰年 2 月、加减月跨年、`monthGrid` 形状、DatePicker UTC ↔ 本地零点往返。
- [ ] `NoteRepositoryTest`：仅日期变化写历史、同值不写、`updateDraft` 保留日期、`restoreRevision` 不清日期。
- [ ] `BackupManagerTest`：`noteDate` 往返一致、旧 JSON 缺字段解为 null。
- [ ] `HasUnsavedChangesTest`：存量仅日期变化 → true；新建仅日期变化 → false。
- [ ] `NoteEditViewModelTest`：现有调用补 `noteDate` 参数（`null`）；新增保存/静默保存日期落库用例。
- [ ] `CalendarViewModelTest`：默认选中今天、切月刷新 marks、选日切换 dayNotes（`StandardTestDispatcher` + `Flow.first {}`）。

### Task 6: 收尾

- [ ] 全量 `.\gradlew :app:testDebugUnitTest` 通过；`assembleDebug` + `assembleRelease` 成功。
- [ ] 版本 1.7.2 → **1.8.0**（`versionCode 1_08_00`）；README 版本行/产物名/测试数、特性列表同步；`docs/backlog.md` 修订记录追加一行。
- [ ] 本计划补「修订记录」。
- [ ] 本地提交（中文 `feat:`，不 push）。

## 修订记录

| 日期 | 内容 |
|---|---|
| 2026-09-15 | 初稿：数据层 → 工具 → 编辑页 → 日历页 → 测试 → 收尾。 |
| 2026-09-15 | 执行完成（v1.8.0）。无结构性偏离；两处实现说明：① 仓库 `saveNote`/`updateDraft` 的 `noteDate` 采用默认值 `null`（App 侧调用点全部显式传值；避免 AI 存新笔记、欢迎笔记与约 150 处既有测试调用点全量改签名）；② 历史快照不含日期，`restoreRevision` 恢复旧版时保留当前日期。全量单测 547 全绿（本次新增 28 个），debug/release 构建通过。 |
