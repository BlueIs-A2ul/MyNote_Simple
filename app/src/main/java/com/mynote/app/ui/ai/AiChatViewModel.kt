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
import com.mynote.app.data.db.AiMessageEntity
import com.mynote.app.data.db.AiSessionEntity
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.data.settings.AiSettingsStore
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
    private val watchdogTimeoutMs: Long = WATCHDOG_TIMEOUT_MS
) : ViewModel() {

    data class UiState(
        val sessions: List<AiSessionEntity> = emptyList(),
        val currentSessionId: Long? = null,
        val messages: List<AiMessageEntity> = emptyList(),
        val streamingText: String = "",
        val sending: Boolean = false,
        val privacyAccepted: Boolean = false,
        val banner: String? = null,
        val apiKeyMissing: Boolean = false,
        val snackbar: String? = null
    )

    private val _state = MutableStateFlow(
        UiState(
            privacyAccepted = settingsStore.isPrivacyAccepted(session.serviceId),
            apiKeyMissing = !settingsStore.hasApiKey(),
            banner = if (settingsStore.hasApiKey()) null else AiSession.KEY_MISSING_REASON
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

    fun selectSession(sessionId: Long) {
        if (sessionId == _state.value.currentSessionId) return
        val session = _state.value.sessions.firstOrNull { it.id == sessionId } ?: return
        if (_state.value.sending) {
            this.session.stop()
            finalizeAssistant(AiMessageEntity.STATUS_INTERRUPTED)
        }
        userStartedNewChat = false
        _state.update { it.copy(currentSessionId = sessionId, streamingText = "", banner = null) }
        observeMessages(sessionId)
    }

    fun newChat() {
        if (_state.value.sending) {
            session.stop()
            finalizeAssistant(AiMessageEntity.STATUS_INTERRUPTED)
        }
        userStartedNewChat = true
        _state.update { it.copy(currentSessionId = null, streamingText = "", banner = null) }
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
            viewModelScope.launch { launchSendNow(sessionId, input) }
        } else {
            viewModelScope.launch {
                val now = System.currentTimeMillis()
                val id = aiRepository.createSession(noteId, session.serviceId, titleFor(input), now)
                _state.update { it.copy(currentSessionId = id) }
                observeMessages(id)
                launchSendNow(id, input)
            }
        }
        return true
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
        _state.update { it.copy(sending = true, streamingText = "", banner = null) }
        sendRequested = false
        session.send(messages)
        startWatchdog()
    }

    /** 网络层未回终态时的兜底：超时未收到终态事件则按半截/失败收尾。 */
    private fun startWatchdog() {
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
            }
            is AiEvent.Done -> finalizeAssistant(AiMessageEntity.STATUS_DONE, event.text)
            is AiEvent.Failed -> {
                if (!_state.value.sending) {
                    if (event.settingsHint) {
                        _state.update { it.copy(banner = event.reason, apiKeyMissing = true) }
                    }
                    return
                }
                val partial = _state.value.streamingText
                finalizeAssistant(
                    if (partial.isBlank()) AiMessageEntity.STATUS_FAILED
                    else AiMessageEntity.STATUS_INTERRUPTED
                )
                _state.update {
                    it.copy(
                        banner = event.reason,
                        apiKeyMissing = event.settingsHint || it.apiKeyMissing
                    )
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
        _state.update { it.copy(sending = false, streamingText = "") }
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
            watchdogTimeoutMs: Long = WATCHDOG_TIMEOUT_MS
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AiChatViewModel(
                    noteId, noteTitle, noteContent, aiRepository, noteRepository,
                    settingsStore, externalScope, session, watchdogTimeoutMs
                )
            }
        }
    }
}
