# MyNote 安卓备忘录

一款**内存占用小、足够灵活、设计简洁**的原生安卓备忘录应用：纯文本笔记 + 图文混排 + 分类 + 可配置主题 + 备份导入导出，全程零敏感存储权限（SAF + Photo Picker）；AI 助手需联网，仅新增普通 `INTERNET` 权限，无存储类敏感权限。

## 功能

- 笔记：创建 / 编辑 / 删除 / 置顶，列表按「置顶优先 + 更新时间倒序」排列
- 搜索：标题与正文全文搜索
- 图文混排：正文以纯文本存储、图片用 `![](img/<name>)` 标记内嵌，查看时文本/图片交错渲染，图片懒加载
- 分类：单维度分类（增/改/删，删除后笔记移入未分类，同名幂等）；主页「未分类」筛选入口可查看全部未分类笔记，配合多选批量分类即可整体归类
- 主题：浅色/深色/跟随系统三态、8 档预设主题色、Android 12+ 动态取色开关（Material You；点选主题色自动关闭动态取色）
- 设置：主页 ⋮ → 设置，主题修改即时生效、重启保持
- 备份：zip 全量备份 / 导入（按 id 去重、冲突保留更新者）
- 导出：单条笔记导出 txt 或图片（纯白 PNG、无任何品牌元素；长笔记可选自动分页或单张长图，含内嵌图片）
- 历史：每次保存自动留存版本（无变化不记；每篇上限 50 条、40 条起提醒），时间线可看字段变更标签与行级 + 行内 diff 对比，一键恢复旧版（恢复也生成一条新记录）
- AI 助手：编辑页唤起，直连 DeepSeek 官方 API（自填 API Key）流式回答；会话按笔记留档，回答可插入正文 / 替换选中 / 复制 / 存为新笔记；Key 仅本机 Keystore 加密保存，首次使用有隐私确认，支持模型切换（deepseek-flash / deepseek-v4-pro）与深度思考开关

## 技术栈

Kotlin · Jetpack Compose + Material 3 · MVVM + 单向数据流（UDF） · Room（SQLite + KSP） · Coil 2 · kotlinx.serialization · Coroutines/Flow · Navigation Compose · Gradle Kotlin DSL + Version Catalog

| 项 | 值 |
|---|---|
| minSdk / targetSdk | 24 / 34 |
| JDK | 17 |
| Gradle | 8.7（Wrapper） |
| 版本 | 1.8.0；`versionName = major.minor.patch`、`versionCode = major*10000+minor*100+patch`，设置页可见 |
| release 产物 | R8 混淆 + 资源压缩，约 1.7MB，`MyNote-<版本>-release.apk`（按 keystore.properties 签名） |

## 构建

前置：JDK 17 + Android SDK（platform 34、build-tools 34）。首次构建需联网下载依赖。

> ⚠️ 实际构建中曾遇到三个问题（全局镜像 init 脚本与仓库模式冲突、dl.google.com TLS 不稳定导致 lint 依赖下载失败等），已修复并记录。**构建前请先阅读 [`docs/build-guide.md`](docs/build-guide.md)**，其中包含环境说明、问题根因与修复方式、命令速查和换机注意事项。

快速开始：

```bat
.\gradlew :app:assembleDebug          rem debug APK
.\gradlew :app:testDebugUnitTest      rem 456 个单元测试
.\gradlew :app:assembleRelease        rem release（R8 + 资源压缩 + 签名）
```

产物：

- debug：`app/build/outputs/apk/debug/app-debug.apk`
- release：`app/build/outputs/apk/release/MyNote-1.8.0-release.apk`（文件名随 versionName 变化）

## 项目结构

```
app/src/main/java/com/mynote/app/
├── MyNoteApp.kt              # Application，持有 AppContainer
├── MainActivity.kt           # 唯一 Activity
├── di/AppContainer.kt        # 手写依赖注入（database/imageStore/repository/backup）
├── data/
│   ├── db/                   # NoteEntity / CategoryEntity / NoteRevisionEntity / AiSessionEntity / AiMessageEntity / DAO / AppDatabase（Room v5 + 迁移）
│   ├── repository/           # NoteRepository（业务逻辑 + 图片垃圾回收）
│   ├── settings/             # ThemeSettingsStore（SharedPreferences + StateFlow）
│   ├── ai/                   # DeepSeek API 客户端（SSE）/ 会话 / 消息组装 / 会话仓库
│   ├── image/                # ImageStore（落盘 / 采样压缩 / GC）
│   ├── backup/               # BackupManager（zip 备份导入导出 / txt 导出）
│   └── export/               # NoteImageRenderer / ImageExportManager（图片导出）
├── ui/
│   ├── theme/                # 主题（8 档色板 / 动态取色应用）
│   ├── notes/                # 列表 / 编辑 / 解析器 / ViewModel
│   ├── categories/           # 分类管理
│   ├── settings/             # 主题与通用设置页 / ViewModel（含 AI 助手 Key / 模型配置）
│   ├── export/               # 导出图片预览页 / ViewModel
│   ├── history/              # 历史时间线 / diff 详情 / 恢复
│   ├── ai/                   # AI 聊天页 / ViewModel（会话抽屉 / 流式回答 / 回答落地）
│   └── navigation/           # Compose Navigation 路由
└── util/                     # 日期格式化等
```

图片不存数据库：文件写入私有目录 `filesDir/notes_images/`，正文只存标记；删除笔记后自动回收孤儿图片。

## 文档

- 设计文档：[`docs/superpowers/specs/2026-08-13-mynote-design.md`](docs/superpowers/specs/2026-08-13-mynote-design.md)
- 主题设置设计：[`docs/superpowers/specs/2026-09-10-theme-settings-design.md`](docs/superpowers/specs/2026-09-10-theme-settings-design.md)
- 图片导出设计：[`docs/superpowers/specs/2026-09-10-note-image-export-design.md`](docs/superpowers/specs/2026-09-10-note-image-export-design.md)
- 历史修改记录设计：[`docs/superpowers/specs/2026-09-10-note-history-design.md`](docs/superpowers/specs/2026-09-10-note-history-design.md)
- AI 助手（DeepSeek 官方 API）设计：[`docs/superpowers/specs/2026-09-15-deepseek-api-assistant-design.md`](docs/superpowers/specs/2026-09-15-deepseek-api-assistant-design.md)
- AI 助手（DeepSeek 官方 API）计划（含修订记录）：[`docs/superpowers/plans/2026-09-15-deepseek-api-assistant.md`](docs/superpowers/plans/2026-09-15-deepseek-api-assistant.md)
- AI 网页端助手设计（已被 API 方案替换）：[`docs/superpowers/specs/2026-09-11-ai-web-assistant-design.md`](docs/superpowers/specs/2026-09-11-ai-web-assistant-design.md)
- AI 网页端助手计划（已被 API 方案替换）：[`docs/superpowers/plans/2026-09-11-ai-web-assistant.md`](docs/superpowers/plans/2026-09-11-ai-web-assistant.md)
- 图片导出计划（含修订记录）：[`docs/superpowers/plans/2026-09-10-note-image-export.md`](docs/superpowers/plans/2026-09-10-note-image-export.md)
- 历史修改记录计划（含修订记录）：[`docs/superpowers/plans/2026-09-10-note-history.md`](docs/superpowers/plans/2026-09-10-note-history.md)
- 实现计划（含修订记录）：[`docs/superpowers/plans/2026-08-13-mynote.md`](docs/superpowers/plans/2026-08-13-mynote.md)
- 构建指南（问题记录 + 环境说明）：[`docs/build-guide.md`](docs/build-guide.md)
