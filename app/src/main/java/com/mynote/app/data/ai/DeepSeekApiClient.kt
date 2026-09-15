package com.mynote.app.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.coroutines.cancellation.CancellationException

/** 流式回答的底层事件（面向会话层；错误文案已是中文）。 */
sealed interface ApiStreamEvent {
    data class Chunk(val text: String) : ApiStreamEvent
    data class Reasoning(val text: String) : ApiStreamEvent
    data class Finished(val text: String, val finishReason: String?, val usage: AiUsage?) : ApiStreamEvent
    data class Error(val message: String, val settingsHint: Boolean = false) : ApiStreamEvent
}

/** 「测试连接」结果：成功带官方模型 id 列表。 */
sealed interface ProbeResult {
    data class Ok(val models: List<String>) : ProbeResult
    data class Failed(val message: String) : ProbeResult
}

/** 单个币种的余额行；金额保留接口原样字符串，避免浮点误差。 */
data class BalanceLine(val currency: String, val total: String, val granted: String, val toppedUp: String)

/** 「查询余额」结果。 */
sealed interface BalanceState {
    data class Ok(val isAvailable: Boolean, val lines: List<BalanceLine>) : BalanceState
    data class Failed(val message: String) : BalanceState
}

/** 可替换的流式实现，便于会话层测试；[cancel] 用于打断阻塞中的读取。 */
interface ChatStreamer {
    fun stream(
        apiKey: String,
        model: String,
        deepThinking: Boolean,
        messages: List<AiChatMessage>
    ): Flow<ApiStreamEvent>

    /** 断开所有进行中的流（HttpURLConnection 阻塞读不响应协程取消）。 */
    fun cancel()
}

/** HTTP 响应抽象：调用方负责 [close] 释放连接。 */
interface HttpStreamConnection : Closeable {
    val statusCode: Int
    val errorBody: String?
    val stream: InputStream?
}

/** HTTP 传输层抽象：测试注入假实现，生产走 [UrlConnectionTransport]。 */
fun interface HttpStreamTransport {
    fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?
    ): HttpStreamConnection
}

/**
 * DeepSeek Chat Completions 客户端（OpenAI 兼容协议）。
 * 手写 SSE：零新增依赖；解析逻辑见 [DeepSeekSseParser]（纯函数）。
 * 429/503 仅在连接已建立、尚未读流时重试一次（间隔 [retryDelayMs]，测试传 0）。
 */
class DeepSeekApiClient(
    private val transport: HttpStreamTransport = UrlConnectionTransport(),
    baseUrl: String = BASE_URL,
    private val retryDelayMs: Long = 1_000
) : ChatStreamer {

    private val baseUrl = baseUrl.trimEnd('/')

    private val activeConnections = CopyOnWriteArraySet<HttpStreamConnection>()

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override fun stream(
        apiKey: String,
        model: String,
        deepThinking: Boolean,
        messages: List<AiChatMessage>
    ): Flow<ApiStreamEvent> = flow {
        val body = json.encodeToString(
            ChatRequest(
                model = model,
                messages = messages.map { MessageDto(it.role, it.content) },
                stream = true,
                thinking = ThinkingDto(if (deepThinking) THINKING_ENABLED else THINKING_DISABLED)
            )
        ).toByteArray(Charsets.UTF_8)

        var connection = openConnection(apiKey, body)
        if (connection == null) {
            emit(ApiStreamEvent.Error(NETWORK_ERROR))
            return@flow
        }

        // 限流/服务器繁忙：尚未开始读流，安全丢弃旧连接后重试一次
        if (connection.statusCode == 429 || connection.statusCode == 503) {
            runCatching { connection.close() }
            delay(retryDelayMs)
            val retried = openConnection(apiKey, body)
            if (retried == null) {
                emit(ApiStreamEvent.Error(NETWORK_ERROR))
                return@flow
            }
            connection = retried
        }

        // 登记连接：stop() 时经 cancel() 从外部断开阻塞读（read 不响应协程取消）
        activeConnections += connection

        try {
            if (connection.statusCode !in 200..299) {
                emit(mapHttpError(connection.statusCode, connection.errorBody))
                return@flow
            }
            val stream = connection.stream
            if (stream == null) {
                emit(ApiStreamEvent.Error("网络错误：响应内容为空"))
                return@flow
            }

            val full = StringBuilder()
            var finishReason: String? = null
            var usage: AiUsage? = null
            var streamError: String? = null
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    if (!currentCoroutineContext().isActive) throw CancellationException()
                    val line = reader.readLine() ?: break
                    val frame = DeepSeekSseParser.parse(line) ?: continue
                    if (frame.error != null) {
                        streamError = frame.error
                        break
                    }
                    val text = frame.text
                    if (!text.isNullOrEmpty()) {
                        full.append(text)
                        emit(ApiStreamEvent.Chunk(text))
                    }
                    val reasoning = frame.reasoning
                    if (!reasoning.isNullOrEmpty()) {
                        emit(ApiStreamEvent.Reasoning(reasoning))
                    }
                    frame.finishReason?.let { finishReason = it }
                    // usage 取流中最后一次非空（可能出现在 choices 为空的末块上）
                    frame.usage?.let { usage = it }
                    if (frame.done) break
                }
            }
            if (streamError != null) {
                emit(ApiStreamEvent.Error(streamError!!))
            } else {
                emit(ApiStreamEvent.Finished(full.toString(), finishReason, usage))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (!currentCoroutineContext().isActive) throw CancellationException()
            // 已发出的 chunk 由上层保留；这里只报告失败原因
            emit(ApiStreamEvent.Error(NETWORK_ERROR))
        } finally {
            activeConnections -= connection
            runCatching { connection.close() }
        }
    }.flowOn(Dispatchers.IO)

    override fun cancel() {
        activeConnections.toList().forEach { runCatching { it.close() } }
    }

    /** 测试用：进行中的连接数。 */
    internal fun activeStreamCount(): Int = activeConnections.size

    /** 建立流式连接；IOException 转为 null（由调用方发错误事件）。 */
    private suspend fun openConnection(apiKey: String, body: ByteArray): HttpStreamConnection? {
        return try {
            transport.execute("POST", "$baseUrl/chat/completions", streamHeaders(apiKey), body)
        } catch (e: IOException) {
            if (!currentCoroutineContext().isActive) throw CancellationException()
            null
        }
    }

    /** 测试连接：GET /models；成功返回官方模型 id 列表。 */
    suspend fun probe(apiKey: String): ProbeResult = withContext(Dispatchers.IO) {
        try {
            val connection = transport.execute("GET", "$baseUrl/models", jsonHeaders(apiKey), null)
            try {
                if (connection.statusCode !in 200..299) {
                    ProbeResult.Failed(mapHttpError(connection.statusCode, connection.errorBody).message)
                } else {
                    val body = readBody(connection.stream)
                        ?: return@withContext ProbeResult.Failed(PARSE_ERROR)
                    parseModelIds(body)
                }
            } finally {
                runCatching { connection.close() }
            }
        } catch (e: IOException) {
            ProbeResult.Failed(NETWORK_ERROR)
        }
    }

    /** 查询余额：GET /user/balance；成功返回可用状态与各币种余额行。 */
    suspend fun fetchBalance(apiKey: String): BalanceState = withContext(Dispatchers.IO) {
        try {
            val connection = transport.execute("GET", "$baseUrl/user/balance", jsonHeaders(apiKey), null)
            try {
                if (connection.statusCode !in 200..299) {
                    BalanceState.Failed(mapHttpError(connection.statusCode, connection.errorBody).message)
                } else {
                    val body = readBody(connection.stream)
                        ?: return@withContext BalanceState.Failed(PARSE_ERROR)
                    parseBalance(body)
                }
            } finally {
                runCatching { connection.close() }
            }
        } catch (e: IOException) {
            BalanceState.Failed(NETWORK_ERROR)
        }
    }

    /** 读取响应体为文本；空流/读取失败返回 null。 */
    private fun readBody(stream: InputStream?): String? {
        if (stream == null) return null
        return runCatching {
            InputStreamReader(stream, Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
    }

    /** 解析 GET /models 响应体，抽取 data[].id；坏数据返回失败。 */
    private fun parseModelIds(body: String): ProbeResult {
        val models = runCatching {
            json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
                ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
        }.getOrNull() ?: return ProbeResult.Failed(PARSE_ERROR)
        return ProbeResult.Ok(models)
    }

    /** 解析 GET /user/balance 响应体；字段缺失按空值容错，坏 JSON 返回失败。 */
    private fun parseBalance(body: String): BalanceState = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val available = root["is_available"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val lines = root["balance_infos"]?.jsonArray?.mapNotNull { element ->
            val info = element.jsonObject
            val currency = info["currency"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            BalanceLine(
                currency = currency,
                total = info["total_balance"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                granted = info["granted_balance"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                toppedUp = info["topped_up_balance"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        }.orEmpty()
        BalanceState.Ok(available, lines)
    }.getOrElse { BalanceState.Failed(PARSE_ERROR) }

    private fun streamHeaders(apiKey: String) = mapOf(
        "Content-Type" to "application/json",
        "Accept" to "text/event-stream",
        "Accept-Encoding" to "identity",
        "Authorization" to "Bearer $apiKey"
    )

    private fun jsonHeaders(apiKey: String) = mapOf(
        "Accept" to "application/json",
        "Accept-Encoding" to "identity",
        "Authorization" to "Bearer $apiKey"
    )

    private fun mapHttpError(status: Int, body: String?): ApiStreamEvent.Error = when (status) {
        400, 422 -> ApiStreamEvent.Error("请求被拒绝（参数错误）" + detail(body))
        401 -> ApiStreamEvent.Error("API Key 无效，请到设置中检查", settingsHint = true)
        402 -> ApiStreamEvent.Error("账户余额不足，请前往 DeepSeek 平台充值")
        429 -> ApiStreamEvent.Error("请求过于频繁，请稍后重试")
        500, 503 -> ApiStreamEvent.Error("DeepSeek 服务器繁忙，请稍后重试")
        else -> ApiStreamEvent.Error("请求失败（HTTP $status）" + detail(body))
    }

    private fun detail(body: String?): String {
        if (body.isNullOrBlank()) return ""
        val message = runCatching {
            json.parseToJsonElement(body)
                .jsonObject["error"]?.jsonObject
                ?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return if (message.isNullOrBlank()) "" else "：$message"
    }

    @Serializable
    private data class MessageDto(val role: String, val content: String)

    @Serializable
    private data class ThinkingDto(val type: String)

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<MessageDto>,
        val stream: Boolean,
        val thinking: ThinkingDto
    )

    companion object {
        const val BASE_URL = "https://api.deepseek.com"
        private const val THINKING_ENABLED = "enabled"
        private const val THINKING_DISABLED = "disabled"
        private const val NETWORK_ERROR = "网络错误，请检查网络后重试"
        private const val PARSE_ERROR = "返回数据无法解析"
    }
}

/** 真实 HTTP 传输：连接 15s / 读 60s；响应流用完必须 [HttpStreamConnection.close]。 */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000
) : HttpStreamTransport {

    override fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?
    ): HttpStreamConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (body != null) {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
            }
        }
        if (body != null) {
            connection.outputStream.use { it.write(body) }
        }
        return UrlConnectionResponse(connection)
    }

    private class UrlConnectionResponse(private val connection: HttpURLConnection) : HttpStreamConnection {

        override val statusCode: Int
            get() = connection.responseCode

        override val errorBody: String?
            get() = runCatching {
                if (connection.responseCode in 200..299) null
                else connection.errorStream?.let { stream ->
                    InputStreamReader(stream, Charsets.UTF_8).use { it.readText() }
                }
            }.getOrNull()

        override val stream: InputStream?
            get() = runCatching { connection.inputStream }.getOrNull()

        override fun close() {
            runCatching { connection.disconnect() }
        }
    }
}
