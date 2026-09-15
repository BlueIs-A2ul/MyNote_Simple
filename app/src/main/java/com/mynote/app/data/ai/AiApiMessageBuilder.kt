package com.mynote.app.data.ai

import com.mynote.app.data.db.AiMessageEntity

/**
 * 把本地会话历史 + 本次输入组装成 API messages。
 * API 无服务端会话，每次请求都必须携带完整历史；笔记正文只进首条用户消息（后续历史里已包含）。
 */
object AiApiMessageBuilder {

    fun build(
        noteTitle: String,
        noteContent: String,
        history: List<AiMessageEntity>,
        input: String
    ): List<AiChatMessage> {
        val includeNoteContext = history.none { it.role == AiMessageEntity.ROLE_USER }
        val messages = ArrayList<AiChatMessage>(history.size + 1)
        for (message in history) {
            val content = message.content.trim()
            if (content.isEmpty()) continue
            val role = if (message.role == AiMessageEntity.ROLE_ASSISTANT) {
                AiChatMessage.ROLE_ASSISTANT
            } else {
                AiChatMessage.ROLE_USER
            }
            messages += AiChatMessage(role, content)
        }
        messages += AiChatMessage(
            AiChatMessage.ROLE_USER,
            AiPromptBuilder.build(noteTitle, noteContent, input, includeNoteContext)
        )
        return messages
    }
}
