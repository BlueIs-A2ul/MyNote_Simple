# AI 助手优化批（51–64）— 设计文档

- 日期：2026-09-15
- 状态：设计已确认（用户"都挺好，依次完成"），实现中
- 关联：`docs/backlog.md` 条目 51–64、`docs/superpowers/specs/2026-09-15-deepseek-api-assistant-design.md`（API 替换基线）
- 范围：在 v1.6.0 API 直连实现之上做一轮优化，不改数据库结构（无迁移）、不新增三方依赖。

## 1. 目标

1. 修复会误伤长回答的看门狗（含思考心跳）与 `sendRequested` 卡死。
2. 补齐失败重试、删除确认、滚动跟随、草稿持久化等体验缺口。
3. 控制上下文预算、展示 token 用量与余额、探测官方模型列表。
4. 隔离会话取消、加固网络重试与编码。

## 2. 冻结契约（并行实现依据）

### 2.1 事件与协议（`data/ai/`）

```kotlin
data class AiUsage(val promptTokens: Int, val completionTokens: Int, val totalTokens: Int)

sealed interface AiEvent {
    data class Chunk(val text: String) : AiEvent
    data class Reasoning(val text: String) : AiEvent        // 新增：思考增量（心跳）
    data class Done(val text: String, val usage: AiUsage? = null) : AiEvent
    data class Failed(val reason: String, val settingsHint: Boolean = false) : AiEvent
}

sealed interface ApiStreamEvent {
    data class Chunk(val text: String) : ApiStreamEvent
    data class Reasoning(val text: String) : ApiStreamEvent
    data class Finished(val text: String, val finishReason: String?, val usage: AiUsage?) : ApiStreamEvent
    data class Error(val message: String, val settingsHint: Boolean = false) : ApiStreamEvent
}

// DeepSeekSseParser.Frame 增字段（默认值，兼容旧用例）：
data class Frame(
    val text: String? = null,
    val reasoning: String? = null,      // delta.reasoning_content
    val finishReason: String? = null,
    val done: Boolean = false,
    val error: String? = null,
    val usage: AiUsage? = null          // 顶层 usage（末块；choices 可能为空也要解析）
)

// DeepSeekApiClient 新增/替换：
sealed interface ProbeResult {
    data class Ok(val models: List<String>) : ProbeResult
    data class Failed(val message: String) : ProbeResult
}
data class BalanceLine(val currency: String, val total: String, val granted: String, val toppedUp: String)
sealed interface BalanceState {
    data class Ok(val isAvailable: Boolean, val lines: List<BalanceLine>) : BalanceState
    data class Failed(val message: String) : BalanceState
}
suspend fun probe(apiKey: String): ProbeResult      // GET /models（替换 verifyApiKey）
fun stream(...): Flow<ApiStreamEvent>               // 行为：Accept-Encoding identity；429/503 预连接重试一次（delay 1s）
suspend fun fetchBalance(apiKey: String): BalanceState   // GET /user/balance
```

- `reasoning_content` 只做展示与心跳，不计入回答正文；`usage` 取流中最后一次非空。
- 429/503 重试仅针对连接建立后的 HTTP 状态，流中途失败不重试。

### 2.2 草稿存储（`data/settings/AiDraftStore.kt`）

```kotlin
interface AiDraftStore {
    fun get(key: String): String
    fun set(key: String, value: String)
}
class PrefsAiDraftStore(context: Context) : AiDraftStore   // prefs: "ai_drafts"
fun aiDraftKey(noteId: Long, sessionId: Long?): String     // "<noteId>:<sessionId ?: 0>"
```

### 2.3 ViewModel 契约（`ui/ai/AiChatViewModel.kt`）

```kotlin
// UiState 增：
val reasoningText: String = ""     // 当前流式思考（终态清空）
val lastUsage: AiUsage? = null     // 最近一次成功回答用量（切换/新对话清空）
val draft: String = ""             // 当前会话草稿

// 构造增参：draftStore: AiDraftStore
// 新增方法：
fun updateDraft(text: String)      // 更新 state + 持久化
fun retry(): Boolean               // 重发最后一条 user 消息（不重复落库）
```

行为规则：

- 看门狗改为空闲超时：`Chunk` / `Reasoning` 每来一次重置 120s；`Done` / `Failed` / 停止取消。语义 = 距上一个流事件超时才判失败。
- `Failed` 且本次无任何半截内容 → 不落库空 `failed` 消息，仅横幅（保留 user 消息）。
- `retry()`：取 `state.messages` 中最后一条 `ROLE_USER`；用其之前的消息构建历史（不含该 user 与其后的回答），`session.send(history + input)`；不重复插入 user；期间 `sending` 置位、看门狗照常。
- 发送成功进入流式后清空草稿（`draftStore.set(key, "")` 且 `draft = ""`）。
- `selectSession` / `newChat`：加载对应草稿、清空 `reasoningText` / `lastUsage`。
- `send()` 两处协程入口包 `try/catch`（CancellationException 除外），失败时复位 `sendRequested` 并横幅「发送失败，请重试」。

### 2.4 UI 契约（`ui/ai/AiChatScreen.kt`）

- 新参数 `draftStore: AiDraftStore`（工厂传递）；输入框值改为 `state.draft`，`onValueChange = vm::updateDraft`，发送成功后由 VM 清空（UI 不再本地清空）。
- 流式自动跟随：仅当列表在底部（最后可见项 ≥ 总数-1）时随 `messages.size` / `streamingText` / `reasoningText` 滚动；用户上滑即暂停。
- 会话删除二次确认（`AlertDialog`）。
- `MessageBubble`：assistant 非 `done` 状态加「重试」；最后一条为 user 且未发送中时也显示「重试」（无回答场景）。
- `StreamingBubble`：`reasoningText` 非空时显示折叠区（默认 3 行 + 展开/收起）。
- 输入区上方显示 `lastUsage`：「上次用量：输入 P / 输出 C / 合计 T tokens」。

### 2.5 上下文预算（`data/ai/AiApiMessageBuilder.kt` + `AiPromptBuilder.kt`）

- `AiPromptBuilder.build(..., maxNoteChars: Int = Int.MAX_VALUE)`：正文超限截断并追加「（正文过长，已截断）」。
- `AiApiMessageBuilder`：`MAX_NOTE_CHARS = 20_000`；历史预算 `MAX_HISTORY_CHARS = 60_000`（按 content 长度累加）——永远保留首条（含正文）与最新一条，优先丢弃最旧的中间消息，保持原顺序。

### 2.6 设置页（`ui/settings/`）

- 「测试连接」改用 `probe`：成功显示「连接正常，可用模型：a、b」；若 `DeepSeekModels.all` 不全在官方列表内，追加「（内置模型与官方不一致，请留意）」。
- 新增「查询余额」按钮：`fetchBalance`，展示币种/总额（含赠金与充值）或不足提示。

### 2.7 容器接线（`di/AppContainer.kt` + `ui/navigation/AppNavHost.kt`）

- `aiSessionFactory` 每次创建独立 `DeepSeekApiClient()`（取消隔离，条目 62）；设置页继续用共享 `deepSeekApiClient`。
- 新增 `aiDraftStore`；`AiChatScreen` 增加 `draftStore` 参数并由 NavHost 传入。

## 3. 非目标

- 完整 Markdown 渲染（只做围栏代码块等宽显示）、图片/附件对话、自定义 Base URL、历史消息编辑、DB 结构变更。

## 4. 测试策略

- 协议：parser 增 `reasoning_content` / `usage`（含空 choices 带 usage）用例；client 增 `probe` / `fetchBalance` / 429 重试一次 / 事件序列含 Reasoning 与 usage；session 转发 Reasoning 与 usage。
- VM：空闲看门狗（持续 chunk 不超时、静止超时）、`retry` 不重复落库、空失败不落库、草稿读写与清空、`sendRequested` 异常复位。
- 纯函数：`AiPromptBuilder` 截断、`AiApiMessageBuilder` 历史裁剪。
- 存储：`AiDraftStore` 键与读写（Robolectric）。
- 设置：`probe` 结果文案（模型一致/不一致/失败）、余额文案。
- 全量 `.\gradlew :app:testDebugUnitTest` + `assembleDebug` + `assembleRelease` 通过。

## 5. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 并行改动接口漂移 | 契约先行冻结（本文 §2），文件所有权互斥 |
| 空闲看门狗让真正卡死的连接拖 120s | 读超时 60s 兜底，双保险 |
| 历史裁剪丢失关键上下文 | 保留首条（含正文）与最新消息；测试覆盖顺序与保留规则 |
| 草稿写 SharedPreferences 频繁 | 内存态 + `apply()`，量级小 |
| 思考内容过长占内存 | 展示默认 3 行折叠；`reasoningText` 仅当前流式生命周期 |

## 修订记录

- 2026-09-15：初版。用户确认 backlog 51–64 全部实施；冻结并行契约（事件/VM/UI/容器）。
