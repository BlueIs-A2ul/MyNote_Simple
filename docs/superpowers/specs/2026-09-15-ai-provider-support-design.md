# AI 服务商可切换（硅基流动 / 自定义兼容）— 设计文档

- 日期：2026-09-15
- 状态：实现中
- 目标：在零新增依赖、零敏感权限的前提下，把 AI 助手从「DeepSeek 专用」升级为「服务商可切换」：内置 DeepSeek 与硅基流动两套预设，并支持用户填写任意 OpenAI 兼容接口地址（自定义服务商）。新增第四家服务商 = 往一个枚举加一条配置。
- 关联：backlog 68（字体，已暂缓）之后的条目 69；README「AI 助手」段；AGENTS.md。

## 1. 协议兼容性结论（调研）

硅基流动 `POST https://api.siliconflow.cn/v1/chat/completions` 与现有实现同源：
- 请求头 `Authorization: Bearer`、`Content-Type: application/json`；
- 流式 SSE `data: {...}`、`data: [DONE]`，`choices[].delta.content` / `delta.reasoning_content`；
- `usage.prompt_tokens / completion_tokens / total_tokens`；
- `GET /v1/models` 返回 `data[].id`（额外支持 `?sub_type=chat` 过滤对话模型）。

需要适配的差异：
| 维度 | DeepSeek | 硅基流动 | 处理 |
|---|---|---|---|
| Base URL | `https://api.deepseek.com` | `https://api.siliconflow.cn/v1` | 每服务商配置 |
| 思考参数 | `thinking: {type: enabled/disabled}` | `enable_thinking: true/false` | 样式枚举（`ThinkingStyle`） |
| 余额接口 | `GET /user/balance`（`is_available` + `balance_infos[]`） | `GET /user/info`（`data.totalBalance/chargeBalance/balance`，兼容顶层同名字段） | 样式枚举（`BalanceStyle`） |
| 模型 id | `deepseek-flash` / `deepseek-v4-pro` | `deepseek-ai/DeepSeek-V4-Flash` 等（org/ 前缀，数量多） | 服务商默认列表 + 可用 `?sub_type=chat` 过滤 |
| 错误体 | `{"error":{"message":…}}` | `{"code","message","data"}` 或纯字符串 | `detail()` 依次尝试三种形态 |
| 错误码文案 | 402 余额不足专属 | 400/401/403/404/429/503/504 | 文案通用化，去掉 DeepSeek 字样 |

## 2. 扩展契约（冻结）

### 2.1 `data/ai/AiProvider.kt`（新增）

```kotlin
enum class ThinkingStyle { DEEPSEEK_OBJECT, BOOLEAN_ENABLE, NONE }
enum class BalanceStyle { DEEPSEEK, SILICON_FLOW, NONE }

enum class AiProvider(
    val serviceId: String,        // 落库 ai_sessions.serviceId + 隐私确认作用域（沿用旧值保证兼容）
    val displayName: String,      // UI 展示名（隐私弹窗/设置页/错误文案）
    val defaultBaseUrl: String,   // CUSTOM 为空，由用户填写
    val defaultModels: List<String>,
    val thinkingStyle: ThinkingStyle,
    val balanceStyle: BalanceStyle,
    val modelsQuery: String?,     // 拼在 /models 后的查询串（如 "?sub_type=chat"）
    val freeModelInput: Boolean,  // true = 模型 id 由用户自由填写（自定义服务商）
    val apiKeyPlaceholder: String,
    val keyHint: String,          // 「在哪创建 Key」的用户提示
    val modelHint: String         // 模型说明文案
) { DEEPSEEK, SILICON_FLOW, CUSTOM }

data class AiEndpoint(serviceId, displayName, baseUrl, thinkingStyle, balanceStyle, modelsQuery)
fun AiProvider.endpoint(customBaseUrl: String?): AiEndpoint  // CUSTOM 时取用户地址；统一 trimEnd('/')
```

**新增第四家服务商**：加枚举项；若思考参数/余额 schema 与现有样式都不同，再加一个 `ThinkingStyle`/`BalanceStyle` 分支并在 `AiApiClient` 对应 `when` 中实现。其余（存储命名空间、会话、UI、错误映射）自动生效。

### 2.2 `AiApiClient`（原 `DeepSeekApiClient`，改名）

- 构造 `(endpoint: AiEndpoint, transport = UrlConnectionTransport(), retryDelayMs = 1_000)`；URL = `endpoint.baseUrl + 路径`。
- 请求体用 `buildJsonObject` 组装（思考字段样式化），`includeThinking=false` 时省略该键 → 422 降级重试机制保留（无思考参数的服务商不触发）。
- `probe(apiKey)`：`GET baseUrl/models + endpoint.modelsQuery`。
- `fetchBalance(apiKey)`：按 `BalanceStyle` 选路径与解析（`NONE` → 不支持文案；`SILICON_FLOW` 兼容 `data.*` 与顶层两种返回）。
- `detail()`：`error.message` → `message` → 纯字符串（截断保护）。
- 错误文案通用化（去掉 DeepSeek 字样）；402/403 合并为账户/权限提示。
- 新增 `interface AiProbe { probe / fetchBalance }`（`AiApiClient` 实现），设置页 VM 用工厂 `(AiEndpoint) -> AiProbe` 注入，测试可替换。

### 2.3 会话（原 `DeepSeekApiSession`，改名 `AiApiSession`）

```kotlin
class AiApiSession(streamer, endpoint: AiEndpoint, credentials, model, deepThinking, scope)
override val serviceId = endpoint.serviceId
override val displayName = endpoint.displayName
```
`AiSession.KEY_MISSING_REASON` 改为通用文案（不再含服务商名）。

### 2.4 设置存储（`AiSettingsStore`）

- 新增 `provider()` / `setProvider()`（默认 DeepSeek）、`customBaseUrl()` / `setCustomBaseUrl()`。
- Key / 模型 / 模型列表 / 深度思考按服务商命名空间：`<key>_<serviceId>`；**DeepSeek 继续读写旧 key 名**（`api_key_encrypted`、`api_model`、`api_models`、`api_deep_thinking`），老用户无感升级、无需迁移。
- 方法签名保持旧调用形状（provider 默认取当前服务商）：`apiKey(p = provider())`、`setApiKey(key, p)`、`models(p)`、`setModels(list, p)`、`model(p)`、`setModel(id, p)`、`deepThinking(p)`、`setDeepThinking(v, p)`。
- 模型语义不变：`model()` 仅在 `models()` 内有效；`freeModelInput` 服务商（CUSTOM）允许任意 id。
- 隐私确认沿用 `privacy_accepted_<serviceId>`（DeepSeek 旧确认自动继续有效）。

### 2.5 UI

- 设置页 AI 分区顶部新增「服务商」`TextTabRow`（DeepSeek / 硅基流动 / 自定义）。
- `自定义` 时显示接口地址输入框（`https://` 校验）与模型 id 自由输入框；预设服务商显示模型 tab（probe 结果）。
- 「深度思考」开关仅在 `thinkingStyle != NONE` 时显示；「查询余额」仅在 `balanceStyle != NONE` 时显示。
- Key 输入 placeholder 与「创建 Key」提示、模型说明文案按服务商切换。
- 聊天页隐私弹窗文案使用 `session.displayName`（新增 `providerName` 状态），不再写死 DeepSeek。

### 2.6 接线（`AppContainer`）

- `aiEndpoint(): AiEndpoint` = 当前服务商 + 自定义地址解析。
- `aiApiClientFactory: (AiEndpoint) -> AiApiClient`（设置页测试连接/余额）。
- `aiSessionFactory`：每次进入聊天页新建 `AiApiClient(aiEndpoint())` + `AiApiSession`，Key/模型/深度思考按当前服务商读取。

## 3. 非目标

- 不做「同一篇笔记的历史会话绑定服务商」：重开旧会话沿用当前选中服务商（消息历史与服务商无关）。
- 不发 `temperature/max_tokens` 等可选参数；不做每服务商的模型参数面板。
- 不自建联网代理/中转；用户 Key 直连所选服务商。

## 4. 测试策略

- `AiProviderTest`：endpoint 解析（预设/自定义/空地址/斜杠归一）。
- `AiSettingsStoreTest`：旧 key 兼容、按服务商隔离（Key/模型/思考）、自定义地址持久化、`freeModelInput` 语义。
- `AiApiClientTest`（原 DeepSeekApiClientTest + 新用例）：DeepSeek 请求体不变；硅基请求体 `enable_thinking` 且无 `thinking`；自定义无思考键；`?sub_type=chat`；错误详情三种形态；硅基余额解析（data/顶层）；不支持余额的提示。
- `AiApiSessionTest`：identity 参数化、通用 Key 缺失文案。
- `SettingsViewModelTest`：切换服务商刷新状态；测试连接写回对应服务商模型；自定义地址保存校验；能力开关文案。
- `AiChatViewModelTest`：banner 通用文案；`providerName` 透传。

## 5. 风险

| 风险 | 缓解 |
|---|---|
| 硅基 `/user/info` 返回结构版本差异（data 包裹 vs 顶层） | 解析同时兼容两种；失败也只影响余额按钮提示 |
| 自定义地址指向恶意服务导致 Key 外泄 | 用户主动填写；UI 明确「Key 将发送到该地址」提示 |
| 模型列表过大（硅基对话模型数十个） | 请求带 `?sub_type=chat` 过滤；tab 可横向滚动 |
| 旧数据（Key/模型/隐私确认）丢失 | DeepSeek 沿用旧 prefs key；serviceId 不变 |
| 与并行会话（日历/Markdown）文件冲突 | `AppContainer` 等共享文件在其提交后再动/提交 |

## 修订记录

- 2026-09-15：初版（基于硅基流动官方文档调研）。
- 2026-09-15（实现）：`AiProvider`/`AiEndpoint`、`AiApiClient`（`AiProbe`）、`AiApiSession`、`AiSseParser` 改名与样式化、`AiSettingsStore` 命名空间隔离、设置页服务商 UI、AppContainer/AppNavHost/AiChat 接线、欢迎笔记文案全部落地；新增/更新测试后全量 547 → 582 全绿，版本 1.10.0（1.9.0 已被并行会话的 Markdown 渲染批占用）。
