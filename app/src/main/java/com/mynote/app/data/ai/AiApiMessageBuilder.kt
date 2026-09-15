package com.mynote.app.data.ai

import com.mynote.app.data.db.AiMessageEntity

/**
 * 把本地会话历史 + 本次输入组装成 API messages。
 * API 无服务端会话，每次请求都必须携带完整历史；笔记正文只进首条用户消息（后续历史里已包含）。
 * 为控制上下文体积，正文与历史均按预算裁剪（设计 §2.5）。
 */
object AiApiMessageBuilder {

    /** 笔记正文最多发送的字符数，超出部分由 [AiPromptBuilder] 截断并加提示。 */
    const val MAX_NOTE_CHARS = 20_000

    /** 历史消息 content 字符数总预算（不含首条与最新一条的保留约束、不含本次输入）。 */
    const val MAX_HISTORY_CHARS = 60_000

    fun build(
        noteTitle: String,
        noteContent: String,
        history: List<AiMessageEntity>,
        input: String
    ): List<AiChatMessage> {
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
        val messages = trimHistory(merged).toMutableList()
        messages += AiChatMessage(
            AiChatMessage.ROLE_USER,
            AiPromptBuilder.build(noteTitle, noteContent, input, includeNoteContext, MAX_NOTE_CHARS)
        )
        return messages
    }

    /**
     * 历史裁剪：首条（含笔记正文）与最新一条永远保留；其余消息从最旧的一条开始
     * 逐条丢弃（幸存消息保持原顺序），直到按 content.length 累加不超过 [MAX_HISTORY_CHARS]。
     * 即使首条 + 最新一条本身已超预算也不再裁剪。空内容消息在调用前已过滤。
     */
    private fun trimHistory(history: List<AiChatMessage>): List<AiChatMessage> {
        if (history.size <= 2) return history
        var total = history.sumOf { it.content.length }
        if (total <= MAX_HISTORY_CHARS) return history
        var firstMiddle = 1
        while (total > MAX_HISTORY_CHARS && firstMiddle <= history.size - 2) {
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
