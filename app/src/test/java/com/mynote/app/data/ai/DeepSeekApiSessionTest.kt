package com.mynote.app.data.ai

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeepSeekApiSessionTest {

    private class FakeStreamer : ChatStreamer {
        var result: Flow<ApiStreamEvent> = emptyFlow()
        val calls = mutableListOf<List<AiChatMessage>>()
        var lastApiKey: String? = null
        var lastModel: String? = null
        var lastDeepThinking: Boolean? = null
        var cancelCount = 0

        override fun stream(
            apiKey: String,
            model: String,
            deepThinking: Boolean,
            messages: List<AiChatMessage>
        ): Flow<ApiStreamEvent> {
            lastApiKey = apiKey
            lastModel = model
            lastDeepThinking = deepThinking
            calls += messages
            return result
        }

        override fun cancel() {
            cancelCount++
        }
    }

    private fun messages() = listOf(AiChatMessage(AiChatMessage.ROLE_USER, "你好"))

    @Test
    fun streamsEventsAndPassesSettings() = runTest {
        val streamer = FakeStreamer()
        val session = DeepSeekApiSession(
            streamer, { "  key-1  " }, { DeepSeekModels.V4_PRO }, { true }, backgroundScope
        )
        val received = mutableListOf<AiEvent>()
        backgroundScope.launch { session.events.collect { received += it } }
        runCurrent()

        streamer.result = flow {
            emit(ApiStreamEvent.Chunk("a"))
            emit(ApiStreamEvent.Chunk("b"))
            emit(ApiStreamEvent.Finished("ab", "stop"))
        }
        session.send(messages())
        runCurrent()

        assertEquals("key-1", streamer.lastApiKey)
        assertEquals(DeepSeekModels.V4_PRO, streamer.lastModel)
        assertEquals(true, streamer.lastDeepThinking)
        assertEquals(
            listOf(AiEvent.Chunk("a"), AiEvent.Chunk("b"), AiEvent.Done("ab")),
            received
        )
    }

    @Test
    fun missingKeyFailsWithoutCallingStreamer() = runTest {
        val streamer = FakeStreamer()
        val session = DeepSeekApiSession(
            streamer, { null }, { DeepSeekModels.FLASH }, { false }, backgroundScope
        )
        val received = mutableListOf<AiEvent>()
        backgroundScope.launch { session.events.collect { received += it } }
        runCurrent()

        session.send(messages())
        runCurrent()

        assertEquals(
            listOf(AiEvent.Failed(AiSession.KEY_MISSING_REASON, settingsHint = true)),
            received
        )
        assertTrue(streamer.calls.isEmpty())
    }

    @Test
    fun errorEventCarriesSettingsHint() = runTest {
        val streamer = FakeStreamer()
        val session = DeepSeekApiSession(
            streamer, { "key" }, { DeepSeekModels.FLASH }, { false }, backgroundScope
        )
        val received = mutableListOf<AiEvent>()
        backgroundScope.launch { session.events.collect { received += it } }
        runCurrent()

        streamer.result = flow { emit(ApiStreamEvent.Error("API Key 无效", settingsHint = true)) }
        session.send(messages())
        runCurrent()

        assertEquals(listOf(AiEvent.Failed("API Key 无效", settingsHint = true)), received)
    }

    @Test
    fun streamFailureBecomesNetworkError() = runTest {
        val streamer = FakeStreamer()
        val session = DeepSeekApiSession(
            streamer, { "key" }, { DeepSeekModels.FLASH }, { false }, backgroundScope
        )
        val received = mutableListOf<AiEvent>()
        backgroundScope.launch { session.events.collect { received += it } }
        runCurrent()

        streamer.result = flow { throw IOException("boom") }
        session.send(messages())
        runCurrent()

        assertEquals(listOf(AiEvent.Failed("网络错误，请检查网络后重试")), received)
    }

    @Test
    fun stopCancelsStreamingWithoutDone() = runTest {
        val streamer = FakeStreamer()
        val session = DeepSeekApiSession(
            streamer, { "key" }, { DeepSeekModels.FLASH }, { false }, backgroundScope
        )
        val received = mutableListOf<AiEvent>()
        backgroundScope.launch { session.events.collect { received += it } }
        runCurrent()

        streamer.result = flow {
            emit(ApiStreamEvent.Chunk("半截"))
            awaitCancellation()
        }
        session.send(messages())
        runCurrent()
        assertEquals(listOf(AiEvent.Chunk("半截")), received)

        session.stop()
        runCurrent()
        assertEquals(listOf(AiEvent.Chunk("半截")), received)
        assertEquals(1, streamer.cancelCount)
    }

    @Test
    fun emptyMessagesDoNotCallStreamer() = runTest {
        val streamer = FakeStreamer()
        val session = DeepSeekApiSession(
            streamer, { "key" }, { DeepSeekModels.FLASH }, { false }, backgroundScope
        )

        session.send(emptyList())
        runCurrent()

        assertTrue(streamer.calls.isEmpty())
    }
}
