# AI 助手切换到 DeepSeek 官方 API（自带 Key）— 设计文档

- 日期：2026-09-15
- 状态：设计已确认（brainstorming 会话），待实现
- 关联：`docs/superpowers/specs/2026-09-11-ai-web-assistant-design.md`（被本设计替换）、`AGENTS.md`、`README.md`
- 前置结论（用户已确认）：
  - **用 API 模式替换网页驱动**：删除 WebView 驱动全套（`AiWebDriver` / `DeepSeekDriver` / `AiDriverRegistry` / `WebViewAiSession` / `AiWebEventParser` 及对应测试），不再保留"显示网页 / 登录态 / 网页会话"路径；
  - 用户在设置页自行填写 **DeepSeek API Key**（自己的账号与用量），仅 DeepSeek 官方，Base URL 固定；
  - API Key 用 **Android Keystore AES/GCM 加密**后存 SharedPreferences（零新增依赖）；
  - 模型二选一：`deepseek-flash`（默认）/ `deepseek-v4-pro`；新增"深度思考"开关（默认关）；
  - 依据官方文档 `https://api-docs.deepseek.com/zh-cn/` 处理协议与错误（2026-09 现行版本）。

## 1. 背景与问题

现有 AI 助手依赖隐藏 WebView 驱动 `chat.deepseek.com`（DOM 注入 + JS 桥）。该方案持续受页面改版、登录态、风控影响，维护成本高。官方 API 与 OpenAI 兼容、支持 SSE 流式、按量计费，用户愿意使用自己的 API Key。替换后可删除整个网页适配层，架构显著简化，回答质量与稳定性由官方接口保证。

## 2. 目标与非目标

**目标**

- 编辑页 AI 入口与聊天体验保持不变：按笔记留档会话/消息、流式回答、停止、五种落地（插入 / 替换 / 复制 / 存为新笔记 / 只看留档）、隐私确认。
- 直连 `https://api.deepseek.com/chat/completions`，`stream: true` SSE 增量渲染；多轮上下文每次全量回传本地历史。
- 设置页配置：API Key（加密存储、可清除）、模型二选一、深度思考开关、测试连接（免费 `GET /models`）。
- 零新增三方依赖：`HttpURLConnection` + kotlinx.serialization 手写 SSE（`INTERNET` 权限已存在）。
- 未配置 / 错误 Key：发送拦截 + 横幅 + "去设置"引导。

**非目标（本次不做）**

- 自定义 Base URL / 第三方兼容服务（OpenAI 协议其他厂商）；余额查询；图片 / 附件对话；回答重试按钮；对话导出。
- AI 会话纳入备份（沿用现有结论）。
- 旧网页会话的网页侧数据迁移（本地历史保留且可直接续聊，`remoteChatId` 仅作为历史列保留）。

## 3. 官方 API 协议要点（2026-09 文档）

- Endpoint：`POST https://api.deepseek.com/chat/completions`；鉴权 `Authorization: Bearer <key>`。
- 模型：`deepseek-flash`（DeepSeek-V4.1-Flash，默认，支持图像理解但本项目仍只发纯文本）、`deepseek-v4-pro`；旧名 `deepseek-chat` / `deepseek-reasoner` 已下线。
- 请求体：`messages[{role: system|user|assistant, content}]`、`stream: true`、`thinking: {type: enabled|disabled}`（默认 enabled，本项目默认显式 disabled；开启时只取 `delta.content`，忽略 `reasoning_content`）。
- 流式：SSE `data: {chat.completion.chunk}`，结束帧 `data: [DONE]`；末尾块可能 `content` 为空且 `finish_reason` 非空；`finish_reason ∈ stop|length|content_filter|tool_calls|insufficient_system_resource|aborted`。
- 错误码：400/422 参数错误、401 认证失败、402 余额不足、429 速率上限、500 服务器故障、503 服务器繁忙。
- 多轮：无服务端会话，客户端维护 `messages`；全量回传可命中上下文硬盘缓存（`prompt_cache_hit_tokens`，计费更低）。
- 模型列表：`GET /models`（测试连接，不产生会话费用）。

## 4. 架构

```
ui/ai/AiChatScreen ──> ui/ai/AiChatViewModel ──> data/ai/AiSession（接口）
                                                      ▲
                                                      │
                              data/ai/DeepSeekApiSession ──> data/ai/DeepSeekApiClient
                                                      │              │
                                                      │              └─ HttpStreamTransport（测试注入）
                              data/ai/AiApiMessageBuilder（纯函数，复用 AiPromptBuilder）
ui/settings/SettingsScreen ──> data/settings/AiSettingsStore ──> ApiKeyCipher（Keystore AES/GCM）
```

### 4.1 `AiSession` 与事件协议（`data/ai/AiSession.kt`）

```kotlin
sealed interface AiEvent {
    data class Chunk(val text: String) : AiEvent
    data class Done(val text: String) : AiEvent
    data class Failed(val reason: String, val settingsHint: Boolean = false) : AiEvent
}

interface AiSession {
    val serviceId: String
    val displayName: String
    val events: SharedFlow<AiEvent>
    fun send(messages: List<AiChatMessage>)
    fun stop()
    fun release()
}
```

- `send` 接收完整 `messages`（由 ViewModel 组装），会话只负责流式执行与错误映射；`settingsHint = true` 表示"去设置"类问题（缺 Key / 401）。
- `serviceId = "deepseek-api"`（隐私确认、会话落库使用）；旧网页会话行 `serviceId = "deepseek"` 保留展示。

### 4.2 `AiChatMessage` / `AiApiMessageBuilder`

- `AiChatMessage(role, content)`，role ∈ user / assistant。
- `AiApiMessageBuilder.build(noteTitle, noteContent, history, input)`：
  - `includeNoteContext = history 中没有任何 user 消息`（会话首条）；
  - 历史消息过滤空内容（`failed` 的空回复不进上下文），按时间顺序映射为 user / assistant；
  - 首条用户消息复用 `AiPromptBuilder.build(...)` 携带笔记标题 + 正文（`NoteContentParser.plainText`），后续只发纯输入。

### 4.3 `DeepSeekApiClient`（`data/ai/`）

- `ChatStreamer` 接口：`fun stream(apiKey, model, deepThinking, messages): Flow<ApiStreamEvent>` + `fun cancel()`；`DeepSeekApiClient` 为默认实现，测试注入假实现。
- `ApiStreamEvent = Chunk(text) | Finished(fullText, finishReason) | Error(message, settingsHint)`。
- 传输层 `HttpStreamTransport.execute(method, url, headers, body): HttpStreamConnection`（`statusCode` / `errorBody` / `stream` / `close`）；生产实现 `UrlConnectionTransport`（连接 15s / 读 60s）。
- 请求 JSON 用 `@Serializable` DTO（kotlinx.serialization）生成；`thinking` 显式 enabled/disabled，其余参数保持官方默认。
- SSE 解析独立纯函数 `DeepSeekSseParser.parse(line): Frame?`（`text` / `finishReason` / `done` / `error`）；忽略空行、注释行、未知行、`reasoning_content`。
- 取消：客户端登记进行中的连接，`cancel()` 从外部线程 close 断开阻塞读（协程取消 / `invokeOnCompletion` 无法打断 `HttpURLConnection.read`）；会话 `stop()` 先取消任务再 `streamer.cancel()`，避免被 close 后的 IOException 误报为网络错误。
- 错误映射：401 Key 无效（settingsHint）→ 402 余额不足 → 429 频率上限 → 500/503 服务器繁忙 → 其他 HTTP 通用文案；400/422 尽量带官方 `error.message`。
- 结束判定：收到 `[DONE]` 或读取结束；`finish_reason = length` 视为成功（内容可能截断）；`content_filter` / `insufficient_system_resource` / `aborted` 在无内容时按失败、有内容时按成功落库。

### 4.4 `DeepSeekApiSession`

- 构造注入：`ChatStreamer`、Key 提供者、模型提供者、深度思考开关、`CoroutineScope`（容器用 `applicationScope`，测试注入 `TestScope`）。
- `send` 前校验 Key（空 → `Failed(KEY_MISSING_REASON, settingsHint = true)`，ViewModel 侧已先行拦截）；收集事件流转发；`stop()` 取消任务并断开连接；`release()` 等同 `stop()`。

### 4.5 Key 存储（`data/settings/`）

- `ApiKeyCipher` 接口 + `KeystoreApiKeyCipher`：AndroidKeyStore 生成 AES/GCM 密钥（别名 `mynote_ai_api_key`），存储格式 `v1:<base64(iv)>:<base64(ciphertext)>`；解密失败 / 密钥失效（换机恢复）返回 null 并清空。
- `AiSettingsStore`：`apiKey()/setApiKey()/hasApiKey()`（内存缓存解密结果）、`model()`、`deepThinking()`、隐私确认（key 为 `privacy_accepted_<serviceId>`）。删除 `selectedServiceId`。
- 测试注入假 cipher（Robolectric 不支持 AndroidKeyStore 真实加解密）。

### 4.6 数据与迁移

- **无 DB 迁移**：`ai_sessions.remoteChatId` 保留为历史列（新行写 null），`AiSessionDao.updateRemoteChatId` 与仓库同方法删除；`serviceId` 新值 `"deepseek-api"`。
- 旧网页会话：历史可读、可直接继续（历史全量发给 API），`remoteChatId` 忽略。

## 5. UI 改动

- `AiChatScreen`：删除 WebView 层 / `AndroidView` / "显示网页" / `BackHandler` / 登录横幅；新增 `onOpenSettings`，横幅在 `apiKeyMissing` 时显示"去设置"；隐私弹窗文案改为 DeepSeek API 直连（Key 为用户自有、费用自理）。
- `SettingsScreen` 新增"AI 助手"分区：
  - 模型 `TextTabRow`：`deepseek-flash` / `deepseek-v4-pro`；
  - "深度思考"开关（副标题说明更慢 / 更贵）；
  - API Key 输入（密码掩码 + 可见切换）、保存 / 清除、"测试连接"按钮；提示仅本机加密保存；
  - 测试连接结果与 Key 校验走 snackbar。
- `AiChatScreen` 其余（会话抽屉、气泡、操作条、草稿、滚动）不动。

## 6. 数据流

1. 设置页保存 Key / 模型 / 深度思考 → `AiSettingsStore`（Key 密文入库）。
2. 编辑页点 AI → `AiChatScreen`；`AiChatViewModel` 持有 `AiSession`（容器工厂创建）。
3. 发送：校验 Key → 读会话历史 → `AiApiMessageBuilder` 组装 → 落 user 消息 → `session.send(messages)` → SSE `Chunk` 刷新 `streamingText` → `Done` 落 assistant（`done`）；`Failed` 按半截 `interrupted` / 空 `failed` 落库 + 横幅。
4. 停止 / 超时 / 离开页面：取消任务，半截按 `interrupted` 落库（沿用现有看门狗 120s 与 `onCleared` 收尾）。

## 7. 错误处理

| 场景 | 行为 |
|---|---|
| 未配置 Key | 发送拦截 + 横幅"未配置 DeepSeek API Key" + "去设置"，不建会话 |
| 401 认证失败 | 流失败 → `failed` 消息 + 横幅 + "去设置" |
| 402 余额不足 | 横幅"账户余额不足，请前往 DeepSeek 平台充值" |
| 429 速率上限 | 横幅"请求过于频繁，请稍后重试" |
| 500/503 | 横幅"DeepSeek 服务器繁忙，请稍后重试" |
| 网络异常 / 超时 | 横幅"网络错误，请检查网络后重试"；看门狗兜底 |
| 流中途 error 帧 | 已收内容按 `interrupted`、无内容按 `failed` |
| `finish_reason = length` | 内容照常落 `done`（可能截断） |
| `content_filter` / 资源中断 | 有内容按 `interrupted`、无内容按 `failed` |
| 生成中删除会话 / 切换 | 沿用现有规则（生成中禁止删除当前会话） |

## 8. 测试策略

- 新增纯 JVM：`AiApiMessageBuilderTest`（首条带正文 / 后续只发输入 / 空内容过滤 / 角色顺序）、`DeepSeekSseParserTest`（delta / [DONE] / reasoning 忽略 / error 帧 / 坏行）、`DeepSeekApiClientTest`（假 transport：请求体、状态码映射、流解析、取消关闭连接）、`DeepSeekApiSessionTest`（假 streamer：事件序列 / 缺 Key / stop 取消）、`DeepSeekModelsTest`（可选）。
- Robolectric：`AiSettingsStoreTest`（假 cipher：Key 往返 / 清除 / 损坏返回 null / 模型 / 深度思考 / 隐私）、`AiChatViewModelTest` 重写（Fake `AiSession`：首条带正文、续聊只发输入、Done/Failed 落库、缺 Key 拦截、页内事件、停止/切换/看门狗等沿用）、DAO / 仓库测试去掉 `remoteChatId` 用例。
- 删除：`DeepSeekDriverTest`、`WebViewAiSessionTest`、`AiWebEventParserTest`、`AiDriverRegistryTest`。
- 全量 `.\gradlew :app:testDebugUnitTest` 绿；`assembleDebug` / `assembleRelease` 通过。

## 9. 人工验证清单（真机）

1. 设置页：保存 Key、掩码显示、清除后回到"未配置"；测试连接成功 / 错误 Key 失败文案。
2. 未配置 Key 进 AI 页：横幅 + "去设置"；配置后重进可发送。
3. 首条发送：请求带笔记标题与正文；流式出现；完成后落库；重启 app 可见。
4. 续聊：只发纯输入 + 历史；回答上下文连续。
5. 停止 / 超时 / 断网：半截 `interrupted`、无内容 `failed`、横幅文案正确。
6. 错误 Key / 余额不足：401 / 402 文案与"去设置"引导。
7. 深度思考开：回答前有等待、最终只落 `content`；关：响应更快。
8. `deepseek-v4-pro` 切换后可用。
9. 旋转 / 返回 / 杀进程：流式半截存为 `interrupted`，已落库消息保留。
10. 旧版本网页会话在列表中可见且可继续聊。
11. 深浅色主题下新增设置项、横幅、弹窗可读。
12. 覆盖安装（旧版本 → 本版本）：笔记 / 历史 / 图片 / 旧 AI 会话完好。

## 10. 风险

| 风险 | 缓解 |
|---|---|
| 手写 HTTP/SSE 的健壮性 | 解析与错误映射拆纯函数 + 假 transport 全覆盖；真机清单覆盖断网 / 取消 |
| Keystore 密钥失效（换机恢复、系统异常） | 解密失败返回 null 并清空，UI 引导重新填写 |
| 官方模型改名 / 下线 | 模型常量集中 `DeepSeekModels`；`/models` 测试连接可快速发现 |
| 移除网页模式属产品行为变化 | README / AGENTS.md / backlog 同步更新；升级说明提示需自备 Key |
| 长历史导致请求变大 | 全量回传可控（缓存命中计费低）；后续可加"历史条数上限"（非本次） |
| 深度思考费用更高 | 默认关闭 + 设置项副标题明示 |

## 修订记录

- 2026-09-15：初版。确认以 API 直连替换网页驱动；Keystore 加密 Key；模型 `deepseek-flash` / `deepseek-v4-pro`；依据官方 2026-09 文档确定 SSE 与错误码处理。
