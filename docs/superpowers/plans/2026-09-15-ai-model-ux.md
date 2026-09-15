# AI 模型选择体验优化（70）实现计划

**Goal:** 按 `docs/superpowers/specs/2026-09-15-ai-model-ux-design.md` 修复三处模型选择体验问题，发布 patch 1.10.1。

**约定：** 在 worktree `ai-model-ux-0915` 开发；release 构建/推送在主树；中文注释/提交；零新增依赖。

## Task 1：文案与状态

- `SettingsViewModel.connectionMessage`：模型数 > 5 → 「连接正常，共 N 个可用模型」。
- `AiChatViewModel`：`UiState.modelName`（初始化取当前服务商模型）。

## Task 2：UI

- `SettingsScreen`：模型数 > 8 → 「当前模型 + 选择」打开发可搜索 `ModelPickerDialog`（LazyColumn + 过滤 + 当前项对勾）。
- `AiChatScreen`：顶栏标题加副标题 `服务商 · 模型`。

## Task 3：测试与收尾

- 更新 `SettingsViewModelTest`（计数文案）与 `AiChatViewModelTest`（modelName）。
- 全量单测；版本 1.10.1 + README/AGENTS/backlog；worktree 内提交 → 主树 `merge --ff-only` → 主树构建 release。

## 修订记录

- 2026-09-15：初版。
- 2026-09-15（实现）：Task 1–3 完成；全量单测 584 全绿；版本 1.10.1；worktree 内提交后由主树 ff-only 合入。
