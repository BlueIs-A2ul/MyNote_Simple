package com.mynote.app.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 解析单行 SSE（DeepSeek Chat Completions 流式响应）。
 * 返回 null 表示忽略该行（空行 / 注释 / 未知字段 / 坏 JSON / 既无正文思考也无 usage）。
 */
object DeepSeekSseParser {

    data class Frame(
        val text: String? = null,
        val reasoning: String? = null,
        val finishReason: String? = null,
        val done: Boolean = false,
        val error: String? = null,
        val usage: AiUsage? = null
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
            // usage 可能挂在 choices 为空的末块上，先于 choices 解析，不能整帧丢弃
            val usage = parseUsage(root)
            val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            val delta = choice?.get("delta")?.jsonObject
            val text = delta?.get("content")?.jsonPrimitive?.contentOrNull
            val reasoning = delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
            val finish = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull
            if (text == null && reasoning == null && finish == null && usage == null) {
                null
            } else {
                Frame(text = text, reasoning = reasoning, finishReason = finish, usage = usage)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 顶层 usage：整体缺失/为 null/非对象时返回 null；单个字段缺失或为 null 按 0 计。 */
    private fun parseUsage(root: JsonObject): AiUsage? {
        val usage = runCatching {
            root["usage"]?.takeIf { it !is JsonNull }?.jsonObject
        }.getOrNull() ?: return null
        fun token(name: String): Int? = runCatching {
            usage[name]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull
        }.getOrNull()
        val prompt = token("prompt_tokens")
        val completion = token("completion_tokens")
        val total = token("total_tokens")
        if (prompt == null && completion == null && total == null) return null
        return AiUsage(prompt ?: 0, completion ?: 0, total ?: 0)
    }

    private const val DATA_PREFIX = "data:"
    private const val DONE_PAYLOAD = "[DONE]"
}
