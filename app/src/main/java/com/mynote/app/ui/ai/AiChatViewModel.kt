package com.mynote.app.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mynote.app.data.ai.AiApiMessageBuilder
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
    private val session: AiSession,
    private val draftStore: AiDraftStore,
    private val watchdogTimeoutMs: Long = WATCHDOG_TIMEOUT_MS
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
        val draft: String = "",
        /** 当前服务商展示名（隐私弹窗/文案用），来自会话身份。 */
        val providerName: String = ""
    )

    private val _state = MutableStateFlow(
        UiState(
            privacyAccepted = settingsStore.isPrivacyAccepted(session.serviceId),
            apiKeyMissing = !settingsStore.hasApiKey(),
            banner = if (settingsStore.hasApiKey()) null else AiSession.KEY_MISSING_REASON,
            draft = draftStore.get(aiDraftKey(noteId, null)),
            providerName = session.displayName
        )
    )
    val state: StateFlow<UiState> = _state

    private var messagesJob: Job? = null
    private var initialSelectionDone = false
    private var userStartedNewChat = false
    private var sendRequested = false
    private var watchdogJob: Job? = null

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
        viewModelScope.launch {
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
                draft = draftStore.get(aiDraftKey(noteId, null))
            )
        }
        observeMessages(null)
    }

    /** 返回是否真正进入发送流程；被拒（空输入 / 生成中 / 重复请求 / 未接受隐私 / 未配置 Key）返回 false。 */
    fun send(rawInput: String): Boolean {
        val input = rawInput.trim()
        val snapshot = _state.value
        if (input.isEmpty() || snapshot.sending || sendRequested || !snapshot.privacyAccepted) return false
        if (!settingsStore.hasApiKey()) {
            _state.update {
                it.copy(banner = AiSession.KEY_MISSING_REASON, apiKeyMissing = true)
            }
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
        val snapshot = _state.value
        if (snapshot.sending || sendRequested) return false
        if (!snapshot.privacyAccepted) return false
        if (!settingsStore.hasApiKey()) {
            _state.update {
                it.copy(banner = AiSession.KEY_MISSING_REASON, apiKeyMissing = true)
            }
            return false
        }
        val lastUserIndex = snapshot.messages.indexOfLast { it.role == AiMessageEntity.ROLE_USER }
        if (lastUserIndex < 0) return false
        val sessionId = snapshot.currentSessionId ?: return false
        val history = snapshot.messages.subList(0, lastUserIndex)
        val input = snapshot.messages[lastUserIndex].content
        // 与首次发送同一路径：首条用户消息仍会带上笔记正文快照
        val messages = AiApiMessageBuilder.build(noteTitle, noteContent, history, input)
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
        val messages = AiApiMessageBuilder.build(noteTitle, noteContent, history, input)
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
                _state.update { it.copy(streamingText = event.text) }
                resetWatchdog()
            }
            is AiEvent.Reasoning -> {
                if (!_state.value.sending) return
                _state.update { it.copy(reasoningText = it.reasoningText + event.text) }
                resetWatchdog()
            }
            is AiEvent.Done -> {
                if (!_state.value.sending) return
                _state.update { it.copy(lastUsage = event.usage) }
                finalizeAssistant(AiMessageEntity.STATUS_DONE, event.text)
            }
            is AiEvent.Failed -> {
                if (!_state.value.sending) {
                    if (event.settingsHint) {
                        _state.update { it.copy(banner = event.reason, apiKeyMissing = true) }
                    }
                    return
                }
                if (_state.value.streamingText.isBlank()) {
                    // 本次没有任何半截内容：不落库空 failed 消息，仅横幅（保留 user 消息）
                    watchdogJob?.cancel()
                    sendRequested = false
                    _state.update {
                        it.copy(
                            sending = false,
                            streamingText = "",
                            reasoningText = "",
                            banner = event.reason,
                            apiKeyMissing = event.settingsHint || it.apiKeyMissing
                        )
                    }
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

    private fun finalizeAssistant(status: String, textOverride: String? = null) {
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
            watchdogTimeoutMs: Long = WATCHDOG_TIMEOUT_MS
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AiChatViewModel(
                    noteId, noteTitle, noteContent, aiRepository, noteRepository,
                    settingsStore, externalScope, session, draftStore, watchdogTimeoutMs
                )
            }
        }
    }
}
