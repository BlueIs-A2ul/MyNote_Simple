# AGENTS.md

MyNote 安卓备忘录（原生 Android，单模块 `:app`）。本文件只记录容易踩坑、从代码里不易看出的约定。

## 项目与约束

- 技术栈：Kotlin + Jetpack Compose (Material 3) + MVVM/UDF；Room(KSP) + Coil 2 + kotlinx.serialization + Navigation Compose；手写 DI（`di/AppContainer.kt`，无 Hilt）。minSdk 24 / target 34 / JDK 17；依赖统一加在 `gradle/libs.versions.toml`。
- 硬约束（设计文档反复强调）：内存占用小、**零新增第三方依赖**、零敏感存储权限（只用 SAF / Photo Picker / FileProvider）。
- 无远程仓库，历史在 `master` 线性推进；不要 push。提交信息用中文 + `feat|fix|docs|chore|refactor:` 前缀（与现有历史一致）。

## 命令（Windows PowerShell，统一 `.\gradlew`）

| 目的 | 命令 |
|---|---|
| debug APK | `.\gradlew :app:assembleDebug` |
| 全部单测（当前 113 个） | `.\gradlew :app:testDebugUnitTest` |
| 单个测试类 | `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.db.NoteDaoTest"` |
| release（R8 + 资源压缩 + 签名） | `.\gradlew :app:assembleRelease` |

产物在 `app/build/outputs/apk/{debug,release}/`。构建/换机前先读 `docs/build-guide.md`（已记录的三个构建坑与根因）。

## 构建环境坑（勿"整理"）

- `settings.gradle.kts` 里阿里云镜像**刻意置前** + `RepositoriesMode.PREFER_SETTINGS`：本机全局 init 脚本会向 project 注入仓库（`FAIL_ON_PROJECT_REPOS` 会冲突），且 dl.google.com TLS 不稳定，镜像置前保证解析不中断。
- 没有 `local.properties`，依赖环境变量 `ANDROID_HOME`。
- 根目录 `keystore.properties` 与 `*.keystore` 已 gitignore，但 release 在配置阶段强制读取（缺失即失败）；debug/单测不受影响。
- `app/build.gradle.kts` 单测 JVM 参数 `--add-opens=java.base/java.io=ALL-UNNAMED` 是 Robolectric + JDK 17 关闭文件流所必需，勿删。

## 测试约定

- Robolectric + `@Config(sdk = [34])`；涉及文字测量/位图渲染的测试必须加 `@GraphicsMode(GraphicsMode.Mode.NATIVE)`（LEGACY 图形下文本 layout 是桩实现，分页/长图断言会失真）。
- ViewModel 测试：`Dispatchers.setMain(StandardTestDispatcher())` + `runTest(dispatcher)`；等待真实 `Dispatchers.Default/IO` 的异步结果用 `Flow.first {}` 或 `CompletableDeferred`（参考 `NoteExportViewModelTest`）。
- 首次运行单测会下载 Robolectric android-all jar（约 100–200MB，一次性）。

## 数据与代码惯例

- 笔记正文为纯文本，图片以 `![](img/<name>)` 标记内嵌：解析/生成必须走 `ui/notes/NoteContentParser.kt`，不要手写正则。图片文件在 `filesDir/notes_images/`、不存库；删除笔记时由 `ImageStore.collectGarbage` 回收孤儿文件。
- Room `version = 2`、`exportSchema = false`；已有手写 `MIGRATION_1_2`（历史表）。修改 Entity 需升版本并自行补迁移与迁移测试（v1 库手工建库模式见 `AppDatabaseMigrationTest`）。
- 笔记历史：每次保存写一条 `note_revisions` 快照（无变化不写；每篇上限 50 条，超出自动裁最旧，`NoteRevisionDao.MAX_PER_NOTE/WARN_AT`）；保存事务在 `NoteRepository.saveNote`，图片 GC 的引用集 = 当前正文 ∪ 含图片标记的历史快照（`getContentsWithImageMarkup`），勿只统计正文。
- 图片导出：`data/export/`（自绘渲染 + 流式缓存导出）+ `ui/export/`（全屏预览 Dialog）。产物为纯白 PNG、无任何品牌元素；渲染用 `NoteImageRenderer.renderPages` 逐页回调，调用方写盘后立即 `recycle()`，峰值内存 ≈ 一页——不要改回"先收集全部页位图"的写法。FileProvider authority 为 `com.mynote.app.fileprovider`，`res/xml/file_paths.xml` 只暴露 `cacheDir/exports`。
- 导出图片固定纯白，不跟随深色模式/主题色；界面主题在 `ui/theme/`（8 档色板 + 动态取色），设置持久化在 `data/settings/ThemeSettingsStore`。
- 新功能先写设计 `docs/superpowers/specs/YYYY-MM-DD-*-design.md`、计划 `docs/superpowers/plans/`（计划末尾记「修订记录」），再动代码；文档、注释、提交均为中文。
