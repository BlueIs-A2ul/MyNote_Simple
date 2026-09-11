package com.mynote.app.data.ai

import android.webkit.WebView
import kotlinx.coroutines.flow.SharedFlow

sealed interface AiWebEvent {
    data object PageReady : AiWebEvent
    data class LoginState(val loggedIn: Boolean) : AiWebEvent
    data class ChatId(val id: String) : AiWebEvent
    data class ReplyChunk(val text: String) : AiWebEvent
    data class ReplyDone(val text: String) : AiWebEvent
    data class ReplyError(val reason: String) : AiWebEvent
    data class PageError(val description: String) : AiWebEvent
}

/** WebView 层抽象：真实实现见 WebViewAiSession；测试用 Fake 替换。 */
interface AiWebSession {
    val events: SharedFlow<AiWebEvent>
    val driver: AiWebDriver

    fun attach(webView: WebView)
    fun openNewChat()
    fun openChat(remoteChatId: String)
    fun send(text: String)
    fun stop()
    fun release()
}
