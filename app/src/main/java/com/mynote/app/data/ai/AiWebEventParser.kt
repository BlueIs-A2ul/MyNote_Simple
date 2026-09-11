package com.mynote.app.data.ai

import org.json.JSONObject

/** 解析 JS 桥发来的 JSON 事件；未知类型 / 坏 JSON 返回 null（忽略即可）。 */
object AiWebEventParser {

    fun parse(json: String): AiWebEvent? = try {
        val root = JSONObject(json)
        val payload = root.optJSONObject("payload") ?: JSONObject()
        when (root.getString("type")) {
            "loginState" -> AiWebEvent.LoginState(payload.optBoolean("loggedIn", false))
            "replyChunk" -> payload.stringOrNull("text")?.let { AiWebEvent.ReplyChunk(it) }
            "replyDone" -> AiWebEvent.ReplyDone(payload.stringOrNull("text") ?: "")
            "replyError", "sendFailed" -> AiWebEvent.ReplyError(payload.stringOrNull("reason") ?: "未知错误")
            "chatId" -> payload.stringOrNull("id")?.let { AiWebEvent.ChatId(it) }
            "pageReady" -> AiWebEvent.PageReady
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    /** 显式 JSON null / 缺失 / 空串统一视为无值，避免把 null 当字符串 "null"。 */
    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}
