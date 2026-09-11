package com.mynote.app.data.ai

import org.json.JSONObject

/** 解析 JS 桥发来的 JSON 事件；未知类型 / 坏 JSON 返回 null（忽略即可）。 */
object AiWebEventParser {

    fun parse(json: String): AiWebEvent? = try {
        val root = JSONObject(json)
        val payload = root.optJSONObject("payload") ?: JSONObject()
        when (root.getString("type")) {
            "loginState" -> AiWebEvent.LoginState(payload.optBoolean("loggedIn", false))
            "replyChunk" -> AiWebEvent.ReplyChunk(payload.optString("text"))
            "replyDone" -> AiWebEvent.ReplyDone(payload.optString("text"))
            "replyError", "sendFailed" -> AiWebEvent.ReplyError(payload.optString("reason", "未知错误"))
            "chatId" -> payload.optString("id").takeIf { it.isNotEmpty() }?.let { AiWebEvent.ChatId(it) }
            "pageReady" -> AiWebEvent.PageReady
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
