# AI 服务商可切换（DeepSeek / 硅基流动 / 自定义）实现计划

**Goal:** 按 `docs/superpowers/specs/2026-09-15-ai-provider-support-design.md` 实现多服务商支持，版本 1.9.0。

**约定：** 中文注释/提交；零新增依赖；无 DB 迁移；DeepSeek 旧 prefs key 与 serviceId 保持兼容；`AppContainer` 提交前确认并行会话已落地（避免夹带对方改动）。

## Task 1：provider 契约与存储

- 新增 `data/ai/AiProvider.kt`：`ThinkingStyle` / `BalanceStyle` / `AiProvider` 枚举 / `AiEndpoint` / `endpoint(customBaseUrl)`。
- `AiSettingsStore`：provider 维度（默认 DeepSeek）+ 自定义地址 + 按服务商命名空间（DeepSeek 沿用旧 key）。

## Task 2：客户端与会话通用化

- `DeepSeekSseParser` → `AiSseParser`（纯改名）。
- `DeepSeekApiClient` → `AiApiClient`：endpoint 化请求体/思考样式/probe 查询串/余额样式/错误详情与文案；新增 `AiProbe` 接口。
- `DeepSeekApiSession` → `AiApiSession`：identity 参数化。
- `AiSession.KEY_MISSING_REASON` 通用化。

## Task 3：设置页与聊天页

- `SettingsViewModel`：服务商状态、自定义地址/模型、能力开关、按服务商刷新；`clientFactory: (AiEndpoint) -> AiProbe`。
- `SettingsScreen`：服务商 tab、自定义输入、条件显示深度思考/查询余额、文案按服务商。
- `AiChatViewModel`：banner 用当前服务商 Key 状态；`providerName` 透传。
- `AiChatScreen`：隐私弹窗用 `providerName`。

## Task 4：接线与文案

- `AppContainer`：`aiEndpoint()` / `aiApiClientFactory` / `aiSessionFactory` 按服务商组装。
- `AppNavHost`：设置页改用 clientFactory。
- `WelcomeNoteSeeder`：AI 段文案不再写死 DeepSeek。

## Task 5：测试与收尾

- 改名/更新既有测试（client/parser/session/store/settings VM/chat VM），新增 provider/硅基请求体/余额/错误详情用例。
- 全量单测 + debug/release 构建；README/AGENTS/backlog/版本同步；仅暂存本功能文件后提交。

## 修订记录

- 2026-09-15：初版。
- 2026-09-15（实现）：Task 1–5 全部完成；全量单测 547 → 582 全绿；版本定为 1.10.0（并行会话已发布 1.9.0）。
