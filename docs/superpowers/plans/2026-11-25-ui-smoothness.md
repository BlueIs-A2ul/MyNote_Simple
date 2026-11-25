# 界面流畅度整改实施计划

对应设计：`docs/superpowers/specs/2026-11-25-ui-smoothness-design.md`
分支：`ui-smoothness-1125`（worktree `.worktrees/ui-smoothness-1125`）

## 步骤

1. **导航转场**（`ui/navigation/AppNavHost.kt`）
   - 新增三个时长常量（进入 180ms / 退出 120ms / pop 140ms）。
   - `NavHost` 传 `enterTransition` / `exitTransition` / `popEnterTransition` / `popExitTransition`。
   - 校验：fade + slideInHorizontally（1/24 屏宽），pop 反向。

2. **NoteRow 去固有尺寸**（`ui/components/NoteRow.kt`）
   - 移除 `IntrinsicSize`、色条 Box 与 `fillMaxHeight`。
   - 行 `drawBehind` 画圆角色条（左 16dp、上下 12dp、宽 3dp、圆角 1.5dp）。
   - 内容前用 `Spacer(13dp)` 保持原「3dp 条 + 10dp 间距」的视觉缩进。
   - 相对时间 `remember(item.updatedAt, now)`。

3. **列表重复计算**（`ui/notes/NotesScreen.kt`、`ui/calendar/CalendarScreen.kt`）
   - `remember(categories)` 预构 `Map<Long, Color>`（值为 `PaperPalette.nearest` 结果）。
   - item 与批量分类弹层只查 map，不再逐次调用 `nearest`。

4. **编辑页输入路径**（`ui/notes/NoteEditScreen.kt`）
   - 计数器改 `mutableIntStateOf` + `LaunchedEffect(content.text)` 内 `delay(150)` 后计算。
   - `dirty` 改 `remember(note, isNewNote) { derivedStateOf { ... } }`，仅在 lambda 中读取。
   - 预览模式块解析加 `remember(content.text)`。

5. **编译校验**
   - `.\.gradlew :app:compileDebugKotlin`。
   - 按用户要求本轮不跑单测；合入 master 前补跑 `testDebugUnitTest`。

6. **提交**
   - 单个提交：`refactor: 收紧导航转场与列表/编辑页重组开销（性能）`。

## 修订记录

- 2026-11-25：初稿并实施。范围限定为「导航转场 + 列表测量 + 行内重复计算 + 编辑页输入路径」，
  设置页 LazyColumn、AI 流式渲染、TextFieldState 重构、baseline profile 记为后续条目。
- 2026-11-25（第二次）：master 已合入 1.13.0 分类抽屉（c58538b），分支 rebase 到该提交；
  NotesScreen 的色表缓存按抽屉版重新落位（抽屉替换了原来的顶部 tab 行），其余四个文件原样沿用。
