# MyNote 主页分类抽屉侧边栏实施计划

> **For agentic workers:** 配套设计文档 `docs/superpowers/specs/2026-11-25-home-category-drawer-design.md`。按「纯逻辑 → 顶栏 → 抽屉 → 接线 → 版本 → 验证」串行推进，步骤使用 `- [x]` 跟踪。

**Goal:** 移除主页顶栏下方的分类 tab，改为「备忘录」标题旁展开的左侧分类抽屉，发布 1.13.0。

**Architecture:** `ModalNavigationDrawer` 包裹 `NotesScreen` 的 `Scaffold`；`PaperTopBar` 增加副标题与标题尾部图标插槽；分类条目模型与文案做成纯函数（`CategoryDrawer.kt` / `CategoryFilter.kt`）便于单测；筛选状态仍由 `NotesViewModel.selectedFilter` 驱动，不新增数据层改动。

**Tech Stack:** Kotlin、Compose Material3（`ModalNavigationDrawer` 属现有依赖）、Robolectric/JUnit4。

---

### Task 1: 纯逻辑（文案与抽屉条目）

**Files:**
- Modify: `app/src/main/java/com/mynote/app/ui/notes/CategoryFilter.kt`
- Create: `app/src/main/java/com/mynote/app/ui/notes/CategoryDrawer.kt`
- Create: `app/src/test/java/com/mynote/app/ui/notes/CategoryDrawerTest.kt`

- [x] `CategoryFilter` 增加 `menuLabel(categories)` 与 `summaryLabel(categories)`：All → 全部 / 全部笔记；Uncategorized → 未分类；Single → 分类名，分类已被删则「未知分类」。
- [x] `CategoryDrawerEntry(filter, label, colorArgb, selected)` + `categoryDrawerEntries(categories, selected)`：固定「全部」「未分类」在前，分类按传入顺序，`selected` 仅当前项为 true，`colorArgb` 透传分类色。
- [x] `CategoryDrawerTest` 覆盖条目顺序、选中标记、色值透传、未知分类与两类文案。

### Task 2: 顶栏标题区

**Files:**
- Modify: `app/src/main/java/com/mynote/app/ui/components/PaperTopBar.kt`

- [x] 新增可选参数 `subtitle`、`titleTrailing`，默认 null，现有调用点零改动。
- [x] 标题区改为「标题（`headlineSmall`，单行省略）+ 副标题（`bodySmall`，`onSurfaceVariant`）+ 尾部插槽」，标题列 `fill = false` 让 chevron 紧跟文字；高度仍为 56dp。

### Task 3: 分类抽屉 UI

**Files:**
- Modify: `app/src/main/java/com/mynote/app/ui/notes/CategoryDrawer.kt`

- [x] `CategoryDrawerContent(categories, selected, onSelect, onManageCategories, onClose)`：
  - 头部「分类」+「按分类查看笔记」+ 关闭按钮；
  - `LazyColumn` 固定「全部」「未分类」→ 分区「我的分类」→ 分类行（色点 + 名称 + 选中高亮）；
  - 无分类空态「还没有分类」+「去创建」；
  - 底部「分类管理」入口。
- [x] 选中行背景为主题色 12% 透明度、文字 `onSurface`、对勾 `primary`，圆角 `AppShapes.small`；其余行 `onSurface`。

### Task 4: 主页接线

**Files:**
- Modify: `app/src/main/java/com/mynote/app/ui/notes/NotesScreen.kt`

- [x] 用 `ModalNavigationDrawer` 包裹 `Scaffold`；`drawerState = rememberDrawerState(Closed)`；`drawerContent` 放 `ModalDrawerSheet`（宽 300dp、右侧 8dp 圆角、surface 底色）。
- [x] 移除 `TextTabRow` 与 `tabs` 构造；顶栏改用 `subtitle`、`titleTrailing`（chevron + 旋转动画 + toggle 抽屉）。
- [x] 抽屉选择：`onFilterSelect` + 关闭；搜索态先走 `closeSearch()`。
- [x] 搜索 / 多选 `BackHandler` 在抽屉打开时不拦截；抽屉打开时补一个关闭 `BackHandler`。
- [x] 多选态不传副标题 / chevron。

### Task 5: 版本与文档

**Files:**
- Modify: `app/build.gradle.kts`、`README.md`

- [x] `appVersionName = "1.13.0"`、`appVersionCode = 1_13_00`。
- [x] README 版本行、release 产物名、单测数量按实际结果更新。

### Task 6: 验证

- [x] `gradlew :app:testDebugUnitTest` 全绿，记录实际用例数。
- [x] `gradlew :app:assembleDebug` 通过。
- [x] 如有模拟器 / 真机：安装 debug APK，验证展开收起、选中、搜索态换分类、多选态入口隐藏、返回键、深色模式。

## 修订记录

- 2026-11-25：初版计划，与设计文档同步落地。
- 2026-11-25：实现完成；在 Pixel 5 / API 35 模拟器上走查了浅色、深色、横屏、搜索态换分类、多选态入口隐藏、返回键关闭与空态，编译 + 724 个单测通过。