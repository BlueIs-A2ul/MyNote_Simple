# MyNote 回收站（软删除）设计文档

> 对应 `docs/backlog.md` 条目 2。目标版本 **1.3.0**（新功能，minor 递增）。

## 背景与目标

现状：编辑页删除笔记后物理删除，历史快照随外键级联清空，误删不可逆（`docs/backlog.md` #2）。本设计引入「回收站」：删除改为软删除（标记 `deletedAt`），30 天可恢复，到期自动清理；回收站页面支持恢复、单条彻底删除、一键清空。

## 数据模型

- `NoteEntity` 新增字段 `deletedAt: Long?`（默认 `null`，Room 列 `deletedAt INTEGER` 可空；置于参数列表末尾带默认值，既有位置参数调用不受影响）。
- Room 升 **v4**：新增 `MIGRATION_3_4`（`ALTER TABLE notes ADD COLUMN deletedAt INTEGER`），并更新迁移测试（新增 v3 手工建库用例）。
- 历史快照 `note_revisions`、AI 会话均保持 `ON DELETE CASCADE`：软删除不影响它们（恢复后历史完好）；彻底删除时级联清空。

## 行为约定

| 操作 | 行为 |
|---|---|
| 删除笔记（编辑页） | `deletedAt = now`，保留分类/置顶/历史快照/图片文件；确认框文案改为「删除后移入回收站，30 天后自动清理」 |
| 列表/搜索/分类视图 | 一律过滤 `deletedAt IS NULL` |
| 恢复 | `deletedAt = null`，原分类/置顶/历史全部还原 |
| 彻底删除（回收站） | 物理删除 + 级联清历史 + 触发图片 GC |
| 到期自动清理 | 应用启动时 + 打开回收站时执行：删除 `now - deletedAt > 30 天` 的笔记并 GC |
| 备份导出 | 只导出未删除笔记（回收站内容不入备份，避免恢复备份时复活垃圾） |
| 备份导入 | `BackupNote` 增加 `deletedAt: Long? = null`（旧备份 JSON 缺字段时取默认 null，向后兼容）；本地存在且备份较新时按备份覆盖（含 resurrect），本地较新保留（含仍在回收站的状态） |
| 图片 GC | 引用集仍 = 全部笔记 ∪ 历史快照：软删除笔记的图片在回收站期间保留，彻底删除/到期清理后才回收 |

## 界面

- 新页面 `ui/trash/TrashScreen.kt` + `TrashViewModel.kt`，路由 `trash`（`AppNavHost`）。
- 入口：列表页溢出菜单「回收站」（放在「分类管理」之后、排序组之前）。
- 页面：`PaperTopBar`（标题「回收站」，返回按钮）；顶部说明条「笔记删除后保留 30 天，到期自动清理」；列表行显示 标题/摘要/「删除于 <dateTime>」；行内两个操作：恢复、彻底删除（`PaperAlertDialog` 确认「删除后不可恢复」）；顶栏「清空」按钮（全部彻底删除，需确认）；空态「回收站是空的」。

## 涉及文件

- 改：`data/db/NoteEntity.kt`、`AppDatabase.kt`、`NoteDao.kt`、`NoteRepository.kt`、`BackupManager.kt`、`ui/notes/NotesScreen.kt`（菜单项 + `onOpenTrash` 参数）、`ui/notes/NoteEditScreen.kt`（删除确认文案）、`ui/navigation/AppNavHost.kt`（路由）、`MainActivity.kt`（启动清理）。
- 新：`ui/trash/TrashScreen.kt`、`ui/trash/TrashViewModel.kt`。
- 测试：`NoteDaoTest`、`NoteRepositoryTest`（含既有 `deleteNoteCascadesRevisions` 语义改为彻底删除）、`BackupManagerTest`、`AppDatabaseMigrationTest`（v3→v4）、新增 `TrashViewModelTest`。

## 测试要点

1. DAO：软删除笔记不出现在 observeAll/observeByCategory/search；`observeDeleted()` 只返回已删且按 `deletedAt DESC`。
2. 仓库：软删除保留历史与图片；恢复清 `deletedAt`；彻底删除级联清历史并回收图片；`purgeExpired(now, ttl)` 只清超期。
3. 备份：`BackupNote` 含 `deletedAt` 的 JSON 往返一致；旧 JSON（无该字段）解码为 null。
4. 迁移：v3 手工建库 → v4 打开后旧数据保留、`deletedAt` 为 null、可正常写值。
5. 回收站 VM：恢复/彻底删除/清空/到期清理调用链正确（参照 `NoteEditViewModelTest` 的 CompletableDeferred 模式）。

## 版本

1.2.0 → **1.3.0**（`versionCode` 1_03_00），README 版本行、产物名、测试数同步；`docs/backlog.md` #2 置为已完成。
