# P1 批次 A 设计：排序文案修正 + 撤销合并 + 列表加载态（1.4.2）

> 日期：2026-09-14｜范围：backlog 条目 21、22、23（全部 P1、非 AI 相关）｜版本：patch → 1.4.2

## 条目 21 · 排序文案「最早创建」与实现相反

- **现状**：`NotesScreen.kt:237` 文案「排序：最早创建」实际设置 `NoteSortMode.CREATED_DESC`（`NoteDao.kt:26-27` 为 `createdAt DESC`，最新在前；`NoteDaoTest.kt:83-88` 亦按此断言）。用户点选后看到的顺序与预期相反。
- **方案**：文案改「排序：最新创建」（含 ✓ 前缀变体）。SQL 与测试不动（它们描述的才是正确语义）。
- **测试**：纯文案，不加 Robolectric UI 测试（沿用既有约定）。

## 条目 22 · 撤销补合并/防抖策略

- **现状**：`NoteEditScreen.kt:466-470` 每次 `onValueChange`（含纯光标/选区移动）调用 `undoController.record(content)`；`NoteUndoController.kt:19-25` 无条件压栈 → 打一句话（30 字）要连按 30 次撤销；100 条上限很快被普通输入耗尽，长文不可撤销。backlog #3 承诺的「连续输入合并、防抖」当初未实现。
- **方案**（纯逻辑改造，零依赖，可单测）：
  - `record(before: TextFieldValue, atMillis: Long = now, force: Boolean = false)`。
  - 合并规则：上一条快照存在、距上次记录 ≤ `MERGE_WINDOW_MS`（600ms）、且两次快照**文本相同（仅选区变化）或相邻单字符增删**（长度差 1 且公共前后缀覆盖剩余部分）时，**替换栈顶**而非压栈。
  - 停顿超过窗口、或 `force = true`（插图、贴 AI 结果这类结构性变更）时强制新开一格。
  - 保留既有语义：任何 record 清空 redo 栈；超 `maxEntries` 丢最旧。
  - 调用点：`NoteEditScreen.kt:468` 默认；`:272`（AI 结果）与 `:287`（插图）传 `force = true`。
- **测试**：`NoteUndoControllerTest` 补：连续插入合并为一段、连续删除合并、窗口过期强制新格、force 强制新格、非相邻修改不合并、选区-only 变更折叠为一条。

## 条目 23 · 主页冷启动加载态

- **现状**：`NotesViewModel.kt:47` `notes` 初始 `emptyList()`，`NotesScreen.kt:340-353` 首帧即渲染「还没有笔记 + 写第一条」（可误点进入新建页），Room 首次结果到达前有一帧错误空态；主页是全应用唯一无加载态的页面。
- **方案**：
  - `NotesViewModel.notes: StateFlow<List<NoteEntity>?>` 初始 `null`（首个真实结果到达前为 null）；`selectAll()` 对 `notes.value.orEmpty()` 操作。
  - `NotesScreen`：`notes == null` → 渲染轻量居中占位（文案「加载中…」+ 小号 `CircularProgressIndicator`）；非空判空后再显示 EmptyState 或列表。
  - 搜索/筛选切换时 `flatMapLatest` 内层 Flow 首值可能短暂为 null 的问题：`stateIn` 会保留最近一次非空值，仅冷启动首帧为 null，符合预期。
- **测试**：`NotesViewModelTest` 适配可空类型（`it != null && it.isNotEmpty()` 等）；新增 `notesIsNullBeforeFirstEmission`。

## 收尾

全量单测通过 → `versionName 1.4.2` / `versionCode 1_04_02` → README 版本行、测试数、产物名同步 → backlog 条目 21/22/23 置「已完成（2026-09-14，v1.4.2）」→ 本地提交（`fix: 排序文案、撤销合并与冷启动加载态并发布 1.4.2`，不 push）。