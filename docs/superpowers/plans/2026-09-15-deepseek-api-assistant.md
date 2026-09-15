# DeepSeek 官方 API AI 助手实现计划

**Goal:** 用 DeepSeek 官方 API（用户自带 Key，SSE 流式）替换网页驱动 AI 助手，删除 WebView 全套，保持按笔记留档与回答落地体验不变。

**Spec:** `docs/superpowers/specs/2026-09-15-deepseek-api-assistant-design.md`

**约定：** 命令统一 `.\gradlew`；文档 / 注释 / 提交中文；零新增三方依赖；不主动 push。

---

## Task 1：API 会话抽象与消息组装

**文件**

- 新增 `app/src/main/java/com/mynote/app/data/ai/AiChatMessage.kt`
- 新增 `app/src/main/java/com/mynote/app/data/ai/AiSession.kt`（`AiEvent` + `AiSession`）
- 新增 `app/src/main/java/com/mynote/app/data/ai/AiApiMessageBuilder.kt`
- 新增 `app/src/main/java/com/mynote/app/data/ai/DeepSeekModels.kt`
- 新增测试 `app/src/test/java/com/mynote/app/data/ai/AiApiMessageBuilderTest.kt`、`DeepSeekModelsTest.kt`

**要点**

- `AiEvent = Chunk | Done | Failed(reason, settingsHint = false)`；`AiSession.send(messages)`。
- `AiApiMessageBuilder.build`：会话无 user 消息 → 首条走 `AiPromptBuilder` 带正文；空内容历史（failed 空回复）过滤；角色按 Room 常量映射。
- `DeepSeekModels`：`FLASH = "deepseek-flash"`（默认）/ `V4_PRO = "deepseek-v4-pro"`。

**验证：** `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.ai.AiApiMessageBuilderTest"` 失败（类不存在）→ 实现后通过。

---

## Task 2：SSE 解析与 API 客户端

**文件**

- 新增 `DeepSeekSseParser.kt`（纯函数 `parse(line): Frame?`）
- 新增 `DeepSeekApiClient.kt`（`ChatStreamer`、`ApiStreamEvent`、DTO、`HttpStreamTransport` / `UrlConnectionTransport`、`verifyApiKey`）
- 新增测试 `DeepSeekSseParserTest.kt`、`DeepSeekApiClientTest.kt`（假 transport）

**要点**

- 逐行解析 `data:`；`[DONE]` 结束；忽略注释 / 空行 / 未知类型 / `reasoning_content`；`error.message` 帧转发。
- 请求体：`model / messages / stream=true / thinking{type}`；`deepThinking` 默认 false → `disabled`。
- 错误映射：401（settingsHint）/ 402 / 429 / 500 / 503 / 其他；400、422 附带官方 message。
- 取消：`invokeOnCompletion { connection.close() }` 断开阻塞读；`flowOn(Dispatchers.IO)`。
- `verifyApiKey`：`GET /models`，成功 null，失败文案。

**验证：** 两个测试类全绿；`AiChatViewModelTest` 此时仍是旧的，暂不跑全量。

---

## Task 3：Keystore Key 存储与设置项

**文件**

- 新增 `data/settings/ApiKeyCipher.kt` + `KeystoreApiKeyCipher.kt`
- 修改 `data/settings/AiSettingsStore.kt`（Key / 模型 / 深度思考；删 `selectedServiceId`）
- 重写测试 `AiSettingsStoreTest.kt`（假 cipher）

**要点**

- 存储格式 `v1:base64(iv):base64(ciphertext)`；解密失败 → null + 清除。
- `AiSettingsStore(context, cipher = KeystoreApiKeyCipher())`，Key 内存缓存。
- 新增 `hasApiKey` / `apiKey` / `setApiKey`（返回是否成功）/ `model` / `setModel` / `deepThinking` / `setDeepThinking`。

**验证：** `AiSettingsStoreTest` 全绿；Robolectric 不触碰真实 Keystore。

---

## Task 4：`DeepSeekApiSession`

**文件**

- 新增 `data/ai/DeepSeekApiSession.kt`
- 新增测试 `DeepSeekApiSessionTest.kt`（假 `ChatStreamer` + `TestScope`）

**要点**

- 注入：`streamer`、`credentials`、`model`、`deepThinking`、`scope`。
- Key 缺失 → `Failed(AiSession.KEY_MISSING_REASON, settingsHint = true)`，不发请求。
- `stop()` 取消任务；事件流用 `SharedFlow(extraBufferCapacity = 64)`。

**验证：** `DeepSeekApiSessionTest` 全绿。

---

## Task 5：ViewModel 改造

**文件**

- 修改 `ui/ai/AiChatViewModel.kt`
- 重写 `app/src/test/java/com/mynote/app/ui/ai/AiChatViewModelTest.kt`（Fake `AiSession`）

**要点**

- 构造参数：删 `registry` / `webSessionFactory`，改注入 `session: AiSession`。
- 删除登录 / 网页可见 / `ChatId` / `PageError` 分支；`UiState` 增 `apiKeyMissing`。
- `send`：Key 缺失拦截（横幅 + 不建会话）；`launchSendNow` 读历史 → `AiApiMessageBuilder` → 落 user → `session.send(messages)`。
- 事件：`Chunk` / `Done` / `Failed(settingsHint)`；停止、会话切换、`onCleared`、看门狗逻辑保留。

**验证：** `AiChatViewModelTest` 全绿（覆盖首条带正文、续聊、缺 Key、失败横幅、停止、切换、看门狗）。

---

## Task 6：UI 与接线

**文件**

- 修改 `ui/ai/AiChatScreen.kt`（删 WebView / 显示网页 / BackHandler；加 `onOpenSettings` + "去设置"）
- 修改 `ui/settings/SettingsViewModel.kt` / `SettingsScreen.kt`（AI 分区：模型 / 深度思考 / Key / 测试连接）
- 修改 `di/AppContainer.kt`（`aiSettingsStore` / `deepSeekApiClient` / `aiSessionFactory`；删 registry / web 工厂）
- 修改 `ui/navigation/AppNavHost.kt`（传参）
- `app/proguard-rules.pro`：序列化 keep 规则扩展到 `com.mynote.app.data.ai.**`

**验证：** `.\gradlew :app:assembleDebug` 通过。

---

## Task 7：删除网页驱动与清理

- 删除 `AiWebDriver.kt` / `DeepSeekDriver.kt` / `AiDriverRegistry.kt` / `WebViewAiSession.kt` / `AiWebEventParser.kt`。
- 删除测试 `DeepSeekDriverTest` / `WebViewAiSessionTest` / `AiWebEventParserTest` / `AiDriverRegistryTest`。
- `AiSessionDao.updateRemoteChatId` 与 `AiChatRepository.updateRemoteChatId` 删除；对应 DAO / 仓库测试用例删除；`AiSessionEntity.remoteChatId` 注释标记为历史列（保留，避免迁移）。
- `BackHandler` / WebView 相关 import 清理。

**验证：** `.\gradlew :app:assembleDebug` 无残留引用。

---

## Task 8：文档与版本

- `app/build.gradle.kts`：`1.5.0` → `1.6.0`，`versionCode 1_06_00`。
- `README.md`：功能描述、版本行、release 产物名、目录说明、AI 设计 / 计划链接。
- `AGENTS.md`：AI 段落改写（API 直连 / Key 加密 / 会话与历史处理）。
- `docs/backlog.md`：网页版遗留条目（36-38）标注随网页版移除作废。

**验证：** 全量 `.\gradlew :app:testDebugUnitTest`、`.\gradlew :app:assembleDebug`、`.\gradlew :app:assembleRelease` 通过。

---

## Task 9：提交

- 中文提交，`feat:` 前缀（实现）+ `docs:`（文档，如拆分）。
- 不 push。

## 修订记录

- 2026-09-15：初版计划。按用户确认的"API 替换网页驱动"方案拆分为 9 个任务；依据官方 2026-09 文档（模型 `deepseek-flash` / `deepseek-v4-pro`、SSE、错误码）确定实现细节。
- 2026-09-15（实现）：取消机制改为显式 `ChatStreamer.cancel()`（设计文档原定的 `invokeOnCompletion` 在阻塞读时不会触发，实测取消测试超时）；`DeepSeekApiClient` 以 `ConcurrentHashMap` 登记活跃连接，`DeepSeekApiSession.stop()` 先取消协程再断连。其余按计划落地；全量单测 351 全绿，debug/release 构建通过。
