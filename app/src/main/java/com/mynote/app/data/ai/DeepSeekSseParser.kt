package com.mynote.app.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 解析单行 SSE（DeepSeek Chat Completions 流式响应）。
 * 返回 null 表示忽略该行（空行 / 注释 / 未知字段 / 坏 JSON / 空 choices）。
 */
object DeepSeekSseParser {

    data class Frame(
        val text: String? = null,
        val finishReason: String? = null,
        val done: Boolean = false,
        val error: String? = null
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(line: String): Frame? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return null
        if (!trimmed.startsWith(DATA_PREFIX)) return null
        val payload = trimmed.removePrefix(DATA_PREFIX).trim()
        if (payload.isEmpty()) return null
        if (payload == DONE_PAYLOAD) return Frame(done = true)

        return try {
            val root = json.parseToJsonElement(payload).jsonObject
            root["error"]?.let { element ->
                val message = element.jsonObject["message"]?.jsonPrimitive?.contentOrNull
                return Frame(error = message ?: "服务返回错误")
            }
            val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            // 思考模式的 reasoning_content 有意忽略：只取最终回答
            val text = choice["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
            if (text == null && finish == null) null else Frame(text = text, finishReason = finish)
        } catch (_: Exception) {
            null
        }
    }

    private const val DATA_PREFIX = "data:"
    private const val DONE_PAYLOAD = "[DONE]"
}
