package com.mynote.app.data.ai

/** DeepSeek API 对话消息（role: user / assistant）。 */
data class AiChatMessage(val role: String, val content: String) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}
