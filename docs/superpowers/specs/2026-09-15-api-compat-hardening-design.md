# AI 兼容性加固批（65–66）— 设计文档

- 日期：2026-09-15
- 状态：设计已确认（用户"好的"），实现中
- 关联：backlog 65–66、`docs/superpowers/specs/2026-09-15-ai-assistant-optimization-design.md`（基础批）
- 目标：降低官方 API 演进（模型改名、参数集调整）带来的发版依赖；零新增依赖、无 DB 迁移。

## 1. 动态模型列表（65）

- `AiSettingsStore`：
  - 新增 `models(): List<String>`：读取持久化列表（prefs key `api_models`，`\n` 连接）；为空时回退 `DeepSeekModels.all`（内置常量，保底）。
  - 新增 `setModels(models: List<String>)`：去空、去重后持久化。
  - `model()` 校验改为「在 `models()` 内」；不在则回退 `DeepSeekModels.DEFAULT`。`setModel(id)` 仅接受在 `models()` 内的 id。
- `SettingsViewModel`：
  - 构造新增注入缝（便于测试，不改客户端类型）：
    `probeFn: suspend (String) -> ProbeResult = { apiClient.probe(it) }`、
    `balanceFn: suspend (String) -> BalanceState = { apiClient.fetchBalance(it) }`。
  - 暴露 `availableModels: StateFlow<List<String>>`（初始 = `aiStore.models()`）。
  - `testConnection(key)` 成功（`ProbeResult.Ok`）时：`aiStore.setModels(models)` → 刷新 `availableModels` → 若当前 `aiModel` 不在新列表内，自动选中第一个（纯函数 `modelAfterRefresh(current, models)`，可单测）。
- `SettingsScreen`：模型 `TextTabRow` 的 tabs 改用 `availableModels`；副标题补「测试连接成功后会更新为官方最新模型列表」。
- `DeepSeekModels` 语义变为「内置默认 + 官方列表缺失时的保底」，成员不变。

## 2. 422 参数降级自愈（66）

- `DeepSeekApiClient`：
  - `ChatRequest.thinking` 允许为 null；Json 配置加 `explicitNulls = false`（null 字段不序列化）。
  - `stream()` 增加兼容回退：首次请求携带 `thinking`；若 HTTP 状态为 **422**，关闭连接、不带 `thinking` 立即重试一次；仍失败则按原错误映射返回。
  - 既有 429/503 预连接退避重试保持不变；两种重试互不叠加（422 回退优先判断）。
- 文案不变；行为在 `DeepSeekApiClientTest` 覆盖（断言第二次请求体不含 `thinking`）。

## 3. 测试策略

- 存储：`AiSettingsStoreTest` 增模型列表读写、回退内置、`model()` 在新列表内/外行为。
- 设置 VM：`SettingsViewModelTest` 用假 `probeFn` 验证「成功后写列表 + 自动切换失效模型」；`modelAfterRefresh` 纯函数用例；保留格式化用例。
- 客户端：422→去 `thinking` 重试成功；422 重试后仍失败映射错误；既有 429/503 用例保持通过。
- 全量 `:app:testDebugUnitTest` + `assembleDebug` + `assembleRelease`；版本 1.7.1。

## 4. 风险

| 风险 | 缓解 |
|---|---|
| 持久化列表来自旧版/损坏数据 | 读取时空项过滤；空则回退内置；模型 id 只作为请求字符串 |
| 去掉 `thinking` 后默认开启思考（更慢/更贵） | 仅在 422 兼容回退路径发生，作为可用的降级而非常态 |
| 官方模型列表接口失败 | 测试连接失败不改已有列表，保持现状 |

## 修订记录

- 2026-09-15：初版。两条加固（动态模型列表 / 422 降级）拆分给两个并行子代理，文件所有权互斥。
- 2026-09-15（实现）：并行轨道 A（客户端 422 回退：`thinking` 可空 + `explicitNulls = false` + 去字段重试一次）与轨道 B（`models()/setModels()` 持久化、`probeFn/balanceFn` 注入缝、`availableModels`、`modelAfterRefresh`）完成；集成修复 1 处测试期望（刷新后失效模型切到新列表首项 flash，而非列表中的新模型）。全量单测 456 全绿，版本 1.7.1（按用户要求按 patch 发布，原 1.8.0 未发布）。
