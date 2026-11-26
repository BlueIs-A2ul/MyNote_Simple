# UI 体验修复批（1.15.0）设计文档

> 来源：2026-11-27 全量 UI/UX 只读复查（主页列表、编辑页、设置 / 分类 / 日历 / AI 聊天页、全局 chrome 与无障碍）。
> 目标版本 **1.15.0**（体验修复批，minor 递增）。零新增第三方依赖、零新增权限、无 Room schema 变更。

## 背景

v1.14.1 之后，backlog 里 102 条可执行项已基本清空，抽屉侧边栏、纸片「更多」面板、列表流畅度与筛选记忆也已落地。本轮复查集中在**边界态、跨页一致性、大字号 / 无障碍适配**三类，共 17 项，全部落在 UI 层。

## 修复清单

### A. 交互逻辑（会让用户卡住或看不到内容）

| # | 问题 | 修复 |
|---|---|---|
| A1 | 主页列表末尾条目被 FAB 遮住（Scaffold padding 不含 FAB） | 列表 `contentPadding = PaddingValues(bottom = 72.dp)` |
| A2 | 编辑页底部工具栏在 360dp 屏溢出、选过日期后裁掉「预览」 | 工具栏改可横向滚动单行（`horizontalScroll` + `heightIn(min = 48.dp)`），组间加 1dp 竖分隔 |
| A3 | 保存触达历史 40 条警告时，Snackbar 阻塞 4 秒才退出编辑页 | 警告经新增 `onNoteMessage` 通道交给列表页显示，编辑页立即退出 |
| A4 | 搜索键盘展开时「更多」面板被 IME 盖住 | 打开面板前隐藏键盘 + 面板内部 `imePadding()` 兜底 |
| A5 | `isSaving` 非 saveable；VM 静默早退会让界面永久只读 | `isSaving` 改 `rememberSaveable`；VM 早退回调「正在保存，请稍候」 |

### B. 视觉一致性

| # | 问题 | 修复 |
|---|---|---|
| B1 | 列表行无条件带 `Role.Checkbox` 语义，普通浏览态读屏误报「复选框」 | 新增 `selectionMode` 参数，仅多选态施加语义 |
| B2 | 纸片面板里非导航项也带「›」箭头（含 destructive 的「删除」） | `PaperPanelItem(showChevron = false)` 用于就地动作 |
| B3 | 顶栏固定 56dp，系统字体放大后标题+副标题被裁 | `.height(56.dp)` → `.heightIn(min = 56.dp)` |
| B4 | AI 聊天页是全 App 唯一原生 Material 页面（TopAppBar / AlertDialog） | 换 `PaperTopBar` + `PaperAlertDialog`，与其余 8 页统一 |
| B5 | 助手气泡下 4 个操作按钮大字体下溢出 | 改 `FlowRow` 自动换行 |

### C. 触控区与可发现性

| # | 问题 | 修复 |
|---|---|---|
| C1 | 设置页 8 个主题色圈触达区 28dp | 每项 `weight(1f) + heightIn(min = 48.dp)` 容器，视觉圆点仍 28dp |
| C2 | 新建分类选色 28dp 且无 contentDescription | 48dp 可点容器 + 「颜色 N」描述 + `Role.RadioButton` |
| C3 | 文字 tab（设置 / 下载 / 导出 / 历史）触达区约 36dp | `heightIn(min = 48.dp)` |
| C4 | 日历日期格可点区 36dp（99 号条目遗留） | 外层 48dp 承接点击，内圆保持 36dp 视觉 |
| C5 | 长按进多选无触感反馈 | `LocalHapticFeedback` + `HapticFeedbackType.LongPress` |
| C6 | 分类色点"点一下换色"无任何提示 | 分类页顶部补一行说明 |

### D. 入口与反馈

| # | 问题 | 修复 |
|---|---|---|
| D1 | 搜索无结果数、无「清空搜索」出口、IME 不是「搜索」 | 副标题「全库搜索 · N 条」；空态补「清空搜索」；`ImeAction.Search` 收键盘 |
| D2 | 批量删除没有「撤销」（单条有） | snackbar 带「撤销」，恢复本批 id |
| D3 | 备份文件名恒为 `mynote-backup.zip` | 改 `mynote-backup-YYYY-MM-DD.zip` |
| D4 | 从笔记返回时搜索键盘被自动顶起 | 仅「主动点搜索图标」时请求焦点 |
| D5 | 分享无 `ActivityNotFoundException` 兜底 | try/catch + snackbar「没有可分享的应用」 |
| D6 | 未开启预测性返回（Android 13+ 无返回手势预览） | manifest `enableOnBackInvokedCallback="true"`（需真机确认抽屉 / 面板场景） |
| D7 | 分类抽屉只显示分类名，看不出各分类有多少条笔记 | 行尾显示可见笔记数（复用 `observeCategoryNoteCounts`，与分类管理页同一口径） |

## 技术要点

- **提示回传通道**：复用既有 `NavResults`（`DELETED_NOTE_ID` / `RESTORE_AT` 同款）。新增 `NavResults.EDITOR_MESSAGE`，编辑页 `onNoteMessage` → 写入 notes 入口的 `SavedStateHandle` → 列表页读取、**先消费再展示**（旋转不重弹）。
- **评测口径不变**：所有改动都在 Composable / VM 层，DAO、Room、备份格式零改动。
- **不引入 `ModalBottomSheet` 新用法**：本轮只把既有原生组件换成纸感组件，不新增弹层。

## 涉及文件

- 主页：`ui/notes/NotesScreen.kt`、`ui/notes/NotesViewModel.kt`、`ui/notes/CategoryDrawer.kt`、`ui/components/PaperActionPanel.kt`、`ui/components/NoteRow.kt`
- 编辑页：`ui/notes/NoteEditScreen.kt`、`ui/notes/NoteEditViewModel.kt`
- 全局 chrome：`ui/components/PaperTopBar.kt`、`ui/components/TextTabRow.kt`、`ui/settings/SettingsScreen.kt`、`ui/categories/CategoriesScreen.kt`、`ui/calendar/CalendarScreen.kt`、`ui/ai/AiChatScreen.kt`
- 导航与平台：`ui/navigation/AppNavHost.kt`、`AndroidManifest.xml`
- 版本与文档：`app/build.gradle.kts`、`README.md`、`docs/backlog.md`

## 测试要点

1. 现有全量单测（740+）必须保持全绿：本轮不改数据层，测试用于防回归（尤其 `NotesViewModelTest`、`NoteEditViewModelTest`）。
2. 新增纯函数（`backupFileName`）与 VM 新方法（`batchDelete` 返回 id、`restoreDeleted(List)`）补 2–3 条单测。
3. Compose 组合期行为（FAB 留白、面板 IME、48dp 触控区、FlowRow 换行）无 UI 测试基础设施，按既有约定靠真机 / 预览验证，文档记录待验项。

## 版本

1.14.1 → **1.15.0**（`versionCode = 11500`）。
