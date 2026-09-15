# AI 兼容性加固批（65–66）实现计划

**Goal:** 按 `docs/superpowers/specs/2026-09-15-api-compat-hardening-design.md` 实现动态模型列表与 422 参数降级。

**约定：** 中文注释/提交；零新增依赖；不主动 push；子代理不运行 Gradle，由中心串行集成。

## 轨道 A（客户端）：66 422 降级

文件所有权：
- `app/src/main/java/com/mynote/app/data/ai/DeepSeekApiClient.kt`
- `app/src/test/java/com/mynote/app/data/ai/DeepSeekApiClientTest.kt`

要点：`ChatRequest.thinking` 可空 + `explicitNulls = false`；422 且本次带了 `thinking` → 去字段立即重试一次；429/503 逻辑不变。

## 轨道 B（设置与存储）：65 动态模型列表

文件所有权：
- `app/src/main/java/com/mynote/app/data/settings/AiSettingsStore.kt`
- `app/src/main/java/com/mynote/app/ui/settings/SettingsViewModel.kt`
- `app/src/main/java/com/mynote/app/ui/settings/SettingsScreen.kt`
- `app/src/test/java/com/mynote/app/data/settings/AiSettingsStoreTest.kt`
- `app/src/test/java/com/mynote/app/ui/settings/SettingsViewModelTest.kt`

要点：`models()/setModels()`（空回退 `DeepSeekModels.all`）、`model()` 按列表校验；VM 增加 `probeFn/balanceFn` 注入缝与 `availableModels`，测试连接成功即刷新并自动切换失效模型；UI 模型 tab 改用 `availableModels`。

## 集成（串行）

- 全量单测 → 修复 → `assembleDebug` / `assembleRelease`。
- backlog 65–66 状态回写、版本 1.8.0、README/AGENTS 测试数更新、提交。

## 修订记录

- 2026-09-15：初版。两条轨道文件互斥，可并行。
- 2026-09-15（实现）：两轨道由并行子代理完成；集成修复 1 处测试期望（刷新后失效模型切到新列表首项）；全量单测 456 全绿，debug/release 构建通过，版本 1.8.0。
