# MyNote — 安卓备忘录应用设计文档

- 日期：2026-08-13
- 状态：已确认（待进入实现计划）

## 1. 目标与约束

一个**内存占用小、足够灵活、设计简洁**的安卓备忘录应用。硬约束优先级：

1. **内存占用小**：原生实现、无重型第三方依赖、图片按需解码、打包体积精简。
2. **足够灵活**：分类组织、图文混排、可导入导出。
3. **设计简洁**：单 Activity + Material 3，浅色/深色主题，克制的一屏一主交互。

## 2. 功能范围（已确认）

- 纯文本备忘录：创建 / 编辑 / 删除 / 置顶 / 列表
- 全文搜索（`LIKE '%kw%'`）
- 图文混排：图片内嵌正文（正文仍以纯文本存储）
- 分类（单维度，每篇笔记属于一个分类，可为空）
- 深色模式（跟随系统）
- 备份 / 导出 / 导入

明确**不做**：提醒通知、Markdown 渲染、标签多对多、云同步、账号体系。

## 3. 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 语言 | Kotlin | 原生，无运行时包袱 |
| UI | Jetpack Compose + Material 3 | 声明式，深色模式/动态取色原生支持 |
| 架构 | MVVM + 单向数据流（UDF） | 分层清晰、易测试 |
| 数据库 | Room（SQLite） | 唯一合理选择，Flow 响应式查询 |
| 图片加载 | Coil 3 | 轻量、协程 + Compose 原生 |
| 序列化 | kotlinx.serialization | 备份 JSON，无反射 |
| 导入导出 | Storage Access Framework（SAF） | 零存储权限 |
| 异步 | Coroutines + Flow | 标准 |
| 导航 | Compose Navigation | 简洁 |
| DI | 手写 `AppContainer` | 免注解处理器，此规模足够 |
| 构建 | Gradle Kotlin DSL + Version Catalog | 依赖集中管理 |

**关键默认值**：`minSdk 24`（Android 7.0）、单模块 `app`、包名 `com.mynote.app`（占位，可改）、应用名 `MyNote`（中文名待定）。

## 4. 项目结构

```
myNote/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        └── java/com/mynote/app/
            ├── MyNoteApp.kt
            ├── MainActivity.kt
            ├── di/AppContainer.kt
            ├── data/
            │   ├── db/                 # NoteEntity / CategoryEntity / DAO / AppDatabase
            │   ├── repository/NoteRepository.kt
            │   ├── image/ImageStore.kt       # 图片落盘、压缩、缩略图、垃圾回收
            │   └── backup/BackupManager.kt   # JSON 导出/导入、zip 打包
            ├── ui/
            │   ├── theme/              # Color / Theme / Type
            │   ├── notes/              # 列表 / 编辑（图文混排）、NoteViewModel
            │   ├── categories/         # 分类管理、CategoryViewModel
            │   └── components/         # 空状态、确认框、图片块等
            └── util/                   # 日期格式化等
```

不引入独立 `domain/` 模块或 usecase 层——当前规模下 `Repository` 直接承载业务逻辑（YAGNI）。

## 5. 数据模型

- **NoteEntity**：`id`(PK)、`title`、`content`(含图片标记的纯文本)、`createdAt`、`updatedAt`、`categoryId`(可空外键)、`pinned`(Boolean)、`color`(Int?)
- **CategoryEntity**：`id`(PK)、`name`(唯一)、`color`(Int?)

索引：`NoteEntity.updatedAt`（列表排序）、`NoteEntity.categoryId`（分类筛选）、`NoteEntity.pinned`。

图片不设独立表：通过解析 `content` 中的 `![](img/<name>)` 引用发现图片文件；删除标记后由垃圾回收清理孤儿文件。

## 6. 图文混排模型（核心决策）

采用**纯文本存储 + 内嵌图片标记语法**，而非富文本二进制格式：

- 正文存储为纯文本，图片用 Markdown 风格标记内嵌：`![](img/<uuid>.<ext>)`。
- **查看**：解析正文拆成「文本段 / 图片段」交错列表，在 `LazyColumn` 中图文混排渲染，图片懒加载。
- **编辑**：纯文本输入框 + 「插入图片」按钮，在光标处插入标记；图片在编辑态显示为可点击/可删除的图片块。
- 优势：正文保持纯文本（搜索、备份、存储都极简），图片按需解码，避免 WebView/富文本编辑器等重型组件。

## 7. 图片存储与处理（内存关键）

- 图片**不存数据库**（不用 BLOB），文件写入应用私有目录 `filesDir/notes_images/`，文件名 UUID。
- 选图后立即处理：
  - 原图按最长边 ~1600px 采样压缩（`BitmapFactory` inSampleSize + 重编码 JPEG/WebP）。
  - 生成 ~300px 缩略图用于列表/正文懒加载。
- 列表/正文用 Coil 3 加载，配 `LazyColumn` 懒加载，只解码可见图片。
- 插入图片时复制进私有目录，不持有外部文件引用。

**路径映射（统一约定）**：物理文件位于私有目录 `filesDir/notes_images/<uuid>.<ext>`；正文标记 `![](img/<uuid>.<ext>)` 中的 `img/` 是**逻辑路径**，渲染时由 `ImageStore` 映射到物理文件；备份 zip 内目录名为 `img/`，导入时解压还原到 `notes_images/`。

## 8. 图片来源与权限

- **相册**：Android 13+ 用系统 Photo Picker（免权限）；老版本回退 SAF。
- **拍照**：系统相机 App 拍照，落回私有目录。
- 全程**不申请** `READ_EXTERNAL_STORAGE` 等敏感权限。

## 9. 架构与数据流

```
UI(Compose) ──事件──▶ ViewModel ──调用──▶ Repository ──▶ DAO ──▶ Room(SQLite)
UI ◀──State── ViewModel ◀──Flow── Repository ◀──Flow── DAO ◀──Room
图片：UI ──▶ ImageStore ──▶ filesDir/notes_images/（正文只存标记）
备份：UI ──▶ BackupManager ──▶ SAF 导出 zip（notes.json + img/）/ 导入解压还原
```

错误处理：DAO 异常在 Repository 边界捕获转为 `Result`/密封类型，UI 以 Snackbar/空态呈现；图片处理失败不阻塞笔记保存（标记保留，图片缺失时显示占位符）。

## 10. 备份 / 导出 / 导入

- 单条笔记导出：`.txt`（图片标记原样保留）。
- 全量备份：`.zip`（`notes.json` + `img/` 图片目录）。
- 导入：解压 → 还原图片文件 → 校验正文标记与图片一一对应 → 合并策略（默认按 id 去重，冲突时保留更新者）。
- 导入/删除笔记后触发图片垃圾回收（清理无引用的孤儿文件）。

## 11. 内存优化策略

- 原生 Kotlin + Compose，不引入大型三方库（无图片上传、无富文本引擎）。
- Room 按需建索引，`Flow` 仅在界面可见时收集，避免常驻查询。
- 单 Activity + `LazyColumn` 惰性渲染 + Coil 懒加载图片。
- 图片采样压缩 + 缩略图，避免大图常驻内存。
- R8/ProGuard 资源压缩；AAB 交付 + 按 ABI 拆分 APK。
- 冷启动不做事，数据库与图片目录懒加载。

## 12. 实现计划（里程碑）

1. **脚手架**：Gradle 项目、Version Catalog、Compose、主题（浅/深）、空 Activity 跑通。
2. **数据层**：Room 实体 + DAO + 数据库 + Repository + JSON 序列化 + ImageStore（落盘/压缩/缩略图）。
3. **核心 UI**：笔记列表（置顶排序、搜索栏）、新建/编辑笔记、图文混排渲染与插入。
4. **分类 + 深色模式**：分类管理/筛选；深浅主题切换、动态取色。
5. **备份/导出/导入**：单条 txt 导出、全量 zip 备份/导入、图片垃圾回收。
6. **打磨**：R8 混淆、AAB + ABI 拆分、空状态、转场动画、性能校验。

## 13. 开放项 / 默认值

- 包名默认 `com.mynote.app`、应用名默认 `MyNote`（中文名如「简记」待定）——可改。
- 分类是否允许删除时把笔记移入「未分类」而非级联删除——默认移入未分类。
- 图片压缩质量与尺寸默认值（1600px / 300px）——后续可调。
