# 版本演进欠账清理（文档对齐 + 死代码/重复实现）— 设计文档

- 日期：2026-09-15
- 状态：设计已确认（用户拍板「第 1 步 + 第 2 步」）
- 范围：非发布批（纯文档 + 纯内部清理，无用户可见行为变更、不改版本号）
- 来源：三路只读审计（工具链/依赖、死代码与重构遗留、文档一致性）+ 主线复核

## 1. 背景与问题

项目从 v1.2.0 快速推进到 v1.10.1，期间有三次大重构（v1.6.0 网页驱动 AI → API 驱动、v1.9.0 自研 Markdown 渲染替换 `CodeBlockParser`、v1.10.0 单服务商 → 多服务商并批量改名）。
重构本身是干净的（584 个单测全绿、无编译级弃用告警），但**围绕重构的文档与少量边角代码没有同步收尾**，具体三类：

1. **文档落后于版本**：`build-guide.md` 的单测数停留在 376（实际 584）；`backlog.md` 里 v1.7.0 已实现的条目 36 仍写「未实现」、条目 37/38 表格与正文自相矛盾；条目 40 描述的 300px 缩略图机制已在 v1.6.1 整体删除，却无任何文档记录这次回退；6 份已发布特性的设计文档状态仍停在「实现中 / 待实现」；backlog 总览表止于条目 50，51–70 无表格行（状态要在两处维护，正是上面矛盾的成因）。
2. **新旧实现并存留下的死代码**：v1.10.x 的端点重构与 v1.7.x 的「半截回答作为新消息落库」改动，留下了 4 处零调用者声明与 1 个从未使用的函数参数。
3. **同一职责的两套实现已经分叉**：`FileNameSanitizer`（txt 导出，上限 80、回退 `note`）与 `ImageExportManager.sanitizeName`（图片导出，上限 40、回退 `笔记`）是同一套规则的两份实现，同一篇长标题导出的 `.txt` 与 `.png` 文件名不一致。

## 2. 决策

| 决策点 | 结论 | 理由 |
|---|---|---|
| 是否动产品行为 | **不动**（唯一例外见下） | 本批定位为欠账清理，任何行为变更另开批次 |
| 文件名清洗如何合并 | 收敛到 `FileNameSanitizer.sanitize(raw, fallback, maxLength)`，图片侧传 `fallback="笔记", maxLength=40` | 保留两侧现有输出（txt 80/`note`、png 40/`笔记`），只删重复实现；顺带修正 `sanitize` 截断后未 trim 空白的潜在瑕疵（截断到 80 位若落在空白处会得到尾随空格） |
| 截断顺序 | 统一为「先按上限截断 → trim 空白 → 去首尾 `.`」 | 与 `ImageExportManager` 现有语义一致，且 `FileNameSanitizerTest` 现有 10 个用例全部继续成立（已逐条推演） |
| 仅被测试引用的 DAO/工具方法 | **保留** `NoteRevisionDao.getByNote`、`ImageStore.dir`、`AiApiClient.activeStreamCount`；**删除**已被替代的 `AiMessageDao.updateContentAndStatus`、`DeepSeekModels.label/isValid`、`AiSessionDao.getById` | 前者是测试的合法访问口（删除需改写 8–15 行测试、零产品收益）；后者是被新版本取代的旧实现，留着会误导 |
| `remoteChatId` 历史列 | **保留不动** | 删除需 `MIGRATION_6_7` 重建表（旧 SQLite 无可靠 `DROP COLUMN`），成本远高于收益 |
| `ImageStore` 的 `thumb_*` 清理分支 | **保留不动** | 对 v1.5.0 之前升级上来的存量安装仍有用 |
| 版本号 | 不递增 | 本批无用户可见变更，按既有惯例（如 `docs: AGENTS.md ...` 提交）不触发版本行同步 |

## 3. 非目标（本次明确不做，另行排期）

- 工具链升级（Gradle/AGP/Kotlin/KSP/Room/Compose BOM/Robolectric，及 `compileSdk/targetSdk 36`）。
- 自定义服务商 `http://` 地址被系统明文策略拦截、以及聊天页不校验地址非空的误导文案（需产品拍板拒绝 http 还是加作用域 network security config）。
- `collectAsState` → `collectAsStateWithLifecycle`（约 30 处，属升级批）、`LocalClipboardManager` → `LocalClipboard`（需 Compose 1.8+，本 BOM 无该 API）、`Material` 图标内联（去 `material-icons-extended`）。
- `AppDatabaseMigrationTest` 手工建库 DDL 去重（约 120 行测试改动）。

## 4. 验收

- `.\gradlew :app:testDebugUnitTest` 全绿；测试数 = 584 − 4（随被替代实现删除的 3 个用例 + `hasUnsavedChanges` 去掉死参数后重复的 1 个用例）+ 2（`FileNameSanitizer` 新增参数化用例）= 582。
- 无用户可见行为变更（文件名清洗两侧输出保持不变，由既有 `FileNameSanitizerTest` / `ImageExportManagerTest` 断言保证）。
- 文档中不再存在「已实现却写未实现」「已删除机制仍作为现状描述」的条目。
