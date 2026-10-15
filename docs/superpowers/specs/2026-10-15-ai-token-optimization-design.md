# AI Token 成本与用量体验优化 — 设计文档

- 日期：2026-10-15
- 状态：待实现（计划版本 1.11.0，feature 批）
- 关联：`docs/superpowers/specs/2026-09-15-ai-assistant-optimization-design.md`（预算与用量的既有基础）、`2026-09-15-api-compat-hardening-design.md`（422 去字段重试的既有基础）、`2026-09-15-ai-provider-support-design.md`（`AiProvider`/`AiEndpoint` 扩展点）
- 范围：零新增三方依赖、无 Room 迁移、零新权限；对预设服务商（DeepSeek / 硅基流动）**请求预算行为零变化**（见 §2.3 数学性质）。
- 背景事实（2026-10-15 核对）：DeepSeek 官方 `deepseek-flash` / `deepseek-v4-pro` 上下文 1M token；硅基流动 `deepseek-ai/DeepSeek-V4-Flash` 1024K、列表内其余模型 ≥160K。当前 `MAX_HISTORY_CHARS=60_000` + `MAX_NOTE_CHARS=20_000` 字符（中文约 0.6–0.75 token/字）折合约 4–5 万 token，对预设服务商远未触及上限；**超限风险集中在自定义端点**（本地推理常见 8k/32k 窗口）。

## 1. 目标

1. **输出侧（成本大头）**：新增 system prompt 约束回答长度与复述——输出 token 单价约为输入的 4–8 倍，无 system 消息时回答长短完全凭模型发挥。
2. **缓存可见**：解析并展示 prompt 缓存命中量——首条消息（笔记正文 + 首问）在会话内字节稳定、是每次请求的固定前缀，DeepSeek 自动前缀缓存下这笔正文边际成本仅约原价一折，但用户现在完全看不见。
3. **用量可靠**：请求体补 `stream_options.include_usage`——OpenAI 兼容的自定义端点不带该字段时不返回 usage，用户对消耗完全失明。
4. **预算对齐 + 超限自愈**：上下文预算按端点上下文窗口动态收紧；400/422 超限时自动裁剪重试一次，仍失败给专用文案（现状两者一律报「请求被拒绝（参数错误）」，用户无从下手）。
5. **会话累计用量**：现状只有「上次用量」一轮数字，无法感知整个会话花了多少。

## 2. 冻结契约（实现依据）

### 2.1 system prompt 与消息组装（`AiPromptBuilder` + `AiApiMessageBuilder`）

```kotlin
// AiChatMessage 新增角色：
const val ROLE_SYSTEM = "system"

// AiPromptBuilder 新增常量（文案冻结，后续按反馈调整走修订记录）：
const val SYSTEM_PROMPT =
    "你是 MyNote 笔记应用内的 AI 助手。回答规则：直接回答用户问题，不复述笔记原文；" +
    "语言简洁，能一句话说清不写三段；仅在用户要求时展开细节；" +
    "涉及笔记内容时忠实于原文，不编造原文中不存在的信息。"
```

`AiApiMessageBuilder.build(...)` 组装顺序冻结为：

1. 过滤空历史 → `trimHistory` 裁剪（含动态预算，见 §2.3）
2. 首条 user 幂等重包笔记正文（现有 `hasNoteContext` 逻辑，**必须在 prepend system 之前**——现有判定基于 `messages.firstOrNull()`，system 挤进 first 会破坏重包）
3. 追加本次输入（现有逻辑）
4. **最后** `listOf(AiChatMessage(ROLE_SYSTEM, SYSTEM_PROMPT)) + messages`

规则：

- system 消息**不落库**（`ai_messages` 只存 user/assistant）、不进 history、不参与 `trimHistory` 裁剪与 `includeNoteContext` 判定（`history.none { role == USER }` 语义不变）。
- system 是消息数组第一个元素 → 稳定前缀最前端，前缀缓存性质**增强**而非破坏。
- 影响面：`AiApiSession.send` 的 `messages.isEmpty()` 检查在 prepend 后恒非空——该校验本就由 VM 输入校验兜底，语义无实际变化，契约注明即可。

### 2.2 请求体、字段剔除阶梯与 400/422 超限自愈（`AiApiClient`）

**请求体新增**（`requestBody`，无条件携带，`stream` 恒为 true 合法；严格校验端点对未识别字段的拒绝由下方剔除阶梯兜底）：

```json
"stream_options": { "include_usage": true }
```

**连接结果处理状态机**（按状态码判定，每次连接只落一个分支；每类自愈至多触发一次，允许跨连接有界叠加，总连接上限 = 1 首次 + 2 剔除 + 1 裁剪 + 1 退避 = **5**）：

| 触发 | 动作 | 次数上限 |
|---|---|---|
| `isContextLengthError(errorBody)`（**400 或 422**）且尚未裁剪过 | `trimToCore(messages)` 裁剪后重连；无可裁则直接映射专用文案 | 1 |
| 422，**或 400 且 `looksLikeUnsupportedField(errorBody)`**，且仍有未剔除的可选字段 | 剔除下一个可选字段立即重连 | 2（两种状态共用同一阶梯） |
| 429 / 503 且尚未退避过 | delay `retryDelayMs` 后重连 | 1 |
| 其余 | 读流 / 走 `mapHttpError` 映射 | — |

- **判定顺序：超限识别最优先**（先于剔除阶梯），避免把「输入过长」误剔成「参数不支持」。
- **可选字段剔除顺序：`stream_options` → `thinking`**。与现状相反方向的取舍：先剔统计性字段、保住用户显式打开的功能性字段（thinking）。两字段都剔完仍触发 → 走现有映射（「请求被拒绝（参数错误）」+ detail）。
- `isContextLengthError(body)` 为私有纯函数，识别锚点（小写匹配）：`context_length_exceeded`、`maximum context length`、`context length` + `exceed`、`reduce the length`、中文 `上下文` +（`超出`|`超过`）。
- `looksLikeUnsupportedField(body)` 为私有纯函数（仅 400 分支需要；422 天然视作参数被拒），识别锚点（小写匹配）：`stream_options`、`include_usage`、`unknown field`、`unrecognized`、`extra fields`、`未知参数`、`不支持的参数`。存在意义：严格校验的自定义端点可能对未识别参数返回 400（而非 422），无此分支时新增的 `stream_options` 会把原本可用的请求打成参数错误；锚点失灵时退化为现状映射（见 §5 风险表）。
- **`trimToCore(messages)`**：`AiApiMessageBuilder` 新增公开纯函数，保留 `[system, 首条 user（含正文）, 最新一条]`，其余中间消息全丢；输入 ≤3 条时原样返回。**无进展判定**：裁剪结果与输入等价（条数与内容均未变）时不再重试、直接映射——「输入 ≤3 条」与「首条 user 即最新一条」都由该判定统一覆盖，不会产生重复项。裁掉的中间消息**仍在本地库里**，下一轮请求按全量历史重建，非永久丢失。
- 超限但已裁剪过（或无可裁）→ 专用错误文案：**「对话内容超出该模型的上下文上限，请缩短笔记正文或新开对话」**（替换通用 400/422 文案，**不拼接 detail**）；非超限 400/422 维持现状文案与测试。

### 2.3 动态上下文预算（`AiProvider` + `AiEndpoint` + `AiSession` + `AiApiMessageBuilder`）

```kotlin
// AiProvider 枚举增字段（来源日期写进注释）：
val contextTokens: Int
// DEEPSEEK      = 1_000_000   // DeepSeek 官方 1M（2026-10 文档）
// SILICON_FLOW  = 1_048_576   // 硅基流动 V4-Flash 1024K；列表内其余模型 ≥160K
// CUSTOM        = 32_000      // 保守值：本地/自建端点常见 8k–32k，超限由 §2.2 自愈兜底

// AiEndpoint 增字段（默认 0，兼容既有测试构造）：
val contextTokens: Int = 0

// AiSession 增（带默认实现，Fake 不破）：
/** 端点上下文窗口（token）；0 = 未知，组装层回退默认预算。 */
val contextTokens: Int get() = 0
// AiApiSession：override val contextTokens: Int = endpoint.contextTokens
```

**预算计算（`AiApiMessageBuilder.build` 增参 `contextTokens: Int = 0`）**：

```kotlin
val totalBudget = if (contextTokens > 0)
    (MAX_HISTORY_CHARS + MAX_NOTE_CHARS).coerceAtMost(contextTokens * 7 / 10)  // 留 30% 给输出
else MAX_HISTORY_CHARS + MAX_NOTE_CHARS                                          // 80_000，现状
val maxNoteChars   = minOf(totalBudget / 2, MAX_NOTE_CHARS)  // 正文截断上限，喂给 AiPromptBuilder.build
val maxHistoryChars = totalBudget - maxNoteChars              // 历史裁剪预算
```

- **换算口径**：字符预算 = token 预算 × 1（按 1 token/字符的保守下限估计——中文实际约 0.6–0.75、英文约 0.25，均远低于该系数，方向安全）。
- **口径边界**：本次输入不计入 `totalBudget`；首条与最新条的「永不舍弃」豁免也可能超预算。两者偏差由 30% 余量与上述保守换算吸收，仍超限时由 §2.2 自愈兜底。
- **数学性质**：`80_000 / 0.7 ≈ 114_286`——`contextTokens ≥ 114_286` 时 `coerceAtMost` 不生效，`minOf` 下限取到 `MAX_NOTE_CHARS`，预算**精确等于现状 60_000/20_000**（若写成裸的 `totalBudget / 2`，默认情形会变成 40_000/40_000：正文上限被放大、历史被收紧，且打挂既有用例）。预设两家服务商（≥160K）全部落在该区间 → **零行为变化**，既有 `AiApiMessageBuilderTest` 断言（`assertEquals(60_000, MAX_HISTORY_CHARS)` 等）全部原样保留。
- 只有 `contextTokens < 114_286`（自定义端点）预算才开始收紧：32k → 总 22_400（正文 11_200 / 历史 11_200）；8k → 总 5_600。
- VM 两个调用点（`launchSendNow` / `retry`）传 `session.contextTokens`。
- 常量 `MAX_HISTORY_CHARS` / `MAX_NOTE_CHARS` 保留，语义升级为「总预算上限的默认分解」。

### 2.4 用量解析补缓存命中（`AiUsage` + `AiSseParser`）

```kotlin
data class AiUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val cacheHitTokens: Int = 0   // 新增：prompt 缓存命中量；缺失/两风格都没有时 0
)
```

- `parseUsage` 增补：取 `usage.prompt_cache_hit_tokens`（DeepSeek 风格）**或** `usage.prompt_tokens_details.cached_tokens`（OpenAI 风格），前者优先；均缺失按 0（与现有单字段容错一致）。
- 默认参数保证所有既有构造点（含测试位置参数 `AiUsage(10, 5, 15)`）零改动。

### 2.5 会话累计用量（`AiChatViewModel` + `AiChatScreen`）

```kotlin
// UiState 增：
val sessionUsage: AiUsage? = null   // 本会话累计；切会话 / 新对话清空（与 lastUsage 同两处）
```

- 累加时机：`handleEvent` 的 `Done` 分支，`event.usage != null` 时逐字段累加（`cacheHitTokens` 同累加）；`usage == null` 不动（口径：仅统计服务商真实回报的用量）。
- **内存态、不落库**——明确口径为「本次进入会话以来」，进程死亡即清零；不做 Room 迁移（成本远大于价值）。
- 文案格式抽纯函数（`ui/ai/` 顶层 `internal fun`，可直接单测，不引入 Compose UI 测试）：

```
formatUsageLine(usage)         → "上次用量：输入 12 / 输出 34 / 合计 46 tokens"
                                  "上次用量：输入 120（缓存命中 96）/ 输出 34 / 合计 154 tokens"   // cacheHitTokens > 0
formatSessionUsageLine(u)      → "本会话累计：输入 512 / 输出 96 / 合计 608 tokens"
                                  "本会话累计：输入 512（缓存命中 300）/ 输出 96 / 合计 608 tokens"  // 累计 cacheHitTokens > 0
```

- `UsageLine` Composable 改为 Column 两行：`lastUsage` 行（现有位置不变）+ `sessionUsage` 非空时第二行；调用点条件从 `lastUsage?.let` 改为 `lastUsage != null || sessionUsage != null`（避免「累计非空但上次为空」时整块不渲染）；两者均 null 时不占位（现状）。

### 2.6 设置页

- **不新增文案**：深度思考开关下已存在「回答更严谨但更慢，思维链按输出计费」（`SettingsScreen.kt:445`），本批不重复。

## 3. 非目标

| 项 | 理由 |
|---|---|
| `max_tokens` 输出硬上限 | 会截断长回答，体验损失 > 节省；服务商自带默认输出上限 |
| 历史自动摘要压缩 | 摘要本身花一次调用，短会话净亏；裁剪 + 自愈已够 |
| 本地精确 token 计数 | 零新增依赖硬约束下无 tokenizer 可用；字符保守估算足够 |
| 自定义端点上下文窗口可配置 | 设置面扩张；CUSTOM 保守值 + 400/422 自愈已覆盖，先按反馈评估 |
| 笔记正文只首轮发送 | 破坏模型持续上下文，且缓存下正文边际成本已约一折 |
| 费用金额估算显示 | 价格表易过时，需持续维护，价值不稳定 |
| usage 落库（会话累计持久化） | 需 Room v7 迁移；内存态口径已明示 |
| system prompt 用户可配置 | 设置面扩张；先按冻结文案上线，按反馈迭代 |
| backlog #84（裁剪保 user/assistant 配对） | 独立条目，不并入本批；`trimToCore` 是即时裁剪不受其影响 |

## 4. 测试策略

- **协议**（`AiSseParserTest`）：`prompt_cache_hit_tokens`、`prompt_tokens_details.cached_tokens`、两者皆缺 → 0、与既有 partial usage 用例共存。
- **客户端**（`AiApiClientTest`）：
  - 请求体含 `"stream_options"` 与 `include_usage: true`（自定义端点同样携带）；
  - 422 阶梯：第一重连剔 `stream_options` 保留 `thinking`（**更新既有两用例断言**——现断言 bodies[1] 不含 thinking，新语义方向相反；`retryOn422DropsEnableThinkingForSiliconFlow` 改造为 `[422, 422, 200]` 三段，否则测不到第二级剔除）；两字段依次剔完仍 422 → 映射（**更新 `422RetriedOnlyOnceThenMapsError`**：3 次连接）；**更新 `customEndpoint422DoesNotRetryWithoutThinking`** → custom 现在携带 `stream_options`，会剔它重试一次并成功（用例改名）；
  - 400 未知字段：`Unrecognized request argument supplied: stream_options` 类错误体 → 剔 `stream_options` 重连成功；锚点不命中（既有 `invalid param` 用例）不剔除、文案不变；
  - 超限自愈：首 400 `context_length_exceeded` → 第二 body 已裁剪 → 200 成功；重试仍 400 → 专用文案（不带 detail）；422 超限错误体同样走裁剪路径；中文错误体（如 `上下文长度超出`）命中识别；`trimToCore` 无进展（≤3 条或首条即最新）时不重试、直接映射；
  - 链路叠加：422 后重连遇 429 → 退避再试一次（总连接 ≤5）；429/503 退避既有用例保留。
- **组装**（`AiApiMessageBuilderTest`）：首条为 system；重包发生在 prepend 之前（历史首条裸输入仍被重包正文）；`contextTokens = 0` 与 `1_000_000` → 预算精确等于现状值 60_000/20_000（`minOf` 下限生效）；`32_000` → 22_400/11_200/11_200；下限边界（如 `57_143` → 正文 20_000 / 历史 20_000）；`trimToCore` 保 `[system, 首, 尾]`、≤3 条原样、无进展不重试、顺序不变。
- **VM**（`AiChatViewModelTest`）：`sessionUsage` 多轮累加、`usage == null` 不动、切会话/新对话清零；`session.contextTokens` 透传到 `build`（经 Fake 断言消息规模）。
- **文案纯函数**：`formatUsageLine` / `formatSessionUsageLine` 含缓存命中分支。
- 全量 `.\gradlew :app:testDebugUnitTest` + `assembleDebug` + `assembleRelease` 通过；版本 1.11.0、同步 README 版本行。

## 5. 风险与缓解

| 风险 | 缓解 |
|---|---|
| system prompt 改变回答风格/模型不遵循 | 文案短小中性；测试锁组装结构不锁风格；可按反馈修订常量 |
| 422/400 剔掉 `stream_options` 后该端点永久无 usage | 仅该端点、仅用量展示受损；下轮请求重新尝试携带（每次 `stream()` 全新判定） |
| 严格校验端点对 `stream_options` 返回 400（未识别参数） | `looksLikeUnsupportedField` 分支剔字段并重连；锚点未覆盖时退化为现状「参数错误」映射（仅该端点、仅此类请求） |
| 超限识别误报（非长度错误被当成超限） | 锚点均围绕上下文长度语义、仅对 400/422 生效；误报最多多一次重试与不同提示文案，不影响成功路径 |
| 400/422 自愈裁掉中间历史导致当轮上下文变薄 | 只在超限时触发；裁掉内容仍在本地库，下轮全量重建；保留首尾含笔记正文 |
| 8k 级端点：笔记主导的超限，`trimToCore` 救不回 | 落到专用文案引导「缩短笔记正文或新开对话」；CUSTOM 预算已按 32k 预裁剪，极小窗口属已知边缘 |
| `contextTokens` 常量随官方调整过时 | ≥114_286 时对预算无任何行为差异，过时无感；真过时到小窗口由 400/422 自愈运行时兜底 |
| 自定义端点间歇性超限反复触发裁剪 | 每次请求至多裁剪重试一次，不会循环 |
| 会话累计为内存态、进程亡即清零 | 文案口径「本会话累计」已明示；不承诺跨进程 |
| 总连接上限 5 次拉长失败反馈 | 每步都是立即判定（仅 429/503 有 1s 退避），剔除与退避允许有界叠加（如 422→429），上限仍 5 次；正常请求恒 1 次连接 |

## 修订记录

- 2026-10-15：初版。范围经用户确认（输出侧 system prompt / 缓存命中可见 / `include_usage` / 动态预算与 400 自愈 / 会话累计）；核对官方上下文现值后把超限主战场定为自定义端点，预设服务商预算零变化（114_286 阈值性质写入 §2.3）；422 剔除顺序定为 `stream_options` 先于 `thinking`（保功能字段）。
- 2026-10-15（评审修订）：修正 §2.3 预算公式为 `minOf(totalBudget / 2, MAX_NOTE_CHARS)`（原式在默认情形会放大正文上限到 40_000、收紧历史到 40_000，破坏「预设服务商零行为变化」并打挂既有用例）；剔除阶梯触发扩展为「400 + 未知字段锚点」并与 422 共用同一阶梯、超限识别改判 400/422、明确允许跨连接有界叠加（§2.2/§4/§5 同步）；§2.5 累计行补充缓存命中展示与调用点条件；补 `trimToCore` 无进展判定、专用文案不带 detail、输入不计预算口径、测试补强（中文超限体 / SiliconFlow 三段 422 / 400 未知字段 / 422→429 链路）与风险表三行；system prompt 末句限定为「涉及笔记内容时…」；非目标表补「自定义上下文窗口配置」。
