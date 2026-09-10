# 笔记历史修改记录 — 设计文档

- 日期：2026-09-10
- 状态：设计完成，待实现
- 关联：`docs/superpowers/specs/2026-09-10-note-image-export-design.md`（同为编辑页能力）、`README.md`
- 前置：brainstorming 会话结论 —— 查看形态选「diff 高亮对比」；支持「恢复旧版且恢复也记一条新历史」；快照时机「仅保存点」；每篇上限 50 条、40 条起提醒

## 1. 背景与问题

当前笔记只有"当前状态"：`NoteRepository.saveNote` 每次保存整行覆盖（`NoteRepository.kt:30-57`），旧内容被直接丢弃，用户无法知道"什么时候改了什么"，误删内容也无法找回。用户希望有类似 git 的修改追踪体验：**按时间线查看每次保存的版本、看见具体差异、并能恢复到任意历史版本**；不需要分支/合并等复杂概念。

## 2. 目标与非目标

**目标**

- 每次有效保存自动产生一条历史快照（有实际变化才记），可查看每次保存的时间与变更字段。
- 版本详情以 diff 形式展示与上一版的具体差异（行级 + 行内字符级高亮），并可切换查看该版全文。
- 可一键恢复到任意历史版本；恢复本身也产生一条新历史（可反悔）。
- 每篇最多保留 50 条，超出自动清理最旧；40 条起在历史页横幅提醒，保存把条数顶到 40 时在编辑页 Snackbar 提醒一次。
- 零新增依赖、零新增权限；保持现有"小内存"基调（快照只存文本，图片不复制文件）。

**非目标（本次不做）**

- 分支、合并、多端同步、自动快照（仅保存点）、回收站、备份 zip 包含历史、历史搜索、手动删除单条历史。
- 修改现有备份导入导出、图片导出、txt 导出的行为。

## 3. 数据模型与迁移

### 3.1 新表 `note_revisions`

```kotlin
@Entity(
    tableName = "note_revisions",
    foreignKeys = [ForeignKey(
        entity = NoteEntity::class,
        parentColumns = ["id"],
        childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("noteId")]
)
data class NoteRevisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val title: String,
    val content: String,
    val categoryId: Long?,   // 无外键：与现有"删分类手动置空"风格一致
    val pinned: Boolean,
    val color: Int?,
    val savedAt: Long        // 该次保存的时间（= 保存后笔记的 updatedAt）
)
```

- 删笔记由外键级联清历史（Room 默认开启外键约束），`deleteNote` 流程不变。
- `categoryId` 不设外键：删分类走现有 `clearCategory`，历史里保留旧值，恢复时若分类已不存在则置空。

### 3.2 迁移

- `AppDatabase`：`entities` 增加 `NoteRevisionEntity`，`version = 2`；新增 `MIGRATION_1_2`（CREATE TABLE + CREATE INDEX，SQL 与 Room 期望完全一致）；`AppContainer` 在 `Room.databaseBuilder` 上 `.addMigrations(MIGRATION_1_2)`（项目首次引入迁移）。
- 这是本项目首次 DB 迁移，为主要风险点，用迁移测试兜底（见 §12）。

### 3.3 `NoteRevisionDao`

| 方法 | 说明 |
|---|---|
| `observeByNote(noteId): Flow<List<NoteRevisionEntity>>` | 按 `savedAt DESC, id DESC` |
| `getByNote(noteId): List<NoteRevisionEntity>` | 一次性读取（测试/对比用） |
| `countByNote(noteId): Int` | 计数、40/50 阈值判断 |
| `insert(revision): Long` | 写快照 |
| `trimTo(noteId, keep): Int` | 删除超出 keep 的最旧记录，返回删除行数 |
| `getContentsWithImageMarkup(): List<String>` | 图片 GC 引用集（SQL 层只取含 `![](img/` 的正文，避免全量载入内存） |

## 4. 写入策略（基线 / 去重 / 裁剪）

全部在 `NoteRepository.saveNote` 内、`AppDatabase.withTransaction`（room-ktx 已依赖）中完成：

1. **新建笔记**：插入笔记后立即写首条快照（`savedAt = now`）。
2. **已有笔记**：
   - **基线兜底**：若该笔记 `countByNote == 0`（如功能上线前的老笔记、备份导入的笔记），先按保存前的老内容补一条（`savedAt = 老 updatedAt`），保证老状态不丢。
   - **去重**：`title / content / categoryId / pinned / color` 与旧值完全一致 → 不写新快照（`updatedAt` 是否变化不算改动）。
   - 有变化 → 更新笔记 + 写一条新快照（`savedAt = now`）。
3. **裁剪**：每次写入后 `trimTo(noteId, 50)`；若发生裁剪，事务提交后调用图片 GC（被裁掉版本的图片引用随之下线）。
4. `saveNote` 返回类型不变（`Long`）；`NoteRepository` 新增 `countRevisions(noteId): Int` 供编辑页做 40 条提醒。

> 说明：恢复也走 `saveNote`，所以"恢复到旧版"天然产生新快照；若恢复结果与当前完全一致（去重命中），则不产生新记录。

## 5. 恢复

- `NoteRepository.restoreRevision(noteId, revisionId): Boolean`：取快照 → 若 `categoryId` 对应分类已不存在则置空 → 按快照调 `saveNote` 写回；`createdAt` 不变、`updatedAt = now`。
- 返回 false 表示版本不存在（并发删除等），UI 提示失败。

## 6. UI 设计

### 6.1 入口

- 编辑页顶栏新增"历史"图标（`Icons.Filled.History`），仅已保存笔记（`noteId != 0`）显示；点击进入历史页 `note_history/{noteId}`。

### 6.2 历史列表

- 顶部栏：返回 + 标题"历史记录" + 计数 `N/50`。
- 警示横幅（40 ≤ N < 50）："历史已达 N/50 条，满 50 条后最旧记录会自动清理"；N = 50 时文案改为"历史已满 50 条，最旧记录将随新记录自动清理"。
- 列表项（新 → 旧）：时间 `yyyy-MM-dd HH:mm`（复用 `TimeFormat`）+ 变更字段标签 + 最新一条标"当前版本"。
- 变更字段标签：与该版上一版做字段级对比（O(1)），标签如"标题已修改""正文已修改""分类已修改""置顶已修改""颜色已修改"；首条为"初始版本"；无变化（理论不可达）不显示。
- 空态：从未产生历史的笔记显示"保存一次后开始记录"。

### 6.3 版本详情

- 点击列表项进入详情（同页内部状态切换，非新路由；系统返回先关详情）。
- 头部：该版时间 + 变更字段标签 + 操作"恢复此版本"（"当前版本"上隐藏）。
- 视图切换：「对比」（默认）/「全文」。
- 对比视图：与**上一版**的行级 diff（§7），删除行红底 + 删除线、新增行绿底、改动行行内字符级高亮；不做折叠，完整展示。
- 全文视图：该版完整内容（纯文本展示）。
- 首条历史：无上一版可比 → 直接显示全文 + "初始版本"标记。
- diff 计算在 `Dispatchers.Default` 后台执行后回填 UI。

### 6.4 恢复交互

- 点"恢复此版本" → `AlertDialog`："将用此版本覆盖当前内容，并生成一条新的历史记录。确定恢复吗？"
- 确认后按钮进入忙状态（防重入）；成功后：关闭详情、刷新列表（新记录出现），并**返回笔记列表页**（把编辑页一并移出回退栈——编辑页的本地未保存文本已过期，回列表再打开最干净）；失败 Snackbar"恢复失败"。
- 恢复后的时间线里，新记录即为刚才的恢复结果，"当前版本"标记随之上移。

### 6.5 40 条提醒

- 保存后若 `countRevisions(noteId) == 40`，编辑页 Snackbar 提示一次"该笔记历史已 40 条，满 50 条后最旧记录会自动清理"，显示完照常返回（仅此一次，不重复打扰）。

## 7. diff 算法（纯函数）

位置：`ui/history/NoteDiff.kt`（与 `NoteContentParser` 同层级的展示逻辑），零依赖手写。

1. 按 `\n` 切行；忽略行尾 `\r`（粘贴内容兼容）。
2. 裁剪公共前缀行、公共后缀行。
3. 中段做行级 LCS（DP）：若 `行数乘积 > 1_000_000` 则跳过对齐，直接"全删 + 全增"兜底（保证大文本不卡顿）。
4. 归并为「删除段 / 新增段」连续块；相邻的删除段与新增段按下标配对：配对行再做**字符级公共前后缀裁剪**，只高亮真正变化的中间字符；未配对行整行高亮。
5. 输出 `List<DiffLine>`：`type(UNCHANGED/REMOVED/ADDED)` + `text` + `emphasis: List<IntRange>`。

UI 配色（固定值，深浅主题都可读）：删除行背景 `#33EF5350`、新增行背景 `#334CAF50`；行内强调背景 `#66EF5350` / `#664CAF50`；文字颜色取主题 `onSurface`。

## 8. 图片 GC 与一致性

- `NoteRepository.collectImageGarbage` 引用集：`noteDao.getAll()` ∪ `noteRevisionDao.getContentsWithImageMarkup()`，经 `NoteContentParser.extractImageNames` 提取后交给 `ImageStore.collectGarbage`。
- 效果：正文里删掉的图片只要还被历史引用就不会被回收；版本被裁剪出 50 条后，其专属图片随 GC 回收。
- GC 触发点：删除笔记后（现有）+ 保存发生历史裁剪后（新增）。

## 9. 架构与数据流

### 9.1 新增/修改文件

```
data/db/
├── NoteRevisionEntity.kt    # 新增：快照实体
├── NoteRevisionDao.kt       # 新增：快照查询/写入/裁剪
└── AppDatabase.kt           # 修改：version=2 + MIGRATION_1_2
data/repository/NoteRepository.kt  # 修改：保存钩子（事务）/恢复/GC/计数
ui/history/
├── NoteDiff.kt              # 新增：行级+行内 LCS diff 纯函数
├── NoteHistoryViewModel.kt  # 新增：时间线/详情/diff/恢复状态
└── NoteHistoryScreen.kt     # 新增：历史列表 + 详情 + 恢复确认
ui/notes/NoteEditScreen.kt   # 修改：历史入口图标 + 40 条 Snackbar
ui/notes/NoteEditViewModel.kt# 修改：保存后计数并上报 40 条事件
ui/navigation/AppNavHost.kt  # 修改：note_history/{noteId} 路由
di/AppContainer.kt           # 修改：迁移 + NoteRevisionDao 注入
```

### 9.2 数据流

1. 编辑页保存 → `NoteRepository.saveNote`（事务：笔记 + 快照 + 裁剪）→ 若裁剪则 GC。
2. 历史页 `NoteHistoryViewModel(noteId)` 通过 `observeRevisions(noteId)` 驱动 `StateFlow<NoteHistoryUiState>`（列表 + 变更标签 + 计数 + 横幅）。
3. 选择版本 → 后台算 diff → 详情状态（diff 行 / 全文 / 是否首版 / 是否当前版）。
4. 恢复 → `restoreRevision` → 成功后事件通知 UI 返回笔记列表并刷新。

## 10. 错误处理

| 场景 | 行为 |
|---|---|
| 保存时数据库事务失败 | 沿用现有行为（异常向上抛）；不产生半截历史 |
| 恢复的版本已被并发裁剪 | 返回 false → Snackbar"恢复失败" |
| 恢复时分类已删除 | 该字段置空为"未分类" |
| 首条历史 / 无上一版 | 显示全文 + "初始版本" |
| 无历史（老笔记未保存过） | 空态"保存一次后开始记录" |
| 超大文本 diff 超阈值 | 退化为全删全增展示，不卡顿 |
| 恢复中重复点击 | 按钮忙状态防重入 |
| 图片文件缺失（渲染无关） | 历史仅存文本，不涉及图片文件读取 |

## 11. 边界

- 无变化保存不产生历史；恢复结果与当前一致时同样不产生（去重规则全局一致）。
- 第 51 条写入时自动裁掉最旧一条；被裁版本的图片在本次 GC 中回收。
- 备份 zip 不含历史：导入到新设备后历史为空，老内容在下次保存时成为基线（§4.2）。
- 不在编辑页显示未保存内容的"临时历史"（非目标）。

## 12. 测试策略

- **`NoteDiffTest`（纯 JVM 单测，不依赖 Android）**：新增/删除/未变/相邻改动配对；行内字符高亮范围；边界（空内容、单行、全改、行尾 `\n`、`\r\n`、超阈值退化）。
- **迁移测试（Robolectric）**：手工按 v1 DDL 建 SQLite 库（notes/categories），用 Room 挂 `MIGRATION_1_2` 打开 → 老数据保留、`note_revisions` 可写、插入/查询正常。
- **`NoteRepositoryTest` 扩展**：新笔记写首条；无变化不写；老笔记首保存补基线 + 新快照；第 51 条裁剪；删笔记级联清历史；恢复写回并记新历史（分类已删置空）；GC 保留历史引用、裁剪后回收。
- **`NoteHistoryViewModelTest`**：列表与计数、40 横幅阈值、详情 diff 状态、恢复防重入与失败事件。
- **`NoteEditViewModelTest` 扩展**：保存到 40 条时上报提醒事件（仅该阈值）。
- 现有 70 个单测保持全绿；`.\gradlew :app:testDebugUnitTest`、`:app:assembleDebug`、`:app:assembleRelease` 通过。
- 不新增 Compose UI 自动化测试（与现有惯例一致），UI 走人工清单。

## 13. 人工验证清单（真机）

1. 旧版本 APK 覆盖安装新版本：数据完好，老笔记首次保存后时间线出现"基线 + 新版"两条。
2. 连续保存、内容不变：不新增历史。
3. 改标题/正文/分类/置顶/颜色：列表标签正确。
4. 浅色与深色主题下 diff 红绿可读；中文长段落只高亮变化的字。
5. 恢复到旧版：确认框 → 列表出现新记录 → 自动回到笔记列表 → 打开笔记内容为旧版。
6. 恢复分类已删除的版本：显示未分类。
7. 40 条横幅、保存到 40 条时的 Snackbar、第 51 条自动裁剪。
8. 历史引用图片：正文删图保存后图片仍在；该版本被裁出 50 条后图片回收。
9. 删除笔记：历史一并清空。
10. 新建空笔记保存后再编辑：历史正常。

## 14. 风险

| 风险 | 缓解 |
|---|---|
| 首次 DB 迁移出错导致老用户数据损坏 | 迁移 SQL 与实体严格对齐 + 迁移测试，真机清单第 1 项覆盖安装验证 |
| 大文本 diff 卡顿 | 行数乘积阈值退化策略；diff 在后台线程计算 |
| 图片 GC 误删历史引用的图片 | GC 引用集包含全部历史；专门单测（正文删图后文件仍在） |
| 恢复后编辑页状态过期 | 恢复成功即返回笔记列表，编辑页出栈 |
