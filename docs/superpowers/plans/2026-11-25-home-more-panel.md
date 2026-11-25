# MyNote 主页「更多」纸片面板实施计划

> **For agentic workers:** 配套设计文档 `docs/superpowers/specs/2026-11-25-home-more-panel-design.md`。按「纯逻辑 → 组件 → 接线 → 复用 → 版本 → 验证」串行推进，步骤使用 `- [x]` 跟踪。

**Goal:** 把主页「⋮」的长下拉换成自绘的纸片分组面板（浏览 / 整理 / 数据），排序收成行内单选，发布 1.14.0。

**Architecture:** 新组件 `PaperActionPanel`（纯渲染，无业务状态）用同窗口 `Box` 覆盖层实现：遮罩 + 贴底面板 + `AnimatedVisibility` 淡入 + `Animatable` 位移，不使用 `ModalBottomSheet`。菜单内容仍由 `NotesScreen` 组装，排序选项抽成纯函数便于单测。

**Tech Stack:** Kotlin、Compose Material3（现有依赖）、JUnit4（纯逻辑测试）。

**Spec:** `docs/superpowers/specs/2026-11-25-home-more-panel-design.md`

## Global Constraints

- 零新增第三方依赖、零新增权限；不涉及 Room schema。
- 圆角上限 8dp、全 App 无阴影（纸感）；文案与注释中文。
- 本分支以 `home-category-drawer-1125` 为基线（分类管理已移入抽屉底部）。

---

### Task 1: 排序选项纯逻辑

**Files:**
- Create: `app/src/main/java/com/mynote/app/ui/notes/NoteSortOptions.kt`
- Create: `app/src/test/java/com/mynote/app/ui/notes/NoteSortOptionsTest.kt`

- [x] `NoteSortMode.sortLabel()`：最近更新 / 最新创建 / 按标题（穷举 `when`）。
- [x] `NoteSortOption(mode, label, selected)` + `noteSortOptions(current)`：顺序按 `NoteSortMode.entries`，只有当前项 `selected`。
- [x] `NoteSortOptionsTest`：顺序固定、选中互斥、文案映射、每种模式都能选中自己。

### Task 2: 纸片面板组件

**Files:**
- Create: `app/src/main/java/com/mynote/app/ui/components/PaperActionPanel.kt`

- [x] `PaperActionPanel(expanded, onDismissRequest, content)`：全屏遮罩（`scrim` 28%，点击关闭）+ 贴底面板；`AnimatedVisibility` 淡入 220ms / 淡出 160ms，面板位移 32dp→0 用 `spring(0.88, MediumLow)`。
- [x] 面板：底色 `background`、0 圆角、顶边 hairline、纸边短横线、`heightIn(max = 屏高 85%)` + 内部滚动、`navigationBarsPadding`、`paneTitle = "更多操作"` 与焦点移入、`BackHandler` 关闭。
- [x] `PaperPanelGroup(title, content)`：`labelMedium` 标题 + 右侧延伸 hairline。
- [x] `PaperPanelItem(text, enabled, destructive, onClick)`：48dp 行、行尾细箭头、禁用 38% 透明、危险色。
- [x] `PaperPanelChoice(text, selected, onClick)`：自绘 16dp 圆环 + 8dp 实心点，`Role.RadioButton`。

### Task 3: 主页接线

**Files:**
- Modify: `app/src/main/java/com/mynote/app/ui/notes/NotesScreen.kt`

- [x] 顶栏 action 只留触发按钮（`Icons.Default.MoreVert`）——多选态也保留，搜索按钮仅正常态。
- [x] 移除正常态 9 项下拉：分类管理（抽屉已有入口）删除，日历 / 回收站进「浏览」，三档排序进行内单选「整理」，导入导出进「数据」，设置单独分隔成块。
- [x] 多选态改用面板：「选择」（全选）/「操作」（置顶 / 取消置顶 / 移动到分类 / 删除），保留原有 enabled 守卫。
- [x] `ModalNavigationDrawer` 外包 `Box`，面板置于其后覆盖顶栏与列表；删除 `DropdownMenuItem`、`PaperOverflowMenu`、`NoteSortMode` 等不再使用的 import。

### Task 4: 设置页复用文案

**Files:**
- Modify: `app/src/main/java/com/mynote/app/ui/settings/SettingsScreen.kt`

- [x] 删除私有 `sortModeLabel`，改用 `NoteSortMode.sortLabel()`，保证设置页与面板同一文案源。

### Task 5: 版本与文档

**Files:**
- Modify: `app/build.gradle.kts`、`README.md`

- [x] `appVersionName = "1.14.0"`、`appVersionCode = 1_14_00`；README 版本行与 APK 产物名同步。

### Task 6: 验证

- [x] `gradlew :app:testDebugUnitTest` 全绿，记录实际用例数。
- [x] `gradlew :app:assembleDebug` 通过。
- [x] 本机无连接设备：手工 UI 走查以编译 + 单测替代，记录在修订记录。

## 修订记录

- 2026-11-25：初版计划。
- 2026-11-25：实现完成；`NoteSortOptionsTest` 3 例，全量单测 727 全绿，`assembleDebug` 通过。本机无连接设备、无 AVD，手工 UI 走查未执行（以编译 + 单测替代）。
