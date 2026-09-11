# AI 网页端助手（DeepSeek 先行）— 设计文档

- 日期：2026-09-11
- 状态：设计已确认（brainstorming 会话），待实现
- 关联：`docs/superpowers/specs/2026-09-10-note-history-design.md`（同为编辑页能力 / DB 迁移先例）、`AGENTS.md`、`README.md`
- 前置结论（用户已确认）：
  - 接入 **DeepSeek 网页版**（`chat.deepseek.com`），不走官方 API、不自建代理；
  - **全自动**：app 内画聊天界面，隐藏 WebView 驱动网页发消息、抓流式回答；
  - 入口是**编辑页助手**（不是独立对话页）；
  - 会话**按笔记留档**，可新开、可续聊；回答落地要**插入正文 / 替换选中 / 复制 / 存为新笔记 / 只看留档**全部五种；
  - **取消内置动作 chip**，只保留自由输入；正文只在**新会话首条消息**附带，后续消息只发用户输入；
  - 服务要**可扩展**：以后可能接豆包 / Kimi 等；
  - 不绕过验证码 / 风控；不支持图片 AI 对话；AI 会话不进备份。

## 1. 背景与问题

MyNote 目前完全离线（无 `INTERNET` 权限、无任何网络代码，见 `AndroidManifest.xml`）。用户希望在笔记编辑中获得 AI 辅助，但不想走 API（免费用量 / 账号 / 成本考虑），而是复用自己在 DeepSeek 网页版的登录态：app 内提供类原生聊天界面，自动把用户输入填入网页输入框并发送，从页面抓取流式回答，展示、留档在 app 里。

代价与边界必须明确：笔记内容会离开设备、发送给 DeepSeek；驱动网页依赖其 DOM 结构，改版即失效（降级为可见网页手动操作）；自动化操作存在账号被风控的理论风险，不做任何绕过。

## 2. 目标与非目标

**目标**

- 编辑页 AI 入口 → 全屏聊天页；自动驱动 `chat.deepseek.com` 完成「填输入 → 点发送 → 流式抓取 → 完成落库」。
- 会话按笔记留档（Room，纯文本），可新开、续聊（按会话 URL 恢复网页上下文）、删除；离线可看历史。
- 回答支持五种落地：插入正文（光标处）、替换进入时的选区、复制到剪贴板、存为新笔记、只留档。
- 首次使用每服务一次隐私确认；出错自动降级为「可见网页 + 用户手操」。
- 服务适配层可扩展：新增服务 = 一个 `AiWebDriver` 实现类 + 注册一行；DB / UI / 会话管理 / 消息存储零改动。
- 零新增三方依赖（WebView / `org.json` 均为系统 API）；仅新增 `INTERNET` 权限。

**非目标（本次不做）**

- 官方 API、自建代理 / 服务端、多设备同步。
- 验证码 / 风控绕过；UA 伪装仅在网页明确拒绝 WebView 且不涉风控时作为兼容手段验证。
- 图片 / 附件对话（发送前用 `NoteContentParser.plainText` 剥离图片标记，只发纯文本）。
- 内置动作 / 提示词模板管理；对话整体导出。
- AI 会话纳入备份导入导出（避免导入后 `noteId` 对应关系失效）。
- 多服务同时在线（同一时刻一个 WebView 驱动一个服务；cookie 按域名天然隔离，登录态可共存）。
- 保证网页改版后依旧可用（选择器集中在 Driver，失败降级，见 §9）。

## 3. 数据模型与迁移

### 3.1 新表 `ai_sessions`

```kotlin
@Entity(
    tableName = "ai_sessions",
    foreignKeys = [ForeignKey(
        entity = NoteEntity::class,
        parentColumns = ["id"],
        childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("noteId")]
)
data class AiSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val serviceId: String,        // 驱动 id，如 "deepseek"（扩展位）
    val title: String,            // 默认"新对话"；首条提问首行截 20 字
    val remoteChatId: String?,    // 网页侧会话 id；null = 网页上下文未建立
    val createdAt: Long,
    val updatedAt: Long
)
```

### 3.2 新表 `ai_messages`

```kotlin
@Entity(
    tableName = "ai_messages",
    foreignKeys = [ForeignKey(
        entity = AiSessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class AiMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String,             // "user" | "assistant"
    val content: String,
    val status: String,           // "done" | "interrupted" | "failed"
    val createdAt: Long
)
```

- 删除笔记 → 级联删会话 → 级联删消息（Room 外键约束）。
- `status`：`interrupted` = 收到部分内容但未完成（超时 / 手动停止 / 离开页面）；`failed` = 未收到任何内容（发送失败、选择器失效、未登录）。
- 消息纯文本，量极小；不做 FTS、不做分页（每会话消息数有限）。

### 3.3 迁移与 DAO

- `AppDatabase`：`entities` 增加两个实体，`version = 3`；新增 `MIGRATION_2_3`（CREATE TABLE + CREATE INDEX，SQL 与 Room 期望完全一致），`AppContainer` 上 `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`。
- 迁移测试沿用 `AppDatabaseMigrationTest` 的手工建库模式（按 v2 DDL 建库 → 挂迁移打开 → 老数据保留、新表可写）。
- `AiSessionDao`：

| 方法 | 说明 |
|---|---|
| `observeByNote(noteId): Flow<List<AiSessionEntity>>` | `updatedAt DESC, id DESC` |
| `getById(id): AiSessionEntity?` | 单会话读取 |
| `insert(session): Long` | 新会话 |
| `updateRemoteChatId(id, remoteChatId)` | 首条消息发送成功后回填 |
| `touch(id, updatedAt)` | 每条消息完成后刷新排序 |
| `deleteById(id)` | 级联删消息 |

- `AiMessageDao`：

| 方法 | 说明 |
|---|---|
| `observeBySession(sessionId): Flow<List<AiMessageEntity>>` | `createdAt ASC, id ASC` |
| `getBySession(sessionId): List<AiMessageEntity>` | 一次性读取（测试 / 上下文判断） |
| `insert(message): Long` | 追加消息 |
| `updateStatusAndContent(id, status, content)` | 流式中断 / 失败时补写半截内容 |

## 4. 服务适配层（可扩展性的核心）

### 4.1 `AiWebDriver` 接口

位置 `data/ai/AiWebDriver.kt`。每个服务一个实现，集中维护 URL 规则、DOM 选择器和注入脚本；所有脚本都是纯 Kotlin 字符串生成（可单测），不引用 Android 类型（`org.json` 的 `JSONObject.quote` 除外，用于安全转义）。

```kotlin
interface AiWebDriver {
    val id: String             // "deepseek"
    val displayName: String    // "DeepSeek"
    val homeUrl: String        // https://chat.deepseek.com/
    fun chatUrl(remoteChatId: String): String     // https://chat.deepseek.com/a/chat/s/<id>
    fun parseChatId(url: String): String?         // 从 location.href 提取会话 id

    fun loginCheckJs(): String         // 结果经桥接发 loginState 事件
    fun newChatJs(): String            // 点击"新对话"（找不到则回首页）
    fun sendMessageJs(text: String): String   // 填输入框 + 点发送；失败发 sendFailed
    fun observeReplyJs(): String       // MutationObserver → replyChunk / replyDone / replyError
    fun stopObservingJs(): String      // 断开 observer
    fun stopGeneratingJs(): String     // 点页面"停止生成"按钮（若存在）
}
```

- `DeepSeekDriver` 是唯一实现。已知实现要点（具体选择器在实现时对着真实页面确定并注释来源）：
  - 输入框优先 `textarea`；React 受控组件用原生 setter + 派发 `input` 事件；若为 `contenteditable` 则退化为 `document.execCommand('insertText')`。
  - 发送按钮按 `aria-label` / 图标类定位；找不到就回车键事件兜底。
  - 回答节点：最后一条 assistant 消息的 markdown 容器；`MutationObserver` 观察子树，节流约 100ms 发 `replyChunk`（`innerText`）；稳定约 1.2s 且"停止生成"按钮消失 → `replyDone`；超时（默认 60s 无新片段）→ `replyError`。
  - 会话 id：`location.pathname` 匹配 `/a/chat/s/([\w-]+)`。
- 代码块 / 表格用 `innerText` 提取会损失部分格式，可接受（纯文本笔记场景）。

### 4.2 事件协议与桥接（与具体服务无关）

`AiWebEvent`（sealed interface，`data/ai/WebViewAiSession.kt`）：

```kotlin
sealed interface AiWebEvent {
    data object PageReady : AiWebEvent
    data class LoginState(val loggedIn: Boolean) : AiWebEvent
    data class ChatId(val id: String) : AiWebEvent
    data class ReplyChunk(val text: String) : AiWebEvent
    data class ReplyDone(val text: String) : AiWebEvent
    data class ReplyError(val reason: String) : AiWebEvent
    data class PageError(val description: String) : AiWebEvent
}
```

- 宿主注入 `window.__mynote = { emit: (type, payload) => MyNoteJsBridge.emit(JSON.stringify({type, payload})) }`；`@JavascriptInterface` 类 `MyNoteJsBridge` 接收 JSON，转 `AiWebEvent` 后在主线程发到 `SharedFlow`（`extraBufferCapacity` 足够，溢出丢 chunk 不影响最终 `replyDone` 全文）。
- Driver 只生成业务脚本，桥接、注入、事件解析由 `WebViewAiSession` 统一处理；加事件类型不需要动 Driver 接口。

### 4.3 `AiWebSession` 接口与实现

`AiWebSession` 做成接口是为了 ViewModel 可测（Fake 实现直接 `emit` 事件，无需 WebView）：

```kotlin
interface AiWebSession {
    val events: SharedFlow<AiWebEvent>
    val driver: AiWebDriver
    fun attach(webView: WebView)          // Compose AndroidView 创建后调用
    fun openNewChat()                     // 回首页 / 点新对话
    fun openChat(remoteChatId: String)    // 导航到指定网页会话
    fun send(text: String)                // 发送（内部先跑 loginCheck）
    fun stop()                            // 停止生成 + 断开观察
    fun checkLogin()
    fun release()                         // 断开观察、清引用（WebView 由 Compose 销毁）
}

class WebViewAiSession(initialDriver: AiWebDriver) : AiWebSession { ... }
```

- 真实实现持有 `WebView` 弱引用 / 可空引用，`attach` 时注册 JS 桥、设 `WebViewClient`（`onPageFinished` 注入 bootstrap + `PageReady`、`onReceivedError` → `PageError`）、`CookieManager.setAcceptThirdPartyCookies(webView, true)`。
- `AppContainer` 提供 `aiWebSessionFactory: (AiWebDriver) -> AiWebSession`，UI 层不 new 具体类；测试注入 Fake。

### 4.4 注册表与扩展方式

- `AiDriverRegistry(drivers: List<AiWebDriver>)`：`find(id)`、`default`（列表首个）、`all`。
- `AppContainer`：`val aiDriverRegistry by lazy { AiDriverRegistry(listOf(DeepSeekDriver())) }`。
- 以后接豆包：新增 `DoubaoDriver : AiWebDriver`（URL、选择器、脚本）+ 注册表里加一个实例；数据模型已有 `serviceId`，会话列表、UI、存储、提示词均无需改动。**注意**：`AiWebDriver` 接口一旦要加方法，所有实现都要改，因此接口尽量只保留服务差异，公共逻辑放桥接层。

## 5. 会话与消息生命周期

### 5.1 状态流转

- 进入 AI 页：`observeByNote(noteId)` 加载会话列表（`updatedAt DESC`）；默认打开最近会话（若有），否则空态"开始新对话"。
- 打开会话：加载本地消息；`remoteChatId != null` 时 `openChat(id)`，否则 `openNewChat()`。
- 新对话：仅清空当前视图（`currentSessionId = null`），**不落库**；发出首条消息时才创建 `ai_sessions` 行（`title` = 首条输入首行截 20 字，空则"新对话"）。
- 发送：
  1. 构造发送文本（§7）；
  2. 乐观插入 user 消息（`done`）；
  3. `webSession.send(text)`；页面回 `ChatId` 时 `updateRemoteChatId`；
  4. `ReplyChunk` → 流式状态原地刷新（节流渲染）；
  5. `ReplyDone` → 插入 assistant 消息（`done`）+ `touch(sessionId)`；
  6. `ReplyError` → 已收到内容按 `interrupted`、无内容按 `failed` 落库；UI 显示失败条 + "显示网页"。
- 停止：`stop()` → 页面停止生成 → 断开观察 → 半截按 `interrupted` 落库。
- 同一会话同时只允许一个请求在飞（生成中发送按钮变停止）。
- 中途离开页面（返回 / 切换会话 / 进程被杀前的正常 dispose）：若正在流式，按 `interrupted` 保存已收片段；**进程被系统直接杀死时流式半截无法保存**（已落库消息不受影响）。

### 5.2 网页上下文与恢复规则

- `remoteChatId` 是「网页上下文是否建立」的唯一标志：非 null 时续聊直接导航到 `chatUrl(id)`，DeepSeek 服务端自带历史；null 时按新网页会话处理（见 §7 发送规则）。
- 导航到已删除 / 失效的网页会话：页面会回到首页或报错，视为 `remoteChatId` 失效 → 置空并按新会话继续（UI 提示"网页会话已失效，已作为新对话继续"）。
- 会话标题不尝试从网页读取（永远本地生成），避免依赖页面结构。

## 6. UI 设计

### 6.1 入口、导航与选区回传

- 编辑页顶栏新增 AI 图标（`Icons.Outlined.AutoAwesome` 之类的内置图标）→ 导航 `ai_chat/{noteId}`。
- 编辑页内容状态从 `String` 升级为 `TextFieldValue`（`rememberSaveable(stateSaver = TextFieldValue.Saver)`），以获得光标与选区；同时修掉现有"插图永远追加到结尾"的粗糙行为（插入改为光标处，属顺带改进）。
- 点 AI 图标时把 `content.selection`（`selStart` / `selEnd`）和当前正文快照（`ai_note_content`，供 §7 构造首条消息用）写进编辑页自己的 `savedStateHandle`，AI 页从 `previousBackStackEntry.savedStateHandle` 读取；路由不带长参数。
- 回答落地回传：AI 页写 `previousBackStackEntry.savedStateHandle` 的 `ai_result_type`（`insert` / `replace`）+ `ai_result_text`，然后 `popBackStack()`；编辑页监听并执行后清除标记。
  - `insert`：`text` 替换 `[cursor, cursor]`（无选区即光标）；`replace`：仅当 `selEnd > selStart` 可用，替换 `[selStart, selEnd]`，光标落在插入内容末尾。

### 6.2 聊天页结构（`ui/ai/AiChatScreen.kt`）

- `Scaffold` + `TopAppBar`：返回、标题"AI 助手"（副标题为当前会话标题）、"显示网页 / 返回聊天"切换；服务切换入口仅当 `registry.all.size > 1` 时显示（当前隐藏，扩展后自动出现）。
- `ModalNavigationDrawer` 会话侧栏：顶部"新对话"按钮 + 会话列表（标题、`TimeFormat` 时间、删除图标）；点选切换会话并关闭抽屉；删除需确认（正在生成中的会话禁止删除）。
- 消息区 `LazyColumn`：user 右 / assistant 左；流式回答原地更新；`interrupted` / `failed` 标灰并附提示；空态引导语。
- 每条 assistant 消息操作条：插入正文 / 替换选中（无选区置灰）/ 复制 / 存为新笔记。
- 底部输入区：`OutlinedTextField`（多行）+ 发送按钮（生成中变停止）；发送前自动 `rememberSaveable` 草稿。
- WebView 层：`AndroidView` 满屏放在 `Box` 最底层，聊天 UI 是不透明 Surface 盖住它（保持页面"可见"，避免浏览器节流）；`showWeb` 时聊天 UI 收起、露出网页，顶部一个"返回聊天"悬浮按钮。
- WebView 生命周期：`remember { WebView(context) }` + `DisposableEffect` 调 `release()` / `destroy()`；`AndroidView` 的 `factory` 里 `attach(webView)`。
- 隐私确认：进入页面时若 `AiSettingsStore` 未记录该服务已确认 → `AlertDialog`（"笔记内容将发送到 DeepSeek 网页处理，回答由网页实时返回；请遵守服务条款"），确认后才允许发送。

### 6.3 未登录 / 降级横幅

- 顶栏下方 `banner`：未登录（"请先登录 DeepSeek，登录后自动继续"）、发送失败（"网页结构可能已更新，可显示网页手动操作"）、网页会话失效等；带"显示网页"action。
- 未登录 / 发送选择器失效时自动 `showWeb = true`，让用户登录 / 处理（不做验证码绕过）；用户点"返回聊天"可回到聊天界面，登录态检测通过后恢复。

## 7. 提示词与发送内容

- 发送内容构造（纯函数 `AiPromptBuilder`，`data/ai/AiPromptBuilder.kt`）：

| 场景 | 发送文本 |
|---|---|
| `remoteChatId == null`（新网页会话 / 上下文丢失） | `【笔记正文】\n{plainText(当前 content)}\n\n【要求】\n{用户输入}` |
| 已有网页上下文（`remoteChatId != null`） | 仅 `{用户输入}` |

- `plainText` 即 `NoteContentParser.plainText`，剥离 `![](img/…)` 图片标记；正文为空时省略【笔记正文】段。
- 笔记有标题时在正文前加一行 `# {title}`（便于 AI 理解），标题也走 plainText 无需处理。
- 正文快照 = 进入 AI 页时编辑页写入的当前内容（打开 AI 页期间编辑器不可编辑，因此等价于"点 AI 图标那一刻"的正文）；不做实时同步，用户想让 AI 看到最新版需返回重进（符合用户确认的规则）。
- 不发送：图片二进制、分类、置顶、历史、其他笔记。

## 8. 架构与数据流

### 8.1 新增 / 修改文件

```
data/db/
├── AiSessionEntity.kt        # 新增
├── AiSessionDao.kt           # 新增
├── AiMessageEntity.kt        # 新增
├── AiMessageDao.kt           # 新增
└── AppDatabase.kt            # 修改：v3 + MIGRATION_2_3
data/ai/
├── AiWebDriver.kt            # 新增：驱动接口 + AiWebEvent
├── DeepSeekDriver.kt         # 新增：DeepSeek 选择器与脚本
├── AiDriverRegistry.kt       # 新增：注册表
├── AiPromptBuilder.kt        # 新增：发送文本构造（纯函数）
├── AiWebSession.kt           # 新增：接口（便于测试替换）
├── WebViewAiSession.kt       # 新增：WebView 实现 + MyNoteJsBridge
└── AiChatRepository.kt       # 新增：会话 / 消息读写（封装 DAO）
data/settings/
└── AiSettingsStore.kt        # 新增：selectedServiceId + 隐私确认（沿用 SharedPreferences 模式）
ui/ai/
├── AiChatViewModel.kt        # 新增：会话 / 流式 / 错误状态机
└── AiChatScreen.kt           # 新增：聊天 UI + 会话抽屉 + WebView 容器
ui/notes/NoteEditScreen.kt    # 修改：TextFieldValue 化 + AI 入口 + 选区传参 + 结果落回
ui/navigation/AppNavHost.kt   # 修改：ai_chat/{noteId} 路由
di/AppContainer.kt            # 修改：迁移、DAO、注册表、Web 会话工厂、设置存储注入
AndroidManifest.xml           # 修改：INTERNET 权限
```

### 8.2 数据流

1. 编辑页点 AI → 写选区 / 正文快照进 `savedStateHandle` → 导航 `ai_chat/{noteId}`。
2. `AiChatViewModel(noteId)` 订阅 `AiChatRepository.observeSessions(noteId)`；构造时 `aiWebSessionFactory(registry.default)` 建会话，UI `attach(webView)`。
3. 发送 → `AiChatRepository` 落库（首条建会话）+ `webSession.send(AiPromptBuilder.build(...))`；web 事件流驱动 `AiChatUiState`。
4. `ReplyDone` → 落库 + `touch`；`ReplyError` / 停止 / dispose → 半截按 `interrupted` 落库。
5. 回答操作：插入 / 替换写 `savedStateHandle` 后返回；复制走系统剪贴板；存为新笔记走 `NoteRepository.saveNote(...)`。

## 9. 错误处理

| 场景 | 行为 |
|---|---|
| 未登录 | `loginCheck` → 自动露网页 + 横幅"请先登录"，`onPageFinished` 后复查，登录成功自动回聊天 |
| 遇验证码 / 风控 | 不识别、不绕过；发送失败会降级露网页，用户手动完成并自行操作 |
| 选择器失效（页面改版） | JS 发 `sendFailed` / `replyError` → 消息 `failed` + 横幅 + "显示网页"；用户可在网页手动发、复制回来 |
| 网页加载失败 / 断网 | `onReceivedError` → `PageError` 横幅 + "重试"（reload） |
| 回答超时（60s 无新片段） | 停止观察，半截按 `interrupted` 落库 |
| 网页会话失效 | `ChatId` 缺失 / 页面报错 → 置空 `remoteChatId`，提示"已作为新对话继续" |
| 发送时无 `currentSessionId` | 先建会话行 → 插 user 消息 → 再发送；发送失败时该会话里已有 `failed` 消息，不会产生空会话 |
| 生成中删除会话 | 禁止（删除按钮置灰），避免与流式写入竞争 |
| 进程死亡 | 流式半截丢失；已落库完整消息保留；重进会话正常 |
| 数据库事务失败 | 沿用现有行为（异常上抛）；不产生半截写入 |

## 10. 边界

- 同一笔记多会话：各自独立的 `remoteChatId`，网页侧互不影响；切换会话 = WebView 导航，切换期间禁止发送。
- 同一服务多笔记：共享 WebView cookie（登录态），但会话上下文由 URL 区分。
- 切换服务（未来）：`AiSettingsStore.selectedServiceId` 驱动；已开会话的 `serviceId` 与服务不符时，自动切 Driver 并加载对应 URL；本地消息不分服务通用展示。
- 网页被其他方式修改（用户在可见网页里手动点开别的会话）：以 app 记录为准；发现 `location` 变化且与当前会话不符时，仅更新 `remoteChatId`（best-effort），不反向创建本地会话。
- 隐私：截图 / 剪贴板只由用户主动触发（复制按钮）；不后台轮询。

## 11. 测试策略

- **`AiPromptBuilderTest`（纯 JVM）**：新上下文带正文 + 要求；已有上下文只发输入；正文为空 / 图片标记剥离；`# 标题` 行；空输入防护。
- **`DeepSeekDriverTest`**：`chatUrl` / `parseChatId`（含非法 URL 返回 null）、`sendMessageJs` 对引号 / 换行 / emoji 的转义安全（生成脚本可解析）。
- **DAO 测试（Robolectric + 内存 Room）**：`AiSessionDao` 排序 / 触达 / 回填；`AiMessageDao` 排序 / 状态更新；删笔记级联删会话与消息；删会话级联删消息。
- **迁移测试**：在现有 `AppDatabaseMigrationTest` 增加 v2 → v3 用例（手工按 v2 DDL 建库，验证老数据保留、两张新表可读写、`MIGRATION_1_2` + `MIGRATION_2_3` 连续升级）。
- **`AiChatViewModelTest`**：注入 `FakeAiWebSession` + 假工厂 + 内存库；覆盖：首条发送建会话并带正文、后续只发输入、`ChatId` 回填、流式 chunk 状态、`ReplyDone` 落库、`ReplyError` 半截 `interrupted` / 空 `failed`、停止按钮、未登录事件 → `webVisible`、删除会话、`remoteChatId == null` 时提示新会话、存为新笔记调用 `NoteRepository`。
- **`NoteEditScreen` 相关**：选区 / 光标插入与替换的纯逻辑抽到可测函数（如 `applyAiResult(TextFieldValue, type, text): TextFieldValue`）做单测；Compose UI 不新增自动化测试（沿用项目惯例）。
- 现有 113 个单测保持全绿；`:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:assembleRelease` 通过。

## 12. 人工验证清单（真机）

1. 首次进入 AI 页：隐私确认框只弹一次；未登录时自动显示网页，登录后自动回聊天。
2. 新建会话发送第一条：网页自动填入并发送，回答流式出现在 app；`remoteChatId` 写入，重进 app 能按 URL 续聊。
3. 同会话追问：只发输入不带正文；网页上下文连续。
4. 新开第二个会话：互不干扰；会话列表按时间倒序。
5. 回答操作：插入正文（光标正确）、选中文本时替换、复制、存为新笔记（标题取首行），返回编辑页内容正确。
6. 断网发送：错误横幅 + 重试；恢复网络后可继续。
7. 模拟选择器失效（改 Driver 常量或等页面改版）：消息失败 + 显示网页可手动操作。
8. 生成中点停止：内容按 `interrupted` 存档，重进可见。
9. 生成中返回：半截存档；重进会话不重复发送。
10. 杀进程重开：已落库消息在，会话可续聊；流式半截丢失符合预期。
11. 多笔记各自会话隔离；删除笔记后对应会话与消息清空（可用备份验证无残留）。
12. 删除会话后网页侧不动，app 列表消失；新建互不干扰。
13. 深浅色主题下聊天 UI、横幅、消息气泡可读；WebView 显示 / 返回切换正常。
14. 系统返回手势：显示网页时先回聊天再退出 AI 页（BackHandler）。
15. 覆盖安装（v2 → v3）：老笔记、历史、图片完好；AI 页可用。

## 13. 风险

| 风险 | 缓解 |
|---|---|
| DeepSeek 页面改版导致自动发送 / 抓取失效 | 选择器集中在 `DeepSeekDriver`，改一处即可；失败统一降级可见网页手动操作；真机清单第 7 项覆盖 |
| 自动化网页操作触发风控 / 账号受限 | 用户已知悉并选择接受；不做验证码绕过；低频使用；失败时退回可见网页（人工操作与正常浏览器一致） |
| WebView 常驻内存（约几十 MB） | 仅 AI 页创建，离开即 `destroy()`；不在编辑页 / 列表页保留 |
| 首次登录复杂（验证码 / 跳转） | 可见网页人工完成；登录态随后持久化（cookie） |
| 笔记内容外发隐私 | 首次确认框；正文仅在需要时发送（新网页上下文第一条）；不自动后台发送 |
| DB v2 → v3 迁移 | 与实体严格对齐 + 迁移测试；真机清单第 15 项覆盖安装验证 |
| `TextFieldValue` 改造触碰编辑页核心逻辑 | 改动点局限（初始化 / 保存 / 预览 / 插图 / 输入框 / 字数统计），现有测试 + 新增纯逻辑测试兜底 |
| JS 注入转义（引号 / 换行 / emoji） | 统一走 `JSONObject.quote` 构造脚本参数，`DeepSeekDriverTest` 覆盖 |
| 流式事件高频刷新导致卡顿 | chunk 节流约 100ms + `LazyColumn` 单条消息原地更新；事件流有溢出丢弃策略，最终以 `replyDone` 全文为准 |
