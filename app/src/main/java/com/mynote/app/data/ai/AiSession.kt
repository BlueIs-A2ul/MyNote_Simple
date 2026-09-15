package com.mynote.app.data.ai

import kotlinx.coroutines.flow.SharedFlow

/** 一次回答的 token 用量（接口顶层 usage）；字段缺失时由解析层按 0 容错。 */
data class AiUsage(val promptTokens: Int, val completionTokens: Int, val totalTokens: Int)

/** AI 回答流事件：增量片段 / 思考增量 / 完成（带全文与用量）/ 失败（面向用户的文案）。 */
sealed interface AiEvent {
    data class Chunk(val text: String) : AiEvent
    data class Reasoning(val text: String) : AiEvent
    data class Done(val text: String, val usage: AiUsage? = null) : AiEvent
    data class Failed(val reason: String, val settingsHint: Boolean = false) : AiEvent
}

/**
 * 单个 AI 会话接口：只负责把 messages 发出去并把回答流事件回传。
 * 历史组装、落库、看门狗都在 ViewModel；测试用 Fake 替换。
 */
interface AiSession {
    val serviceId: String
    val displayName: String
    val events: SharedFlow<AiEvent>

    fun send(messages: List<AiChatMessage>)
    fun stop()
    fun release()

    companion object {
        /** 未配置 Key 的统一文案（同时作为 settingsHint 判定锚点）。 */
        const val KEY_MISSING_REASON = "未配置 API Key，请在「设置 → AI 助手」填写"
    }
}
