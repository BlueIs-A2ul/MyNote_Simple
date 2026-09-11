package com.mynote.app.ui.ai

import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mynote.app.data.ai.AiChatRepository
import com.mynote.app.data.ai.AiDriverRegistry
import com.mynote.app.data.ai.AiPromptBuilder
import com.mynote.app.data.ai.AiWebDriver
import com.mynote.app.data.ai.AiWebEvent
import com.mynote.app.data.ai.AiWebSession
import com.mynote.app.data.db.AiMessageEntity
import com.mynote.app.data.db.AiSessionEntity
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.data.settings.AiSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    registry: AiDriverRegistry,
    webSessionFactory: (AiWebDriver) -> AiWebSession
) : ViewModel() {

    data class UiState(
        val sessions: List<AiSessionEntity> = emptyList(),
        val currentSessionId: Long? = null,
        val messages: List<AiMessageEntity> = emptyList(),
        val streamingText: String = "",
        val sending: Boolean = false,
        val webVisible: Boolean = false,
        val loggedIn: Boolean? = null,
        val privacyAccepted: Boolean = false,
        val banner: String? = null,
        val snackbar: String? = null
    )

    private val driver: AiWebDriver =
        settingsStore.selectedServiceId()?.let { registry.find(it) } ?: registry.default

    private val webSession: AiWebSession = webSessionFactory(driver)

    private val _state = MutableStateFlow(
        UiState(privacyAccepted = settingsStore.isPrivacyAccepted(driver.id))
    )
    val state: StateFlow<UiState> = _state

    private var messagesJob: Job? = null
    private var initialSelectionDone = false
    private var userStartedNewChat = false
    private var sendRequested = false

    init {
        viewModelScope.launch {
            aiRepository.observeSessions(noteId).collectLatest { sessions ->
                _state.update { it.copy(sessions = sessions) }
                if (!initialSelectionDone && !userStartedNewChat) {
                    initialSelectionDone = true
                    if (_state.value.currentSessionId == null) {
                        if (sessions.isNotEmpty()) selectSession(sessions.first().id)
                        else webSession.openNewChat()
                    }
                }
            }
        }
        viewModelScope.launch {
            webSession.events.collect { handleWebEvent(it) }
        }
    }

    fun onWebViewAttached(webView: WebView) {
        webSession.attach(webView)
    }

    fun releaseWebView() {
        webSession.release()
    }

    fun selectSession(sessionId: Long) {
        if (sessionId == _state.value.currentSessionId) return
        val session = _state.value.sessions.firstOrNull { it.id == sessionId } ?: return
        if (_state.value.sending) {
            webSession.stop()
            finalizeAssistant(AiMessageEntity.STATUS_INTERRUPTED)
        }
        userStartedNewChat = false
        _state.update { it.copy(currentSessionId = sessionId, streamingText = "", banner = null) }
        observeMessages(sessionId)
        if (session.remoteChatId != null) webSession.openChat(session.remoteChatId)
        else webSession.openNewChat()
    }

    fun newChat() {
        if (_state.value.sending) {
            webSession.stop()
            finalizeAssistant(AiMessageEntity.STATUS_INTERRUPTED)
        }
        userStartedNewChat = true
        _state.update { it.copy(currentSessionId = null, streamingText = "", banner = null) }
        observeMessages(null)
        webSession.openNewChat()
    }

    fun send(rawInput: String) {
        val input = rawInput.trim()
        val snapshot = _state.value
        if (input.isEmpty() || snapshot.sending || sendRequested || !snapshot.privacyAccepted) return
        sendRequested = true
        val sessionId = snapshot.currentSessionId
        if (sessionId != null) {
            val session = snapshot.sessions.firstOrNull { it.id == sessionId }
            viewModelScope.launch { launchSendNow(sessionId, session?.remoteChatId, input) }
        } else {
            viewModelScope.launch {
                val now = System.currentTimeMillis()
                val id = aiRepository.createSession(noteId, driver.id, titleFor(input), now)
                _state.update { it.copy(currentSessionId = id) }
                observeMessages(id)
                launchSendNow(id, null, input)
            }
        }
    }

    fun stop() {
        if (!_state.value.sending) return
        webSession.stop()
        finalizeAssistant(AiMessageEntity.STATUS_INTERRUPTED)
    }

    fun deleteSession(sessionId: Long) {
        if (_state.value.sending && sessionId == _state.value.currentSessionId) return
        viewModelScope.launch {
            aiRepository.deleteSession(sessionId)
            if (_state.value.currentSessionId == sessionId) {
                _state.update { it.copy(currentSessionId = null, streamingText = "") }
                observeMessages(null)
            }
        }
    }

    fun acceptPrivacy() {
        settingsStore.acceptPrivacy(driver.id)
        _state.update { it.copy(privacyAccepted = true) }
    }

    fun toggleWebVisible() {
        _state.update { it.copy(webVisible = !it.webVisible) }
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
        webSession.release()
    }

    private suspend fun launchSendNow(sessionId: Long, remoteChatId: String?, input: String) {
        val payload = AiPromptBuilder.build(
            noteTitle = noteTitle,
            noteContent = noteContent,
            userInput = input,
            includeNoteContext = remoteChatId == null
        )
        val now = System.currentTimeMillis()
        aiRepository.appendMessage(sessionId, AiMessageEntity.ROLE_USER, input, AiMessageEntity.STATUS_DONE, now)
        _state.update { it.copy(sending = true, streamingText = "", banner = null) }
        sendRequested = false
        webSession.send(payload)
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

    private fun handleWebEvent(event: AiWebEvent) {
        when (event) {
            is AiWebEvent.LoginState -> _state.update {
                it.copy(
                    loggedIn = event.loggedIn,
                    banner = if (event.loggedIn) null else "请先登录 ${driver.displayName}，登录后重新发送",
                    webVisible = it.webVisible || !event.loggedIn
                )
            }
            is AiWebEvent.ChatId -> {
                if (!_state.value.sending) return
                val sessionId = _state.value.currentSessionId ?: return
                viewModelScope.launch { aiRepository.updateRemoteChatId(sessionId, event.id) }
            }
            is AiWebEvent.ReplyChunk -> {
                if (!_state.value.sending) return
                _state.update { it.copy(streamingText = event.text) }
            }
            is AiWebEvent.ReplyDone -> finalizeAssistant(AiMessageEntity.STATUS_DONE, event.text)
            is AiWebEvent.ReplyError -> {
                if (!_state.value.sending) return
                val partial = _state.value.streamingText
                finalizeAssistant(
                    if (partial.isBlank()) AiMessageEntity.STATUS_FAILED
                    else AiMessageEntity.STATUS_INTERRUPTED
                )
                _state.update {
                    it.copy(banner = "${event.reason}（可显示网页手动操作）", webVisible = true)
                }
            }
            is AiWebEvent.PageError -> {
                if (_state.value.sending) {
                    val partial = _state.value.streamingText
                    finalizeAssistant(
                        if (partial.isBlank()) AiMessageEntity.STATUS_FAILED
                        else AiMessageEntity.STATUS_INTERRUPTED
                    )
                }
                _state.update { it.copy(banner = event.description) }
            }
            AiWebEvent.PageReady -> _state.update { it.copy(banner = null) }
        }
    }

    private fun finalizeAssistant(status: String, textOverride: String? = null) {
        val snapshot = _state.value
        if (!snapshot.sending) return
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
        fun factory(
            noteId: Long,
            noteTitle: String,
            noteContent: String,
            aiRepository: AiChatRepository,
            noteRepository: NoteRepository,
            settingsStore: AiSettingsStore,
            externalScope: CoroutineScope,
            registry: AiDriverRegistry,
            webSessionFactory: (AiWebDriver) -> AiWebSession
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AiChatViewModel(
                    noteId, noteTitle, noteContent, aiRepository, noteRepository,
                    settingsStore, externalScope, registry, webSessionFactory
                )
            }
        }
    }
}
