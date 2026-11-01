# AI 助手 system 提示词自定义 设计

日期：2026-10-30
状态：已实现（方案经用户逐项确认）

## 1. 背景与问题

有用户反馈「预设提示词质量太低」，希望自己写。改之前用户**完全无法干预**：

| 证据 | 位置 |
|---|---|
| system 提示词是编译期常量，且设计上「文案冻结」 | `data/ai/AiPromptBuilder.kt:11`；`specs/2026-10-15-ai-token-optimization-design.md:25` |
| 唯一注入点写死 `AiPromptBuilder.SYSTEM_PROMPT` | `data/ai/AiApiMessageBuilder.kt:64` |
| 设置页 AI 段只暴露服务商/地址/模型/思考/Key | `ui/settings/SettingsScreen.kt:321-500` |
| 存储已具备（prefs `ai_settings`，按 serviceId 隔离） | `data/settings/AiSettingsStore.kt` |

## 2. 方案选择（已确认）

采用 **方案 B：内置默认 + 可完全覆盖**，编辑界面为独立全屏页。

已排除：

- **A 追加式「补充要求」**：删不掉用户真正讨厌的内置规则（「简洁」「不复述」），等于没解决问题。
- **C 多套提示词库**：要新增 Room 表 + `MIGRATION_6_7` + 迁移测试，约 4 倍工作量；对单人备忘录过度设计。
- **D 会话级覆盖**：B 的超集，等「不同笔记用不同提示词」的真实需求再叠加，届时 B 无需返工。

## 3. 范围

### 3.1 接管

只有 **system 提示词**可被用户替换。

### 3.2 明确不动

- `AiPromptBuilder.build` 的笔记包装（`【笔记正文】` / `【要求】` 表头）与正文截断预算（`MAX_NOTE_CHARS` / `MAX_HISTORY_CHARS`）——它们是「正文只进首条消息 + 请求时幂等重包」的锚点，被用户改坏后无法自动恢复。
- 截断提示文案「（正文过长，已截断）」。
- 默认提示词**文案本身一字不改**，只是从「不可改的常量」变成「可覆盖的默认值」。
- 已落库的历史消息不因改提示词而变化。

## 4. 契约

### 4.1 数据层（`data/settings/AiSettingsStore.kt`）

- 新 prefs key `system_prompt`，**全局**（不加 serviceId 后缀：换服务商不该丢提示词）。
- `customSystemPrompt(): String` —— 原文；空白表示未自定义。
- `setCustomSystemPrompt(text: String?)` —— trim 后按上限截断；空白等价清除（回退内置）。
- 不新增 Room 表、不动数据库版本。

### 4.2 提示词解析（`data/ai/AiPromptBuilder.kt`）

- `SYSTEM_PROMPT` 语义改为「内置默认」。
- 新增 `MAX_SYSTEM_PROMPT_CHARS = 2_000`。
- 新增 `resolveSystemPrompt(custom: String?)`：空白/全空白 → `SYSTEM_PROMPT`；否则 trim 后 take(MAX)。

### 4.3 请求层（`data/ai/AiApiMessageBuilder.kt`）

- `build(..., systemPrompt: String = AiPromptBuilder.SYSTEM_PROMPT)`。
- 默认参数置于参数表末尾 → **既有 25 处测试调用零改动**。
- prepend 使用传入值。

### 4.4 ViewModel 接线（`ui/ai/AiChatViewModel.kt`）

- 两处 `AiApiMessageBuilder.build`（发送、重试）显式传
  `systemPrompt = AiPromptBuilder.resolveSystemPrompt(settingsStore.customSystemPrompt())`。
- 每次发送都从 store 读取 → **改完立即对下一次发送生效**（含进行中的会话）。

### 4.5 设置 VM + UI

- `SettingsViewModel`：`customSystemPrompt: StateFlow<String>`、`setSystemPrompt`、`resetSystemPrompt`、`refreshSystemPrompt`。
- 设置页 AI 段「深度思考」之后新增「系统提示词」行：显示「内置默认」或自定义首行摘要 +「编辑」→ `onOpenPromptEditor()`。
- 新路由 `settings/prompt` → `ui/settings/PromptEditScreen.kt`：
  - 多行 `OutlinedTextField`，逐字写入（沿用本页「接口地址」的即时保存先例）；
  - 字数计数 `n/2000`，达上限后拒绝继续输入（避免「界面显示」与「已存储」不一致）；
  - 「恢复默认」（有自定义时可用）→ 二次确认；
  - 字段下方只读展示内置默认原文，标注「留空即使用以下内置默认」。

## 5. 文件清单

新增：

- `app/src/main/java/com/mynote/app/ui/settings/PromptEditScreen.kt`
- `docs/superpowers/specs/2026-10-30-ai-custom-prompt-design.md`（本文）
- `docs/superpowers/plans/2026-10-30-ai-custom-prompt.md`

修改：

- `data/settings/AiSettingsStore.kt`、`data/ai/AiPromptBuilder.kt`、`data/ai/AiApiMessageBuilder.kt`
- `ui/ai/AiChatViewModel.kt`、`ui/settings/SettingsViewModel.kt`、`ui/settings/SettingsScreen.kt`、`ui/navigation/AppNavHost.kt`

单测：

- `AiSettingsStoreTest`、`AiPromptBuilderTest`、`AiApiMessageBuilderTest`、`SettingsViewModelTest`

## 6. 验收标准

1. 设置页出现「系统提示词」行，未自定义时显示「内置默认」。
2. 编辑页可写入、即时生效、可恢复默认；清空保存后回到「内置默认」。
3. 自定义后下一次请求的首条消息 `role=system` 且内容为用户文本；未自定义时为内置默认原文（既有 25 处用例不改即通过）。
4. 上限 2000：输入框物理限制 + store 截断双保险。
5. 切换服务商后自定义提示词保留（全局作用域）。
6. `:app:testDebugUnitTest` 全绿、`:app:assembleDebug` 成功。

## 7. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 用户写成废提示词导致回答崩 | 「恢复默认」+ 编辑页常显内置默认原文 |
| 提示词过长挤占上下文 | 2000 字符上限（约 1~1.5k tokens） |
| 老用户行为变化 | 默认文案一字不改；未自定义时请求与改动前完全一致 |
| 「文案冻结」约定被打破 | 本节即修订记录；默认文案仍由代码集中维护 |

## 8. 修订记录

- 2026-10-30 初版：方案 B + 独立编辑页。用户逐项确认：作用域全局、不接管笔记包装、上限 2000、改完即时生效。
