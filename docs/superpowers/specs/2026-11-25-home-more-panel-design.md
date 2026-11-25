# 主页「更多」面板设计文档

> 用户需求：主页右上角溢出菜单内容太多，整理收纳；面板**不用移动端原生底部弹层**，自行设计。目标版本 **1.14.0**（新功能，minor 递增）。

## 背景与目标

现状：主页顶栏右上「⋮」弹出 M3 `DropdownMenu`，正常态 9 项——分类管理 / 回收站 / 日历 / 排序×3 / 导出备份 / 导入备份 / 设置；多选态另有一份 5 项（全选 / 置顶 / 取消置顶 / 移动到分类 / 删除）。九项平铺成一条长下拉，既没有分组，排序又摊平成三行带「✓」前缀的长文案，扫读成本高。

目标：把「⋮」换成一枚自绘的**纸片面板**，按「浏览 / 整理 / 数据」分组承载同样的入口，排序收成行内单选。所有条目仍是**一次点击到达**（不增加跳转层级），只是从纵向长列表变成有结构的分栏。

两项结构性变化：

1. 分类管理从菜单中移除——同批次合入的左侧分类抽屉（`CategoryDrawer`）底部已有「分类管理」入口，两处并存是重复（本分支叠加在 `home-category-drawer-1125` 之上）。
2. 排序三项合并为「整理」组的行内单选，不再占三行。

## 面板结构

```
（遮罩：全屏，onBackground 28% 不透明度，点击关闭）
┌────────────────────────────────────────────┐  ← 顶边 1dp hairline
│                 ─────                      │  ← 28×2dp 纸边短横线
│  浏览 ────────────────────────────────     │
│     日历                                ›  │
│     回收站                              ›  │
│  整理 ────────────────────────────────     │
│     ◉ 最近更新                             │
│     ○ 最新创建                             │
│     ○ 按标题                               │
│  数据 ────────────────────────────────     │
│     导出备份                               │
│     导入备份                               │
│  ────────────────────────────────────────  │
│     设置                                ›  │
└────────────────────────────────────────────┘  ← navigationBarsPadding
```

多选态沿用同一面板，分两组：

```
（标题处已有「已选 N 项」，面板不再重复计数）
  选择 ────────────────────────────────
     全选
  操作 ────────────────────────────────
     置顶
     取消置顶
     移动到分类
     删除                                  （error 色）
```

## 交互设计

- **展开**：点顶栏「⋮」→ 遮罩与面板同时入场（面板自下 32dp 处升起并落定），无系统拖拽把手、无锚点档位。
- **关闭**：点遮罩、系统返回键、选中任一条目，三种方式等价。面板打开期间注册 `BackHandler`，优先级高于搜索/多选的返回处理。
- **排序**：点任意一行即选中并关闭面板；选中态是自绘圆环 + 实心圆点，不显示「✓ 排序：」这类长前缀，组内一行一个模式。
- **禁用**：导出备份在导出进行中禁用（沿用 `exporting`）；多选态下置顶 / 取消置顶 / 移动到分类 / 删除在空选时禁用（沿用现有守卫）。
- **滚动**：内容高度自适应；超过屏幕 85% 时面板内部纵向滚动，安全区仍贴底。
- **无障碍**：面板根节点 `paneTitle = "更多操作"`，展开时把焦点移入面板；排序组用 `selectableGroup()` + `Role.RadioButton`，行文案即名称；遮罩不进入焦点树。

## 视觉规范（纸感）

| 项 | 取值 | 理由 |
|---|---|---|
| 面板底色 | `colorScheme.background` | 与页面同色，靠遮罩与 hairline 分层，全 App 不用阴影 |
| 顶边 | 1dp `outlineVariant` hairline | 纸片与列表的分界 |
| 圆角 | 0 | 直边更像一张纸；抽屉虽用 8dp，但那是浮起的卡片，这里是贴底推入的纸片 |
| 纸边短横线 | 28×2dp，`onSurfaceVariant` 35%，居中，上间距 10dp | 替代原生 sheet 的拖拽把手，只作"可关闭"的视觉线索 |
| 组标题 | `labelMedium` + `onSurfaceVariant`，右侧接一条 `outlineVariant` 引导线（weight 1f），上 16dp / 下 4dp | 印刷目录式排版，分组一眼可辨 |
| 行 | 高 48dp，左右 24dp，`bodyLarge` | 与抽屉行同密度 |
| 导航类行尾 | 16dp 细箭头 `onSurfaceVariant` | 区分"去某页"与"就地切换" |
| 单选环 | 16dp 圆环 1.5dp（未选 `outlineVariant` / 选中 `primary`）+ 8dp 实心点 | 不用系统 RadioButton，避免原生观感 |
| 设置 | 上方 hairline + 56dp 行高 | 一级入口，单独成块 |
| 按压反馈 | 系统 ripple（有界） | 反馈可识别，不自造 |

**动效**（手写 `updateTransition`，不用 `ModalBottomSheet` 的内建动画）：

- 遮罩 alpha：0 → 0.28，tween 220ms。
- 面板位移：32dp → 0，`spring(dampingRatio = 0.88f, stiffness = StiffnessMediumLow)`——轻微沉降感，像纸落到桌面，不做回弹夸张。
- 面板 alpha：0 → 1，tween 160ms；关闭时 140ms 反向、位移 16dp 退出。

## 技术方案

- **不用 `ModalBottomSheet`**：它自带拖拽把手、8dp 圆角、阴影、锚点高度档位与系统滑动手势，造型不可控，也无法做组标题引导线这类排版。用户也明确要求自绘。
- **不用 `Dialog` / `Popup` 独立窗口**：需要遮罩与面板同帧演出，且要复用页面主题；同窗口内 `Box` 覆盖层最省事，也能直接用 `BackHandler`，不涉及窗口 token 与 IME 锚点问题。
- **接入方式**：`NotesScreen` 最外层 `Box`，`ModalNavigationDrawer` 之后放 `PaperActionPanel`，覆盖顶栏与列表。
- **新组件** `ui/components/PaperActionPanel.kt`（无业务状态，纯渲染）：
  - `PaperActionPanel(expanded, onDismissRequest, content)`
  - `PaperPanelGroup(title, content)`
  - `PaperPanelItem(text, enabled, destructive, onClick)`
  - `PaperPanelChoice(text, selected, onClick)`
- **排序数据** `ui/notes/NoteSortOptions.kt`：`NoteSortOption(mode, label, selected)` + `noteSortOptions(current)` 纯函数，顺序固定为「最近更新 / 最新创建 / 按标题」；`NoteSortMode.label()` 从 `SettingsScreen` 的私有 `sortModeLabel` 提上来共用（设置页与面板同一文案源）。
- `PaperOverflowMenu`（下拉）保留，编辑页 4 项仍用它，本次不动。
- 不新增依赖、不新增权限、不涉及数据层与 schema。

## 涉及文件

- 新：`ui/components/PaperActionPanel.kt`、`ui/notes/NoteSortOptions.kt`、`app/src/test/java/com/mynote/app/ui/notes/NoteSortOptionsTest.kt`、本设计文档与配套计划。
- 改：`ui/notes/NotesScreen.kt`（两处菜单换面板、分类管理移除、面板状态、BackHandler）、`ui/settings/SettingsScreen.kt`（改用共用 label）、`app/build.gradle.kts`（版本）、`README.md`（版本行 / 测试数）。

## 测试要点

1. `noteSortOptions`：三种模式各自选中互斥、顺序固定、文案与设置页一致。
2. `NoteSortMode.label()` 覆盖全部枚举分支（穷举 `when`，新增模式编译期报错）。
3. 回归：`NotesViewModelTest`、`CategoryDrawerTest` 等既有用例全绿；`assembleDebug` 通过。
4. 手工（有设备时）：展开/关闭三种方式、排序切换即时生效且重启保留、多选态空选禁用、导出中禁用、深色模式与 8 档主题色、窄屏滚动、TalkBack 焦点与返回键。

## 版本

`1.13.0` → **1.14.0**（`versionCode` 11400）。依赖分类抽屉分支先合入 master（本分支以其为基线）。

## 修订记录

- 2026-11-25：初版。方案对比中「原位收纳（排序/备份各收一层）」因仍需两步点击、「顶栏分层（日历上顶栏）」因顶栏已有标题副标题与搜索，均落选；采用分组面板。
