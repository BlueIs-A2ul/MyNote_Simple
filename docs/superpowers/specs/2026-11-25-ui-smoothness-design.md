# 界面流畅度整改设计（转场 / 列表测量 / 输入路径）

- 日期：2026-11-25
- 状态：已实现（分支 `ui-smoothness-1125`，基于 master c58538b，已含 1.13.0 分类抽屉）
- 关联：无 backlog 编号，来自 2026-11-25 体验复查（含 v1.13.0 抽屉版实机帧数据）

## 1. 现象

交互（点开笔记、返回、开关抽屉、输入）有"慢半拍/轻微掉帧"的感觉，静止时无异常（实测静止 3s 零帧，说明没有常驻动画偷跑）。
实测（debug 包 + 模拟器，绝对值不代表真机）：列表→编辑页一次 23 帧、30% janky、p50 34ms、p90 150ms；冷启动 2.35–3.04s。

## 2. 根因

### 2.1 导航转场走 Navigation 默认 700ms 全屏淡入淡出（主因）

`AppNavHost` 未指定任何 transition。navigation-compose 2.8.1 在 transition 为 null 时的默认值经反编译确认为
`fadeIn(tween(700))` / `fadeOut(tween(700))`：每次跳转两整屏内容叠加绘制约 42 帧，且这 0.7s 内目标屏必须完成首帧组合。

### 2.2 NoteRow 用 IntrinsicSize.Min 画左侧色条

`NoteRow` 为画 3dp 竖条给整行加 `height(IntrinsicSize.Min)`。Row 内含 Column + 两个 Text，固有尺寸测量会把子树按 min/max 各测一遍，
每个可见行、每次行重组都要付这笔测量成本。

### 2.3 行内重复计算（每次重组重跑）

`PaperPalette.nearest`（HSV 转换 + 6 次色相距离）、`TimeFormat.relativeDate`（3 个 Calendar + clone）、
分类 map 二次查找、`query.trim()` 都在 item 内容 lambda 里直接调用；叠加每分钟 `now` tick，会让所有可见行周期性重算。

### 2.4 编辑页每次按键做全文正则 + 组合期读取

`wordCount`/`imageCount` 的 `derivedStateOf` 内部是 `plainText`（Regex.replace，整串复制）与 `extractImageNames`（findAll），
每个按键对全文扫两遍；`dirty` 在组合期直接读 `content.text`，使每次按键整屏（Scaffold/顶栏/底部栏）参与重组。

## 3. 方案

1. **导航转场**：在 `AppNavHost` 的 `NavHost` 上统一指定短转场——进入 180ms 淡入 + 轻微横向位移（1/24 屏宽），
   退出 120ms 淡出，pop 140/120ms。视觉仍是"淡"，但不再有 0.7s 的双屏拖尾。
2. **NoteRow 去固有尺寸**：色条改用 `drawBehind` 直接在行坐标里画（左 16dp、上下各 12dp 内缩、圆角 1.5dp），
   删除 `IntrinsicSize`/`fillMaxHeight`/`weight` 之外的色条节点，视觉保持 1:1；相对时间结果按 `(updatedAt, now)` 记忆。
3. **列表重复计算**：`NotesScreen` / `CalendarScreen` 在列表层用 `remember(categories)` 预构 `categoryId → Color` 映射，
   item 内只做一次 map 查找；批量分类弹层同样复用该映射。
4. **编辑页输入路径**：字数/图数改为 `LaunchedEffect(content.text) + delay(150)` 的防抖计算（连续输入时不断取消，等价防抖），
   计数器用 `mutableIntStateOf`；`dirty` 改为 `derivedStateOf`，只在返回/保存/历史入口的 lambda 里读取，退出组合期；
   预览模式的 `NoteContentParser.parse` 加 `remember(content.text)`。

## 4. 不做的事（本轮）

- 设置页 `verticalScroll` 改 `LazyColumn`：属结构性重排，需要逐段视觉核对，另立条目。
- AI 流式渲染改纯文本 + 滚动节流：涉及行为变化，需人工观察长回答，另立条目。
- `TextFieldState` 重构（让重组只发生在输入框内部）：改动面大，需与 `NoteUndoController` 一起做。
- baseline profile / profileinstaller：新增构建插件与依赖，需先拍板"零新增三方依赖"的口径。

## 5. 影响面与风险

- 改动文件：`ui/navigation/AppNavHost.kt`、`ui/components/NoteRow.kt`、`ui/notes/NotesScreen.kt`、
  `ui/calendar/CalendarScreen.kt`、`ui/notes/NoteEditScreen.kt`。
- 均为等价重构 + 动画参数，不改数据/业务语义；无 Room、无权限、无新依赖。
- 风险点：`NoteRow` 色条位置需与旧实现像素级一致（左 16dp、上下 12dp、宽 3dp、圆角 1.5dp）；
  转场时长过短会显得生硬，180/120ms 为折中值，可按真机观感微调。

## 6. 验收

- `.\.gradlew :app:compileDebugKotlin` 通过（本轮按用户要求不跑单测，合入前补跑全量）。
- 交互期 `dumpsys gfxinfo` 的 janky 比例与 p90 相对整改前下降；跳转不再有 >100ms 的单帧。
- 列表视觉与原实现一致（色条、缩进、行高）。
