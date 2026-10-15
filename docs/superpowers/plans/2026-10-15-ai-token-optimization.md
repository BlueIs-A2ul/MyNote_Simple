# AI Token 成本与用量体验优化 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 `docs/superpowers/specs/2026-10-15-ai-token-optimization-design.md`（契约冻结于其 §2）实现 1.11.0 批：system prompt、动态上下文预算、缓存命中解析、400/422 超限自愈、会话累计用量。

**Architecture:** 全部改动集中在 `data/ai/` 与 `ui/ai/` 的既有文件；客户端 `AiApiClient.stream()` 从「一次性两分支」重构为「连接级自愈循环」（每类自愈至多一次、总连接 ≤5）；预算计算下沉到 `AiApiMessageBuilder.build`；缓存命中等字段缺失一律按 0 容错。

**Tech Stack:** Kotlin + Jetpack Compose + kotlinx.serialization + HttpURLConnection（零新增依赖、无 Room 迁移、零新权限）。

**约定（与仓库既有惯例一致）：** Windows PowerShell 统一 `.\gradlew`；中文注释/提交；任务内按「实现 → 测试 → 全绿 → 提交」推进；不主动 push；单测 JVM 参数与 Robolectric 配置勿动。

---

## 执行环境（开始前）

- 仓库根（主树）执行：`git worktree add .worktrees/ai-token-1015 -b ai-token-1015`
- 从主树复制两个未跟踪的签名文件（否则任一 Gradle 任务都会配置失败）：
  - 仓库根 `keystore.properties` → worktree 的仓库根
  - `app/mynote-release.keystore` → worktree 的 `app/`
- 之后所有命令在 worktree 根运行；`ANDROID_HOME` 环境变量已配置（无 `local.properties`）。
- 首次构建偏慢属正常；release 构建与发布按 AGENTS.md 留在主树（Task 8）。

## 文件所有权（任务间不重叠）

| 任务 | 文件 |
|---|---|
| T1 | `data/ai/AiSession.kt`（AiUsage）、`data/ai/AiSseParser.kt`、`AiSseParserTest.kt` |
| T2/T3 | `data/ai/AiChatMessage.kt`、`data/ai/AiPromptBuilder.kt`、`data/ai/AiApiMessageBuilder.kt`、`AiApiMessageBuilderTest.kt` |
| T4 | `data/ai/AiProvider.kt`、`data/ai/AiSession.kt`、`data/ai/AiApiSession.kt`、`ui/ai/AiChatViewModel.kt`、`AiProviderTest.kt`、`AiApiSessionTest.kt`、`AiChatViewModelTest.kt` |
| T5/T6 | `data/ai/AiApiClient.kt`、`AiApiClientTest.kt` |
| T7 | `ui/ai/UsageFormat.kt`（新建）、`UsageFormatTest.kt`（新建）、`ui/ai/AiChatViewModel.kt`、`ui/ai/AiChatScreen.kt`、`AiChatViewModelTest.kt` |
| T8 | `app/build.gradle.kts`、`README.md`、`AGENTS.md`、本计划 |

> T4 与 T7 先后触及 `AiChatViewModel.kt` / `AiChatViewModelTest.kt`：串行执行，T7 在 T4 完成后开始。

---

### Task 1: 用量解析补缓存命中（`AiUsage.cacheHitTokens`）

**Files:**
- Modify: `app/src/main/java/com/mynote/app/data/ai/AiSession.kt`（`AiUsage` 顶层）
- Modify: `app/src/main/java/com/mynote/app/data/ai/AiSseParser.kt`（`parseUsage`）
- Test: `app/src/test/java/com/mynote/app/data/ai/AiSseParserTest.kt`

- [ ] **Step 1: 给 `AiUsage` 增字段（默认 0，所有既有构造点零改动）**

```kotlin
/** 一次回答的 token 用量（接口顶层 usage）；字段缺失时由解析层按 0 容错。 */
data class AiUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    /** prompt 缓存命中量（DeepSeek `prompt_cache_hit_tokens` 或 OpenAI `prompt_tokens_details.cached_tokens`）；缺失按 0。 */
    val cacheHitTokens: Int = 0
)
```

- [ ] **Step 2: 给 `AiSseParserTest` 追加 4 个用例（文件末尾 `}` 之前）**

```kotlin
    @Test
    fun parsesDeepSeekStyleCacheHitTokens() {
        val frame = AiSseParser.parse(
            """data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12,"prompt_cache_hit_tokens":8}}"""
        )

        assertEquals(AiUsage(10, 2, 12, 8), frame?.usage)
    }

    @Test
    fun parsesOpenAiStyleCachedTokens() {
        val frame = AiSseParser.parse(
            """data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12,"prompt_tokens_details":{"cached_tokens":8}}}"""
        )

        assertEquals(AiUsage(10, 2, 12, 8), frame?.usage)
    }

    @Test
    fun cacheHitPrefersDeepSeekFieldOverDetails() {
        val frame = AiSseParser.parse(
            """data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12,"prompt_cache_hit_tokens":7,"prompt_tokens_details":{"cached_tokens":8}}}"""
        )

        assertEquals(AiUsage(10, 2, 12, 7), frame?.usage)
    }

    @Test
    fun cacheHitDefaultsToZeroWhenAbsent() {
        val frame = AiSseParser.parse(
            """data: {"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}"""
        )

        assertEquals(AiUsage(3, 1, 4, 0), frame?.usage)
    }
```

- [ ] **Step 3: 运行测试确认失败（编译错误：`AiUsage` 无第 4 参）**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.ai.AiSseParserTest"`
Expected: 编译失败（`Too many arguments` / `No value passed` 指向 `AiUsage`）

- [ ] **Step 4: 实现 `parseUsage` 缓存命中解析（替换既有方法体）**

```kotlin
    /** 顶层 usage：整体缺失/为 null/非对象时返回 null；单个字段缺失或为 null 按 0 计。 */
    private fun parseUsage(root: JsonObject): AiUsage? {
        val usage = runCatching {
            root["usage"]?.takeIf { it !is JsonNull }?.jsonObject
        }.getOrNull() ?: return null
        fun token(name: String): Int? = runCatching {
            usage[name]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull
        }.getOrNull()
        val prompt = token("prompt_tokens")
        val completion = token("completion_tokens")
        val total = token("total_tokens")
        if (prompt == null && completion == null && total == null) return null
        // 缓存命中：DeepSeek 风格字段优先，OpenAI 风格嵌套字段其次；均缺失按 0
        val cacheHit = token("prompt_cache_hit_tokens")
            ?: runCatching {
                usage["prompt_tokens_details"]?.takeIf { it !is JsonNull }?.jsonObject
                    ?.get("cached_tokens")?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull
            }.getOrNull()
            ?: 0
        return AiUsage(prompt ?: 0, completion ?: 0, total ?: 0, cacheHit)
    }
```

- [ ] **Step 5: 运行测试确认全绿（含既有 partial usage 用例）**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.ai.AiSseParserTest"`
Expected: PASS（16 个用例全绿）

- [ ] **Step 6: 提交**

```powershell
git add app/src/main/java/com/mynote/app/data/ai/AiSession.kt app/src/main/java/com/mynote/app/data/ai/AiSseParser.kt app/src/test/java/com/mynote/app/data/ai/AiSseParserTest.kt
git commit -m "feat: AI 用量解析补缓存命中字段"
```

---

### Task 2: system prompt 与动态上下文预算（组装层）

**File:**
- Modify: `app/src/main/java/com/mynote/app/data/ai/AiChatMessage.kt`
- Modify: `app/src/main/java/com/mynote/app/data/ai/AiPromptBuilder.kt`
- Modify: `app/src/main/java/com/mynote/app/data/ai/AiApiMessageBuilder.kt`
- Test: `app/src/test/java/com/mynote/app/data/ai/AiApiMessageBuilderTest.kt`（全量替换）

- [ ] **Step 1: `AiChatMessage` 增 system 角色常量**

```kotlin
/** OpenAI 兼容对话消息（role: user / assistant / system）；对端服务商由 [AiEndpoint] 决定。 */
data class AiChatMessage(val role: String, val content: String) {
    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}
```

- [ ] **Step 2: `AiPromptBuilder` 增冻结文案常量（放在 `NOTE_CONTEXT_HEADER` 之后）**

```kotlin
    /** 固定 system 提示：约束回答长度与复述（设计 §2.1，文案冻结；调整走设计修订记录）。 */
    const val SYSTEM_PROMPT =
        "你是 MyNote 笔记应用内的 AI 助手。回答规则：直接回答用户问题，不复述笔记原文；" +
            "语言简洁，能一句话说清不写三段；仅在用户要求时展开细节；" +
            "涉及笔记内容时忠实于原文，不编造原文中不存在的信息。"
```

- [ ] **Step 3: 重写 `AiApiMessageBuilder`（全量替换文件内容）**

要点：`build` 增参 `contextTokens`；预算 `minOf(totalBudget / 2, MAX_NOTE_CHARS)`；`trimHistory` 改为预算参数；system 在最后一步 prepend（必须在重包之后）。`AiApiSession.send` 的空校验无需改动（system 只在组装输出、恒非空是预期语义，契约已注明）。

```kotlin
package com.mynote.app.data.ai

import com.mynote.app.data.db.AiMessageEntity

/**
 * 把本地会话历史 + 本次输入组装成 API messages（最前追加 system 提示）。
 * API 无服务端会话，每次请求都必须携带完整历史；笔记正文只进首条用户消息。
 * 注意：本地落库的首条用户消息是**裸输入**（聊天记录不展示提示词包装），因此这里在请求时
 * 兜底重包一次——否则第二轮起 `history` 里已有 user 消息，正文就再也不会被发送。
 * 为控制上下文体积，正文与历史均按预算裁剪（设计 §2.3）：默认 60_000/20_000，端点窗口更小时收紧。
 */
object AiApiMessageBuilder {

    /** 笔记正文最多发送的字符数（默认上限），超出部分由 [AiPromptBuilder] 截断并加提示。 */
    const val MAX_NOTE_CHARS = 20_000

    /** 历史消息 content 字符数总预算的默认值（不含本次输入）；端点窗口更小时按设计 §2.3 收紧。 */
    const val MAX_HISTORY_CHARS = 60_000

    fun build(
        noteTitle: String,
        noteContent: String,
        history: List<AiMessageEntity>,
        input: String,
        contextTokens: Int = 0
    ): List<AiChatMessage> {
        // 动态预算：留 30% 给输出；窗口够大时与现状 60_000/20_000 完全一致（minOf 下限保证正文不放大）
        val totalBudget = if (contextTokens > 0) {
            (MAX_HISTORY_CHARS + MAX_NOTE_CHARS).coerceAtMost(contextTokens * 7 / 10)
        } else {
            MAX_HISTORY_CHARS + MAX_NOTE_CHARS
        }
        val maxNoteChars = minOf(totalBudget / 2, MAX_NOTE_CHARS)
        val maxHistoryChars = totalBudget - maxNoteChars

        val includeNoteContext = history.none { it.role == AiMessageEntity.ROLE_USER }
        val merged = ArrayList<AiChatMessage>(history.size)
        for (message in history) {
            val content = message.content.trim()
            if (content.isEmpty()) continue
            val role = if (message.role == AiMessageEntity.ROLE_ASSISTANT) {
                AiChatMessage.ROLE_ASSISTANT
            } else {
                AiChatMessage.ROLE_USER
            }
            merged += AiChatMessage(role, content)
        }
        val messages = trimHistory(merged, maxHistoryChars).toMutableList()
        // 首条若是裸输入，补回笔记正文（幂等：已带表头则原样保留）
        messages.firstOrNull()
            ?.takeIf { it.role == AiChatMessage.ROLE_USER && !AiPromptBuilder.hasNoteContext(it.content) }
            ?.let { first ->
                messages[0] = AiChatMessage(
                    AiChatMessage.ROLE_USER,
                    AiPromptBuilder.build(noteTitle, noteContent, first.content, true, maxNoteChars)
                )
            }
        messages += AiChatMessage(
            AiChatMessage.ROLE_USER,
            AiPromptBuilder.build(noteTitle, noteContent, input, includeNoteContext, maxNoteChars)
        )
        // system 最后 prepend：稳定前缀置于最前，且不干扰上面的重包与裁剪判定
        return listOf(AiChatMessage(AiChatMessage.ROLE_SYSTEM, AiPromptBuilder.SYSTEM_PROMPT)) + messages
    }

    /**
     * 历史裁剪：首条（含笔记正文）与最新一条永远保留；其余消息从最旧的一条开始
     * 逐条丢弃（幸存消息保持原顺序），直到按 content.length 累加不超过 [maxHistoryChars]。
     * 即使首条 + 最新一条本身已超预算也不再裁剪。空内容消息在调用前已过滤。
     */
    private fun trimHistory(history: List<AiChatMessage>, maxHistoryChars: Int): List<AiChatMessage> {
        if (history.size <= 2) return history
        var total = history.sumOf { it.content.length }
        if (total <= maxHistoryChars) return history
        var firstMiddle = 1
        while (total > maxHistoryChars && firstMiddle <= history.size - 2) {
            total -= history[firstMiddle].content.length
            firstMiddle++
        }
        if (firstMiddle == 1) return history
        val kept = ArrayList<AiChatMessage>(history.size - firstMiddle + 1)
        kept += history.first()
        kept.addAll(history.subList(firstMiddle, history.size - 1))
        kept += history.last()
        return kept
    }
}
```

- [ ] **Step 4: 全量替换 `AiApiMessageBuilderTest`（system 前置后所有下标 +1）**

```kotlin
package com.mynote.app.data.ai

import com.mynote.app.data.db.AiMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiApiMessageBuilderTest {

    private fun message(
        role: String,
        content: String,
        status: String = AiMessageEntity.STATUS_DONE,
        id: Long = 1
    ) = AiMessageEntity(id, 1, role, content, status, 1)

    @Test
    fun firstMessageIncludesNoteContext() {
        val messages = AiApiMessageBuilder.build("标题", "正文", emptyList(), "帮我总结")

        assertEquals(2, messages.size)
        assertEquals(AiChatMessage.ROLE_SYSTEM, messages[0].role)
        assertEquals(AiPromptBuilder.SYSTEM_PROMPT, messages[0].content)
        assertEquals(AiChatMessage.ROLE_USER, messages[1].role)
        assertTrue(messages[1].content.contains("【笔记正文】"))
        assertTrue(messages[1].content.contains("# 标题"))
        assertTrue(messages[1].content.contains("正文"))
        assertTrue(messages[1].content.contains("帮我总结"))
    }

    @Test
    fun laterMessageOmitsNoteContextAndKeepsHistory() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "【笔记正文】\n# 标题\n正文\n\n【要求】\n帮我总结", id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "好的", id = 2)
        )
        val messages = AiApiMessageBuilder.build("标题", "正文", history, "第二问")

        assertEquals(4, messages.size)
        assertEquals(AiChatMessage.ROLE_SYSTEM, messages[0].role)
        assertEquals(AiChatMessage.ROLE_USER, messages[1].role)
        assertEquals(AiChatMessage.ROLE_ASSISTANT, messages[2].role)
        assertEquals("第二问", messages[3].content)
        assertFalse(messages[3].content.contains("【笔记正文】"))
        // 首条已带正文表头：幂等，不重复包裹
        assertEquals("【笔记正文】\n# 标题\n正文\n\n【要求】\n帮我总结", messages[1].content)
    }

    /** 落库的是裸输入（不是包好正文的那条），第二轮起仍必须把笔记正文带回上下文。 */
    @Test
    fun rawFirstUserMessageGetsNoteContextRestored() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "帮我总结", id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "好的", id = 2)
        )
        val messages = AiApiMessageBuilder.build("标题", "正文", history, "再短一点")

        assertEquals(4, messages.size)
        assertEquals(AiChatMessage.ROLE_USER, messages[1].role)
        assertTrue(messages[1].content.contains(AiPromptBuilder.NOTE_CONTEXT_HEADER))
        assertTrue(messages[1].content.contains("# 标题"))
        assertTrue(messages[1].content.contains("正文"))
        assertTrue(messages[1].content.contains("帮我总结"))
        // 本轮新输入仍只发纯输入
        assertEquals("再短一点", messages[3].content)
    }

    /** 笔记正文为空时不包裹（与首轮口径一致）。 */
    @Test
    fun rawFirstUserMessageWithoutNoteBodyStaysRaw() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "你好", id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "好的", id = 2)
        )
        val messages = AiApiMessageBuilder.build("标题", "", history, "继续")

        assertEquals("你好", messages[1].content)
    }

    @Test
    fun blankHistoryContentIsFiltered() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "问", id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "", AiMessageEntity.STATUS_FAILED, id = 2),
            message(AiMessageEntity.ROLE_ASSISTANT, "半截", AiMessageEntity.STATUS_INTERRUPTED, id = 3)
        )
        val messages = AiApiMessageBuilder.build("标题", "正文", history, "继续")

        assertEquals(4, messages.size)
        // 首条为裸输入，请求时补回笔记正文；其后消息保持原样
        assertTrue(messages[1].content.contains(AiPromptBuilder.NOTE_CONTEXT_HEADER))
        assertTrue(messages[1].content.endsWith("问"))
        assertEquals(listOf("半截", "继续"), messages.drop(2).map { it.content })
    }

    @Test
    fun blankNoteContentOnlySendsInput() {
        val messages = AiApiMessageBuilder.build("", "", emptyList(), "你好")

        assertEquals(2, messages.size)
        assertEquals(AiChatMessage.ROLE_SYSTEM, messages[0].role)
        assertEquals("你好", messages[1].content)
    }

    @Test
    fun inputIsTrimmed() {
        val messages = AiApiMessageBuilder.build("", "", emptyList(), "  你好  ")

        assertEquals("你好", messages[1].content)
    }

    @Test
    fun imageMarkupIsStrippedFromNoteContext() {
        val messages = AiApiMessageBuilder.build("标题", "看图 ![](img/a.png) 结束", emptyList(), "问")

        assertFalse(messages[1].content.contains("![]("))
        assertTrue(messages[1].content.contains("看图"))
    }

    @Test
    fun budgetConstantsAreFrozen() {
        assertEquals(20_000, AiApiMessageBuilder.MAX_NOTE_CHARS)
        assertEquals(60_000, AiApiMessageBuilder.MAX_HISTORY_CHARS)
    }

    @Test
    fun noteBodyOverBudgetIsTruncatedInFirstMessage() {
        val content = "x".repeat(AiApiMessageBuilder.MAX_NOTE_CHARS + 1)
        val messages = AiApiMessageBuilder.build("", content, emptyList(), "总结")

        assertEquals(2, messages.size)
        assertTrue(messages[1].content.contains("（正文过长，已截断）"))
        assertEquals(AiApiMessageBuilder.MAX_NOTE_CHARS, messages[1].content.count { it == 'x' })
    }

    @Test
    fun historyAtBudgetIsKeptIntact() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "u".repeat(20_000), id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "a".repeat(20_000), id = 2),
            message(AiMessageEntity.ROLE_USER, "v".repeat(20_000), id = 3)
        )
        val messages = AiApiMessageBuilder.build("标题", "正文", history, "继续")

        assertEquals(5, messages.size)
        // 首条是裸输入（历史里的落库形态），请求时补回笔记正文
        assertTrue(messages[1].content.contains(AiPromptBuilder.NOTE_CONTEXT_HEADER))
        assertTrue(messages[1].content.contains("正文"))
        assertEquals(listOf("a", "v", "继"), messages.drop(2).map { it.content.first().toString() })
    }

    @Test
    fun overBudgetByOneDropsSingleOldestMiddle() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "f".repeat(20_000), id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "m".repeat(20_000), id = 2),
            message(AiMessageEntity.ROLE_USER, "l".repeat(20_001), id = 3)
        )
        val messages = AiApiMessageBuilder.build("", "", history, "继续")

        assertEquals(4, messages.size)
        assertEquals(listOf("f", "l", "继"), messages.drop(1).map { it.content.first().toString() })
    }

    @Test
    fun overBudgetDropsOldestMiddleMessagesKeepingOrder() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "first".padEnd(10_000, 'f'), id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "m1".padEnd(30_000, '1'), id = 2),
            message(AiMessageEntity.ROLE_USER, "m2".padEnd(30_000, '2'), id = 3),
            message(AiMessageEntity.ROLE_ASSISTANT, "latest".padEnd(1_000, 'l'), id = 4)
        )
        val messages = AiApiMessageBuilder.build("", "", history, "继续")

        assertEquals(5, messages.size)
        assertEquals(listOf('f', 'm', 'l', '继'), messages.drop(1).map { it.content.first() })
        assertEquals("m2", messages[2].content.take(2))
        assertEquals("latest", messages[3].content.take(6))
    }

    @Test
    fun dropsOldestMiddleUntilBackWithinBudget() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "first".padEnd(30_000, 'f'), id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "m1".padEnd(30_000, '1'), id = 2),
            message(AiMessageEntity.ROLE_USER, "m2".padEnd(30_000, '2'), id = 3),
            message(AiMessageEntity.ROLE_ASSISTANT, "latest".padEnd(1_000, 'l'), id = 4)
        )
        val messages = AiApiMessageBuilder.build("", "", history, "继续")

        assertEquals(4, messages.size)
        assertEquals("first", messages[1].content.take(5))
        assertEquals("latest", messages[2].content.take(6))
        assertEquals("继续", messages[3].content)
        assertFalse(messages.any { it.content.startsWith("m") })
    }

    @Test
    fun firstAndLatestKeptEvenIfAloneOverBudget() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "f".repeat(50_000), id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "m".repeat(50_000), id = 2),
            message(AiMessageEntity.ROLE_USER, "l".repeat(50_000), id = 3)
        )
        val messages = AiApiMessageBuilder.build("", "", history, "继续")

        assertEquals(4, messages.size)
        assertEquals(listOf("f", "l", "继"), messages.drop(1).map { it.content.first().toString() })
        assertTrue(messages.none { it.content.first() == 'm' })
    }

    @Test
    fun twoMessageHistoryIsNeverDropped() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "f".repeat(40_000), id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "l".repeat(40_000), id = 2)
        )
        val messages = AiApiMessageBuilder.build("", "", history, "继续")

        assertEquals(4, messages.size)
        assertEquals(listOf("f", "l", "继"), messages.drop(1).map { it.content.first().toString() })
    }

    @Test
    fun blankHistoryContentIsFilteredBeforeBudget() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "问", id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "", AiMessageEntity.STATUS_FAILED, id = 2),
            message(AiMessageEntity.ROLE_ASSISTANT, "答", id = 3)
        )
        val messages = AiApiMessageBuilder.build("", "", history, "继续")

        assertEquals(4, messages.size)
        assertEquals(listOf("问", "答", "继续"), messages.drop(1).map { it.content })
    }

    /** 大窗口端点：预算精确等于现状 60_000/20_000（coerceAtMost 不生效）。 */
    @Test
    fun largeContextKeepsCurrentBudgets() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "u".repeat(20_000), id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "a".repeat(20_000), id = 2),
            message(AiMessageEntity.ROLE_USER, "v".repeat(20_000), id = 3)
        )
        val messages = AiApiMessageBuilder.build("标题", "正文", history, "继续", contextTokens = 1_000_000)

        assertEquals(5, messages.size)
        assertEquals(listOf("a", "v", "继"), messages.drop(2).map { it.content.first().toString() })
    }

    /** 32k 窗口：总预算 22_400 → 正文 11_200 / 历史 11_200。 */
    @Test
    fun tightContextShrinksNoteBudget() {
        val content = "x".repeat(12_000)
        val messages = AiApiMessageBuilder.build("", content, emptyList(), "总结", contextTokens = 32_000)

        assertTrue(messages[1].content.contains("（正文过长，已截断）"))
        assertEquals(11_200, messages[1].content.count { it == 'x' })
    }

    @Test
    fun tightContextShrinksHistoryBudget() {
        val history = listOf(
            message(AiMessageEntity.ROLE_USER, "f".repeat(5_000), id = 1),
            message(AiMessageEntity.ROLE_ASSISTANT, "m".repeat(5_000), id = 2),
            message(AiMessageEntity.ROLE_USER, "l".repeat(5_000), id = 3)
        )
        val messages = AiApiMessageBuilder.build("", "", history, "继续", contextTokens = 32_000)

        // 历史预算 11_200：15_000 超限，丢中间一条后 10_000 回到预算内
        assertEquals(4, messages.size)
        assertEquals(listOf("f", "l", "继续"), messages.drop(1).map { it.content })
    }

    /** minOf 下限：正文上限永远不会被预算放大（1M 与刚好 40_000 边界都不超过 MAX_NOTE_CHARS）。 */
    @Test
    fun noteBudgetNeverExceedsDefaultCap() {
        val content = "x".repeat(AiApiMessageBuilder.MAX_NOTE_CHARS + 1)

        val huge = AiApiMessageBuilder.build("", content, emptyList(), "总结", contextTokens = 1_000_000)
        assertEquals(AiApiMessageBuilder.MAX_NOTE_CHARS, huge[1].content.count { it == 'x' })

        // 57_143 × 0.7 = 40_000（整数除法），正文下限仍取 MAX_NOTE_CHARS
        val mid = AiApiMessageBuilder.build("", content, emptyList(), "总结", contextTokens = 57_143)
        assertEquals(AiApiMessageBuilder.MAX_NOTE_CHARS, mid[1].content.count { it == 'x' })
    }
}
```

- [ ] **Step 5: 运行组装层测试确认全绿**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.ai.AiApiMessageBuilderTest"`
Expected: PASS（21 个用例全绿）

- [ ] **Step 6: 提交**

```powershell
git add app/src/main/java/com/mynote/app/data/ai/AiChatMessage.kt app/src/main/java/com/mynote/app/data/ai/AiPromptBuilder.kt app/src/main/java/com/mynote/app/data/ai/AiApiMessageBuilder.kt app/src/test/java/com/mynote/app/data/ai/AiApiMessageBuilderTest.kt
git commit -m "feat: AI 请求组装补 system prompt 与动态上下文预算"
```

---

### Task 3: 超限最小化裁剪 `trimToCore`

**Files:**
- Modify: `app/src/main/java/com/mynote/app/data/ai/AiApiMessageBuilder.kt`（`build` 之后插入公开纯函数）
- Test: `app/src/test/java/com/mynote/app/data/ai/AiApiMessageBuilderTest.kt`（末尾追加）

- [ ] **Step 1: 在 `AiApiMessageBuilder` 的 `build` 之后、`trimHistory` 之前插入**

```kotlin
    /**
     * 超限自愈用的最小化裁剪：保留 `[system（若有）, 首条 user（含正文）, 最新一条]`，
     * 其余中间消息全丢；条数 ≤3（无可裁）时原样返回。
     * 仅用于请求层即时自救，不落库、不影响下一轮按全量历史重建。
     */
    fun trimToCore(messages: List<AiChatMessage>): List<AiChatMessage> {
        if (messages.size <= 3) return messages
        val system = messages.first().takeIf { it.role == AiChatMessage.ROLE_SYSTEM }
        val firstUser = messages.firstOrNull { it.role == AiChatMessage.ROLE_USER }
        val last = messages.last()
        val kept = ArrayList<AiChatMessage>(3)
        system?.let { kept += it }
        if (firstUser != null && firstUser !== last) kept += firstUser
        kept += last
        return kept
    }
```

- [ ] **Step 2: 在 `AiApiMessageBuilderTest` 末尾追加 3 个用例**

```kotlin
    @Test
    fun trimToCoreKeepsSystemFirstUserAndLatest() {
        val messages = listOf(
            AiChatMessage(AiChatMessage.ROLE_SYSTEM, "sys"),
            AiChatMessage(AiChatMessage.ROLE_USER, "首"),
            AiChatMessage(AiChatMessage.ROLE_ASSISTANT, "1"),
            AiChatMessage(AiChatMessage.ROLE_USER, "2"),
            AiChatMessage(AiChatMessage.ROLE_ASSISTANT, "3"),
            AiChatMessage(AiChatMessage.ROLE_USER, "尾")
        )

        val trimmed = AiApiMessageBuilder.trimToCore(messages)

        assertEquals(listOf("sys", "首", "尾"), trimmed.map { it.content })
        assertEquals(
            listOf(
                AiChatMessage.ROLE_SYSTEM,
                AiChatMessage.ROLE_USER,
                AiChatMessage.ROLE_USER
            ),
            trimmed.map { it.role }
        )
    }

    @Test
    fun trimToCoreReturnsSameWhenNoMiddle() {
        val messages = listOf(
            AiChatMessage(AiChatMessage.ROLE_SYSTEM, "sys"),
            AiChatMessage(AiChatMessage.ROLE_USER, "首"),
            AiChatMessage(AiChatMessage.ROLE_USER, "尾")
        )

        assertEquals(messages, AiApiMessageBuilder.trimToCore(messages))
    }

    @Test
    fun trimToCoreWithoutSystemKeepsFirstUserAndLatest() {
        val messages = listOf(
            AiChatMessage(AiChatMessage.ROLE_USER, "首"),
            AiChatMessage(AiChatMessage.ROLE_ASSISTANT, "1"),
            AiChatMessage(AiChatMessage.ROLE_ASSISTANT, "2"),
            AiChatMessage(AiChatMessage.ROLE_USER, "尾")
        )

        assertEquals(listOf("首", "尾"), AiApiMessageBuilder.trimToCore(messages).map { it.content })
    }
```

- [ ] **Step 3: 运行测试确认全绿**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.ai.AiApiMessageBuilderTest"`
Expected: PASS（24 个用例全绿）

- [ ] **Step 4: 提交**

```powershell
git add app/src/main/java/com/mynote/app/data/ai/AiApiMessageBuilder.kt app/src/test/java/com/mynote/app/data/ai/AiApiMessageBuilderTest.kt
git commit -m "feat: AI 组装新增超限最小化裁剪 trimToCore"
```

---

### Task 4: 端点上下文窗口贯通（Provider → Endpoint → Session → VM）

**Files:**
- Modify: `app/src/main/java/com/mynote/app/data/ai/AiProvider.kt`（枚举字段 + `endpoint()` 透传）
- Modify: `app/src/main/java/com/mynote/app/data/ai/AiSession.kt`（接口属性，默认 0）
- Modify: `app/src/main/java/com/mynote/app/data/ai/AiApiSession.kt`（override）
- Modify: `app/src/main/java/com/mynote/app/ui/ai/AiChatViewModel.kt`（两个调用点）
- Test: `AiProviderTest.kt`、`AiApiSessionTest.kt`、`AiChatViewModelTest.kt`

- [ ] **Step 1: `AiProvider` 增 `contextTokens` 字段与取值**

构造函数末尾（`modelHint` 之后）加：

```kotlin
    val modelHint: String,
    /** 端点上下文窗口（token，2026-10 核对）；0 = 未知，组装层按默认预算。 */
    val contextTokens: Int
```

三个枚举项末尾分别加（保持其余参数不动）：

```kotlin
        modelHint = "deepseek-flash 更快更省；deepseek-v4-pro 更强。测试连接成功后会更新为官方最新模型列表。",
        contextTokens = 1_000_000
```

```kotlin
        modelHint = "deepseek-ai/DeepSeek-V4-Flash 更快更省；Pro/ 前缀模型更强。测试连接成功后会更新为官方对话模型列表。",
        contextTokens = 1_048_576
```

```kotlin
        modelHint = "兼容 OpenAI 协议（/chat/completions + SSE）；地址需包含版本路径，如 https://example.com/v1。",
        contextTokens = 32_000
```

`endpoint()` 返回处透传：

```kotlin
        return AiEndpoint(
            serviceId = serviceId,
            displayName = displayName,
            baseUrl = url,
            thinkingStyle = thinkingStyle,
            balanceStyle = balanceStyle,
            modelsQuery = modelsQuery,
            contextTokens = contextTokens
        )
```

- [ ] **Step 2: `AiEndpoint` 增字段（默认 0，兼容既有构造）**

```kotlin
/** 运行时端点配置：请求地址、身份与协议样式，客户端与会话只依赖它。 */
data class AiEndpoint(
    val serviceId: String,
    val displayName: String,
    val baseUrl: String,
    val thinkingStyle: ThinkingStyle,
    val balanceStyle: BalanceStyle,
    val modelsQuery: String?,
    /** 端点上下文窗口（token）；0 = 未知，组装层回退默认预算。 */
    val contextTokens: Int = 0
)
```

- [ ] **Step 3: `AiSession` 接口增属性（默认实现，既有 Fake 不破）**

```kotlin
interface AiSession {
    val serviceId: String
    val displayName: String

    /** 端点上下文窗口（token）；0 = 未知，组装层回退默认预算。 */
    val contextTokens: Int get() = 0

    val events: SharedFlow<AiEvent>
    ...
```

- [ ] **Step 4: `AiApiSession` override**

在 `override val displayName: String = endpoint.displayName` 之后加：

```kotlin
    override val contextTokens: Int = endpoint.contextTokens
```

- [ ] **Step 5: `AiChatViewModel` 两个调用点传窗口**

（1）`retry()` 内：

```kotlin
        val messages = AiApiMessageBuilder.build(noteTitle, noteContent, history, input, session.contextTokens)
```

（2）`launchSendNow()` 内：

```kotlin
        val messages = AiApiMessageBuilder.build(noteTitle, noteContent, history, input, session.contextTokens)
```

- [ ] **Step 6: 测试更新**

（a）`AiProviderTest`：`deepSeekDefaults` / `siliconFlowDefaults` / `customEndpointUsesUserAddressAndTrimsTrailingSlash` 三个用例各加一行：

```kotlin
        assertEquals(1_000_000, endpoint.contextTokens)   // DeepSeek
        assertEquals(1_048_576, endpoint.contextTokens)   // 硅基流动
        assertEquals(32_000, endpoint.contextTokens)      // 自定义
```

（b）`AiApiSessionTest` 追加：

```kotlin
    @Test
    fun contextTokensComesFromEndpoint() = runTest {
        val deepSeek = AiApiSession(
            FakeStreamer(), AiProvider.DEEPSEEK.endpoint(), { "key" }, { "m" }, { false }, backgroundScope
        )
        val custom = AiApiSession(
            FakeStreamer(), AiProvider.CUSTOM.endpoint("https://example.com/v1"), { "key" }, { "m" }, { false }, backgroundScope
        )

        assertEquals(1_000_000, deepSeek.contextTokens)
        assertEquals(32_000, custom.contextTokens)
    }
```

（c）`AiChatViewModelTest`：`FakeAiSession` 增可覆写属性（类体首部）：

```kotlin
        override var contextTokens: Int = 0
```

并追加透传用例：

```kotlin
    @Test
    fun sessionContextTokensShapesRequestBudget() = runTest(dispatcher) {
        val longNote = "x".repeat(12_000)
        val noteId = noteRepo.saveNote(null, "标题", longNote, null, false, null)
        fake.contextTokens = 32_000
        val vm = createVm(noteId, content = longNote)

        vm.send("总结")
        vm.state.first { it.sending }

        // 32k 窗口 → 正文预算 11_200
        val sent = fake.sent.single()
        assertEquals(11_200, sent.last().content.count { it == 'x' })
    }
```

- [ ] **Step 7: 运行相关测试确认全绿**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.ai.AiProviderTest" --tests "com.mynote.app.data.ai.AiApiSessionTest" --tests "com.mynote.app.ui.ai.AiChatViewModelTest"`
Expected: PASS

- [ ] **Step 8: 提交**

```powershell
git add app/src/main/java/com/mynote/app/data/ai/AiProvider.kt app/src/main/java/com/mynote/app/data/ai/AiSession.kt app/src/main/java/com/mynote/app/data/ai/AiApiSession.kt app/src/main/java/com/mynote/app/ui/ai/AiChatViewModel.kt app/src/test/java/com/mynote/app/data/ai/AiProviderTest.kt app/src/test/java/com/mynote/app/data/ai/AiApiSessionTest.kt app/src/test/java/com/mynote/app/ui/ai/AiChatViewModelTest.kt
git commit -m "feat: AI 端点上下文窗口贯通至会话与 ViewModel"
```

---

### Task 5: 请求体补 `stream_options` + 参数剔除阶梯 + 连接循环

**Files:**
- Modify: `app/src/main/java/com/mynote/app/data/ai/AiApiClient.kt`
- Test: `app/src/test/java/com/mynote/app/data/ai/AiApiClientTest.kt`

- [ ] **Step 1: 重写 `stream()`（连接级自愈循环，本步先落「剔除 + 退避」，超限裁剪在 T6 加入）**

用以下实现整体替换现有 `stream()` 方法（读流部分不变）：

```kotlin
    override fun stream(
        apiKey: String,
        model: String,
        deepThinking: Boolean,
        messages: List<AiChatMessage>
    ): Flow<ApiStreamEvent> = flow {
        var includeStreamOptions = true
        var includeThinking = endpoint.thinkingStyle != ThinkingStyle.NONE
        var backedOff = false

        // 连接级自愈循环：每轮至多消耗一种机会（剔除字段 / 退避），总连接上限 = 1 首次 + 2 剔除 + 1 退避 = 4
        var picked: HttpStreamConnection? = null
        while (true) {
            val opened = openConnection(
                apiKey,
                requestBody(model, messages, deepThinking, includeThinking, includeStreamOptions)
            )
            if (opened == null) {
                emit(ApiStreamEvent.Error(NETWORK_ERROR))
                return@flow
            }

            val status = opened.statusCode
            val errorBody = opened.errorBody

            // 1. 参数被拒（422 无锚点；400 需未识别字段锚点）：按阶梯剔除可选字段
            if (status == 422 || (status == 400 && looksLikeUnsupportedField(errorBody))) {
                if (includeStreamOptions || includeThinking) {
                    runCatching { opened.close() }
                    if (includeStreamOptions) includeStreamOptions = false else includeThinking = false
                    continue
                }
            }

            // 2. 限流/服务器繁忙：尚未开始读流，安全丢弃旧连接后重试一次
            if ((status == 429 || status == 503) && !backedOff) {
                runCatching { opened.close() }
                delay(retryDelayMs)
                backedOff = true
                continue
            }

            picked = opened
            break
        }
        // 循环只在 picked 赋值后 break；!! 仅做类型收窄
        val connection = picked!!

        // 登记连接：stop() 时经 cancel() 从外部断开阻塞读（read 不响应协程取消）
        activeConnections += connection

        try {
            if (connection.statusCode !in 200..299) {
                emit(mapHttpError(connection.statusCode, connection.errorBody))
                return@flow
            }
            val stream = connection.stream
            if (stream == null) {
                emit(ApiStreamEvent.Error("网络错误：响应内容为空"))
                return@flow
            }

            val full = StringBuilder()
            var finishReason: String? = null
            var usage: AiUsage? = null
            var streamError: String? = null
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    if (!currentCoroutineContext().isActive) throw CancellationException()
                    val line = reader.readLine() ?: break
                    val frame = AiSseParser.parse(line) ?: continue
                    if (frame.error != null) {
                        streamError = frame.error
                        break
                    }
                    val text = frame.text
                    if (!text.isNullOrEmpty()) {
                        full.append(text)
                        emit(ApiStreamEvent.Chunk(text))
                    }
                    val reasoning = frame.reasoning
                    if (!reasoning.isNullOrEmpty()) {
                        emit(ApiStreamEvent.Reasoning(reasoning))
                    }
                    frame.finishReason?.let { finishReason = it }
                    // usage 取流中最后一次非空（可能出现在 choices 为空的末块上）
                    frame.usage?.let { usage = it }
                    if (frame.done) break
                }
            }
            if (streamError != null) {
                emit(ApiStreamEvent.Error(streamError!!))
            } else {
                emit(ApiStreamEvent.Finished(full.toString(), finishReason, usage))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (!currentCoroutineContext().isActive) throw CancellationException()
            // 已发出的 chunk 由上层保留；这里只报告失败原因
            emit(ApiStreamEvent.Error(NETWORK_ERROR))
        } finally {
            activeConnections -= connection
            runCatching { connection.close() }
        }
    }.flowOn(Dispatchers.IO)
```

- [ ] **Step 2: `requestBody` 增 `includeStreamOptions` 参数（无条件携带，剔除时置 false）**

```kotlin
    private fun requestBody(
        model: String,
        messages: List<AiChatMessage>,
        deepThinking: Boolean,
        includeThinking: Boolean,
        includeStreamOptions: Boolean
    ): ByteArray {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                messages.forEach { message ->
                    addJsonObject {
                        put("role", message.role)
                        put("content", message.content)
                    }
                }
            }
            put("stream", true)
            if (includeStreamOptions) {
                putJsonObject("stream_options") { put("include_usage", true) }
            }
            if (includeThinking) {
                when (endpoint.thinkingStyle) {
                    ThinkingStyle.DEEPSEEK_OBJECT ->
                        putJsonObject("thinking") {
                            put("type", if (deepThinking) THINKING_ENABLED else THINKING_DISABLED)
                        }
                    ThinkingStyle.BOOLEAN_ENABLE -> put("enable_thinking", deepThinking)
                    ThinkingStyle.NONE -> Unit
                }
            }
        }
        return body.toString().toByteArray(Charsets.UTF_8)
    }
```

同步更新 `requestBody` 上方 KDoc 最后一句为：`因重试时字段可能不同，每次请求都必须重新调用，不能复用旧字节。`

- [ ] **Step 3: 新增 `looksLikeUnsupportedField` 私有纯函数（放在 `detail()` 之前）**

```kotlin
    /** 400 错误体是否指向未识别的可选字段（422 天然视作参数被拒，无需锚点）。 */
    private fun looksLikeUnsupportedField(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val lower = body.lowercase()
        return lower.contains("stream_options") ||
            lower.contains("include_usage") ||
            lower.contains("unknown field") ||
            lower.contains("unrecognized") ||
            lower.contains("extra fields") ||
            lower.contains("未知参数") ||
            lower.contains("不支持的参数")
    }
```

- [ ] **Step 4: 更新 `AiApiClientTest`**

（a）`requestBodyCarriesModelMessagesAndThinkingFlag` 增 `stream_options` 断言（在 `stream` 断言之后）：

```kotlin
        assertEquals(
            true,
            root["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.content.toBoolean()
        )
```

（b）删除 `retryOn422WithoutThinkingThenSucceeds`，替换为：

```kotlin
    @Test
    fun retryOn422DropsStreamOptionsThenSucceeds() = runTest {
        val transport = FakeTransport(
            FakeConnection(
                statusCode = 422,
                errorBody = """{"error":{"message":"thinking is not supported"}}"""
            ),
            FakeConnection(
                body = sse(
                    """data: {"choices":[{"delta":{"content":"好"}}]}""",
                    """data: {"choices":[{"delta":{"content":""},"finish_reason":"stop"}]}"""
                )
            )
        )

        val events = client(transport, retryDelayMs = 0)
            .stream("key", "deepseek-flash", true, messages).toList()

        assertEquals("422 应剔除 stream_options 重试一次", 2, transport.calls)
        assertTrue("首次请求体应携带 stream_options", transport.bodies[0].contains("\"stream_options\""))
        assertFalse("第一级剔除 stream_options", transport.bodies[1].contains("\"stream_options\""))
        assertTrue("thinking 是功能性字段，第一级不剔除", transport.bodies[1].contains("\"thinking\""))
        assertEquals(
            listOf(
                ApiStreamEvent.Chunk("好"),
                ApiStreamEvent.Finished("好", "stop", null)
            ),
            events
        )
    }
```

（c）重写 `retryOn422DropsEnableThinkingForSiliconFlow` 为三段：

```kotlin
    @Test
    fun retryOn422DropsEnableThinkingForSiliconFlow() = runTest {
        val transport = FakeTransport(
            FakeConnection(statusCode = 422, errorBody = """{"message":"enable_thinking is not supported"}"""),
            FakeConnection(statusCode = 422, errorBody = """{"message":"enable_thinking is not supported"}"""),
            FakeConnection(body = sse("data: [DONE]"))
        )

        client(transport, endpoint = siliconFlow, retryDelayMs = 0)
            .stream("key", "m", true, messages).toList()

        assertEquals("两字段依次剔除：共 3 次连接", 3, transport.calls)
        assertTrue(transport.bodies[0].contains("enable_thinking"))
        assertFalse("第一级先剔 stream_options", transport.bodies[1].contains("\"stream_options\""))
        assertTrue("第一级后保留 enable_thinking", transport.bodies[1].contains("enable_thinking"))
        assertFalse("第二级剔除 enable_thinking", transport.bodies[2].contains("enable_thinking"))
    }
```

（d）重写 `422RetriedOnlyOnceThenMapsError`：

```kotlin
    @Test
    fun `422DropsBothFieldsThenMapsError`() = runTest {
        val transport = FakeTransport(
            FakeConnection(statusCode = 422, errorBody = """{"error":{"message":"thinking is not supported"}}"""),
            FakeConnection(statusCode = 422, errorBody = """{"error":{"message":"invalid model"}}"""),
            FakeConnection(statusCode = 422, errorBody = """{"error":{"message":"invalid model"}}""")
        )

        val events = client(transport, retryDelayMs = 0)
            .stream("key", "nope", false, messages).toList()

        assertEquals("两字段依次剔完：共 3 次连接", 3, transport.calls)
        assertFalse(transport.bodies[1].contains("\"stream_options\""))
        assertFalse("第二次重试体不再携带 thinking", transport.bodies[2].contains("\"thinking\""))
        assertEquals("请求被拒绝（参数错误）：invalid model", (events.single() as ApiStreamEvent.Error).message)
    }
```

（e）重写 `customEndpoint422DoesNotRetryWithoutThinking`：

```kotlin
    @Test
    fun customEndpoint422DropsStreamOptionsThenSucceeds() = runTest {
        val transport = FakeTransport(
            FakeConnection(statusCode = 422, errorBody = """{"message":"bad param"}"""),
            FakeConnection(body = sse("data: [DONE]"))
        )

        val events = client(transport, endpoint = custom, retryDelayMs = 0)
            .stream("key", "m", true, messages).toList()

        assertEquals("自定义端点现在会剔除 stream_options 重试一次", 2, transport.calls)
        assertFalse(transport.bodies[1].contains("\"stream_options\""))
        assertEquals(ApiStreamEvent.Finished("", null, null), events.single())
    }
```

（f）新增 3 个用例：

```kotlin
    @Test
    fun customEndpointBodyCarriesStreamOptions() = runTest {
        val transport = FakeTransport(FakeConnection(body = sse("data: [DONE]")))

        client(transport, endpoint = custom).stream("key", "my-model", false, messages).toList()

        assertTrue(transport.body!!.contains("\"stream_options\":{\"include_usage\":true}"))
    }

    @Test
    fun retryOn400UnknownStreamOptionsThenSucceeds() = runTest {
        val transport = FakeTransport(
            FakeConnection(
                statusCode = 400,
                errorBody = """{"error":{"message":"Unrecognized request argument supplied: stream_options"}}"""
            ),
            FakeConnection(body = sse("data: [DONE]"))
        )

        val events = client(transport, endpoint = custom, retryDelayMs = 0)
            .stream("key", "m", false, messages).toList()

        assertEquals("400 未识别字段也应剔除重试一次", 2, transport.calls)
        assertTrue(transport.bodies[0].contains("\"stream_options\""))
        assertFalse(transport.bodies[1].contains("\"stream_options\""))
        assertEquals(ApiStreamEvent.Finished("", null, null), events.single())
    }

    @Test
    fun fieldDropThenRateLimitStacksWithinConnectionBudget() = runTest {
        val transport = FakeTransport(
            FakeConnection(statusCode = 422, errorBody = """{"message":"bad param"}"""),
            FakeConnection(statusCode = 429, errorBody = """{"error":{"message":"rate limited"}}"""),
            FakeConnection(body = sse("data: [DONE]"))
        )

        val events = client(transport, endpoint = custom, retryDelayMs = 0)
            .stream("key", "m", false, messages).toList()

        assertEquals("剔除与退避允许有界叠加：共 3 次连接", 3, transport.calls)
        assertEquals(ApiStreamEvent.Finished("", null, null), events.single())
    }
```

- [ ] **Step 5: 运行客户端测试确认全绿**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.ai.AiApiClientTest"`
Expected: PASS（既有 429/503、probe、balance 等用例不受影响）

- [ ] **Step 6: 提交**

```powershell
git add app/src/main/java/com/mynote/app/data/ai/AiApiClient.kt app/src/test/java/com/mynote/app/data/ai/AiApiClientTest.kt
git commit -m "feat: AI 请求体补 include_usage 并扩展参数剔除阶梯"
```

---

### Task 6: 400/422 上下文超限自愈与专用文案

**Files:**
- Modify: `app/src/main/java/com/mynote/app/data/ai/AiApiClient.kt`
- Test: `app/src/test/java/com/mynote/app/data/ai/AiApiClientTest.kt`

- [ ] **Step 1: `stream()` 循环加入超限裁剪分支（在「剔除字段」分支之前）**

变量区改为：

```kotlin
        var includeStreamOptions = true
        var includeThinking = endpoint.thinkingStyle != ThinkingStyle.NONE
        var contextTrimmed = false
        var backedOff = false
        var currentMessages = messages

        // 连接级自愈循环：每轮至多消耗一种机会（剔除字段 / 裁剪 / 退避），
        // 总连接上限 = 1 首次 + 2 剔除 + 1 裁剪 + 1 退避 = 5
```

`openConnection` 改用 `currentMessages`：

```kotlin
                requestBody(model, currentMessages, deepThinking, includeThinking, includeStreamOptions)
```

在「1. 参数被拒」分支之前插入：

```kotlin
            // 1. 超限识别最优先（400/422 均可能是上下文超限）：裁剪中间历史后重试一次；
            //    无可裁时不再走其它自愈，直接收束到专用文案映射
            if ((status == 400 || status == 422) && isContextLengthError(errorBody) && !contextTrimmed) {
                val trimmed = AiApiMessageBuilder.trimToCore(currentMessages)
                if (trimmed.size != currentMessages.size) {
                    runCatching { opened.close() }
                    currentMessages = trimmed
                    contextTrimmed = true
                    continue
                }
                picked = opened
                break
            }
```

（原「1. 参数被拒」「2. 限流」注释序号顺延为 2、3。）

- [ ] **Step 2: 最终映射与专用文案**

`try` 内非 2xx 分支改为：

```kotlin
            if (connection.statusCode !in 200..299) {
                emit(mapStreamHttpError(connection.statusCode, connection.errorBody))
                return@flow
            }
```

在 `mapHttpError` 之后新增两个私有函数：

```kotlin
    /** 400/422 且命中上下文超限锚点：替换为可操作的专用文案（不拼接 detail）。 */
    private fun mapStreamHttpError(status: Int, body: String?): ApiStreamEvent.Error =
        if ((status == 400 || status == 422) && isContextLengthError(body)) {
            ApiStreamEvent.Error(CONTEXT_OVERFLOW_MESSAGE)
        } else {
            mapHttpError(status, body)
        }

    /** 错误体是否指向上下文超限（小写锚点匹配）。 */
    private fun isContextLengthError(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val lower = body.lowercase()
        if (lower.contains("context_length_exceeded")) return true
        if (lower.contains("maximum context length")) return true
        if (lower.contains("context length") && lower.contains("exceed")) return true
        if (lower.contains("reduce the length")) return true
        return lower.contains("上下文") && (lower.contains("超出") || lower.contains("超过"))
    }
```

companion object 增常量：

```kotlin
        const val CONTEXT_OVERFLOW_MESSAGE = "对话内容超出该模型的上下文上限，请缩短笔记正文或新开对话"
```

- [ ] **Step 3: 更新类 KDoc（描述新自愈语义）**

```kotlin
/**
 * OpenAI 兼容 Chat Completions 客户端（DeepSeek / 硅基流动 / 自定义地址）。
 * 协议差异（思考参数样式、余额样式、模型列表过滤）全部由 [AiEndpoint] 驱动。
 * 手写 SSE：零新增依赖；解析逻辑见 [AiSseParser]（纯函数）。
 * 连接级自愈（每类至多一次、总连接 ≤5，可叠加）：
 * 400/422 上下文超限 → 裁剪中间历史重试一次，仍超限给专用文案；
 * 422（或 400 未识别字段）→ 按 stream_options → thinking 顺序剔除字段重试；
 * 429/503 → 退避 [retryDelayMs] 后重试。
 */
```

- [ ] **Step 4: `AiApiClientTest` 追加 5 个超限用例（类属性区加预算错误体常量）**

在 `private val messages = ...` 之后加：

```kotlin
    private val contextOverflowBody =
        """{"error":{"code":"context_length_exceeded","message":"This model's maximum context length is 8192 tokens"}}"""
```

追加用例：

```kotlin
    @Test
    fun contextOverflowTrimsHistoryAndRetries() = runTest {
        val longMessages = listOf(
            AiChatMessage(AiChatMessage.ROLE_USER, "第一问"),
            AiChatMessage(AiChatMessage.ROLE_ASSISTANT, "第一答"),
            AiChatMessage(AiChatMessage.ROLE_USER, "第二问"),
            AiChatMessage(AiChatMessage.ROLE_ASSISTANT, "第二答"),
            AiChatMessage(AiChatMessage.ROLE_USER, "第三问")
        )
        val transport = FakeTransport(
            FakeConnection(statusCode = 400, errorBody = contextOverflowBody),
            FakeConnection(
                body = sse(
                    """data: {"choices":[{"delta":{"content":"好"}}]}""",
                    """data: {"choices":[{"delta":{"content":""},"finish_reason":"stop"}]}"""
                )
            )
        )

        val events = client(transport, endpoint = custom, retryDelayMs = 0)
            .stream("key", "m", false, longMessages).toList()

        assertEquals(2, transport.calls)
        val first = Json.parseToJsonElement(transport.bodies[0]).jsonObject["messages"]!!.jsonArray
        val second = Json.parseToJsonElement(transport.bodies[1]).jsonObject["messages"]!!.jsonArray
        assertEquals(5, first.size)
        assertEquals("仅保留首条 user 与最新一条", 2, second.size)
        assertEquals("第一问", second[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("第三问", second[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertTrue("裁剪不丢 stream_options", transport.bodies[1].contains("\"stream_options\""))
        assertEquals(
            listOf(ApiStreamEvent.Chunk("好"), ApiStreamEvent.Finished("好", "stop", null)),
            events
        )
    }

    @Test
    fun contextOverflowAfterTrimMapsDedicatedMessage() = runTest {
        val longMessages = listOf(
            AiChatMessage(AiChatMessage.ROLE_USER, "第一问"),
            AiChatMessage(AiChatMessage.ROLE_ASSISTANT, "第一答"),
            AiChatMessage(AiChatMessage.ROLE_USER, "第二问"),
            AiChatMessage(AiChatMessage.ROLE_ASSISTANT, "第二答"),
            AiChatMessage(AiChatMessage.ROLE_USER, "第三问")
        )
        val transport = FakeTransport(
            FakeConnection(statusCode = 400, errorBody = contextOverflowBody),
            FakeConnection(statusCode = 400, errorBody = contextOverflowBody)
        )

        val events = client(transport, endpoint = custom, retryDelayMs = 0)
            .stream("key", "m", false, longMessages).toList()

        assertEquals(2, transport.calls)
        assertEquals(
            "对话内容超出该模型的上下文上限，请缩短笔记正文或新开对话",
            (events.single() as ApiStreamEvent.Error).message
        )
    }

    @Test
    fun contextOverflowWithoutTrimmableHistoryMapsDedicatedMessage() = runTest {
        val short = listOf(
            AiChatMessage(AiChatMessage.ROLE_USER, "一"),
            AiChatMessage(AiChatMessage.ROLE_USER, "二")
        )

        for (status in listOf(400, 422)) {
            val transport = FakeTransport(FakeConnection(statusCode = status, errorBody = contextOverflowBody))

            val events = client(transport, endpoint = custom, retryDelayMs = 0)
                .stream("key", "m", false, short).toList()

            assertEquals("HTTP $status 无可裁时不再重试、也不走剔除阶梯", 1, transport.calls)
            assertEquals(
                "对话内容超出该模型的上下文上限，请缩短笔记正文或新开对话",
                (events.single() as ApiStreamEvent.Error).message
            )
        }
    }

    @Test
    fun contextOverflowOn422AlsoTrims() = runTest {
        val longMessages = listOf(
            AiChatMessage(AiChatMessage.ROLE_USER, "第一问"),
            AiChatMessage(AiChatMessage.ROLE_ASSISTANT, "第一答"),
            AiChatMessage(AiChatMessage.ROLE_USER, "第二问"),
            AiChatMessage(AiChatMessage.ROLE_ASSISTANT, "第二答"),
            AiChatMessage(AiChatMessage.ROLE_USER, "第三问")
        )
        val transport = FakeTransport(
            FakeConnection(
                statusCode = 422,
                errorBody = """{"error":{"message":"maximum context length exceeded"}}"""
            ),
            FakeConnection(body = sse("data: [DONE]"))
        )

        val events = client(transport, endpoint = custom, retryDelayMs = 0)
            .stream("key", "m", false, longMessages).toList()

        assertEquals("422 超限不应走剔除阶梯", 2, transport.calls)
        val second = Json.parseToJsonElement(transport.bodies[1]).jsonObject["messages"]!!.jsonArray
        assertEquals(2, second.size)
        assertTrue("超限裁剪不丢 stream_options", transport.bodies[1].contains("\"stream_options\""))
        assertEquals(ApiStreamEvent.Finished("", null, null), events.single())
    }

    @Test
    fun chineseContextOverflowBodyIsRecognized() = runTest {
        val longMessages = listOf(
            AiChatMessage(AiChatMessage.ROLE_USER, "第一问"),
            AiChatMessage(AiChatMessage.ROLE_ASSISTANT, "第一答"),
            AiChatMessage(AiChatMessage.ROLE_USER, "第二问"),
            AiChatMessage(AiChatMessage.ROLE_ASSISTANT, "第二答"),
            AiChatMessage(AiChatMessage.ROLE_USER, "第三问")
        )
        val transport = FakeTransport(
            FakeConnection(statusCode = 400, errorBody = """{"error":{"message":"上下文长度超出模型限制"}}"""),
            FakeConnection(body = sse("data: [DONE]"))
        )

        val events = client(transport, endpoint = custom, retryDelayMs = 0)
            .stream("key", "m", false, longMessages).toList()

        assertEquals(2, transport.calls)
        assertEquals(ApiStreamEvent.Finished("", null, null), events.single())
    }
```

- [ ] **Step 5: 运行客户端测试确认全绿**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.ai.AiApiClientTest"`
Expected: PASS（含既有非超限 400「invalid param」不触发自愈的用例）

- [ ] **Step 6: 提交**

```powershell
git add app/src/main/java/com/mynote/app/data/ai/AiApiClient.kt app/src/test/java/com/mynote/app/data/ai/AiApiClientTest.kt
git commit -m "feat: AI 上下文超限自动裁剪重试与专用文案"
```

---

### Task 7: 会话累计用量与文案（VM + UI）

**Files:**
- Create: `app/src/main/java/com/mynote/app/ui/ai/UsageFormat.kt`
- Create: `app/src/test/java/com/mynote/app/ui/ai/UsageFormatTest.kt`
- Modify: `app/src/main/java/com/mynote/app/ui/ai/AiChatViewModel.kt`
- Modify: `app/src/main/java/com/mynote/app/ui/ai/AiChatScreen.kt`
- Test: `app/src/test/java/com/mynote/app/ui/ai/AiChatViewModelTest.kt`

- [ ] **Step 1: 新建 `UsageFormat.kt`（纯函数，可直接单测）**

```kotlin
package com.mynote.app.ui.ai

import com.mynote.app.data.ai.AiUsage

/** 上次用量单行文案；cacheHitTokens > 0 时附缓存命中片段。 */
internal fun formatUsageLine(usage: AiUsage): String =
    if (usage.cacheHitTokens > 0) {
        "上次用量：输入 ${usage.promptTokens}（缓存命中 ${usage.cacheHitTokens}）/ 输出 ${usage.completionTokens} / 合计 ${usage.totalTokens} tokens"
    } else {
        "上次用量：输入 ${usage.promptTokens} / 输出 ${usage.completionTokens} / 合计 ${usage.totalTokens} tokens"
    }

/** 本会话累计用量单行文案；口径「本次进入会话以来」（内存态，进程结束清零）。 */
internal fun formatSessionUsageLine(usage: AiUsage): String =
    if (usage.cacheHitTokens > 0) {
        "本会话累计：输入 ${usage.promptTokens}（缓存命中 ${usage.cacheHitTokens}）/ 输出 ${usage.completionTokens} / 合计 ${usage.totalTokens} tokens"
    } else {
        "本会话累计：输入 ${usage.promptTokens} / 输出 ${usage.completionTokens} / 合计 ${usage.totalTokens} tokens"
    }
```

- [ ] **Step 2: 新建 `UsageFormatTest.kt`**

```kotlin
package com.mynote.app.ui.ai

import com.mynote.app.data.ai.AiUsage
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageFormatTest {

    @Test
    fun usageLineWithoutCacheHitOmitsCacheSegment() {
        assertEquals(
            "上次用量：输入 12 / 输出 34 / 合计 46 tokens",
            formatUsageLine(AiUsage(12, 34, 46))
        )
    }

    @Test
    fun usageLineWithCacheHitShowsCacheSegment() {
        assertEquals(
            "上次用量：输入 120（缓存命中 96）/ 输出 34 / 合计 154 tokens",
            formatUsageLine(AiUsage(120, 34, 154, 96))
        )
    }

    @Test
    fun sessionUsageLineWithoutCacheHitOmitsCacheSegment() {
        assertEquals(
            "本会话累计：输入 512 / 输出 96 / 合计 608 tokens",
            formatSessionUsageLine(AiUsage(512, 96, 608))
        )
    }

    @Test
    fun sessionUsageLineWithCacheHitShowsCacheSegment() {
        assertEquals(
            "本会话累计：输入 512（缓存命中 300）/ 输出 96 / 合计 608 tokens",
            formatSessionUsageLine(AiUsage(512, 96, 608, 300))
        )
    }
}
```

- [ ] **Step 3: `AiChatViewModel` 增 `sessionUsage` 状态**

`UiState` 中 `lastUsage` 之后加：

```kotlin
        val sessionUsage: AiUsage? = null,
```

`selectSession` 与 `newChat` 的 `copy(...)` 块中 `lastUsage = null,` 之后各加：

```kotlin
                sessionUsage = null,
```

`handleEvent` 的 `Done` 分支改为：

```kotlin
            is AiEvent.Done -> {
                if (!_state.value.sending) return
                _state.update { state ->
                    val accumulated = event.usage?.let { used ->
                        val previous = state.sessionUsage ?: AiUsage(0, 0, 0)
                        AiUsage(
                            promptTokens = previous.promptTokens + used.promptTokens,
                            completionTokens = previous.completionTokens + used.completionTokens,
                            totalTokens = previous.totalTokens + used.totalTokens,
                            cacheHitTokens = previous.cacheHitTokens + used.cacheHitTokens
                        )
                    } ?: state.sessionUsage
                    state.copy(lastUsage = event.usage, sessionUsage = accumulated)
                }
                finalizeAssistant(AiMessageEntity.STATUS_DONE, event.text)
            }
```

- [ ] **Step 4: `AiChatScreen` 调用点与 `UsageLine` 改两行**

调用点：

```kotlin
            if (state.lastUsage != null || state.sessionUsage != null) {
                UsageLine(state.lastUsage, state.sessionUsage)
            }
```

组件：

```kotlin
/** 输入区上方的用量提示：上次一轮 + 本会话累计（调用点保证至少一行非空）。 */
@Composable
private fun UsageLine(lastUsage: AiUsage?, sessionUsage: AiUsage?) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        lastUsage?.let {
            Text(
                formatUsageLine(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        sessionUsage?.let {
            Text(
                formatSessionUsageLine(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
```

- [ ] **Step 5: `AiChatViewModelTest` 更新与新增**

（a）`reasoningAccumulatesAndDoneRecordsUsage` 的在 `lastUsage` 断言后追加：

```kotlin
        assertEquals(usage, vm.state.value.sessionUsage)
```

（b）重命名并扩展 `newChatClearsLastUsage` → `newChatClearsLastAndSessionUsages`（在 `assertNull(vm.state.value.lastUsage)` 后追加）：

```kotlin
        assertNull(vm.state.value.sessionUsage)
```

（c）追加 3 个用例：

```kotlin
    @Test
    fun sessionUsageAccumulatesAcrossTurns() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)

        vm.send("问一")
        vm.state.first { it.sending }
        fake.emit(AiEvent.Done("答一", AiUsage(10, 20, 30, 5)))
        vm.state.first { !it.sending }

        vm.send("问二")
        vm.state.first { it.sending && fake.sent.size == 2 }
        fake.emit(AiEvent.Done("答二", AiUsage(4, 6, 10, 2)))
        vm.state.first { !it.sending }

        assertEquals(AiUsage(14, 26, 40, 7), vm.state.value.sessionUsage)
        assertEquals(AiUsage(4, 6, 10, 2), vm.state.value.lastUsage)
    }

    @Test
    fun doneWithoutUsageKeepsSessionUsage() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问一")
        vm.state.first { it.sending }
        fake.emit(AiEvent.Done("答一", AiUsage(10, 20, 30, 5)))
        vm.state.first { !it.sending }

        vm.send("问二")
        vm.state.first { it.sending && fake.sent.size == 2 }
        fake.emit(AiEvent.Done("答二"))
        vm.state.first { !it.sending && it.lastUsage == null }

        assertEquals("usage 缺失的轮次不改变累计", AiUsage(10, 20, 30, 5), vm.state.value.sessionUsage)
        assertNull(vm.state.value.lastUsage)
    }

    @Test
    fun selectSessionClearsSessionUsage() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("第一会话问")
        vm.state.first { it.sending }
        fake.emit(AiEvent.Done("答", AiUsage(10, 20, 30, 5)))
        vm.state.first { !it.sending }
        val firstSessionId = aiRepo.observeSessions(noteId).first().single().id
        assertEquals(AiUsage(10, 20, 30, 5), vm.state.value.sessionUsage)

        vm.newChat()
        vm.send("第二会话问")
        vm.state.first { it.sending && fake.sent.size == 2 }
        fake.emit(AiEvent.Done("答二", AiUsage(1, 2, 3)))
        vm.state.first { !it.sending }
        assertEquals(AiUsage(1, 2, 3), vm.state.value.sessionUsage)

        vm.selectSession(firstSessionId)
        assertNull(vm.state.value.sessionUsage)
        assertNull(vm.state.value.lastUsage)
    }
```

- [ ] **Step 6: 运行相关测试确认全绿**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.ai.UsageFormatTest" --tests "com.mynote.app.ui.ai.AiChatViewModelTest"`
Expected: PASS

- [ ] **Step 7: 提交**

```powershell
git add app/src/main/java/com/mynote/app/ui/ai/UsageFormat.kt app/src/test/java/com/mynote/app/ui/ai/UsageFormatTest.kt app/src/main/java/com/mynote/app/ui/ai/AiChatViewModel.kt app/src/main/java/com/mynote/app/ui/ai/AiChatScreen.kt app/src/test/java/com/mynote/app/ui/ai/AiChatViewModelTest.kt
git commit -m "feat: AI 会话累计用量显示"
```

---

### Task 8: 集成验证与收尾（含主树发布准备）

- [ ] **Step 1: worktree 全量验证**

Run: `.\gradlew :app:testDebugUnitTest`
Expected: 全绿（含既有 610 个 + 本批新增用例）

Run: `.\gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 主树合入（ff-only，保持线性）**

```powershell
git -C D:\desktop\myNote merge --ff-only ai-token-1015
git -C D:\desktop\myNote worktree remove .worktrees/ai-token-1015
git -C D:\desktop\myNote branch -d ai-token-1015
```

> 主树 `app/src/main/java/com/mynote/app/ui/ai/AiChatScreen.kt` 有在途未提交改动（流式滚动定位修复，与 UsageLine 区域不重叠）；如合并受阻，先与该改动确认归属再处理。

- [ ] **Step 3: 版本与文档同步（主树）**

- `app/build.gradle.kts`：`val appVersionName = "1.11.0"`、`val appVersionCode = 1_11_00`
- `README.md`：第 28 行版本行改 `1.11.0`；第 41 行单测数改为实测值；第 48 行产物名改 `MyNote-1.11.0-release.apk`
- `AGENTS.md`：单测数同步为实测值

- [ ] **Step 4: release 构建（主树）**

Run: `.\gradlew :app:assembleRelease`
Expected: BUILD SUCCESSFUL（产物 `app/build/outputs/apk/release/MyNote-1.11.0-release.apk`）

- [ ] **Step 5: 提交与发布（发布动作按用户明确要求执行）**

```powershell
git add app/build.gradle.kts README.md AGENTS.md
git commit -m "feat: AI Token 成本与用量体验优化（system prompt/缓存命中/超限自愈/会话累计）并发布 1.11.0"
```

发布（tag + GitHub release）按 AGENTS.md 流程在用户明确要求时执行：注解 tag `v1.11.0` → `gh release create`（累积式 changelog + APK SHA-256）。

- [ ] **Step 6: 回写本计划的修订记录（实施结果、测试计数、遇到的问题）**

## 修订记录

- 2026-10-15：初版。按设计文档（含评审修订）拆 8 个任务；T4/T7 串行（共享 `AiChatViewModel.kt`）；release 构建与发布按 AGENTS.md 留主树。
</content>
