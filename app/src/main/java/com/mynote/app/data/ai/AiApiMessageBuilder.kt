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

    /** @param contextTokens 端点上下文窗口（token）；0 = 未知，回退默认预算（设计 §2.3）。 */
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
