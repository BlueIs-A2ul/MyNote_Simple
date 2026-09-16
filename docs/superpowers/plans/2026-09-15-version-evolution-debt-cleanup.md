# 版本演进欠账清理（文档对齐 + 死代码/重复实现）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 v1.6–v1.10 三次大重构留下的文档漂移、死代码与重复实现一次性收尾，且不改变任何用户可见行为。

**Architecture:** 纯删除 + 纯收敛 + 文档回写。代码侧只有一处实现合并（文件名清洗），其余为删除零调用者声明与死参数；文档侧只回写「当前态」文档，历史修订记录一律不改写、只追加。

**Tech Stack:** Kotlin 2.0.20 / AGP 8.5.2 / Room 2.6.1 / Robolectric 4.13（本批不动工具链）；测试断言用 JUnit4 + Robolectric `@Config(sdk = [34])`。

**设计文档：** `docs/superpowers/specs/2026-09-15-version-evolution-debt-cleanup-design.md`

---

## 文件清单

**修改（代码，7 个）**
- `app/src/main/java/com/mynote/app/di/AppContainer.kt` — 删死方法 `aiEndpoint()`
- `app/src/main/java/com/mynote/app/data/ai/AiChatRepository.kt` — 删死方法 `getSession()`
- `app/src/main/java/com/mynote/app/data/db/AiSessionDao.kt` — 删仅测试引用的 `getById()`
- `app/src/main/java/com/mynote/app/data/db/AiMessageDao.kt` — 删被替代的 `updateContentAndStatus()`
- `app/src/main/java/com/mynote/app/data/ai/DeepSeekModels.kt` — 删 `label()` / `isValid()`
- `app/src/main/java/com/mynote/app/util/FileNameSanitizer.kt` — 加 `maxLength` 参数、截断后 trim
- `app/src/main/java/com/mynote/app/data/export/ImageExportManager.kt` — `sanitizeName` 改为委托
- `app/src/main/java/com/mynote/app/ui/notes/NoteEditScreen.kt` — 删死参数 `initialCategoryId`
- `app/src/main/java/com/mynote/app/ui/components/PaperTopBar.kt` — 弃用图标换 `AutoMirrored`
- 注释订正：`ui/theme/Color.kt`、`data/ai/AiChatMessage.kt`、`data/ai/AiPromptBuilder.kt`

**修改（测试，6 个）**
- `app/src/test/java/com/mynote/app/data/db/AiMessageDaoTest.kt` — 删用例、serviceId 更新
- `app/src/test/java/com/mynote/app/data/db/AiSessionDaoTest.kt` — 断言改写、serviceId 更新
- `app/src/test/java/com/mynote/app/data/ai/AiChatRepositoryTest.kt` — serviceId 更新
- `app/src/test/java/com/mynote/app/data/ai/DeepSeekModelsTest.kt` — 删 2 个用例
- `app/src/test/java/com/mynote/app/ui/notes/HasUnsavedChangesTest.kt` — 26 处调用改 7 参
- `app/src/test/java/com/mynote/app/util/FileNameSanitizerTest.kt` — 新增 2 个用例
- `app/src/test/java/com/mynote/app/ui/ai/MarkdownParserTest.kt` — 注释订正

**修改（文档）**：`docs/build-guide.md`、`docs/backlog.md`、`README.md`、10 份 spec + 2 份 plan 的状态行。

---

## Task 1: 文档对齐（先做，零代码风险）

**Files:**
- Modify: `docs/build-guide.md:85,87`
- Modify: `docs/backlog.md:51-61,65,274,290-292,389,419,426`
- Modify: `README.md:29,60,80-99`
- Modify: `docs/superpowers/specs/*.md`（10 份状态行）、`docs/superpowers/plans/2026-09-11-ai-web-assistant.md`、`docs/superpowers/plans/2026-09-15-ai-provider-support.md`

- [ ] **Step 1: build-guide 数字纠正**

`docs/build-guide.md:85` → `| 全部单元测试 | `.\gradlew :app:testDebugUnitTest` | 584 个测试全部 PASS |`
`docs/build-guide.md:87` → 体积由 `约 1.7MB` 改为 `约 2.0MB`（实测 `MyNote-1.10.1-release.apk` = 1.97MB），与 README 口径统一。

- [ ] **Step 2: backlog 条目状态回写（表格与正文两处一起改）**

| 位置 | 改前 | 改后 |
|---|---|---|
| `:51` | `已建议（2026-09-14，待实现）` | `已完成（2026-09-15，v1.7.0，随条目 52）` |
| `:52` | 条目 37 `已建议（…待实现）` | `❌ 已作废（v1.6.0 移除网页驱动）` |
| `:53` | 条目 38 同上 | `❌ 已作废（v1.6.0 移除网页驱动）` |
| `:55` | 条目 40 `已完成（2026-09-14，v1.5.0）` | `已完成（2026-09-14，v1.5.0）⚠ 缩略图机制已于 v1.6.1 移除，仅保留 Coil 缓存上限` |
| `:61` | 条目 46 `已建议（…待实现）` | `部分完成（导入已随条目 43 于 v1.6.1 流式化；导出流式化待实现）` |
| `:274` | `### 36. AI 失败重试按钮（API 模式下仍适用，未实现）` | `### 36. AI 失败重试按钮 ✅ 已完成（2026-09-15，v1.7.0，随条目 52）` |
| `:290-292` | 条目 40「预览用 thumbFile…」现状/建议 | 现状补一句「缩略图机制已于 v1.6.1 整体移除（`ImageStore.kt` 只保留 `thumb_*` 遗留清理），预览直接读原图」；建议改为「本条的缩略图部分已作废，仅 Coil 缓存上限生效」 |
| `:389` | `ui/ai/（AiChatScreen/AiChatViewModel/DeepSeekDriver）` | `ui/ai/（AiChatScreen/AiChatViewModel/AiApiSession）` |

- [ ] **Step 3: 总览表补 51–70 行（消除两处维护状态的结构性成因）**

在 `:65` 之后追加 6 行：
```
| 51–64 | AI 助手优化批（看门狗/重试/预算/用量/草稿等） | P1 | L | 已完成（2026-09-15，v1.7.0） | — |
| 65–66 | AI 兼容性加固（动态模型列表 / 422 降级） | P1 | S/M | 已完成（2026-09-15，v1.7.1） | — |
| 67 | 新用户欢迎笔记 | P1 | S | 已完成（2026-09-15，v1.7.2） | — |
| 68 | 字体切换 | P2 | L | 评估后暂缓（用户拍板不实现） | — |
| 69 | AI 服务商可切换 | P1 | L | 已完成（2026-09-15，v1.10.0） | — |
| 70 | AI 模型选择体验优化 | P1 | S/M | 已完成（2026-09-15，v1.10.1） | — |
```

- [ ] **Step 4: 修正 `:426` 的重复计数并追加本次修订记录行**

`:426` 的「新增 68、移除旧 12」重复计入了随 v1.8.0 基线统计的 56 条 → 改为「本批净增 0（新增 `MarkdownPlainTextTest` 12、移除 `CodeBlockParserTest` 12）；Markdown 特性累计新增 68、移除 12，其中 56 条已计入 v1.8.0 的 547 基线」。
文末追加一行修订记录，记录：本次清理批内容、缩略图回退补记、条目状态修正、v1.6.0 单测数 351 校正为 352、非发布批不改版本号。

- [ ] **Step 5: README 三处**

`:29` 产物体积改为与 build-guide 一致的「约 2.0MB」；`:60` `settings/` 描述补全为「ThemeSettingsStore / NoteSortStore / TrashRetentionStore / OnboardingStore / AiSettingsStore」；`:80-99` 文档清单补 `docs/backlog.md` 与 v1.9.0 的 Markdown 渲染设计 + 计划。

- [ ] **Step 6: 10 份 spec + 2 份 plan 的状态行**

统一把已发布特性的状态从「实现中 / 待实现」改为「已实现（版本）」：
`ai-assistant-optimization`→v1.7.0、`api-compat-hardening`→v1.7.1、`new-user-welcome-note`→v1.7.2、`ai-provider-support`→v1.10.0、`ai-model-ux`→v1.10.1、`ai-markdown-rendering`→v1.9.0、`paper-ui-redesign`→已实现、`mynote-design`→已实现（并注明缩略图方案已于 v1.6.1 移除）、`deepseek-api-assistant`→已实现（v1.6.0，并注明 v1.10.0 已多服务商化并改名）。
两份**已作废**的文档加横幅：`specs/2026-09-11-ai-web-assistant-design.md` 与 `plans/2026-09-11-ai-web-assistant.md` 顶部注明「已被 API 方案替换，WebView 层已于 v1.6.0 删除，本文仅存历史」；`plans/2026-09-15-ai-provider-support.md` Goal 处注明「最终版本定为 1.10.0」。

- [ ] **Step 7: 提交**

```bash
git add docs README.md
git commit -m "docs: 回写版本演进遗留的文档欠账（单测数/条目状态/缩略图回退/设计文档状态）"
```

---

## Task 2: 文件名清洗合并（本批唯一实现改动，先写测试）

**Files:**
- Modify: `app/src/main/java/com/mynote/app/util/FileNameSanitizer.kt`
- Modify: `app/src/main/java/com/mynote/app/data/export/ImageExportManager.kt:24-32`
- Test: `app/src/test/java/com/mynote/app/util/FileNameSanitizerTest.kt`

- [ ] **Step 1: 先加两个失败用例**

```kotlin
@Test
fun customMaxLengthIsHonored() {
    assertEquals("a".repeat(40), FileNameSanitizer.sanitize("a".repeat(120), maxLength = 40))
}

@Test
fun truncationDoesNotLeaveTrailingWhitespace() {
    // 截断点落在空白处时不得留下尾随空格（图片导出侧原有语义）
    assertEquals("a", FileNameSanitizer.sanitize("a" + " ".repeat(100) + "b", maxLength = 40))
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.util.FileNameSanitizerTest"`
Expected: 编译失败（`sanitize` 无 `maxLength` 参数）。

- [ ] **Step 3: 实现**

```kotlin
fun sanitize(raw: String, fallback: String = "note", maxLength: Int = MAX_LENGTH): String {
    val replaced = buildString(raw.length) {
        for (c in raw) append(if (c in ILLEGAL_CHARS || c.code < 32) '_' else c)
    }
    // 先按上限截断，再去掉首尾空白与连续的 "."（截断点落在空白处不会留下尾随空格）
    val result = replaced.take(maxLength.coerceAtLeast(0)).trim().trim('.')
    return if (result.isEmpty()) fallback else result
}
```

- [ ] **Step 4: 图片导出委托（删掉重复正则与分叉阈值）**

```kotlin
/** 文件名基名清洗：与 txt 导出同一实现，仅上限 40 + 回退「笔记」。 */
fun sanitizeName(title: String): String =
    FileNameSanitizer.sanitize(title, fallback = "笔记", maxLength = 40)
```

- [ ] **Step 5: 跑两侧测试**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.util.FileNameSanitizerTest" --tests "com.mynote.app.data.export.ImageExportManagerTest"`
Expected: PASS（`ImageExportManagerTest` 的 3 个 `sanitizeName*` 断言语义不变）。

- [ ] **Step 6: 提交（与 Task 3 合并提交亦可）**

---

## Task 3: 删死代码与死参数

**Files / 证据（均已复核为零生产引用）**

- [ ] **Step 1: `AppContainer.aiEndpoint()`**（`di/AppContainer.kt:80-82`，全仓零引用）整段删除；确认 `AiEndpoint` 仍被 `aiApiClientFactory` 与 `aiSessionFactory` 使用，import 保留。
- [ ] **Step 2: `AiChatRepository.getSession()`**（`data/ai/AiChatRepository.kt:18`）删除。
- [ ] **Step 3: `AiSessionDao.getById()`**（`data/db/AiSessionDao.kt:14-15`）删除；`AiSessionDaoTest.kt:63` 断言改为 `assertEquals(999, db.aiSessionDao().observeByNote(noteId).first().single().updatedAt)`。
- [ ] **Step 4: `AiMessageDao.updateContentAndStatus()`**（`data/db/AiMessageDao.kt:20-21`）+ 用例 `AiMessageDaoTest.updateContentAndStatusForInterrupted`（`:56-66`）删除（v1.7.0 起半截回答改走 `appendMessage` 新消息落库）。
- [ ] **Step 5: `DeepSeekModels.label()` / `isValid()`**（`DeepSeekModels.kt:13-19`）+ 用例 `validityCheck`、`labelFallsBackToId` 删除（v1.10.1 动态模型列表后由 `AiSettingsStore.model()` 承担校验；`label` 是恒等映射）。
- [ ] **Step 6: `hasUnsavedChanges` 死参数 `initialCategoryId`**（`ui/notes/NoteEditScreen.kt:255` 形参、`:404` 实参）删除；`HasUnsavedChangesTest` 26 处调用改为 7 参（第 3 个参数位删除，其余顺序不变）。
- [ ] **Step 7: 弃用图标**：`ui/components/PaperTopBar.kt:14,43` → `androidx.compose.material.icons.automirrored.filled.ArrowBack` + `Icons.AutoMirrored.Filled.ArrowBack`（与 `ui/ai/AiChatScreen.kt:20` 一致）。
- [ ] **Step 8: 注释订正**：`ui/theme/Color.kt:46-47`（`NoteColors` 并非"兼容旧调用点"，而是 4 处在用的现行命名）、`data/ai/AiChatMessage.kt:3`（去掉 DeepSeek 专属表述）、`data/ai/AiPromptBuilder.kt:11,15`（网页版语境已随 v1.6.0 失效）、`ui/ai/MarkdownParserTest.kt:82`（`CodeBlockParser` 已于 v1.9.0 移除）。
- [ ] **Step 9: 测试内服务商身份统一**：`AiChatRepositoryTest`、`AiSessionDaoTest`、`AiMessageDaoTest` 中的 `serviceId = "deepseek"`（历史网页版身份，已不对应任何服务商）改为 `AiProvider.DEEPSEEK.serviceId`（dao 测试补 import）。
- [ ] **Step 10: 跑全量测试**

Run: `.\gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，583 个用例全绿、0 失败。

- [ ] **Step 11: 提交**

```bash
git add app
git commit -m "chore: 删除版本演进遗留的死代码与重复实现（端点死方法/AI 遗留实现/死参数/文件名清洗合并）"
```

---

## Task 4: 收尾（主树执行）

- [ ] **Step 1:** 主树 `git checkout -- docs/backlog.md` 丢弃被本分支完整包含的旧工作区改动（已核对内容一致）。
- [ ] **Step 2:** `git merge --ff-only version-debt-0915`（保持 master 线性）。
- [ ] **Step 3:** `git worktree remove .worktrees/version-debt-0915` + `git branch -d version-debt-0915`。
- [ ] **Step 4:** 不 push（AGENTS.md：仅用户明确要求时执行）。

---

## 修订记录

| 日期 | 内容 |
|---|---|
| 2026-09-15 | 初版：按「文档对齐 + 代码清理」两批拆分为 4 个任务；明确不做工具链升级与 `http://` 策略变更（见设计文档 §3）。 |
| 2026-09-15 | 执行完成：Task 1–3 全部落地，全量单测 584 → 582 全绿（删 4 个用例：`AiMessageDaoTest.updateContentAndStatusForInterrupted`、`DeepSeekModelsTest.validityCheck` / `labelFallsBackToId`、`HasUnsavedChangesTest` 去掉死参数后重复的 1 个；新增 2 个 `FileNameSanitizer` 参数化用例；另 `AiSessionDaoTest` 的 1 个断言改为走 `observeByNote`）。两处偏离计划：`AiPromptBuilder.maxNoteChars` 的默认值按 YAGNI 保留（只订正注释），`AppDatabaseMigrationTest` 内的历史 `serviceId` 夹具保持原样（模拟旧库数据）。Task 4 收尾在主树执行。 |
