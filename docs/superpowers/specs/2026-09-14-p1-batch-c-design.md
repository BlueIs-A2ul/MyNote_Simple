# P1 批次 C 设计：搜索信息补齐、排序全视图、列表性能、分类颜色、设置分区（1.4.4）

> 日期：2026-09-14｜范围：backlog 条目 29–35（全部 P1、非 AI 相关）｜版本：patch → 1.4.4

## 条目 29 · 搜索分类归属 / 深命中线索 / 排序口径拍板

- **现状**：搜索全库但隐藏 tab 与行内分类名；命中在正文深处时摘要无高亮线索；排序只在「全部」tab 生效，分类/未分类/搜索视图静默忽略且菜单无解释（与 #11 验收「搜索/分类视图沿用」、#16 验收「非全部 tab 禁用」冲突）。
- **拍板**：以 #11 口径为准，**排序作用于全部视图**（#16 的禁用说明作废）。DAO 为 `observeByCategory` / `observeUncategorized` / `search` 各补 CREATED_DESC 与 TITLE_ASC 变体（默认方法按 `NoteSortMode` 分发，新增 SQL 不变更既有查询）；Repository 与 ViewModel 全链路透传 `sortMode`；排序菜单在非多选态始终可用。
- **搜索信息补齐**：行内分类名条件放宽为「全部 tab **或**搜索态」；新增纯函数 `snippetForHighlight(text, query, maxChars)`——命中在前 maxChars 内取前缀，否则取命中位置附近窗口（带省略号），`NoteRow` 搜索态用其生成摘要与高亮。
- **测试**：`NoteDaoTest` 补排序变体各视图用例；`HighlightTextTest` 或新用例补 `snippetForHighlight`（命中靠后取窗口、无命中取前缀、边界省略号）。

## 条目 30 · 相对时间分钟/小时级

- **现状**：`TimeFormat.relativeDate` 只有 今天/昨天/M月d日/yyyy年M月d日 四档，60s tick 刷不出任何变化。
- **方案**：在既有档位前插入 `<60s「刚刚」、<60min「N 分钟前」、<24h「N 小时前」`（沿用「今天/昨天」口径，跨天优先）。补边界单测（59s/60s、59min/60min、23h/24h）。

## 条目 31 · 置顶视觉增强 + 回收站行可预览

- **现状**：置顶图钉 12dp 同色小图标，无分区；回收站行纯 Row 不可点，彻底删除前无法预览。
- **方案**：
  - 列表：图钉改 `primary` 色；LazyColumn 中置顶/其余之间插分组标题（「置顶」「其他」，无置顶时不显示）。
  - 回收站：行 clickable → 只读预览（标题 + 纯文本摘要 + 「图片 N 张」+「还有 N 天自动清理」）；彻底删除/清空确认框带标题与摘要预览。
- **测试**：TrashViewModel/预览计算（剩余天数）可单测；纯 UI 部分不加组合测试。

## 条目 32 · 搜索防抖 + NoteRow 重组/解析缓存

- **现状**：`NotesViewModel` 每键重启 LIKE 全表扫描；`NoteRow` 每次重组全量 `plainText` + 高亮。
- **方案**：VM 内 `query.debounce(200).distinctUntilChanged()` 后再进组合流（输入框仍即时响应）；`NoteRow` 用 `remember(note.content)` 缓存纯文本、`remember(note.content, query)` 缓存高亮文本；`NotesScreen` 的分类名查找改 `remember(categories)` 的 id→entity Map。
- **测试**：VM 防抖链路（设 query 后不立即出结果、虚拟时间推进后出结果）；纯函数部分沿用既有测试。

## 条目 33 · 分类颜色可选可改

- **现状**：分类颜色 `NoteColors.random()` 随机；新建/编辑对话框无颜色选项；6 色板中两色相近，>6 分类必然撞色。
- **方案**：新建对话框加 6 色选择（默认「最少使用色」替代随机）；行内分类点编辑时点色点循环换色并持久化（Repository 新增 `updateCategoryColor`）。
- **测试**：`CategoriesViewModelTest` 补「指定颜色创建」「默认取最少使用色」「换色持久化」。

## 条目 34 · 设置页通用分区

- **现状**：设置页只有外观；默认排序仅藏在首页菜单；回收站保留期硬编码 30 天。
- **方案**：新增「通用」分区：
  - 默认排序：三选一，读写现有 `NoteSortStore`。
  - 回收站保留期：新增 `TrashRetentionStore`（7/30/90 天，默认 30），`NoteRepository.purgeExpiredDeletedNotes` 的 ttl 由调用方（MainActivity 启动清理、TrashViewModel）经 store 提供；回收站页文案显示实际保留天数。
  - 列表密度：本期不做（涉及 NoteRow 布局参数化，记为后续）。
- **测试**：`TrashRetentionStore` 读写默认值；`SettingsViewModelTest` 补排序/保留期联动。

## 条目 35 · 动态取色与色板联动反馈

- **现状**：动态取色开启时 8 色圈仍显示选中环（指向旧索引，实际色来自壁纸）；点色圈静默关闭动态取色。
- **方案**：动态开启时色板降透明度并显示「使用系统取色」提示；点任意色圈 → 关闭动态取色 + 选中该色 + snackbar「已切换到手动取色」。

## 收尾

全量单测通过 → 1.4.4 / 1_04_04 → README 同步（版本/测试数/产物名）→ backlog 条目 29-35 置已完成（v1.4.4）→ 本地提交（不 push）。