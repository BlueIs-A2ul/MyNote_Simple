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
 * 解析单行 SSE（OpenAI 兼容 Chat Completions 流式响应，DeepSeek / 硅基流动等适用）。
 * 返回 null 表示忽略该行（空行 / 注释 / 未知字段 / 坏 JSON / 既无正文思考也无 usage）。
 */
object AiSseParser {

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
        return parsePayload(trimmed.removePrefix(DATA_PREFIX).trim())
    }

    /**
     * 解析非流式响应体：部分服务商/网关忽略 stream:true，直接返回整段 Chat Completion JSON。
     * 只接受以左花括号开头的 JSON；其它内容（空体 / HTML 错误页等）返回 null。
     */
    fun parseBody(body: String): Frame? {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return null
        return parsePayload(trimmed)
    }

    /** 该行是否为 SSE 的 data: 行；客户端用它区分「流式响应」与「整段 JSON 响应体」。 */
    fun isDataLine(line: String): Boolean = line.trim().startsWith(DATA_PREFIX)

    private fun parsePayload(payload: String): Frame? {
        if (payload.isEmpty()) return null
        if (payload == DONE_PAYLOAD) return Frame(done = true)
        return try {
            parseRoot(json.parseToJsonElement(payload).jsonObject)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRoot(root: JsonObject): Frame? {
        root["error"]?.let { element ->
            val message = runCatching {
                element.jsonObject["message"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            return Frame(error = message ?: "服务返回错误")
        }
        // usage 可能挂在 choices 为空的末块上，先于 choices 解析，不能整帧丢弃
        val usage = parseUsage(root)
        val choice = runCatching {
            root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        }.getOrNull()
        val delta = runCatching { choice?.get("delta")?.jsonObject }.getOrNull()
        // 非流式响应把正文放在 message 里；个别网关会用单条 SSE 包整段 JSON，一并兼容
        val message = runCatching { choice?.get("message")?.jsonObject }.getOrNull()
        val text = runCatching {
            delta?.get("content")?.jsonPrimitive?.contentOrNull
                ?: message?.get("content")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val reasoning = runCatching {
            delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
                ?: message?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val finish = runCatching {
            choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return if (text == null && reasoning == null && finish == null && usage == null) {
            null
        } else {
            Frame(text = text, reasoning = reasoning, finishReason = finish, usage = usage)
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
        // 缓存命中：DeepSeek 风格字段优先，OpenAI 风格嵌套字段其次；均缺失按 0
        val cacheHit = token("prompt_cache_hit_tokens")
            ?: runCatching {
                usage["prompt_tokens_details"]?.takeIf { it !is JsonNull }?.jsonObject
                    ?.get("cached_tokens")?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull
            }.getOrNull()
            ?: 0
        return AiUsage(prompt ?: 0, completion ?: 0, total ?: 0, cacheHit)
    }

    private const val DATA_PREFIX = "data:"
    private const val DONE_PAYLOAD = "[DONE]"
}
