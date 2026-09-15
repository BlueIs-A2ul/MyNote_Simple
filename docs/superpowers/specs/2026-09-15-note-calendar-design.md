# MyNote 笔记日期与日历视图设计文档

> 用户新增需求（非 `docs/backlog.md` 条目）。目标版本 **1.8.0**（新功能，minor 递增）。

## 背景与目标

现状：笔记只有创建/更新/删除时间，没有任何「归属日期」概念，也没有日历相关代码。本设计让用户能在编辑页给笔记手动指定一个日期，并新增只读日历页：按月展示标记过的日期，点选某天查看当天笔记、点笔记进编辑页。纯本地功能、零新增依赖（`DatePickerDialog` 由现有 Material3 提供）。

## 数据模型

- `NoteEntity` 参数列表**末尾**新增 `noteDate: Long? = null`，语义为「所选日期的本地零点毫秒」；`@Entity` 的 `indices` 增加 `Index("noteDate")`。
- Room 升 **v6**：新增 `MIGRATION_5_6` = `ALTER TABLE notes ADD COLUMN noteDate INTEGER` + `CREATE INDEX IF NOT EXISTS index_notes_noteDate ON notes(noteDate)`；旧笔记该列为 `null`（不出现在日历），迁移测试补 v5 手工建库用例。
- `NoteListItem` **不加字段**：主列表与日历行都不展示该日期，沿用现有 `substr(content,1,400)` 投影列集。
- `NoteDao` 新增（均 `deletedAt IS NULL`）：
  - `observeByDateRange(startInclusive, endExclusive): List<NoteListItem>`：`noteDate >= :start AND noteDate < :end`，`ORDER BY pinned DESC, updatedAt DESC`（月/日视图共用同一方法）。
  - `observeDateMarks(startInclusive, endExclusive): List<DateMark>`：`SELECT noteDate, COUNT(*) ... GROUP BY noteDate`，供日历圆点。
  - 新 POJO `data/db/DateMark.kt`：`data class DateMark(val noteDate: Long, val count: Int)`。
- `NoteRepository`：`saveNote` / `updateDraft` 末尾**追加必填参数** `noteDate: Long?`（不给默认值，让编译器强制所有调用方显式传入，避免静默丢日期）；`saveNote` 的 `changed` 显式比较补 `noteDate`。
- `BackupManager.BackupNote` 增加 `noteDate: Long? = null`（旧备份缺字段解为 null）；`toBackup` / `toEntity` 映射同步。
- 日期计算一律走 `java.util.Calendar`（minSdk 24 无 desugaring），不引入 `java.time`。

## 行为约定

| 操作 | 行为 |
|---|---|
| 编辑页设置/修改日期 | 存为所选日期本地零点毫秒；参与 `changed` 判定，**仅日期变化也写一条历史快照**（与置顶/颜色一致） |
| 清除日期 | 置 `null`，日历页不再出现 |
| 未标记日期的笔记 | 不出现在日历（含全部旧笔记，迁移后为 `null`） |
| 主列表/搜索/分类/回收站 | 不受影响，排序与行内展示维持现状 |
| 退后台静默保存 | `saveDraft` 与标题/正文一样带上当前日期，不丢 |
| 软删除/恢复/彻底删除 | 语义不变，日期随行保留 |
| 备份 | 导出含 `noteDate`；旧备份缺字段解为 `null`；旧版本 App 因 `ignoreUnknownKeys = true` 可忽略该字段 |
| 新建笔记 | 日期不单独构成「有内容」：仍按「标题/正文非空」决定是否入库与未保存提示 |

时区细节：M3 `DatePicker` 返回的是所选日期的 **UTC 零点毫秒**，必须经纯函数转换为本地零点（`pickerMillisToLocalDay` / `localDayToPickerMillis`），否则 UTC+8 下会偏移一天。

## 界面

### 编辑页

- 底部操作栏「图片 | 分类 ▾ | **日期** | 预览」并列插入「日期」按钮：未设置显示「日期」，已设置显示 `TimeFormat.dateLabel(noteDate, now)`（同年 `M月d日`，跨年 `yyyy年M月d日`）。
- 点击弹 Material3 `DatePickerDialog`（`@OptIn(ExperimentalMaterial3Api::class)`，属现有依赖，零新增）：确定写回、取消不写；已设置日期时 dismiss 区提供「清除」。
- 回填逻辑补 `noteDate`；`save` / `saveDraft` / `ON_STOP` 静默保存全链路透传。
- `hasUnsavedChanges`：存量笔记逐项比较补 `noteDate`；新建笔记维持「标题/正文非空」规则。

### 日历页（新路由 `calendar`）

- 入口：列表页溢出菜单「日历」，放在「回收站」之后、排序组之前；`NotesScreen` 增加 `onOpenCalendar` 参数，`AppNavHost` 注册路由并接线。
- `ui/calendar/CalendarViewModel.kt`：`year/month`（默认当前月）、`selectedDay`（默认今天）、`marks`（当月哪些天有笔记，驱动圆点）、`dayNotes`（选中日笔记流，`flatMapLatest(observeByDateRange)`）。
- `ui/calendar/CalendarScreen.kt` 用**单个 `LazyColumn`**（月份网格、当日区段头、笔记行作为一个整体滚动，避免嵌套滚动）：
  - `PaperTopBar`：返回 + 标题「2026年9月」+「今天」按钮（跳回今天）+「‹ / ›」前后月。
  - 周标题固定**周一起始**「一 二 三 四 五 六 日」（不随系统区域变化）；网格 5–6 行 × 7 列，非本月格子留白。
  - 单元格状态：今天描边、选中日主题色填充、有笔记的日子数字下带 4dp 小圆点；颜色一律走 `MaterialTheme.colorScheme`。
  - 区段头「9月10日 · 3 篇」+ `NoteRow` 列表，点击进编辑页；返回后仍停在原月份/选中日（ViewModel 挂在导航返回栈条目上）。
  - 空态「这天还没有笔记」+ 提示「在编辑页底部给笔记添加日期后会出现在这里」；`dayNotes` 为 `null`（加载中）时沿用列表页冷启动占位，不闪空态。
  - 本期只读：无 FAB、无多选、不支持在日历页新建或改日期。
- 纯函数 `util/CalendarDates.kt`：月首/月尾毫秒、加减月（跨年）、当月天数、周一对齐偏移、网格日列表、本地零点、DatePicker UTC 互转；`TimeFormat` 新增 `monthLabel` / `dayLabel` / `dateLabel`。全部可单测。

## 涉及文件

- 改：`data/db/NoteEntity.kt`、`data/db/AppDatabase.kt`、`data/db/NoteDao.kt`、`data/repository/NoteRepository.kt`、`data/backup/BackupManager.kt`、`ui/notes/NoteEditScreen.kt`、`ui/notes/NotesScreen.kt`、`ui/navigation/AppNavHost.kt`、`util/TimeFormat.kt`、`app/build.gradle.kts`（版本）、`README.md`（版本行）。
- 新：`data/db/DateMark.kt`、`ui/calendar/CalendarScreen.kt`、`ui/calendar/CalendarViewModel.kt`、`util/CalendarDates.kt`。
- 测试：`AppDatabaseMigrationTest`、`NoteDaoTest`、`NoteRepositoryTest`、`BackupManagerTest`、`HasUnsavedChangesTest`、新增 `CalendarDatesTest`、新增 `CalendarViewModelTest`。

## 测试要点

1. 迁移 v5→v6：旧数据保留、`noteDate` 为 `null`、可正常写入。
2. DAO：范围查询含起不含止、排除软删除与 `null`、置顶优先排序；`observeDateMarks` 按日计数正确。
3. 纯函数：月网格对齐（周一起始）、闰年/跨年加减月、DatePicker UTC ↔ 本地零点往返一致。
4. 仓库：仅日期变化写历史快照；无变化不写；`updateDraft` 保留日期。
5. `hasUnsavedChanges`：存量笔记仅日期变化 → `true`；新建笔记仅日期变化 → `false`。
6. 备份：`noteDate` 往返一致；旧 JSON 缺字段解为 `null`。
7. `CalendarViewModel`：切月刷新 marks、选日切换 dayNotes（`StandardTestDispatcher` + `Flow.first {}` 模式）。

## 版本

1.7.1 → **1.8.0**（`versionCode` 10800），README 版本行与测试总数按实际运行结果同步。
