package com.mynote.app.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mynote.app.data.ai.AiApiMessageBuilder
import com.mynote.app.data.ai.AiPromptBuilder
import com.mynote.app.data.ai.AiChatRepository
import com.mynote.app.data.ai.AiEvent
import com.mynote.app.data.ai.AiSession
import com.mynote.app.data.ai.AiUsage
import com.mynote.app.data.db.AiMessageEntity
import com.mynote.app.data.db.AiSessionEntity
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.data.settings.AiDraftStore
import com.mynote.app.data.settings.AiSettingsStore
import com.mynote.app.data.settings.aiDraftKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AiChatViewModel(
    private val noteId: Long,
    private val noteTitle: String,
    private val noteContent: String,
    private val aiRepository: AiChatRepository,
    private val noteRepository: NoteRepository,
    private val settingsStore: AiSettingsStore,
    private val externalScope: CoroutineScope,
    // 条目 81：服务商可能在设置页被切换，会话需要能按当前服务商重建
    private var session: AiSession,
    private val draftStore: AiDraftStore,
    private val watchdogTimeoutMs: Long = WATCHDOG_TIMEOUT_MS,
    private val sessionFactory: (String) -> AiSession = { session }
) : ViewModel() {

    data class UiState(
        val sessions: List<AiSessionEntity> = emptyList(),
        val currentSessionId: Long? = null,
        val messages: List<AiMessageEntity> = emptyList(),
        val streamingText: String = "",
        val reasoningText: String = "",
        val sending: Boolean = false,
        val privacyAccepted: Boolean = false,
        val banner: String? = null,
        val apiKeyMissing: Boolean = false,
        val snackbar: String? = null,
        val lastUsage: AiUsage? = null,
        /** 本会话累计用量（内存态；切会话 / 新对话清空，进程结束清零）。 */
        val sessionUsage: AiUsage? = null,
        val draft: String = "",
        /** 当前服务商展示名（隐私弹窗/文案用），来自会话身份。 */
        val providerName: String = "",
        /** 进入会话时的模型 id（顶栏副标题展示；会话中改设置需重进生效）。 */
        val modelName: String = ""
    )

    private val _state = MutableStateFlow(
        UiState(
            privacyAccepted = settingsStore.isPrivacyAccepted(session.serviceId),
            apiKeyMissing = !settingsStore.hasApiKey(),
            banner = if (settingsStore.hasApiKey()) null else AiSession.KEY_MISSING_REASON,
            draft = draftStore.get(aiDraftKey(noteId, null)),
            providerName = session.displayName,
            modelName = settingsStore.model()
        )
    )
    val state: StateFlow<UiState> = _state

    private var messagesJob: Job? = null
    private var initialSelectionDone = false
    private var userStartedNewChat = false
    private var sendRequested = false
    private var watchdogJob: Job? = null
    private var eventsJob: Job? = null
    // 条目 85：流式分片的合并缓冲。长回答每片都整体重解析 Markdown 是 O(n²)，
    // 超过阈值后按 80ms 合并刷新；短回答仍逐片即时上屏（行为不变）。
    private val pendingChunk = StringBuilder()
    private var lastFlushAt = 0L

    init {
        viewModelScope.launch {
            aiRepository.observeSessions(noteId).collectLatest { sessions ->
                _state.update { it.copy(sessions = sessions) }
                if (!initialSelectionDone && !userStartedNewChat) {
                    initialSelectionDone = true
                    if (_state.value.currentSessionId == null) {
                        if (sessions.isNotEmpty()) selectSession(sessions.first().id)
                        else observeMessages(null)
                    }
                }
            }
        }
        eventsJob = viewModelScope.launch {
            session.events.collect { handleEvent(it) }
        }
    }

    /**
     * 条目 81：设置页切服务商后，旧会话的 endpoint / key / model 仍是闭包捕获的旧值，
     * 继续发送会把请求发给旧服务商（账单与界面都不符）。发请求前对齐当前服务商。
     */
    private fun ensureSessionMatchesProvider() {
        val current = settingsStore.provider()
        if (session.serviceId == current.serviceId) return
        eventsJob?.cancel()
        session.stop()
        session = sessionFactory(current.serviceId)
        val accepted = settingsStore.isPrivacyAccepted(session.serviceId)
        val hasKey = settingsStore.hasApiKey()
        _state.update {
            it.copy(
                providerName = session.displayName,
                modelName = settingsStore.model(),
                privacyAccepted = accepted,
                apiKeyMissing = !hasKey,
                banner = if (hasKey) null else AiSession.KEY_MISSING_REASON
            )
        }
        eventsJob = viewModelScope.launch {
            session.events.collect { handleEvent(it) }
        }
    }

    /** 切换会话：旧流按半截收尾，清空思考/用量，并加载目标会话的草稿。 */
    fun selectSession(sessionId: Long) {
        if (sessionId == _state.value.currentSessionId) return
        val target = _state.value.sessions.firstOrNull { it.id == sessionId } ?: return
        if (_state.value.sending) {
            this.session.stop()
            finalizeAssistant(AiMessageEntity.STATUS_INTERRUPTED)
        }
        userStartedNewChat = false
        _state.update {
            it.copy(
                currentSessionId = sessionId,
                streamingText = "",
                reasoningText = "",
                banner = null,
                lastUsage = null,
                sessionUsage = null,
                draft = draftStore.get(aiDraftKey(noteId, sessionId))
            )
        }
        observeMessages(sessionId)
    }

    /** 开启新对话：清空思考/用量，并加载新对话草稿（草稿键的 sessionId 为 0）。 */
    fun newChat() {
        if (_state.value.sending) {
            session.stop()
            finalizeAssistant(AiMessageEntity.STATUS_INTERRUPTED)
        }
        userStartedNewChat = true
        _state.update {
            it.copy(
                currentSessionId = null,
                streamingText = "",
                reasoningText = "",
                banner = null,
                lastUsage = null,
                sessionUsage = null,
                draft = draftStore.get(aiDraftKey(noteId, null))
            )
        }
        observeMessages(null)
    }

    /** 返回是否真正进入发送流程；被拒（空输入 / 生成中 / 重复请求 / 未接受隐私 / 未配置 Key）返回 false。 */
    fun send(rawInput: String): Boolean {
        ensureSessionMatchesProvider()
        val input = rawInput.trim()
        val snapshot = _state.value
        if (input.isEmpty() || snapshot.sending || sendRequested || !snapshot.privacyAccepted) return false
        if (!settingsStore.hasApiKey()) {
            _state.update {
                it.copy(banner = AiSession.KEY_MISSING_REASON, apiKeyMissing = true)
            }
            return false
        }
        // 条目 88：自定义服务商可能没有默认模型，留空会发出 "model": "" 被服务商 400
        if (settingsStore.model().isBlank()) {
            _state.update { it.copy(banner = MODEL_MISSING_REASON) }
            return false
        }
        sendRequested = true
        val sessionId = snapshot.currentSessionId
        if (sessionId != null) {
            viewModelScope.launch {
                try {
                    launchSendNow(sessionId, input)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    resetSendStateAfterFailure()
                }
            }
        } else {
            viewModelScope.launch {
                try {
                    val now = System.currentTimeMillis()
                    val id = aiRepository.createSession(noteId, session.serviceId, titleFor(input), now)
                    // 新对话草稿键为 "<noteId>:0"，会话创建后清掉，再由 launchSendNow 清新会话键
                    clearDraft(null)
                    _state.update { it.copy(currentSessionId = id) }
                    observeMessages(id)
                    launchSendNow(id, input)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    resetSendStateAfterFailure()
                }
            }
        }
        return true
    }

    /**
     * 重发最后一条用户消息：历史只取其之前的消息（不含该条与其后的回答），
     * 不重复插入 user、不重复落库；被拒（生成中 / 无 user 消息 / 无会话 / 未接受隐私 / 未配置 Key）返回 false。
     */
    fun retry(): Boolean {
        ensureSessionMatchesProvider()
        val snapshot = _state.value
        if (snapshot.sending || sendRequested) return false
        if (!snapshot.privacyAccepted) return false
        if (!settingsStore.hasApiKey()) {
            _state.update {
                it.copy(banner = AiSession.KEY_MISSING_REASON, apiKeyMissing = true)
            }
            return false
        }
        if (settingsStore.model().isBlank()) {
            _state.update { it.copy(banner = MODEL_MISSING_REASON) }
            return false
        }
        val lastUserIndex = snapshot.messages.indexOfLast { it.role == AiMessageEntity.ROLE_USER }
        if (lastUserIndex < 0) return false
        val sessionId = snapshot.currentSessionId ?: return false
        val history = snapshot.messages.subList(0, lastUserIndex)
        val input = snapshot.messages[lastUserIndex].content
        // 与首次发送同一路径：首条用户消息仍会带上笔记正文快照
        val messages = AiApiMessageBuilder.build(
            noteTitle, noteContent, history, input, session.contextTokens,
            systemPrompt = AiPromptBuilder.resolveSystemPrompt(settingsStore.customSystemPrompt())
        )
        _state.update {
            it.copy(sending = true, streamingText = "", reasoningText = "", banner = null)
        }
        session.send(messages)
        resetWatchdog()
        return true
    }

    /** 更新输入框草稿：同步内存态与当前会话（新对话为键 0）的持久化草稿。 */
    fun updateDraft(text: String) {
        _state.update { it.copy(draft = text) }
        draftStore.set(aiDraftKey(noteId, _state.value.currentSessionId), text)
    }

    fun stop() {
        if (!_state.value.sending) return
        session.stop()
        finalizeAssistant(AiMessageEntity.STATUS_INTERRUPTED)
    }

    fun deleteSession(sessionId: Long) {
        if (_state.value.sending && sessionId == _state.value.currentSessionId) return
        viewModelScope.launch {
            // 条目 88：删会话时一并清掉该会话的输入草稿，否则 ai_drafts 会随删笔记无限累积
            draftStore.set(aiDraftKey(noteId, sessionId), "")
            aiRepository.deleteSession(sessionId)
            if (_state.value.currentSessionId == sessionId) {
                newChat()
            }
        }
    }

    fun acceptPrivacy() {
        settingsStore.acceptPrivacy(session.serviceId)
        _state.update { it.copy(privacyAccepted = true) }
    }

    fun saveAsNote(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            noteRepository.saveNote(null, titleFor(text), text, null, false, null)
            _state.update { it.copy(snackbar = "已存为新笔记") }
        }
    }

    fun consumeSnackbar() {
        _state.update { it.copy(snackbar = null) }
    }

    override fun onCleared() {
        super.onCleared()
        flushChunks()
        val snapshot = _state.value
        val sessionId = snapshot.currentSessionId
        if (snapshot.sending && sessionId != null) {
            val text = snapshot.streamingText
            val status = if (text.isBlank()) AiMessageEntity.STATUS_FAILED
            else AiMessageEntity.STATUS_INTERRUPTED
            externalScope.launch {
                val now = System.currentTimeMillis()
                aiRepository.appendMessage(
                    sessionId, AiMessageEntity.ROLE_ASSISTANT, text,
                    status, now
                )
                aiRepository.touch(sessionId, now)
            }
        }
        session.release()
    }

    private suspend fun launchSendNow(sessionId: Long, input: String) {
        // API 无服务端会话：每次请求都携带本地历史；笔记正文只进首条用户消息
        val history = aiRepository.getMessages(sessionId)
        val messages = AiApiMessageBuilder.build(
            noteTitle, noteContent, history, input, session.contextTokens,
            systemPrompt = AiPromptBuilder.resolveSystemPrompt(settingsStore.customSystemPrompt())
        )
        val now = System.currentTimeMillis()
        aiRepository.appendMessage(sessionId, AiMessageEntity.ROLE_USER, input, AiMessageEntity.STATUS_DONE, now)
        clearDraft(sessionId)
        _state.update { it.copy(sending = true, streamingText = "", reasoningText = "", banner = null) }
        sendRequested = false
        session.send(messages)
        resetWatchdog()
    }

    /** 进入流式后清空草稿（内存态与持久化同步清），避免切走再回来重复带出已发送内容。 */
    private fun clearDraft(sessionId: Long?) {
        draftStore.set(aiDraftKey(noteId, sessionId), "")
        _state.update { it.copy(draft = "") }
    }

    /**
     * 空闲看门狗：Chunk / Reasoning 每来一次就 cancel + 重启计时，
     * 语义是「距上一个流事件超过阈值」才按半截/失败收尾；Done / Failed / 停止会取消它。
     */
    private fun resetWatchdog() {
        if (watchdogTimeoutMs <= 0L) return
        watchdogJob?.cancel()
        watchdogJob = viewModelScope.launch {
            delay(watchdogTimeoutMs)
            if (!_state.value.sending) return@launch
            val partial = _state.value.streamingText
            // 条目 82：超时必须真正断流。只收尾界面的话，旧连接会继续跑到服务端生成完，
            // token 照常计费后整段丢弃；用户点「重试」也不会打断它。
            session.stop()
            finalizeAssistant(
                if (partial.isBlank()) AiMessageEntity.STATUS_FAILED
                else AiMessageEntity.STATUS_INTERRUPTED
            )
            _state.update { it.copy(banner = "回答超时，请重试") }
        }
    }

    /** 发送协程内部异常时复位发送态：sendRequested 不复位会让后续发送被永久拒绝。 */
    private fun resetSendStateAfterFailure() {
        watchdogJob?.cancel()
        sendRequested = false
        _state.update {
            it.copy(
                sending = false,
                streamingText = "",
                reasoningText = "",
                banner = "发送失败，请重试"
            )
        }
    }

    private fun observeMessages(sessionId: Long?) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            if (sessionId == null) {
                _state.update { it.copy(messages = emptyList()) }
            } else {
                aiRepository.observeMessages(sessionId).collectLatest { messages ->
                    _state.update { it.copy(messages = messages) }
                }
            }
        }
    }

    private fun handleEvent(event: AiEvent) {
        when (event) {
            is AiEvent.Chunk -> {
                if (!_state.value.sending) return
                // Chunk 是增量分片（服务端 delta），必须追加；全文另有 Done 事件携带
                accumulateChunk(event.text)
                resetWatchdog()
            }
            is AiEvent.Reasoning -> {
                if (!_state.value.sending) return
                _state.update { it.copy(reasoningText = it.reasoningText + event.text) }
                resetWatchdog()
            }
            is AiEvent.Done -> {
                if (!_state.value.sending) return
                flushChunks()
                _state.update { state ->
                    // usage 缺失的轮次不改变累计口径（只统计服务商真实回报的用量）
                    val accumulated = event.usage?.let { used ->
                        val previous = state.sessionUsage ?: AiUsage(0, 0, 0)
                        AiUsage(
                            promptTokens = previous.promptTokens + used.promptTokens,
                            completionTokens = previous.completionTokens + used.completionTokens,
                            totalTokens = previous.totalTokens + used.totalTokens,
                            cacheHitTokens = previous.cacheHitTokens + used.cacheHitTokens
                        )
                    } ?: state.sessionUsage
                    state.copy(lastUsage = event.usage, sessionUsage = accumulated)
                }
                // 条目 83：200 但没有任何内容（服务端忽略 stream、网关返回整段普通 JSON 等）
                // 不能按 done 落一条空回答——既没内容、也不会出现重试按钮
                val text = event.text.ifEmpty { _state.value.streamingText }
                if (text.isBlank()) {
                    // 条目 83：结束原因能解释「为什么没有内容」，优先用它给文案
                    finishWithoutMessage(blankAnswerReason(event.finishReason))
                    return
                }
                finalizeAssistant(AiMessageEntity.STATUS_DONE, text)
            }
            is AiEvent.Failed -> {
                flushChunks()
                if (!_state.value.sending) {
                    if (event.settingsHint) {
                        _state.update { it.copy(banner = event.reason, apiKeyMissing = true) }
                    }
                    return
                }
                if (_state.value.streamingText.isBlank()) {
                    // 本次没有任何半截内容：不落库空 failed 消息，仅横幅（保留 user 消息）
                    finishWithoutMessage(event.reason, event.settingsHint)
                } else {
                    finalizeAssistant(AiMessageEntity.STATUS_INTERRUPTED)
                    _state.update {
                        it.copy(
                            banner = event.reason,
                            apiKeyMissing = event.settingsHint || it.apiKeyMissing
                        )
                    }
                }
            }
        }
    }

    /**
     * 条目 85：分片合并。短于 [STREAM_FLUSH_MIN_CHARS] 时立即上屏（与旧行为一致），
     * 长回答按 [STREAM_FLUSH_INTERVAL_MS] 合并，避免每个分片都触发整篇 Markdown 重解析。
     */
    private fun accumulateChunk(text: String) {
        pendingChunk.append(text)
        val now = System.currentTimeMillis()
        if (pendingChunk.length < STREAM_FLUSH_MIN_CHARS ||
            now - lastFlushAt >= STREAM_FLUSH_INTERVAL_MS
        ) {
            flushChunks()
        }
    }

    private fun flushChunks() {
        if (pendingChunk.isEmpty()) return
        val text = pendingChunk.toString()
        pendingChunk.setLength(0)
        lastFlushAt = System.currentTimeMillis()
        _state.update { it.copy(streamingText = it.streamingText + text) }
    }

    /** 本轮没有任何可落库内容时收尾：停表、复位发送态、只留横幅（失败气泡不再成串出现）。 */
    private fun finishWithoutMessage(reason: String, settingsHint: Boolean = false) {
        watchdogJob?.cancel()
        sendRequested = false
        _state.update {
            it.copy(
                sending = false,
                streamingText = "",
                reasoningText = "",
                banner = reason,
                apiKeyMissing = settingsHint || it.apiKeyMissing
            )
        }
    }

    private fun finalizeAssistant(status: String, textOverride: String? = null) {
        flushChunks()
        val snapshot = _state.value
        if (!snapshot.sending) return
        watchdogJob?.cancel()
        val text = textOverride?.takeIf { it.isNotEmpty() } ?: snapshot.streamingText
        sendRequested = false
        _state.update { it.copy(sending = false, streamingText = "", reasoningText = "") }
        val sessionId = snapshot.currentSessionId ?: return
        externalScope.launch {
            val now = System.currentTimeMillis()
            aiRepository.appendMessage(sessionId, AiMessageEntity.ROLE_ASSISTANT, text, status, now)
            aiRepository.touch(sessionId, now)
        }
    }

    private fun titleFor(input: String): String {
        val firstLine = input.lineSequence().firstOrNull()?.trim().orEmpty()
        if (firstLine.isEmpty()) return "新对话"
        return if (firstLine.length > 20) firstLine.take(20) + "…" else firstLine
    }

    companion object {
        private const val WATCHDOG_TIMEOUT_MS = 120_000L

        /** 条目 85：回答累计超过该长度后才启用合并刷新（短回答保持逐片上屏）。 */
        private const val STREAM_FLUSH_MIN_CHARS = 300

        /** 条目 85：合并刷新的最小间隔。 */
        private const val STREAM_FLUSH_INTERVAL_MS = 80L

        /** 条目 88：自定义服务商未填模型时的提示。 */
        const val MODEL_MISSING_REASON = "未选择模型，请在「设置 → AI 助手」填写模型 id"

        /** 条目 83：HTTP 200 但流里没有任何内容的统一文案（同时作为「可重试」提示）。 */
        internal const val EMPTY_ANSWER_REASON = "回答为空，请重试"

        /** 条目 83：内容被服务商安全策略拦下时的文案。 */
        internal const val CONTENT_FILTER_REASON = "回答被服务商的安全策略拦截，请换一种问法"

        /** 条目 83：服务商资源不足（DeepSeek 的 insufficient_system_resource）。 */
        internal const val RESOURCE_EXHAUSTED_REASON = "服务商暂时资源不足，请稍后重试"

        /** 条目 83：空回答时按结束原因给出更具体的提示。 */
        internal fun blankAnswerReason(finishReason: String?): String = when (finishReason) {
            "content_filter" -> CONTENT_FILTER_REASON
            "insufficient_system_resource" -> RESOURCE_EXHAUSTED_REASON
            else -> EMPTY_ANSWER_REASON
        }

        fun factory(
            noteId: Long,
            noteTitle: String,
            noteContent: String,
            aiRepository: AiChatRepository,
            noteRepository: NoteRepository,
            settingsStore: AiSettingsStore,
            externalScope: CoroutineScope,
            session: AiSession,
            draftStore: AiDraftStore,
            watchdogTimeoutMs: Long = WATCHDOG_TIMEOUT_MS,
            sessionFactory: (String) -> AiSession = { session }
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AiChatViewModel(
                    noteId, noteTitle, noteContent, aiRepository, noteRepository,
                    settingsStore, externalScope, session, draftStore, watchdogTimeoutMs, sessionFactory
                )
            }
        }
    }
}
