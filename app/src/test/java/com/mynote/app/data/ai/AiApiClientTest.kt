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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiApiClientTest {

    private val deepSeek = AiProvider.DEEPSEEK.endpoint()
    private val siliconFlow = AiProvider.SILICON_FLOW.endpoint()
    private val custom = AiProvider.CUSTOM.endpoint("https://example.com/v1/")

    private fun client(
        transport: HttpStreamTransport,
        endpoint: AiEndpoint = deepSeek,
        retryDelayMs: Long = 1_000
    ) = AiApiClient(endpoint, transport, retryDelayMs)

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

        val events = client(transport).stream("key", "deepseek-flash", false, messages).toList()

        assertEquals(
            listOf(
                ApiStreamEvent.Chunk("Hello"),
                ApiStreamEvent.Chunk("!"),
                ApiStreamEvent.Finished("Hello!", "stop", null)
            ),
            events
        )
        assertEquals("POST", transport.method)
        assertEquals("https://api.deepseek.com/chat/completions", transport.url)
        assertEquals("Bearer key", transport.headers["Authorization"])
        assertEquals("text/event-stream", transport.headers["Accept"])
        assertEquals("identity", transport.headers["Accept-Encoding"])
    }

    @Test
    fun requestBodyCarriesModelMessagesAndThinkingFlag() = runTest {
        val transport = FakeTransport(FakeConnection(body = sse("data: [DONE]")))

        client(transport).stream("key", "deepseek-v4-pro", true, messages).toList()

        assertTrue("正常请求体应包含 thinking 键", transport.body!!.contains("\"thinking\""))
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

        client(transport).stream("key", "deepseek-flash", false, messages).toList()

        val root = Json.parseToJsonElement(transport.body!!).jsonObject
        assertEquals("disabled", root["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun siliconFlowUsesEnableThinkingField() = runTest {
        val transport = FakeTransport(FakeConnection(body = sse("data: [DONE]")))

        client(transport, endpoint = siliconFlow).stream("key", "deepseek-ai/DeepSeek-V4-Flash", true, messages).toList()

        assertEquals("https://api.siliconflow.cn/v1/chat/completions", transport.url)
        val body = transport.body!!
        assertTrue("硅基流动应发送 enable_thinking", body.contains("\"enable_thinking\":true"))
        assertFalse("硅基流动不应发送 thinking 对象", body.contains("\"thinking\""))
    }

    @Test
    fun siliconFlowDisablesThinkingExplicitly() = runTest {
        val transport = FakeTransport(FakeConnection(body = sse("data: [DONE]")))

        client(transport, endpoint = siliconFlow).stream("key", "m", false, messages).toList()

        assertTrue(transport.body!!.contains("\"enable_thinking\":false"))
    }

    @Test
    fun customEndpointOmitsThinkingAndNormalizesBaseUrl() = runTest {
        val transport = FakeTransport(FakeConnection(body = sse("data: [DONE]")))

        client(transport, endpoint = custom).stream("key", "my-model", true, messages).toList()

        assertEquals("https://example.com/v1/chat/completions", transport.url)
        val body = transport.body!!
        assertFalse(body.contains("\"thinking\""))
        assertFalse(body.contains("enable_thinking"))
    }

    @Test
    fun http401MapsToSettingsHint() = runTest {
        val transport = FakeTransport(
            FakeConnection(statusCode = 401, errorBody = """{"error":{"message":"Authentication Fails"}}""")
        )

        val events = client(transport).stream("bad", "deepseek-flash", false, messages).toList()

        val error = events.single() as ApiStreamEvent.Error
        assertEquals("API Key 无效，请到设置中检查", error.message)
        assertTrue(error.settingsHint)
    }

    @Test
    fun httpErrorsMapToChineseMessages() = runTest {
        val cases = mapOf(
            402 to "账户余额不足或无访问权限，请检查服务商账户",
            403 to "账户余额不足或无访问权限，请检查服务商账户",
            429 to "请求过于频繁，请稍后重试",
            500 to "服务商服务器繁忙，请稍后重试",
            503 to "服务商服务器繁忙，请稍后重试"
        )
        for ((status, expected) in cases) {
            val events = client(FakeTransport(FakeConnection(statusCode = status)), retryDelayMs = 0)
                .stream("key", "deepseek-flash", false, messages).toList()
            assertEquals("HTTP $status", expected, (events.single() as ApiStreamEvent.Error).message)
        }
    }

    @Test
    fun http400IncludesServerDetail() = runTest {
        val transport = FakeTransport(
            FakeConnection(statusCode = 422, errorBody = """{"error":{"message":"invalid model"}}""")
        )

        val events = client(transport).stream("key", "nope", false, messages).toList()

        assertEquals("请求被拒绝（参数错误）：invalid model", (events.single() as ApiStreamEvent.Error).message)
    }

    @Test
    fun http400DetailFallsBackToTopLevelMessage() = runTest {
        val transport = FakeTransport(
            FakeConnection(
                statusCode = 400,
                errorBody = """{"code":20012,"message":"invalid param","data":"x"}"""
            )
        )

        val events = client(transport, endpoint = siliconFlow).stream("key", "m", false, messages).toList()

        assertEquals("请求被拒绝（参数错误）：invalid param", (events.single() as ApiStreamEvent.Error).message)
    }

    @Test
    fun http400DetailFallsBackToPlainStringBody() = runTest {
        val transport = FakeTransport(
            FakeConnection(statusCode = 400, errorBody = "\"Bad Request\"")
        )

        val events = client(transport).stream("key", "m", false, messages).toList()

        assertEquals("请求被拒绝（参数错误）：Bad Request", (events.single() as ApiStreamEvent.Error).message)
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

        val events = client(transport).stream("key", "deepseek-flash", false, messages).toList()

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

        val events = client(transport).stream("key", "deepseek-flash", false, messages).toList()

        assertEquals(ApiStreamEvent.Finished("很长", "length", null), events.last())
    }

    @Test
    fun reasoningAndUsageAreStreamed() = runTest {
        val transport = FakeTransport(
            FakeConnection(
                body = sse(
                    """data: {"choices":[{"delta":{"reasoning_content":"想一"}}]}""",
                    """data: {"choices":[{"delta":{"reasoning_content":"想二"}}]}""",
                    """data: {"choices":[{"delta":{"content":"答案"}}]}""",
                    """data: {"choices":[{"delta":{"content":""},"finish_reason":"stop"}]}""",
                    """data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":4,"total_tokens":14}}""",
                    "data: [DONE]"
                )
            )
        )

        val events = client(transport).stream("key", "deepseek-v4-pro", true, messages).toList()

        assertEquals(
            listOf(
                ApiStreamEvent.Reasoning("想一"),
                ApiStreamEvent.Reasoning("想二"),
                ApiStreamEvent.Chunk("答案"),
                ApiStreamEvent.Finished("答案", "stop", AiUsage(10, 4, 14))
            ),
            events
        )
    }

    @Test
    fun usageTakesLastNonEmpty() = runTest {
        val transport = FakeTransport(
            FakeConnection(
                body = sse(
                    """data: {"choices":[{"delta":{"content":"a"}}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""",
                    """data: {"choices":[],"usage":{"prompt_tokens":9,"completion_tokens":3,"total_tokens":12}}""",
                    "data: [DONE]"
                )
            )
        )

        val events = client(transport).stream("key", "deepseek-flash", false, messages).toList()

        assertEquals(ApiStreamEvent.Finished("a", null, AiUsage(9, 3, 12)), events.last())
    }

    @Test
    fun ioFailureBeforeHeadersBecomesNetworkError() = runTest {
        var calls = 0
        val transport = HttpStreamTransport { _, _, _, _ ->
            calls++
            throw IOException("boom")
        }

        val events = client(transport, retryDelayMs = 0)
            .stream("key", "deepseek-flash", false, messages).toList()

        assertEquals("网络错误，请检查网络后重试", (events.single() as ApiStreamEvent.Error).message)
        assertEquals("连接未建立时不重试", 1, calls)
    }

    @Test
    fun connectionIsClosedAfterCompletion() = runTest {
        var closed = false
        val transport = FakeTransport(FakeConnection(body = sse("data: [DONE]")) { closed = true })

        client(transport).stream("key", "deepseek-flash", false, messages).toList()

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
        val client = client(
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
    fun retryOn429Or503ThenSucceeds() = runTest {
        for (status in listOf(429, 503)) {
            var firstClosed = false
            val first = FakeConnection(
                statusCode = status,
                errorBody = """{"error":{"message":"busy"}}""",
                onClose = { firstClosed = true }
            )
            val second = FakeConnection(
                body = sse(
                    """data: {"choices":[{"delta":{"content":"好"}}]}""",
                    """data: {"choices":[{"delta":{"content":""},"finish_reason":"stop"}]}"""
                )
            )
            val transport = FakeTransport(first, second)

            val events = client(transport, retryDelayMs = 0)
                .stream("key", "deepseek-flash", false, messages).toList()

            assertEquals("HTTP $status 应重试一次", 2, transport.calls)
            assertTrue("HTTP $status 首次连接应关闭", firstClosed)
            assertEquals(
                listOf(
                    ApiStreamEvent.Chunk("好"),
                    ApiStreamEvent.Finished("好", "stop", null)
                ),
                events
            )
        }
    }

    @Test
    fun twoRetryableFailuresStopAfterSingleRetry() = runTest {
        val transport = FakeTransport(
            FakeConnection(statusCode = 429, errorBody = """{"error":{"message":"rate limited"}}"""),
            FakeConnection(statusCode = 429)
        )

        val events = client(transport, retryDelayMs = 0)
            .stream("key", "deepseek-flash", false, messages).toList()

        assertEquals(2, transport.calls)
        assertEquals("请求过于频繁，请稍后重试", (events.single() as ApiStreamEvent.Error).message)
    }

    @Test
    fun retryOn422WithoutThinkingThenSucceeds() = runTest {
        val transport = FakeTransport(
            FakeConnection(
                statusCode = 422,
                errorBody = """{"error":{"message":"thinking is not supported"}}"""
            ),
            FakeConnection(
                body = sse(
                    """data: {"choices":[{"delta":{"content":"好"}}]}""",
                    """data: {"choices":[{"delta":{"content":""},"finish_reason":"stop"}]}"""
                )
            )
        )

        val events = client(transport, retryDelayMs = 0)
            .stream("key", "deepseek-flash", true, messages).toList()

        assertEquals("422 应去掉 thinking 重试一次", 2, transport.calls)
        assertTrue("首次请求体应携带 thinking", transport.bodies[0].contains("\"thinking\""))
        assertTrue("422 回退后请求体不应携带 thinking", !transport.bodies[1].contains("\"thinking\""))
        assertEquals(
            listOf(
                ApiStreamEvent.Chunk("好"),
                ApiStreamEvent.Finished("好", "stop", null)
            ),
            events
        )
    }

    @Test
    fun retryOn422DropsEnableThinkingForSiliconFlow() = runTest {
        val transport = FakeTransport(
            FakeConnection(statusCode = 422, errorBody = """{"message":"enable_thinking is not supported"}"""),
            FakeConnection(body = sse("data: [DONE]"))
        )

        client(transport, endpoint = siliconFlow, retryDelayMs = 0)
            .stream("key", "m", true, messages).toList()

        assertEquals(2, transport.calls)
        assertTrue(transport.bodies[0].contains("enable_thinking"))
        assertFalse(transport.bodies[1].contains("enable_thinking"))
    }

    @Test
    fun `422RetriedOnlyOnceThenMapsError`() = runTest {
        val transport = FakeTransport(
            FakeConnection(statusCode = 422, errorBody = """{"error":{"message":"thinking is not supported"}}"""),
            FakeConnection(statusCode = 422, errorBody = """{"error":{"message":"invalid model"}}""")
        )

        val events = client(transport, retryDelayMs = 0)
            .stream("key", "nope", false, messages).toList()

        assertEquals("422 只应重试一次", 2, transport.calls)
        assertTrue("重试请求体不应携带 thinking", !transport.bodies[1].contains("\"thinking\""))
        assertEquals("请求被拒绝（参数错误）：invalid model", (events.single() as ApiStreamEvent.Error).message)
    }

    @Test
    fun customEndpoint422DoesNotRetryWithoutThinking() = runTest {
        val transport = FakeTransport(
            FakeConnection(statusCode = 422, errorBody = """{"message":"bad param"}"""),
            FakeConnection(body = sse("data: [DONE]"))
        )

        val events = client(transport, endpoint = custom, retryDelayMs = 0)
            .stream("key", "m", true, messages).toList()

        assertEquals("无思考字段的端点 422 不应重试", 1, transport.calls)
        assertEquals("请求被拒绝（参数错误）：bad param", (events.single() as ApiStreamEvent.Error).message)
    }

    @Test
    fun probeParsesModelList() = runTest {
        val transport = FakeTransport(
            FakeConnection(
                body = """{"object":"list","data":[{"id":"deepseek-flash"},{"id":"deepseek-v4-pro"}]}"""
            )
        )

        val result = client(transport).probe("key")

        assertEquals(ProbeResult.Ok(listOf(DeepSeekModels.FLASH, DeepSeekModels.V4_PRO)), result)
        assertEquals("GET", transport.method)
        assertEquals("https://api.deepseek.com/models", transport.url)
        assertEquals("Bearer key", transport.headers["Authorization"])
    }

    @Test
    fun probeAppendsModelsQueryForSiliconFlow() = runTest {
        val transport = FakeTransport(FakeConnection(body = """{"data":[{"id":"a/b"}]}"""))

        val result = client(transport, endpoint = siliconFlow).probe("key")

        assertEquals("https://api.siliconflow.cn/v1/models?sub_type=chat", transport.url)
        assertEquals(ProbeResult.Ok(listOf("a/b")), result)
    }

    @Test
    fun probeReportsAuthFailure() = runTest {
        val transport = FakeTransport(FakeConnection(statusCode = 401))

        val result = client(transport).probe("bad")

        assertEquals(ProbeResult.Failed("API Key 无效，请到设置中检查"), result)
    }

    @Test
    fun probeRejectsMalformedBody() = runTest {
        val transport = FakeTransport(FakeConnection(body = "not-json"))

        val result = client(transport).probe("key")

        assertEquals(ProbeResult.Failed("返回数据无法解析"), result)
    }

    @Test
    fun probeNetworkFailureBecomesMessage() = runTest {
        val transport = HttpStreamTransport { _, _, _, _ -> throw IOException("boom") }

        val result = client(transport).probe("key")

        assertEquals(ProbeResult.Failed("网络错误，请检查网络后重试"), result)
    }

    @Test
    fun fetchBalanceParsesDeepSeekLines() = runTest {
        val transport = FakeTransport(
            FakeConnection(
                body = """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"110.00","granted_balance":"10.00","topped_up_balance":"100.00"}]}"""
            )
        )

        val result = client(transport).fetchBalance("key")

        assertEquals(
            BalanceState.Ok(true, listOf(BalanceLine("CNY", "110.00", "10.00", "100.00"))),
            result
        )
        assertEquals("GET", transport.method)
        assertEquals("https://api.deepseek.com/user/balance", transport.url)
    }

    @Test
    fun fetchBalanceToleratesMissingFields() = runTest {
        val unavailable = client(
            FakeTransport(FakeConnection(body = """{"is_available":false}"""))
        ).fetchBalance("key")
        assertEquals(BalanceState.Ok(false, emptyList()), unavailable)

        val partialLine = client(
            FakeTransport(FakeConnection(body = """{"is_available":true,"balance_infos":[{"currency":"USD"}]}"""))
        ).fetchBalance("key")
        assertEquals(BalanceState.Ok(true, listOf(BalanceLine("USD", "", "", ""))), partialLine)

        val noCurrency = client(
            FakeTransport(FakeConnection(body = """{"is_available":true,"balance_infos":[{"total_balance":"1.00"}]}"""))
        ).fetchBalance("key")
        assertEquals(BalanceState.Ok(true, emptyList()), noCurrency)
    }

    @Test
    fun fetchBalanceParsesSiliconFlowWrappedData() = runTest {
        val transport = FakeTransport(
            FakeConnection(
                body = """{"code":20000,"message":"OK","status":true,"data":{"balance":"7.40","chargeBalance":"15.25","totalBalance":"22.65"}}"""
            )
        )

        val result = client(transport, endpoint = siliconFlow).fetchBalance("key")

        assertEquals("https://api.siliconflow.cn/v1/user/info", transport.url)
        assertEquals(
            BalanceState.Ok(
                true,
                listOf(BalanceLine("CNY", "22.65", "7.40", "15.25", grantedLabel = "可用"))
            ),
            result
        )
    }

    @Test
    fun fetchBalanceParsesSiliconFlowTopLevelFields() = runTest {
        val transport = FakeTransport(
            FakeConnection(body = """{"balance":"0.00","chargeBalance":"1.00","totalBalance":"1.00"}""")
        )

        val result = client(transport, endpoint = siliconFlow).fetchBalance("key")

        assertEquals(
            BalanceState.Ok(
                false,
                listOf(BalanceLine("CNY", "1.00", "0.00", "1.00", grantedLabel = "可用"))
            ),
            result
        )
    }

    @Test
    fun fetchBalanceUnsupportedForCustomEndpoint() = runTest {
        var calls = 0
        val transport = HttpStreamTransport { _, _, _, _ ->
            calls++
            FakeConnection(body = "{}")
        }

        val result = client(transport, endpoint = custom).fetchBalance("key")

        assertEquals(BalanceState.Failed("当前服务商不支持余额查询"), result)
        assertEquals("不支持时不应发起请求", 0, calls)
    }

    @Test
    fun fetchBalanceReportsHttpFailure() = runTest {
        val transport = FakeTransport(FakeConnection(statusCode = 402))

        val result = client(transport).fetchBalance("key")

        assertEquals(BalanceState.Failed("账户余额不足或无访问权限，请检查服务商账户"), result)
    }

    @Test
    fun fetchBalanceNetworkFailureBecomesMessage() = runTest {
        val transport = HttpStreamTransport { _, _, _, _ -> throw IOException("boom") }

        val result = client(transport).fetchBalance("key")

        assertEquals(BalanceState.Failed("网络错误，请检查网络后重试"), result)
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

    private class FakeTransport(private val connections: List<HttpStreamConnection>) : HttpStreamTransport {

        constructor(vararg connections: HttpStreamConnection) : this(connections.toList())

        var calls = 0
            private set
        var method: String? = null
            private set
        var url: String? = null
            private set
        var headers: Map<String, String> = emptyMap()
            private set
        var body: String? = null
            private set

        /** 按调用顺序记录每次请求体（重试场景需要比较先后差异）。 */
        val bodies = mutableListOf<String>()

        override fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?
        ): HttpStreamConnection {
            val connection = connections[minOf(calls, connections.lastIndex)]
            calls++
            this.method = method
            this.url = url
            this.headers = headers
            this.body = body?.toString(Charsets.UTF_8)
            this.body?.let { bodies += it }
            return connection
        }
    }
}
