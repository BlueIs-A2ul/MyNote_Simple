package com.mynote.app.data.ai

/** OpenAI 兼容对话消息（role: user / assistant / system）；对端服务商由 [AiEndpoint] 决定。 */
data class AiChatMessage(val role: String, val content: String) {
    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}
