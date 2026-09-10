# 笔记导出为图片 — 设计文档

- 日期：2026-09-10
- 状态：已实现（2026-09-10，70 个单测全绿 + debug/release 构建通过；真机 UI 手工验证待做）
- 关联：`docs/superpowers/specs/2026-08-13-mynote-design.md`（备份与导出）、`README.md`
- 前置：brainstorming 会话结论 —— 版式选「纯白纸面」；长笔记支持「分页 / 单张长图」二选一；PNG 1080px；保存与分享都要；全程无品牌元素

## 1. 背景与问题

当前单条笔记只能导出为 txt：编辑页顶栏分享图标直接调用 `BackupManager.exportNoteAsTxt()`（`NoteEditScreen.kt:169`）。用户希望把笔记以**图片**形式导出分享，且图片必须足够纯净：不出现应用图标、应用名、水印或任何品牌元素，只有内容本身。同时导出需符合应用既有基调：内存占用小、零敏感权限（SAF / FileProvider）、不新增第三方依赖。

## 2. 目标与非目标

**目标**

- 编辑页可将当前笔记导出为纯白底 PNG，导出内容 = 编辑器中当前内容（含未保存修改）。
- 正文文字与内嵌图片（`![](img/<name>)`）交错渲染，草稿与成品一致。
- 长笔记自动分页；导出预览页可在「分页 N 张 / 单张长图」间切换，由用户按效果自行选择。
- 保存到文件（SAF）与系统分享（FileProvider）都提供，多页时可保存到文件夹 / 一次分享多张。
- 零新增依赖、零新增权限；版式固定纯白，不跟随深色模式与主题色。

**非目标（本次不做）**

- Markdown / 富文本排版（笔记本身是纯文本 + 图片标记）。
- PDF 导出、批量导出多篇笔记、从列表页导出。
- 自定义图片模板、水印、字体、字号、背景色。
- 改变现有 txt 导出与 zip 备份导入导出的行为。

## 3. 导出画面规范（纯白纸面）

设计基准宽 360dp；导出 `scale = 3` → 1080px 宽，预览 `scale = 1` → 360px 宽。所有 sp/dp 一律按固定比例换算为像素，**不受系统字体缩放影响**，保证版式稳定。

| 项目 | 规格 |
|---|---|
| 背景 | 纯白 `#FFFFFF`，不透明 |
| 水平/顶部/底部内边距 | 24dp（导出 72px） |
| 标题 | 22sp 粗体 `#111111`；标题为空则整块省略；超长自动换行 |
| 日期 | 12sp `#9A9A9A`，格式 `yyyy-MM-dd`（复用 `TimeFormat.date`）；取 `updatedAt`，新建/未保存笔记取当前时间 |
| 正文 | 16sp、行高 1.7、`#333333`，保留原始换行；由 `NoteContentParser.parse` 拆为文本/图片块 |
| 内嵌图片 | 占满内容宽（导出 936px），等比缩放，圆角 8dp；图片文件缺失则跳过（不留空位） |
| 块间距 | 标题→日期 6dp；日期→正文块 16dp；正文块之间 12dp；图片上下各 12dp；标题/日期均省略时正文从顶部内边距开始 |
| 品牌元素 | 无图标、无应用名、无水印、无页码、无任何标识 |
| 字体 | 系统默认字体，不打包字体文件 |

版式参照 brainstorming 选定的「A · 纯白纸面」：标题、日期、正文、图片自上而下单列排布。

## 4. 分页与单张模型

- 页面宽 1080px，页高上限 4096px；可用内容高 = 4096 − 72（上）− 72（下）= 3952px。
- **分页只在文本行边界**：一行完整放不下就整体移到下一页，绝不裁断一行；文本块按行拆到后续页继续。
- **图片块**：放不下当前页剩余空间时整张移到下一页；若图高超过一页可用内容高，则等比缩小到一页内（宽或高先到限）。
- 除末页外每页位图高固定 4096px；末页高 = 内容底部 + 72px 底部内边距（空内容的极端情况取 72px 上下边距的最小图）。
- **单张长图**：一张位图，高 = 内容总高 + 上下边距，宽 1080px。
- 分页与单张共用同一套测量/绘制引擎，仅输出方式不同。

## 5. 交互流程

### 5.1 入口

- 编辑页顶栏分享图标 → 弹出 `AlertDialog`「导出为…」：
  - 「文本文档 (txt)」= 现有行为不变（`CreateDocument("text/plain")` + `exportNoteAsTxt`）。
  - 「图片 (PNG)」→ 打开导出预览页。
- 标题与正文都为空时，「图片」项置灰，附「还没有内容」提示。

### 5.2 导出预览页

- 形式：全屏 Compose `Dialog`（`usePlatformDefaultWidth = false`）覆盖编辑页，返回即关闭并取消渲染。
- 数据：以打开瞬间的 title/content 快照为准（含未保存修改）；日期用 `updatedAt`，新建笔记用当前时间。
- 预览：中间区域可滚动，分页模式纵向排列 N 页（页间留缝、细边框区分），单张模式显示长图缩略；预览用 `scale = 1`（360px 宽）低清渲染。
- 模式切换：分页测量结果页数 > 1 时顶部出现 `SegmentedButton`「分页 N 张 / 单张长图」，默认选中**分页**；只装得下一页时不显示切换。切换模式即按新模式重新低清渲染预览。
- 单张模式提醒：预计总高 > 12000px 时显示「长图较大，生成可能需要几秒」。
- 预览内存保护：单张模式预览总高超过 8000px、或分页模式页数超过 16 页时，进一步等比缩小预览比例（仅影响预览，导出仍为 1080px）。
- 底部「保存」「分享」按钮；生成中显示进度并禁用按钮，此期间可返回取消。

### 5.3 保存

- 单页：`CreateDocument("image/png")` 系统「另存为」，建议文件名 `标题-yyyyMMdd.png`（`yyyyMMdd` 为导出当天日期）。
- 多页：`OpenDocumentTree` 选择一个文件夹（一次选择），用 `DocumentsContract.createDocument(resolver, treeUri, "image/png", name)` 依次写入 `标题-yyyyMMdd-1.png`、`-2.png`…（同样为导出当天日期）。
- 结果反馈：成功 Snackbar「已保存」；多页中途失败提示「已保存 N 张，写入中断」。

### 5.4 分享

- 页面 PNG 写入 `cacheDir/exports/`，经 FileProvider（authority `com.mynote.app.fileprovider`）生成 content URI。
- 单页 `ACTION_SEND`；多页 `ACTION_SEND_MULTIPLE` + `EXTRA_STREAM: ArrayList<Uri>`；统一 `Intent.createChooser`。
- 每次开始导出前清空 `cacheDir/exports/`，避免旧文件堆积。

## 6. 架构与数据流

### 6.1 新增文件

```
data/export/
├── ExportModels.kt         # PageMode(PAGED/SINGLE)、RenderedPage、内部 PageLayout 等
├── NoteImageRenderer.kt    # 测量 + 排版 + 分页 + 逐页绘制（核心引擎）
└── ImageExportManager.kt   # SAF 保存 / FileProvider 分享 / 文件名清理 / 临时文件清理
ui/export/
├── NoteExportDialog.kt     # 全屏预览 UI + ActivityResult 接线 + Snackbar
└── NoteExportViewModel.kt  # 预览状态、模式切换、全清渲染调度
res/xml/file_paths.xml      # FileProvider 的 cache-path 映射
```

### 6.2 数据流

1. 编辑页点「图片」→ 以 title/content/updatedAt 快照创建 `NoteExportViewModel`（factory 注入 renderer、exportManager、`rememberTextMeasurer()` 的 `TextMeasurer`）。
2. ViewModel `init` 在 `Dispatchers.Default` 低清渲染（scale 1）→ `StateFlow<ExportUiState>`（Loading / Ready(pages, mode, pageCount) / Error）。
3. 点「保存 / 分享」→ 后台全清渲染（scale 3）→ 页面 Bitmap 暂存 ViewModel → 走对应 launcher/分享。
4. 渲染管线：`NoteContentParser.parse` 拆块 → 标题/日期/文本块经 `TextMeasurer.measure` 得到 `TextLayoutResult`，图片块用 `BitmapFactory`（`inJustDecodeBounds` 先量尺寸）→ 分页规划 → 逐页 `Bitmap` + `CanvasDrawScope.draw { }` 绘制（`drawText` / `drawImage`）→ 输出 `RenderedPage`。
5. 线程：测量、解码、绘制、编码全部在 `Dispatchers.Default` / IO；UI 线程只显示 Bitmap。关闭预览取消协程。

### 6.3 关键设计点

- **分页规划与绘制分离**：规划器输入「已测量块 + 行高信息」，输出每页的块/行引用，纯逻辑可单测；绘制只消费规划结果。
- **预览与导出同引擎**：仅 `scale` 不同，保证「看到什么就导出什么」。
- **单张 OOM 兜底**：捕获 `OutOfMemoryError` 后提示「内容太长，建议分页」并自动切到分页模式重渲预览。
- `TextMeasurer` 由 Composable 层 `rememberTextMeasurer()` 提供，经 factory 传入 ViewModel（Compose 1.7 稳定 API）。
- FileProvider 来自现有 `androidx.core`，**零新增依赖、零新增权限**（SAF + FileProvider 均不需要运行时权限，minSdk 24 可用）。

## 7. 错误处理

| 场景 | 行为 |
|---|---|
| 空笔记（标题与正文均空） | 入口「图片」置灰 + 「还没有内容」 |
| 图片文件缺失 | 跳过该图，其余内容照常渲染 |
| 预览渲染失败 | Snackbar「预览生成失败」，可重试 |
| 单张长图 OOM | 提示「内容太长，建议分页」并自动切换分页模式 |
| 用户取消保存 / 渲染中返回 | 无操作 / 取消协程并关闭预览 |
| 写入失败 | Snackbar「保存失败」；多页时提示已写入张数 |
| 分享无可用应用（`ActivityNotFoundException`） | Snackbar「没有可分享的应用」 |
| 标题含 `\ / : * ? " < > |` 或控制字符 | 替换为 `_`；清理后为空用「笔记」 |

## 8. 测试策略

- **新增 `NoteImageRendererTest`**（Robolectric + 原生图形 `@GraphicsMode(NATIVE)`）：
  - 短笔记 → 1 页，宽度 1080，页高 = 内容高 + 上下边距；
  - 长文本 → 多页且每页高 ≤ 4096；各页文本按序拼接等于原文（行不被裁断）；
  - 图片块放不下当前页剩余空间 → 整张移到下一页；
  - 超高图片 → 缩放后放进一页内；
  - 标题为空 → 不渲染标题区。
- **新增 `ImageExportManagerTest`**（Robolectric）：
  - `sanitizeName` 非法字符替换 / 空标题回退「笔记」；
  - 单页与多页文件名序列（`-1`、`-2`…）；
  - 分享前清理 `cacheDir/exports` 旧文件。
- 现有 39 个单测保持全绿；`.\gradlew :app:testDebugUnitTest`、`:app:assembleDebug`、`:app:assembleRelease` 通过。
- 真机手工验证：纯白输出（深色与各主题色下不变）、保存单页/多页、分享单张/多张、超长笔记两种模式、返回取消。

## 9. 涉及文件

| 文件 | 变更 |
|---|---|
| `data/export/ExportModels.kt` | 新增 |
| `data/export/NoteImageRenderer.kt` | 新增 |
| `data/export/ImageExportManager.kt` | 新增 |
| `ui/export/NoteExportDialog.kt` | 新增 |
| `ui/export/NoteExportViewModel.kt` | 新增 |
| `res/xml/file_paths.xml` | 新增 |
| `app/src/main/AndroidManifest.xml` | 新增 FileProvider `<provider>` |
| `ui/notes/NoteEditScreen.kt` | 分享图标改为格式选择对话框 + 挂导出预览 |
| `di/AppContainer.kt` | 注册 `NoteImageRenderer` / `ImageExportManager` |
| `app/src/test/.../NoteImageRendererTest.kt` | 新增 |
| `app/src/test/.../ImageExportManagerTest.kt` | 新增 |
| `README.md` | 功能列表补充图片导出 |
