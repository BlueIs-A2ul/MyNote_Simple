# AI 助手优化批（51–64）实现计划

**Goal:** 按 `docs/superpowers/specs/2026-09-15-ai-assistant-optimization-design.md` 完成 backlog 51–64。

**约定：** 契约见设计 §2（冻结，实现不得擅改）；Windows PowerShell 统一 `.\gradlew`；中文注释/提交；零新增依赖；不主动 push。

## 文件所有权（并行轨道，互斥）

| 轨道 | 文件 | 条目 |
|---|---|---|
| P 协议 | `data/ai/AiSession.kt`、`DeepSeekSseParser.kt`、`DeepSeekApiClient.kt`、`DeepSeekApiSession.kt` + 3 个测试类 | 51(心跳)、57、58、59(协议侧)、64 |
| V ViewModel | `ui/ai/AiChatViewModel.kt` + `AiChatViewModelTest.kt` | 51、52、56、60(VM 侧)、63 |
| U 聊天 UI | `ui/ai/AiChatScreen.kt` | 53、55、59(UI 侧)、61 的渲染接入 |
| B 组装 | `data/ai/AiPromptBuilder.kt`、`AiApiMessageBuilder.kt` + 2 个测试类 | 54、61 的解析纯函数（`CodeBlockParser` 可放 `ui/ai/`） |
| S 存储 | `data/settings/AiDraftStore.kt` + 测试、`di/AppContainer.kt`、`ui/navigation/AppNavHost.kt` | 60(存储侧)、62 |
| T 设置 | `ui/settings/SettingsViewModel.kt`、`SettingsScreen.kt`、`SettingsViewModelTest.kt` | 58 |

> 轨道间只通过设计 §2 契约通信；**任何轨道不得修改别人名下的文件**。

## Task 1（P）：协议与客户端

- 按设计 §2.1 落地事件、Frame、ProbeResult/BalanceState、`probe`（替换 `verifyApiKey`）、`fetchBalance`、`Accept-Encoding: identity`、429/503 单次预连接重试。
- 测试：`DeepSeekSseParserTest` 增 reasoning/usage（含空 choices 带 usage）；`DeepSeekApiClientTest` 增 probe/balance/重试一次/推理与 usage 事件；`DeepSeekApiSessionTest` 增 Reasoning 转发、Done(usage) 透传。

## Task 2（B）：上下文预算与代码块解析

- `AiPromptBuilder.build` 增 `maxNoteChars` 截断；`AiApiMessageBuilder` 增 `MAX_NOTE_CHARS`/`MAX_HISTORY_CHARS` 裁剪。
- 新增纯函数 `ui/ai/CodeBlockParser.kt`：把回答拆成 `Text | CodeBlock` 片段（``` 围栏，容错未闭合），供 UI 渲染；纯 JVM 测试。

## Task 3（V）：ViewModel

- 按设计 §2.3 实现：空闲看门狗、Reasoning 累积、`lastUsage`、草稿（构造注入 `AiDraftStore`）、`retry()`、空失败不落库、`sendRequested` 异常复位。
- 更新 `AiChatViewModelTest`（构造增 `draftStore` 假实现；新增/改写用例）。

## Task 4（U）：聊天 UI

- 按设计 §2.4 实现：`draftStore` 参数、草稿输入、滚动跟随、删除确认、重试按钮、思考折叠、用量提示；用 `CodeBlockParser` 渲染代码块（等宽、横向滚动）。

## Task 5（S）：草稿存储与容器接线

- `AiDraftStore` + `PrefsAiDraftStore` + `aiDraftKey` + Robolectric 测试。
- `AppContainer`：`aiDraftStore`、`aiSessionFactory` 每会话新建客户端；`AppNavHost` 传参。

## Task 6（T）：设置页

- 「测试连接」改 `probe`（模型列表比对提示）；新增「查询余额」。
- `SettingsViewModelTest` 更新/新增。

## Task 7（集成，串行）

- 全量 `.\gradlew :app:testDebugUnitTest`；修集成问题（优先使用子代理处理独立失败域）。
- `.\gradlew :app:assembleDebug`、`:app:assembleRelease`。
- backlog 51–64 状态回写、README 版本行（1.7.0）、提交。

## 修订记录

- 2026-09-15：初版。六条并行轨道 + 串行集成；契约冻结于设计文档 §2。
- 2026-09-15（实现）：六轨道由并行子代理完成（协议/VM/UI/组装/存储/设置），中心串行集成。集成期修复三处：
  1. `AiApiMessageBuilderTest` 4 个用例把 `"继续"` 误当作 `content.first()` 的期望值（应为 `"继"`）；
  2. `DeepSeekApiClient.cancel()` 的 `ConcurrentHashMap` 视图在并发移除下 `next()` 抛 `NoSuchElementException`，改 `CopyOnWriteArraySet`；
  3. 两个看门狗测试在 `runTest` 挂起等待 Room 流时被自动推进的虚拟时间提前触发（探针确认），改为 `runCurrent()` + `state.value` 断言且不提前 await Room。
  验收：全量单测 442 全绿，debug/release 构建通过，版本 1.7.0。
- 说明：本批设计文档在提交前被并行会话的 1.6.1 提交（`c9ef7a7`）一并纳入，内容无改动。
