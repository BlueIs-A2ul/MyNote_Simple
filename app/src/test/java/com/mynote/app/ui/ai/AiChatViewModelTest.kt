package com.mynote.app.ui.ai

import android.content.Context
import android.webkit.WebView
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import com.mynote.app.data.ai.AiChatRepository
import com.mynote.app.data.ai.AiDriverRegistry
import com.mynote.app.data.ai.AiWebDriver
import com.mynote.app.data.ai.AiWebEvent
import com.mynote.app.data.ai.AiWebSession
import com.mynote.app.data.db.AiMessageEntity
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.image.ImageStore
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.data.settings.AiSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var aiRepo: AiChatRepository
    private lateinit var noteRepo: NoteRepository
    private lateinit var settings: AiSettingsStore
    private lateinit var fake: FakeAiWebSession
    private val vms = mutableListOf<AiChatViewModel>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        aiRepo = AiChatRepository(db.aiSessionDao(), db.aiMessageDao())
        noteRepo = NoteRepository(db.noteDao(), db.categoryDao(), db.noteRevisionDao(), ImageStore(context), db)
        settings = AiSettingsStore(context)
        settings.acceptPrivacy("deepseek")
        fake = FakeAiWebSession(FakeDriver)
    }

    @After
    fun teardown() {
        vms.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
        db.close()
    }

    private fun createVm(
        noteId: Long,
        title: String = "标题",
        content: String = "正文",
        store: AiSettingsStore = settings
    ): AiChatViewModel {
        val vm = AiChatViewModel(
            noteId = noteId,
            noteTitle = title,
            noteContent = content,
            aiRepository = aiRepo,
            noteRepository = noteRepo,
            settingsStore = store,
            externalScope = CoroutineScope(dispatcher),
            registry = AiDriverRegistry(listOf(FakeDriver)),
            webSessionFactory = { fake }
        )
        vms += vm
        return vm
    }

    @Test
    fun firstSendCreatesSessionAndIncludesNoteContext() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("帮我总结")
        vm.state.first { it.sending }

        val session = aiRepo.observeSessions(noteId).first { it.isNotEmpty() }.single()
        assertEquals("帮我总结", session.title)
        assertEquals("deepseek", session.serviceId)
        assertTrue(fake.sent.single().contains("【笔记正文】"))
        assertTrue(fake.sent.single().contains("正文"))
        assertTrue(fake.sent.single().contains("帮我总结"))

        val messages = aiRepo.observeMessages(session.id).first { it.isNotEmpty() }
        assertEquals(1, messages.size)
        assertEquals(AiMessageEntity.ROLE_USER, messages[0].role)
        assertEquals("帮我总结", messages[0].content)
    }

    @Test
    fun laterSendOmitsNoteContext() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("第一问")
        vm.state.first { it.sending }

        fake.emit(AiWebEvent.ChatId("chat-1"))
        vm.state.first { it.sessions.firstOrNull()?.remoteChatId == "chat-1" }
        fake.emit(AiWebEvent.ReplyDone("答"))
        vm.state.first { !it.sending }

        vm.send("第二问")
        vm.state.first { it.sending && fake.sent.size == 2 }
        assertTrue(fake.sent[0].contains("【笔记正文】"))
        assertEquals("第二问", fake.sent[1])
    }

    @Test
    fun replyDoneStoresAssistantMessage() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.state.first { it.sending }
        val sessionId = aiRepo.observeSessions(noteId).first().single().id

        fake.emit(AiWebEvent.ReplyChunk("答"))
        vm.state.first { it.streamingText == "答" }
        fake.emit(AiWebEvent.ReplyDone("答"))
        vm.state.first { !it.sending }

        val messages = aiRepo.observeMessages(sessionId).first { it.size == 2 }
        assertEquals(AiMessageEntity.ROLE_ASSISTANT, messages[1].role)
        assertEquals("答", messages[1].content)
        assertEquals(AiMessageEntity.STATUS_DONE, messages[1].status)
    }

    @Test
    fun replyErrorWithPartialStoresInterrupted() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.state.first { it.sending }
        val sessionId = aiRepo.observeSessions(noteId).first().single().id

        fake.emit(AiWebEvent.ReplyChunk("半截"))
        vm.state.first { it.streamingText == "半截" }
        fake.emit(AiWebEvent.ReplyError("回答超时"))
        vm.state.first { !it.sending && it.banner != null }

        val messages = aiRepo.observeMessages(sessionId).first { it.size == 2 }
        assertEquals("半截", messages[1].content)
        assertEquals(AiMessageEntity.STATUS_INTERRUPTED, messages[1].status)
        assertTrue(vm.state.value.webVisible)
    }

    @Test
    fun replyErrorWithoutPartialStoresFailed() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.state.first { it.sending }
        val sessionId = aiRepo.observeSessions(noteId).first().single().id

        fake.emit(AiWebEvent.ReplyError("未找到输入框"))
        vm.state.first { !it.sending && it.banner != null }

        val messages = aiRepo.observeMessages(sessionId).first { it.size == 2 }
        assertEquals(AiMessageEntity.STATUS_FAILED, messages[1].status)
        assertEquals("", messages[1].content)
    }

    @Test
    fun notLoggedInShowsBannerAndWeb() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        runCurrent()
        fake.emit(AiWebEvent.LoginState(false))
        vm.state.first { it.banner != null }
        assertTrue(vm.state.value.banner!!.contains("登录"))
        assertTrue(vm.state.value.webVisible)
    }

    @Test
    fun newChatClearsCurrentSession() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.state.first { it.sending }
        assertTrue(vm.state.value.currentSessionId != null)

        vm.newChat()
        assertNull(vm.state.value.currentSessionId)
        assertTrue(vm.state.value.messages.isEmpty())
    }

    @Test
    fun deleteSessionRemovesIt() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.state.first { it.sending }
        val sessionId = aiRepo.observeSessions(noteId).first().single().id
        fake.emit(AiWebEvent.ReplyDone("答"))
        vm.state.first { !it.sending }

        vm.deleteSession(sessionId)
        vm.state.first { it.sessions.isEmpty() }
        assertEquals(0, aiRepo.getMessages(sessionId).size)
        assertNull(vm.state.value.currentSessionId)
    }

    @Test
    fun saveAsNoteCreatesNoteWithFirstLineTitle() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.saveAsNote("第一行\n第二行")
        vm.state.first { it.snackbar != null }

        val notes = noteRepo.observeNotes().first()
        val created = notes.first { it.content == "第一行\n第二行" }
        assertEquals("第一行", created.title)
    }

    @Test
    fun privacyMustBeAcceptedBeforeSend() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val fresh = AiSettingsStore(context)
        val vm = createVm(noteId, store = fresh)

        assertFalse(vm.state.value.privacyAccepted)
        vm.send("问")
        assertNull(vm.state.value.currentSessionId)
        assertTrue(fake.sent.isEmpty())

        vm.acceptPrivacy()
        assertTrue(vm.state.value.privacyAccepted)
        assertTrue(fresh.isPrivacyAccepted("deepseek"))
    }

    @Test
    fun stopDuringStreamingSavesInterrupted() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.state.first { it.sending }
        val sessionId = aiRepo.observeSessions(noteId).first { it.isNotEmpty() }.single().id

        fake.emit(AiWebEvent.ReplyChunk("半截"))
        vm.state.first { it.streamingText == "半截" }
        vm.stop()
        assertFalse(vm.state.value.sending)

        val messages = aiRepo.observeMessages(sessionId).first { it.size == 2 }
        assertEquals("半截", messages[1].content)
        assertEquals(AiMessageEntity.STATUS_INTERRUPTED, messages[1].status)

        fake.emit(AiWebEvent.ReplyDone("迟到"))
        runCurrent()
        assertEquals(2, aiRepo.getMessages(sessionId).size)
    }

    @Test
    fun newChatDuringSendingFinalizesOldSession() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.state.first { it.sending }
        val sessionId = aiRepo.observeSessions(noteId).first { it.isNotEmpty() }.single().id

        fake.emit(AiWebEvent.ReplyChunk("半截"))
        vm.state.first { it.streamingText == "半截" }
        vm.newChat()

        assertFalse(vm.state.value.sending)
        assertNull(vm.state.value.currentSessionId)

        val messages = aiRepo.observeMessages(sessionId).first { it.size == 2 }
        assertEquals("半截", messages[1].content)
        assertEquals(AiMessageEntity.STATUS_INTERRUPTED, messages[1].status)
    }

    @Test
    fun selectSessionDuringSendingFinalizesOldSession() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.state.first { it.sending }
        val sessionId = aiRepo.observeSessions(noteId).first { it.isNotEmpty() }.single().id

        fake.emit(AiWebEvent.ReplyChunk("半截"))
        vm.state.first { it.streamingText == "半截" }

        val secondId = aiRepo.createSession(noteId, "deepseek", "第二个", System.currentTimeMillis())
        vm.state.first { it.sessions.any { session -> session.id == secondId } }
        vm.selectSession(secondId)

        assertFalse(vm.state.value.sending)
        assertEquals(secondId, vm.state.value.currentSessionId)

        val oldMessages = aiRepo.observeMessages(sessionId).first { it.size == 2 }
        assertEquals("半截", oldMessages[1].content)
        assertEquals(AiMessageEntity.STATUS_INTERRUPTED, oldMessages[1].status)

        runCurrent()
        assertEquals(0, aiRepo.getMessages(secondId).size)
    }

    @Test
    fun lateReplyChunkAfterDoneIsIgnored() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.state.first { it.sending }
        val sessionId = aiRepo.observeSessions(noteId).first { it.isNotEmpty() }.single().id

        fake.emit(AiWebEvent.ReplyDone("答"))
        vm.state.first { !it.sending }
        aiRepo.observeMessages(sessionId).first { it.size == 2 }

        fake.emit(AiWebEvent.ReplyChunk("幽灵"))
        runCurrent()
        assertEquals("", vm.state.value.streamingText)
        assertEquals(2, aiRepo.getMessages(sessionId).size)
    }

    @Test
    fun rapidDoubleSendOnlySendsOnce() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.send("问")
        vm.state.first { it.sending }
        runCurrent()

        val sessions = aiRepo.observeSessions(noteId).first { it.isNotEmpty() }
        assertEquals(1, sessions.size)
        assertEquals(1, fake.sent.size)
        assertEquals(1, aiRepo.getMessages(sessions.single().id).size)
    }

    @Test
    fun pageErrorWithPartialStoresInterruptedWithoutPartialStoresFailed() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问一")
        vm.state.first { it.sending }
        val sessionId = aiRepo.observeSessions(noteId).first { it.isNotEmpty() }.single().id

        fake.emit(AiWebEvent.ReplyChunk("半截"))
        vm.state.first { it.streamingText == "半截" }
        fake.emit(AiWebEvent.PageError("网页加载失败"))
        vm.state.first { !it.sending && it.banner != null }
        val afterPartial = aiRepo.observeMessages(sessionId).first { it.size == 2 }
        assertEquals("半截", afterPartial[1].content)
        assertEquals(AiMessageEntity.STATUS_INTERRUPTED, afterPartial[1].status)

        vm.send("问二")
        vm.state.first { it.sending }
        fake.emit(AiWebEvent.PageError("页面未就绪"))
        vm.state.first { !it.sending && it.banner == "页面未就绪" }
        val all = aiRepo.observeMessages(sessionId).first { it.size == 4 }
        assertEquals(AiMessageEntity.STATUS_FAILED, all[3].status)
        assertEquals("", all[3].content)
    }

    @Test
    fun chatIdIgnoredWhenNotSending() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.state.first { it.sending }
        fake.emit(AiWebEvent.ReplyDone("答"))
        vm.state.first { !it.sending }

        fake.emit(AiWebEvent.ChatId("late-chat"))
        runCurrent()
        assertNull(aiRepo.observeSessions(noteId).first { it.isNotEmpty() }.single().remoteChatId)
        assertNull(vm.state.value.sessions.single().remoteChatId)
    }

    private object FakeDriver : AiWebDriver {
        override val id = "deepseek"
        override val displayName = "DeepSeek"
        override val homeUrl = "https://example.com/"
        override fun chatUrl(remoteChatId: String) = homeUrl + remoteChatId
        override fun parseChatId(url: String): String? = null
        override fun loginCheckJs() = ""
        override fun newChatJs() = ""
        override fun sendMessageJs(text: String) = ""
        override fun observeReplyJs() = ""
        override fun stopObservingJs() = ""
        override fun stopGeneratingJs() = ""
    }

    private class FakeAiWebSession(override val driver: AiWebDriver) : AiWebSession {
        private val _events = MutableSharedFlow<AiWebEvent>(extraBufferCapacity = 16)
        override val events: SharedFlow<AiWebEvent> = _events

        val sent = mutableListOf<String>()
        var newChatCount = 0

        override fun attach(webView: WebView) = Unit
        override fun openNewChat() { newChatCount++ }
        override fun openChat(remoteChatId: String) = Unit
        override fun send(text: String) { sent += text }
        override fun stop() = Unit
        override fun release() = Unit

        fun emit(event: AiWebEvent) { _events.tryEmit(event) }
    }
}
