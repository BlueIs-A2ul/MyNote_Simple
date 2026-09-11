# 纸感极简 UI 改版 — 设计文档

- 日期：2026-09-11
- 状态：设计已确认，待实现
- 关联：`docs/superpowers/specs/2026-09-10-theme-settings-design.md`（主题色管理）、`docs/superpowers/specs/2026-08-13-mynote-design.md`

## 1. 背景与问题

功能完整，但视觉呈现是「简陋」而不是「简洁」。诊断出的五个根因：

1. **全默认**：`ui/theme/Type.kt` 只有一行 `Typography()`；圆角、阴影、表面色全走 Material 3 默认值 → 像脚手架 Demo。
2. **控件压过内容**：列表页重边框搜索框 + 一排 FilterChip；编辑页 5 个顶栏图标 + 一排 chip + 底部「图标+文字」行，视觉噪音大于笔记本身。
3. **层级扁平**：所有笔记卡同一权重，标题/摘要/时间只靠默认字号差，没有留白节奏。
4. **三套色彩打架**：笔记 `color` 整卡填充、分类色、主题色互不相让。
5. **细节缺失**：空状态只有一行灰字，没有引导与状态过渡。

结论：做一次覆盖全部界面的「纸感极简」视觉改版。极简不是「没有设计」，而是把设计放进排版、留白和层级里。

## 2. 目标与非目标

**目标**

- 建立一套纸感设计系统（颜色 / 排版 / 间距 / 圆角），覆盖 6 屏与全部对话框。
- 内容优先：控件退居次要，排版承担层级。
- 保留现有功能行为、设置项与数据；零新增第三方依赖。

**非目标（本次不做）**

- 不改数据库、仓库、备份、导出渲染逻辑、导航结构。
- 不加新功能（长按多选、滑动操作、自由取色等）。
- 不做大圆角、大阴影、花哨转场。
- 不改导出 PNG 的纯白输出（仍不跟随主题）。

## 3. 已确认决策（brainstorm 记录）

| 决策点 | 结论 |
|---|---|
| 设计方向 | A · 纸感编辑风（暖白纸底、去卡片容器、发丝线、排版即设计） |
| 圆角 | 小圆角，上限 8dp |
| 字体 | 标题衬线（宋体感）+ 正文/界面黑体 |
| 列表搜索 | 收进图标，点击展开为无边框输入行 |
| 分类筛选 | 文字 tab + 2dp 强调色下划线 |
| 编辑页 | 底部工具条（图片 / 分类 / 预览） |
| 主题 | 纸面背景固定，主题色只做强调 |
| 颜色 | 分类色 = 行首 3dp 细色条；笔记 `color` 字段不再参与视觉 |

## 4. 设计系统

### 4.1 颜色（`ui/theme/Color.kt`、`Theme.kt`）

纸面中性色（浅色 / 深色）：

| Token | 浅色 | 深色 |
|---|---|---|
| background | `#FAF8F4` | `#1C1B19` |
| surface | `#FFFDF9` | `#242320` |
| surfaceVariant | `#F1EDE6` | `#2C2A27` |
| surfaceContainerLow | `#F6F2EB` | `#211F1D` |
| surfaceContainer | `#F1EDE6` | `#2C2A27` |
| surfaceContainerHigh | `#EDE8E0` | `#35322E` |
| onBackground / onSurface | `#2B2A27` | `#EDEAE4` |
| onSurfaceVariant | `#8A857D` | `#A39D93` |
| outline | `#D8D2C8` | `#45423D` |
| outlineVariant（发丝线） | `#E8E4DC` | `#35332F` |

- 主题色（8 预设 + 动态取色）只决定 `primary` / `primaryContainer` / `secondary` 等强调槽位；`Theme.kt` 在最终 scheme 上 `.copy()` 覆盖上表全部中性 token。动态取色与预设逻辑、设置入口全部保留。
- **纸感分类色板**（6 色，低饱和）：赭石 `#C98A6B`、苔绿 `#7FA588`、黛蓝 `#8E9BC4`、藤黄 `#B8A06E`、绛紫 `#A58AA8`、青灰 `#7FA8A5`。
- **旧分类色映射**：`PaperPalette.nearest(argb)` 将库中已存的高饱和分类色按 HSV 色相圆距离映射到最近的纸感色；饱和度 < 0.1 时回退到第一色。映射只影响显示（`NoteRow` 色条、`CategoryDot`），不改数据库；纸感色板自身颜色映射结果不变（幂等）。
- 新分类的随机色从纸感色板取（`NoteColors` 替换为新板）。

### 4.2 排版（`ui/theme/Type.kt`）

| 用途 | 字体 | 字号/行高 | 字重 | M3 槽位 |
|---|---|---|---|---|
| 页面标题 | 衬线 | 20 / 28 | 700 | headlineSmall |
| 编辑页标题输入 | 衬线 | 18 / 26 | 700 | titleLarge |
| 列表笔记标题 | 衬线 | 16 / 24 | 600 | titleMedium |
| 正文 | 黑体 | 15 / 24 | 400 | bodyLarge |
| 摘要 / 次级说明 | 黑体 | 13 / 20 | 400 | bodySmall |
| 按钮 / tab | 黑体 | 13 | 500 | labelLarge |
| 时间戳 | 黑体 | 11 | 400 | labelSmall |

衬线用 `FontFamily.Serif`（Android 7+ 系统自带 Noto Serif CJK，minSdk 24 满足），不加字体文件；设备缺字时系统自动回退黑体。

### 4.3 间距

4 / 8 / 12 / 16 / 24dp 体系。屏幕水平边距 16dp；列表行垂直 padding 12dp；行内元素间距 8dp；区块之间 24dp；分隔一律用发丝线而不是留白切割。

### 4.4 圆角（`ui/theme/Shape.kt` 新增）

`extraSmall=4, small=4, medium=6, large=8, extraLarge=8`（dp），**上限 8dp**。底部弹层仅顶部圆角 8dp。

### 4.5 动效

仅列表增删用 `animateItem()`；页面切换沿用导航默认，不加自定义转场。

## 5. 复用组件（`ui/components/` 新增）

按职责拆分为小文件，避免单文件膨胀：

| 文件 | 内容 |
|---|---|
| `PaperTopBar.kt` | `PaperTopBar`：56dp 高；标题 20sp 衬线；可选返回图标与尾部 actions；背景 `background`，无阴影。同文件附 `HairlineDivider`（1dp `outlineVariant`） |
| `TextTabRow.kt` | 横向可滑文字 tab；选中 = `onBackground` + 2dp `primary` 下划线，未选 = `onSurfaceVariant`；13sp/500 |
| `NoteRow.kt` | 列表行：3dp 分类色条 + 衬线标题 + 2 行摘要 + 右侧相对日期；置顶时标题前 12dp pin 图标；色条用 `PaperPalette.nearest()` 映射后的分类色 |
| `EmptyState.kt` | 居中细线图标 + 13sp 次级文案 + 强调色文字按钮 |
| `PaperAlertDialog.kt` | 统一 AlertDialog 外观：6dp 圆角、衬线 16sp 标题、`surface` 容器 |
| `CategoryDot.kt` | 6dp 圆形分类色点（映射后的纸感色），供分类管理与分类弹层使用 |

## 6. 各屏方案

### 6.1 笔记列表 `NotesScreen`

- 顶栏：`PaperTopBar`「备忘录」+ 搜索图标 + ⋯（分类管理 / 导出备份 / 导入备份 / 设置）。
- 搜索：点图标在顶栏下方展开无边框输入行（底部发丝线），自动聚焦，右侧关闭按钮；**激活时隐藏分类 tab**，关闭后恢复；查询语义不变（全局搜索，不受分类限制）。
- 分类：`TextTabRow`，「全部」+ 各分类；横向滚动（修复现 `Row` 溢出问题）。
- 列表行 `NoteRow`：
  - 行首 3dp 分类色条（高随内容，圆角 1.5dp）；未分类用 3dp 透明占位保持对齐。
  - 标题（衬线 16sp/600，1 行）→ 摘要（13sp/20，2 行，`onSurfaceVariant`）→ 右侧相对日期（11sp）。
  - 置顶行标题前加 12dp pin 图标（`onSurfaceVariant`），排序仍 `pinned DESC, updatedAt DESC`。
  - 行间发丝线；行垂直 padding 12dp。
- 相对日期：`TimeFormat.relativeDate(timestamp: Long, now: Long = System.currentTimeMillis())`——今天 →「今天」，昨天 →「昨天」，同年 →「M月d日」，跨年 →「yyyy年M月d日」。
- 空状态：`EmptyState`（细线图标 +「还没有笔记」+「写第一条」）。
- FAB：40dp、8dp 圆角、`primary` 底。

### 6.2 笔记编辑 `NoteEditScreen`

- 顶栏：无页面标题；← · 置顶图标（激活时 `primary`）·「保存」文字按钮（`primary`）· ⋯（历史记录 / 导出 / 删除，删除用 `error` 色）。原 5 个图标收敛为 2 个 + 菜单。
- 标题输入：无边框 `BasicTextField`，衬线 18sp/700，占位「标题」。
- 正文输入：无边框 `BasicTextField`，15sp/24，占位「开始记录…」。
- 底部工具条（正文下方，发丝线上边）：`图片` `分类▾` `预览` 三个 13sp 文字按钮水平均布；键盘弹出时通过 `imePadding` 贴在键盘上方。
- 分类选择：`分类▾` 打开底部弹层（顶部 8dp 圆角）：「未分类」+ 各分类（`CategoryDot` + 名称，选中态 `primary`）+「+ 新建分类」。**行为微调：新增「未分类」项，可清除笔记分类**（当前只能改不能清）。
- 预览：工具条「预览」切换（激活时文字 `primary`）；正文区按 `NoteContentParser.parse` 渲染文本与图片（图片 8dp 圆角）。
- 插入图片：追加 `![](img/…)` 到正文末尾，逻辑不变。
- 历史上限 snackbar、删除确认、导出选择对话框行为不变，仅换 `PaperAlertDialog` 外观。

### 6.3 分类管理 `CategoriesScreen`

- 顶栏：`PaperTopBar`「分类管理」+ 返回 +「新建」文字按钮。
- 行：`CategoryDot` + 名称（15sp）+ 右侧删除图标（18dp，`onSurfaceVariant`）；点行内联重命名（无边框输入 + 保存/取消文字按钮）；行间发丝线。
- 空状态：`EmptyState`（「暂无分类」+「新建分类」）。

### 6.4 设置 `SettingsScreen`

- 顶栏：`PaperTopBar`「设置」+ 返回。
- 分组：小字 label 标题（如「外观」）+ 发丝线分隔的条目。
- 深色模式：`TextTabRow`（跟随系统 / 浅色 / 深色）替换大圆角 `SegmentedButton`。
- 动态取色：保留 `Switch` 行（Android 12+ 显示），标题 + 13sp 说明文字。
- 主题色：8 个 28dp 圆点，选中描边 1.5dp `onBackground`；点击时若动态取色开启则自动关闭（行为不变）。

### 6.5 历史记录 `NoteHistoryScreen`

- 顶栏：`PaperTopBar`「历史记录 (n/50)」+ 返回（详情态返回关闭详情，行为不变）。
- 列表：行 = 时间（15sp）+ 变更摘要（13sp `onSurfaceVariant`）+「当前版本」11sp `primary`；发丝线分隔。
- 上限提示 banner：`surfaceVariant` 底、6dp 圆角、13sp。
- 详情：「对比 / 全文」改为 `TextTabRow`（首条只有全文）；diff 红绿功能色与行删除线保持不变；底部「恢复此版本」保留填充按钮，4dp 圆角。
- 恢复确认走 `PaperAlertDialog`。

### 6.6 导出预览 `NoteExportDialog`

- 全屏 Dialog 保留；顶栏 `PaperTopBar`「导出图片」+ 返回。
- 「分页 N 张 / 单张长图」改为 `TextTabRow`；长图提示 13sp。
- 页面预览图：8dp 圆角 + 1dp `outlineVariant` 描边；进度条、保存/分享按钮逻辑不变，按钮 4dp 圆角。
- 导出渲染与 PNG 输出不受本次改版影响。

## 7. 边界与错误处理

| 场景 | 行为 |
|---|---|
| 库中旧的高饱和分类色 | 显示时 `PaperPalette.nearest()` 映射，不改库 |
| 分类色无法映射（低饱和灰） | 回退纸感色板第一色 |
| 搜索激活时 | 隐藏分类 tab；查询仍全局；关闭搜索恢复原分类筛选 |
| 键盘弹出（编辑页） | 底部工具条随 `imePadding` 上移，不被遮挡 |
| 系统无衬线中文字体 | 回退黑体，布局不破 |
| 深色模式 | 纸面换暖黑，分类色板/强调色不变 |
| 导出 PNG | 固定纯白，与纸面主题无关 |

## 8. 测试策略

- **新增** `TimeFormatTest`：`relativeDate` 今天 / 昨天 / 同年 / 跨年 / 零点边界。
- **新增** `PaperPaletteTest`：色板 6 色；高饱和色映射到预期最近色；低饱和回退第一色。
- 现有 113 个单测保持全绿；`ThemePresetsTest` 不受影响（本方案只覆盖中性 token，不动预设 primary/container/secondary）。
- `.\gradlew :app:testDebugUnitTest` 与 `.\gradlew :app:assembleDebug` 通过；真机/模拟器手工核对全部屏幕的浅色/深色、预设主题色与动态取色。

## 9. 涉及文件

| 文件 | 变更 |
|---|---|
| `ui/theme/Color.kt` | 纸面中性色、纸感分类色板、`PaperPalette.nearest()` |
| `ui/theme/Type.kt` | 定制 `AppTypography` |
| `ui/theme/Shape.kt` | 新增，小圆角 Shapes |
| `ui/theme/Theme.kt` | scheme `.copy()` 覆盖纸面中性 token |
| `ui/components/PaperTopBar.kt` | 新增，`PaperTopBar` + `HairlineDivider` |
| `ui/components/TextTabRow.kt` | 新增 |
| `ui/components/NoteRow.kt` | 新增 |
| `ui/components/EmptyState.kt` | 新增 |
| `ui/components/PaperAlertDialog.kt` | 新增 |
| `ui/components/CategoryDot.kt` | 新增 |
| `ui/notes/NotesScreen.kt` | 列表页改版 |
| `ui/notes/NoteEditScreen.kt` | 编辑页改版（底部工具条、分类弹层） |
| `ui/categories/CategoriesScreen.kt` | 分类管理改版 |
| `ui/settings/SettingsScreen.kt` | 设置页改版 |
| `ui/history/NoteHistoryScreen.kt` | 历史页改版 |
| `ui/export/NoteExportDialog.kt` | 导出预览改版 |
| `util/TimeFormat.kt` | 新增 `relativeDate()` |
| `app/src/test/.../TimeFormatTest.kt` | 新增测试 |
| `app/src/test/.../PaperPaletteTest.kt` | 新增测试 |

不改动：`data/`（数据库、仓库、备份、导出渲染）、`di/AppContainer.kt`、`ui/navigation/AppNavHost.kt`、`MainActivity.kt`、各 ViewModel 逻辑。
