# 主页分类抽屉侧边栏设计文档

> 用户新增需求。目标版本 **1.13.0**（新功能，minor 递增）。

## 背景与目标

现状：主页顶栏（`PaperTopBar`，标题「备忘录」）下方紧跟一条横向文字分类 tab（全部 / 未分类 / 各分类），分类一多就需横向滚动；这条 tab 既是顶栏的「第二层」，也长期占用列表上方的纵向空间。

目标：移除顶栏下方的分类 tab，把「分类」这层导航收进左侧抽屉式侧边栏（sidebar）；入口是「备忘录」标题旁内联的展开图标，展开后从左侧滑出，用于浏览与切换分类。列表区因此整体上移，首页回到「一个顶栏 + 内容」的单层结构。

不改变数据与筛选语义：筛选仍由 `NotesViewModel.selectedFilter` 单一来源驱动，分类实体、排序、多选、搜索、新建预选分类等既有规则全部保留；零新增依赖（复用 Material3 `ModalNavigationDrawer`，AI 会话页已有同款先例）。

## 交互设计

### 顶栏标题区

- 正常态：标题「备忘录」+ 副标题（当前筛选，如「全部笔记」「工作」）+ 尾部 chevron；chevron 是 40dp 点击区，点击展开 / 收起抽屉。
- chevron 用 `Icons.Default.KeyboardArrowDown`，展开时旋转 180°（`animateFloatAsState`），配 `contentDescription`「展开分类 / 收起分类」。
- 搜索态：副标题改为「全库搜索」，标题与 chevron 保持可点（搜索期间仍可换分类；换分类会先结束搜索，见下）。
- 多选态：标题为「已选 N 项」，左侧返回箭头，无副标题与 chevron，不提供抽屉入口。

### 抽屉

- 实现：`ModalNavigationDrawer` + `ModalDrawerSheet`，默认 `Closed`；遮罩、侧滑关闭、返回键关闭均沿用 Material3 行为，另在抽屉打开时注册 `BackHandler` 兜底。
- 尺寸与造型：宽 300dp（小屏仍留出遮罩可点、可侧滑），右侧上下 8dp 圆角，`surface` 底色，符合纸感「圆角上限 8dp」。
- 头部：「分类」标题 + 「按分类查看笔记」说明 + 右上角关闭按钮，下方 hairline 分隔。
- 浏览区（`LazyColumn`）：
  - 「全部」「未分类」固定置顶；
  - 分区标题「我的分类」，随后按现有顺序列出各分类，行首 6dp 分类色点（`PaperPalette.nearest`，与列表行一致）；
  - 当前筛选项高亮：主题色 12% 透明度圆角底 + `onSurface` 文字 + `primary` 对勾（深色/动态取色下都比 `secondaryContainer` 更轻）；
  - 无分类时显示「还没有分类」与「去创建」文字按钮。
- 底部：hairline 分隔 + 「分类管理」入口（铅笔图标），点击进入既有分类管理页。
- 选择行为：点击条目 → 应用筛选 → 抽屉收起；若当前处于搜索态，先执行与「关闭搜索」相同的清理（清空关键词、退出多选、隐藏键盘），保证选完立刻看到该分类内容。
- 状态不落盘：抽屉开合是纯 UI 状态，由 `rememberDrawerState` 的 Saver 负责旋转恢复，不写入 SharedPreferences / Room。

### 与既有逻辑的衔接

- 分类被删除后自动回落「全部」的 `LaunchedEffect` 原样保留；副标题与抽屉高亮都跟随 `selectedFilter`，不会出现「tab 高亮错位」的替代问题。
- 新建笔记的预选分类仍是 `viewModel.newNoteCategoryId`；空态文案、多选跨筛选清理、排序项、FAB 均不变。
- 移除 `TextTabRow` 在主页的使用；该组件在导出弹窗、设置页、历史页仍有使用，组件本身保留。

## 技术方案

- `PaperTopBar` 增加两个可选参数：`subtitle: String?`、`titleTrailing: @Composable (() -> Unit)?`，默认值保证现有调用点零改动；标题区改为「标题 + 副标题 + 尾部图标」组合，副标题用 `bodySmall` + `onSurfaceVariant`。
- 新增 `ui/notes/CategoryDrawer.kt`：
  - `CategoryDrawerEntry(filter, label, colorArgb, selected)` 纯数据与 `categoryDrawerEntries(categories, selected)` 纯函数，负责条目顺序、文案与选中标记，便于单测；
  - `CategoryDrawerContent(...)` 无状态 Composable，只负责渲染与回调，不持有业务状态。
- `CategoryFilter.kt` 增加 `menuLabel(categories)`（抽屉文案：全部 / 未分类 / 分类名 / 未知分类）与 `summaryLabel(categories)`（副标题：全部笔记 / 未分类 / 分类名）。
- `NotesScreen` 外层包 `ModalNavigationDrawer`，删除顶部 tab 与相应 import；搜索 / 多选的 `BackHandler` 在抽屉打开时让位给抽屉关闭。
- 不新增权限、不新增三方依赖、不涉及 Room schema 变更。

## 涉及文件

- 改：`ui/components/PaperTopBar.kt`、`ui/notes/NotesScreen.kt`、`ui/notes/CategoryFilter.kt`、`app/build.gradle.kts`（版本）、`README.md`（版本行 / 测试数）。
- 新：`ui/notes/CategoryDrawer.kt`、`app/src/test/java/com/mynote/app/ui/notes/CategoryDrawerTest.kt`、本设计文档与配套计划。

## 测试要点

1. `categoryDrawerEntries`：固定「全部」「未分类」在前，分类保持仓库返回顺序；`selected` 只标记当前项；分类色值透传。
2. 标签：`menuLabel` / `summaryLabel` 对三种筛选与「分类已不存在」的兜底文案。
3. 回归：现有 `NotesViewModelTest` 全绿；`assembleDebug` 通过。
4. 手工：安装 debug APK，验证展开 / 收起、选中切换、搜索态换分类、多选态无入口、返回键关闭、深色模式与主题色。

## 版本

1.12.3 → **1.13.0**（`versionCode` 11300），README 版本行与测试总数按实际运行结果同步。
