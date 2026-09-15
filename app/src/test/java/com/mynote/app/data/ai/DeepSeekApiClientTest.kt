package com.mynote.app.data.ai

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekApiClientTest {

    private val messages = listOf(
        AiChatMessage(AiChatMessage.ROLE_USER, "你好"),
        AiChatMessage(AiChatMessage.ROLE_ASSISTANT, "你好呀"),
        AiChatMessage(AiChatMessage.ROLE_USER, "再问一句")
    )

    @Test
    fun streamsChunksThenFinished() = runTest {
        val transport = FakeTransport(
            FakeConnection(
                body = sse(
                    """data: {"choices":[{"delta":{"content":"Hello"}}]}""",
                    ": keep-alive",
                    """data: {"choices":[{"delta":{"content":"!"}}]}""",
                    """data: {"choices":[{"delta":{"content":""},"finish_reason":"stop"}]}""",
                    "data: [DONE]"
                )
            )
        )

        val events = DeepSeekApiClient(transport).stream("key", "deepseek-flash", false, messages).toList()

        assertEquals(
            listOf(
                ApiStreamEvent.Chunk("Hello"),
                ApiStreamEvent.Chunk("!"),
                ApiStreamEvent.Finished("Hello!", "stop")
            ),
            events
        )
        assertEquals("POST", transport.method)
        assertEquals("https://api.deepseek.com/chat/completions", transport.url)
        assertEquals("Bearer key", transport.headers["Authorization"])
        assertEquals("text/event-stream", transport.headers["Accept"])
    }

    @Test
    fun requestBodyCarriesModelMessagesAndThinkingFlag() = runTest {
        val transport = FakeTransport(FakeConnection(body = sse("data: [DONE]")))

        DeepSeekApiClient(transport).stream("key", "deepseek-v4-pro", true, messages).toList()

        val root = Json.parseToJsonElement(transport.body!!).jsonObject
        assertEquals("deepseek-v4-pro", root["model"]!!.jsonPrimitive.content)
        assertEquals(true, root["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("enabled", root["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val sent = root["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), sent.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("再问一句", sent.last()["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun thinkingDisabledByDefault() = runTest {
        val transport = FakeTransport(FakeConnection(body = sse("data: [DONE]")))

        DeepSeekApiClient(transport).stream("key", "deepseek-flash", false, messages).toList()

        val root = Json.parseToJsonElement(transport.body!!).jsonObject
        assertEquals("disabled", root["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun http401MapsToSettingsHint() = runTest {
        val transport = FakeTransport(
            FakeConnection(statusCode = 401, errorBody = """{"error":{"message":"Authentication Fails"}}""")
        )

        val events = DeepSeekApiClient(transport).stream("bad", "deepseek-flash", false, messages).toList()

        val error = events.single() as ApiStreamEvent.Error
        assertEquals("API Key 无效，请到设置中检查", error.message)
        assertTrue(error.settingsHint)
    }

    @Test
    fun httpErrorsMapToChineseMessages() = runTest {
        val cases = mapOf(
            402 to "账户余额不足，请前往 DeepSeek 平台充值",
            429 to "请求过于频繁，请稍后重试",
            500 to "DeepSeek 服务器繁忙，请稍后重试",
            503 to "DeepSeek 服务器繁忙，请稍后重试"
        )
        for ((status, expected) in cases) {
            val events = DeepSeekApiClient(FakeTransport(FakeConnection(statusCode = status)))
                .stream("key", "deepseek-flash", false, messages).toList()
            assertEquals("HTTP $status", expected, (events.single() as ApiStreamEvent.Error).message)
        }
    }

    @Test
    fun http400IncludesServerDetail() = runTest {
        val transport = FakeTransport(
            FakeConnection(statusCode = 422, errorBody = """{"error":{"message":"invalid model"}}""")
        )

        val events = DeepSeekApiClient(transport).stream("key", "nope", false, messages).toList()

        assertEquals("请求被拒绝（参数错误）：invalid model", (events.single() as ApiStreamEvent.Error).message)
    }

    @Test
    fun midStreamErrorFrameBecomesError() = runTest {
        val transport = FakeTransport(
            FakeConnection(
                body = sse(
                    """data: {"choices":[{"delta":{"content":"半截"}}]}""",
                    """data: {"error":{"message":"quota exceeded"}}"""
                )
            )
        )

        val events = DeepSeekApiClient(transport).stream("key", "deepseek-flash", false, messages).toList()

        assertEquals(ApiStreamEvent.Chunk("半截"), events[0])
        assertEquals("quota exceeded", (events[1] as ApiStreamEvent.Error).message)
    }

    @Test
    fun lengthFinishReasonStillFinishes() = runTest {
        val transport = FakeTransport(
            FakeConnection(
                body = sse(
                    """data: {"choices":[{"delta":{"content":"很长"}}]}""",
                    """data: {"choices":[{"delta":{"content":""},"finish_reason":"length"}]}"""
                )
            )
        )

        val events = DeepSeekApiClient(transport).stream("key", "deepseek-flash", false, messages).toList()

        assertEquals(ApiStreamEvent.Finished("很长", "length"), events.last())
    }

    @Test
    fun ioFailureBeforeHeadersBecomesNetworkError() = runTest {
        val transport = HttpStreamTransport { _, _, _, _ -> throw IOException("boom") }

        val events = DeepSeekApiClient(transport).stream("key", "deepseek-flash", false, messages).toList()

        assertEquals("网络错误，请检查网络后重试", (events.single() as ApiStreamEvent.Error).message)
    }

    @Test
    fun connectionIsClosedAfterCompletion() = runTest {
        var closed = false
        val transport = FakeTransport(FakeConnection(body = sse("data: [DONE]")) { closed = true })

        DeepSeekApiClient(transport).stream("key", "deepseek-flash", false, messages).toList()

        assertTrue(closed)
    }

    @Test
    fun cancelClosesActiveConnection() = runTest {
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val blocking = PipedInputStream()
        val connection = object : HttpStreamConnection {
            override val statusCode = 200
            override val errorBody: String? = null
            override val stream: InputStream = blocking
            override fun close() {
                closed.countDown()
                runCatching { blocking.close() }
            }
        }
        val client = DeepSeekApiClient(
            HttpStreamTransport { _, _, _, _ ->
                opened.countDown()
                connection
            }
        )
        val job = launch(Dispatchers.IO) {
            client.stream("key", "deepseek-flash", false, messages).collect {}
        }

        assertTrue(opened.await(3, TimeUnit.SECONDS))
        val deadline = System.currentTimeMillis() + 3_000
        while (client.activeStreamCount() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        client.cancel()
        assertTrue(closed.await(3, TimeUnit.SECONDS))
        job.cancel()
    }

    @Test
    fun verifyApiKeyUsesGetModels() = runTest {
        val transport = FakeTransport(FakeConnection(body = """{"object":"list","data":[]}"""))

        val result = DeepSeekApiClient(transport).verifyApiKey("key")

        assertNull(result)
        assertEquals("GET", transport.method)
        assertEquals("https://api.deepseek.com/models", transport.url)
        assertEquals("Bearer key", transport.headers["Authorization"])
    }

    @Test
    fun verifyApiKeyReportsAuthFailure() = runTest {
        val transport = FakeTransport(FakeConnection(statusCode = 401))

        val result = DeepSeekApiClient(transport).verifyApiKey("bad")

        assertEquals("API Key 无效，请到设置中检查", result)
    }

    private fun sse(vararg lines: String): String = lines.joinToString("\n", postfix = "\n")

    private class FakeConnection(
        override val statusCode: Int = 200,
        body: String = "",
        override val errorBody: String? = null,
        private val onClose: (() -> Unit)? = null
    ) : HttpStreamConnection {
        private val bytes = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        override val stream: InputStream = bytes
        override fun close() {
            onClose?.invoke()
        }
    }

    private class FakeTransport(private val connection: HttpStreamConnection) : HttpStreamTransport {
        var method: String? = null
        var url: String? = null
        var headers: Map<String, String> = emptyMap()
        var body: String? = null

        override fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?
        ): HttpStreamConnection {
            this.method = method
            this.url = url
            this.headers = headers
            this.body = body?.toString(Charsets.UTF_8)
            return connection
        }
    }
}
