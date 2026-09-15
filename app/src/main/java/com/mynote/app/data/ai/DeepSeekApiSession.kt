package com.mynote.app.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * DeepSeek API 会话：校验 Key → 流式执行 → 映射 [AiEvent]。
 * 历史组装与落库在 ViewModel；协程随 stop()/release() 取消（连接的断开由客户端负责）。
 */
class DeepSeekApiSession(
    private val streamer: ChatStreamer,
    private val credentials: () -> String?,
    private val model: () -> String,
    private val deepThinking: () -> Boolean,
    private val scope: CoroutineScope
) : AiSession {

    override val serviceId: String = SERVICE_ID
    override val displayName: String = "DeepSeek"

    private val _events = MutableSharedFlow<AiEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: SharedFlow<AiEvent> = _events

    private var job: Job? = null

    override fun send(messages: List<AiChatMessage>) {
        val apiKey = credentials()?.trim().orEmpty()
        if (apiKey.isEmpty()) {
            _events.tryEmit(AiEvent.Failed(AiSession.KEY_MISSING_REASON, settingsHint = true))
            return
        }
        if (messages.isEmpty()) return
        job?.cancel()
        job = scope.launch {
            streamer.stream(apiKey, model(), deepThinking(), messages)
                .catch { error ->
                    if (error is CancellationException) throw error
                    _events.emit(AiEvent.Failed("网络错误，请检查网络后重试"))
                }
                .collect { event ->
                    when (event) {
                        is ApiStreamEvent.Chunk -> _events.emit(AiEvent.Chunk(event.text))
                        is ApiStreamEvent.Reasoning -> _events.emit(AiEvent.Reasoning(event.text))
                        is ApiStreamEvent.Finished -> _events.emit(AiEvent.Done(event.text, event.usage))
                        is ApiStreamEvent.Error ->
                            _events.emit(AiEvent.Failed(event.message, event.settingsHint))
                    }
                }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        // 先取消协程再断连：阻塞读被 close 打断后不会再上报网络错误
        streamer.cancel()
    }

    override fun release() = stop()

    companion object {
        const val SERVICE_ID = "deepseek-api"
    }
}
