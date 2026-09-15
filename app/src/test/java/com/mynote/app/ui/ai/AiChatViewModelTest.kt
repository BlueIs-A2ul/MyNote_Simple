package com.mynote.app.ui.ai

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.ai.AiChatMessage
import com.mynote.app.data.ai.AiChatRepository
import com.mynote.app.data.ai.AiEvent
import com.mynote.app.data.ai.AiSession
import com.mynote.app.data.ai.AiUsage
import com.mynote.app.data.ai.AiProvider
import com.mynote.app.data.db.AiMessageEntity
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.image.ImageStore
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.data.settings.AiDraftStore
import com.mynote.app.data.settings.AiSettingsStore
import com.mynote.app.data.settings.ApiKeyCipher
import com.mynote.app.data.settings.aiDraftKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
    private lateinit var fake: FakeAiSession
    private val vms = mutableListOf<AiChatViewModel>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ai_drafts", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        aiRepo = AiChatRepository(db.aiSessionDao(), db.aiMessageDao())
        noteRepo = NoteRepository(db.noteDao(), db.categoryDao(), db.noteRevisionDao(), ImageStore(context), db)
        settings = AiSettingsStore(context, FakeCipher())
        settings.acceptPrivacy(AiProvider.DEEPSEEK.serviceId)
        settings.setApiKey("sk-test")
        fake = FakeAiSession()
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
        store: AiSettingsStore = settings,
        draftStore: FakeAiDraftStore = FakeAiDraftStore(),
        watchdogTimeoutMs: Long = 0L
    ): AiChatViewModel {        val vm = AiChatViewModel(
            noteId = noteId,
            noteTitle = title,
            noteContent = content,
            aiRepository = aiRepo,
            noteRepository = noteRepo,
            settingsStore = store,
            externalScope = CoroutineScope(dispatcher),
            session = fake,
            draftStore = draftStore,
            watchdogTimeoutMs = watchdogTimeoutMs
        )
        vms += vm
        return vm
    }

    @Test
    fun stateCarriesProviderNameFromSession() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)

        assertEquals(AiProvider.DEEPSEEK.displayName, vm.state.value.providerName)
    }

    @Test
    fun firstSendCreatesSessionAndIncludesNoteContext() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("帮我总结")
        vm.state.first { it.sending }

        val session = aiRepo.observeSessions(noteId).first { it.isNotEmpty() }.single()
        assertEquals("帮我总结", session.title)
        assertEquals(AiProvider.DEEPSEEK.serviceId, session.serviceId)

        val sent = fake.sent.single()
        assertEquals(AiChatMessage.ROLE_USER, sent.last().role)
        assertTrue(sent.last().content.contains("【笔记正文】"))
        assertTrue(sent.last().content.contains("正文"))
        assertTrue(sent.last().content.contains("帮我总结"))

        val messages = aiRepo.observeMessages(session.id).first { it.isNotEmpty() }
        assertEquals(1, messages.size)
        assertEquals(AiMessageEntity.ROLE_USER, messages[0].role)
        assertEquals("帮我总结", messages[0].content)
    }

    @Test
    fun laterSendKeepsHistoryAndOmitsNoteContext() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("第一问")
        vm.state.first { it.sending }
        fake.emit(AiEvent.Done("答"))
        vm.state.first { !it.sending }

        vm.send("第二问")
        vm.state.first { it.sending && fake.sent.size == 2 }
        assertEquals(3, fake.sent[1].size)
        assertEquals(AiChatMessage.ROLE_ASSISTANT, fake.sent[1][1].role)
        assertEquals("第二问", fake.sent[1].last().content)
        assertTrue(fake.sent[0].last().content.contains("【笔记正文】"))
    }

    @Test
    fun doneStoresAssistantMessage() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.state.first { it.sending }
        val sessionId = aiRepo.observeSessions(noteId).first().single().id

        fake.emit(AiEvent.Chunk("答"))
        vm.state.first { it.streamingText == "答" }
        fake.emit(AiEvent.Done("答"))
        vm.state.first { !it.sending }

        val messages = aiRepo.observeMessages(sessionId).first { it.size == 2 }
        assertEquals(AiMessageEntity.ROLE_ASSISTANT, messages[1].role)
        assertEquals("答", messages[1].content)
        assertEquals(AiMessageEntity.STATUS_DONE, messages[1].status)
    }

    @Test
    fun reasoningAccumulatesAndDoneRecordsUsage() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.state.first { it.sending }

        fake.emit(AiEvent.Reasoning("先想"))
        vm.state.first { it.reasoningText == "先想" }
        fake.emit(AiEvent.Reasoning("一步"))
        vm.state.first { it.reasoningText == "先想一步" }
        assertEquals("", vm.state.value.streamingText)

        val usage = AiUsage(promptTokens = 10, completionTokens = 20, totalTokens = 30)
        fake.emit(AiEvent.Done("答案", usage))
        vm.state.first { !it.sending }

        assertEquals("", vm.state.value.reasoningText)
        assertEquals(usage, vm.state.value.lastUsage)
    }

    @Test
    fun failedWithPartialStoresInterruptedAndShowsBanner() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.state.first { it.sending }
        val sessionId = aiRepo.observeSessions(noteId).first().single().id

        fake.emit(AiEvent.Chunk("半截"))
        vm.state.first { it.streamingText == "半截" }
        fake.emit(AiEvent.Failed("网络错误，请检查网络后重试"))
        vm.state.first { !it.sending && it.banner != null }

        val messages = aiRepo.observeMessages(sessionId).first { it.size == 2 }
        assertEquals("半截", messages[1].content)
        assertEquals(AiMessageEntity.STATUS_INTERRUPTED, messages[1].status)
        assertEquals("网络错误，请检查网络后重试", vm.state.value.banner)
        assertFalse(vm.state.value.apiKeyMissing)
    }

    @Test
    fun failedWithoutPartialDoesNotStoreMessage() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.state.first { it.sending }
        val sessionId = aiRepo.observeSessions(noteId).first().single().id

        fake.emit(AiEvent.Failed("账户余额不足，请前往 DeepSeek 平台充值"))
        vm.state.first { !it.sending && it.banner != null }

        // 没有任何半截内容：不落库空 failed 消息，仅保留 user 消息与横幅
        val messages = aiRepo.getMessages(sessionId)
        assertEquals(1, messages.size)
        assertEquals(AiMessageEntity.ROLE_USER, messages[0].role)
        assertEquals("账户余额不足，请前往 DeepSeek 平台充值", vm.state.value.banner)
    }

    @Test
    fun settingsHintFailureMarksApiKeyMissing() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.state.first { it.sending }

        fake.emit(AiEvent.Failed("API Key 无效，请到设置中检查", settingsHint = true))
        vm.state.first { !it.sending && it.apiKeyMissing }

        assertTrue(vm.state.value.banner!!.contains("API Key"))
    }

    @Test
    fun missingKeyBlocksSendAndShowsBanner() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        settings.setApiKey("")
        val vm = createVm(noteId)

        assertTrue(vm.state.value.apiKeyMissing)
        assertFalse(vm.send("问"))
        assertNull(vm.state.value.currentSessionId)
        assertEquals(AiSession.KEY_MISSING_REASON, vm.state.value.banner)
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun settingsHintFailureWhenIdleOnlyShowsBanner() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        runCurrent()

        fake.emit(AiEvent.Failed(AiSession.KEY_MISSING_REASON, settingsHint = true))
        vm.state.first { it.apiKeyMissing }

        assertTrue(vm.state.value.messages.isEmpty())
    }

    @Test
    fun privacyMustBeAcceptedBeforeSend() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val fresh = AiSettingsStore(context, FakeCipher())
        fresh.setApiKey("sk-test")
        val vm = createVm(noteId, store = fresh)

        assertFalse(vm.state.value.privacyAccepted)
        vm.send("问")
        assertNull(vm.state.value.currentSessionId)
        assertTrue(fake.sent.isEmpty())

        vm.acceptPrivacy()
        assertTrue(vm.state.value.privacyAccepted)
        assertTrue(fresh.isPrivacyAccepted(AiProvider.DEEPSEEK.serviceId))
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
        fake.emit(AiEvent.Done("答"))
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
        val created = notes.first { it.summary == "第一行\n第二行" }
        assertEquals("第一行", created.title)
    }

    @Test
    fun stopDuringStreamingSavesInterrupted() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.state.first { it.sending }
        val sessionId = aiRepo.observeSessions(noteId).first { it.isNotEmpty() }.single().id

        fake.emit(AiEvent.Chunk("半截"))
        vm.state.first { it.streamingText == "半截" }
        vm.stop()
        assertFalse(vm.state.value.sending)
        assertEquals(1, fake.stopCount)

        val messages = aiRepo.observeMessages(sessionId).first { it.size == 2 }
        assertEquals("半截", messages[1].content)
        assertEquals(AiMessageEntity.STATUS_INTERRUPTED, messages[1].status)

        fake.emit(AiEvent.Done("迟到"))
        runCurrent()
        assertEquals(2, aiRepo.getMessages(sessionId).size)
    }

    @Test
    fun stopClearsReasoningText() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.state.first { it.sending }

        fake.emit(AiEvent.Reasoning("思考中"))
        vm.state.first { it.reasoningText == "思考中" }

        vm.stop()
        vm.state.first { !it.sending }
        assertEquals("", vm.state.value.reasoningText)
    }

    @Test
    fun newChatDuringSendingFinalizesOldSession() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.state.first { it.sending }
        val sessionId = aiRepo.observeSessions(noteId).first { it.isNotEmpty() }.single().id

        fake.emit(AiEvent.Chunk("半截"))
        vm.state.first { it.streamingText == "半截" }
        vm.newChat()

        assertFalse(vm.state.value.sending)
        assertNull(vm.state.value.currentSessionId)
        assertEquals("", vm.state.value.reasoningText)

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

        fake.emit(AiEvent.Chunk("半截"))
        vm.state.first { it.streamingText == "半截" }

        val secondId = aiRepo.createSession(noteId, AiProvider.DEEPSEEK.serviceId, "第二个", System.currentTimeMillis())
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
    fun lateChunkAfterDoneIsIgnored() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.state.first { it.sending }
        val sessionId = aiRepo.observeSessions(noteId).first { it.isNotEmpty() }.single().id

        fake.emit(AiEvent.Done("答"))
        vm.state.first { !it.sending }
        aiRepo.observeMessages(sessionId).first { it.size == 2 }

        fake.emit(AiEvent.Chunk("幽灵"))
        runCurrent()
        assertEquals("", vm.state.value.streamingText)
        fake.emit(AiEvent.Reasoning("幽灵思考"))
        runCurrent()
        assertEquals("", vm.state.value.reasoningText)
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
    fun sendReturnsFalseWhenRejected() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)

        assertFalse(vm.send("  "))
        assertTrue(vm.send("问"))
        vm.state.first { it.sending }
        assertFalse(vm.send("第二问"))
        assertEquals(1, fake.sent.size)
    }

    @Test
    fun sendExceptionResetsFlagsAndShowsBanner() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        fake.throwOnSend = true

        assertTrue(vm.send("问"))
        vm.state.first { it.banner == "发送失败，请重试" }
        assertFalse(vm.state.value.sending)
        assertTrue(fake.sent.isEmpty())

        // sendRequested 已复位：下一次发送仍被接受
        fake.throwOnSend = false
        assertTrue(vm.send("再问"))
        vm.state.first { it.sending }
        assertEquals(1, fake.sent.size)
    }

    @Test
    fun watchdogFinalizesWhenNoTerminalEvent() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId, watchdogTimeoutMs = 1_000L)
        vm.send("问")
        vm.state.first { it.sending }

        // 不在推进前 await Room：runTest 自动推进虚拟时间会提前触发看门狗，导致断言失真
        advanceTimeBy(1_001)
        assertFalse(vm.state.value.sending)

        val sessionId = aiRepo.observeSessions(noteId).first { it.isNotEmpty() }.single().id
        val messages = aiRepo.observeMessages(sessionId).first { it.size == 2 }
        assertEquals(AiMessageEntity.STATUS_FAILED, messages[1].status)
        assertTrue(vm.state.value.banner != null)
    }

    @Test
    fun idleWatchdogKeepsAliveWhileStreamEventsArrive() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId, watchdogTimeoutMs = 1_000L)
        vm.send("问")
        vm.state.first { it.sending }

        // 注意：runTest 会在测试挂起等待 Room 流时自动推进虚拟时间（会提前触发看门狗），
        // 因此看门狗相关断言一律用 runCurrent + state.value，不 await Room。
        fake.emit(AiEvent.Chunk("第一段"))
        runCurrent()
        assertEquals("第一段", vm.state.value.streamingText)

        advanceTimeBy(900)
        fake.emit(AiEvent.Reasoning("心跳"))
        runCurrent()
        assertEquals("心跳", vm.state.value.reasoningText)

        advanceTimeBy(900)
        fake.emit(AiEvent.Chunk("第一段第二段"))
        runCurrent()
        assertEquals("第一段第二段", vm.state.value.streamingText)

        advanceTimeBy(900)
        // 每个流事件都会重置计时：距上一个事件只过了 900ms，不应超时
        assertTrue(vm.state.value.sending)

        fake.emit(AiEvent.Done("完成"))
        runCurrent()
        assertFalse(vm.state.value.sending)

        val sessionId = aiRepo.observeSessions(noteId).first { it.isNotEmpty() }.single().id
        val messages = aiRepo.observeMessages(sessionId).first { it.size == 2 }
        assertEquals(AiMessageEntity.STATUS_DONE, messages[1].status)
    }

    @Test
    fun idleWatchdogTimesOutAfterQuietPeriod() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId, watchdogTimeoutMs = 1_000L)
        vm.send("问")
        vm.state.first { it.sending }

        fake.emit(AiEvent.Chunk("半截"))
        runCurrent()
        assertEquals("半截", vm.state.value.streamingText)

        advanceTimeBy(900)
        assertTrue(vm.state.value.sending)

        advanceTimeBy(200)
        assertFalse(vm.state.value.sending)

        val sessionId = aiRepo.observeSessions(noteId).first { it.isNotEmpty() }.single().id
        val messages = aiRepo.observeMessages(sessionId).first { it.size == 2 }
        assertEquals("半截", messages[1].content)
        assertEquals(AiMessageEntity.STATUS_INTERRUPTED, messages[1].status)
        assertTrue(vm.state.value.banner != null)
    }

    @Test
    fun retryResendsLastUserWithoutDuplicating() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("第一问")
        vm.state.first { it.sending }
        fake.emit(AiEvent.Done("答一"))
        vm.state.first { !it.sending }

        vm.send("第二问")
        vm.state.first { it.sending && fake.sent.size == 2 }
        fake.emit(AiEvent.Failed("网络错误，请检查网络后重试"))
        vm.state.first { !it.sending && it.banner != null }
        vm.state.first { it.messages.size == 3 }

        val sessionId = aiRepo.observeSessions(noteId).first().single().id
        assertEquals(3, aiRepo.getMessages(sessionId).size)

        assertTrue(vm.retry())
        vm.state.first { it.sending }
        assertEquals(3, fake.sent.size)
        val retried = fake.sent[2]
        assertEquals(3, retried.size)
        assertEquals("第二问", retried.last().content)
        assertFalse(retried.last().content.contains("【笔记正文】"))
        assertEquals(3, aiRepo.getMessages(sessionId).size)

        fake.emit(AiEvent.Done("答二"))
        vm.state.first { !it.sending }
        aiRepo.observeMessages(sessionId).first { it.size == 4 }
    }

    @Test
    fun retryRejectedWhenSendingOrNoUserMessage() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)

        assertFalse(vm.retry())

        vm.send("问")
        vm.state.first { it.sending }
        assertFalse(vm.retry())
        assertEquals(1, fake.sent.size)
    }

    @Test
    fun retryRejectedWithoutApiKey() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.state.first { it.sending }
        fake.emit(AiEvent.Failed("网络错误，请检查网络后重试"))
        vm.state.first { !it.sending }
        vm.state.first { it.messages.size == 1 }

        settings.setApiKey("")
        assertFalse(vm.retry())
        assertEquals(AiSession.KEY_MISSING_REASON, vm.state.value.banner)
        assertEquals(1, fake.sent.size)
    }

    @Test
    fun draftIsPersistedAndClearedAfterSend() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val draftStore = FakeAiDraftStore()
        val vm = createVm(noteId, draftStore = draftStore)

        vm.updateDraft("未发送的想法")
        assertEquals("未发送的想法", vm.state.value.draft)
        assertEquals("未发送的想法", draftStore.get(aiDraftKey(noteId, null)))

        vm.send(vm.state.value.draft)
        vm.state.first { it.sending }
        assertEquals("", vm.state.value.draft)
        assertEquals("", draftStore.get(aiDraftKey(noteId, null)))
        val sessionId = aiRepo.observeSessions(noteId).first { it.isNotEmpty() }.single().id
        assertEquals("", draftStore.get(aiDraftKey(noteId, sessionId)))
    }

    @Test
    fun draftFollowsSessionSwitch() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val draftStore = FakeAiDraftStore()
        val vm = createVm(noteId, draftStore = draftStore)

        vm.updateDraft("新对话草稿")
        assertEquals("新对话草稿", draftStore.get(aiDraftKey(noteId, null)))

        vm.send("问")
        vm.state.first { it.sending }
        fake.emit(AiEvent.Done("答"))
        vm.state.first { !it.sending }
        val sessionId = aiRepo.observeSessions(noteId).first().single().id

        vm.updateDraft("会话内草稿")
        assertEquals("会话内草稿", draftStore.get(aiDraftKey(noteId, sessionId)))

        vm.newChat()
        assertEquals("", vm.state.value.draft)

        vm.updateDraft("再次新对话草稿")
        vm.selectSession(sessionId)
        assertEquals(sessionId, vm.state.value.currentSessionId)
        assertEquals("会话内草稿", vm.state.value.draft)

        vm.newChat()
        assertEquals("再次新对话草稿", vm.state.value.draft)
    }

    @Test
    fun newChatClearsLastUsage() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.state.first { it.sending }

        val usage = AiUsage(promptTokens = 10, completionTokens = 20, totalTokens = 30)
        fake.emit(AiEvent.Done("答", usage))
        vm.state.first { !it.sending }
        assertEquals(usage, vm.state.value.lastUsage)

        vm.newChat()
        assertNull(vm.state.value.lastUsage)
    }

    private class FakeCipher : ApiKeyCipher {
        override fun encrypt(plain: String): String? = "enc:$plain"
        override fun decrypt(stored: String): String? =
            stored.removePrefix("enc:").takeIf { stored.startsWith("enc:") }
    }

    /** 内存草稿存储：只做读写记录，键语义与 PrefsAiDraftStore 一致。 */
    private class FakeAiDraftStore : AiDraftStore {
        private val values = mutableMapOf<String, String>()

        override fun get(key: String): String = values[key].orEmpty()

        override fun set(key: String, value: String) {
            values[key] = value
        }
    }

    private class FakeAiSession : AiSession {
        override val serviceId: String = AiProvider.DEEPSEEK.serviceId
        override val displayName: String = AiProvider.DEEPSEEK.displayName

        private val _events = MutableSharedFlow<AiEvent>(extraBufferCapacity = 16)
        override val events: SharedFlow<AiEvent> = _events

        val sent = mutableListOf<List<AiChatMessage>>()
        var stopCount = 0
        var throwOnSend = false

        override fun send(messages: List<AiChatMessage>) {
            if (throwOnSend) throw IllegalStateException("模拟发送异常")
            sent += messages
        }

        override fun stop() {
            stopCount++
        }

        override fun release() = Unit

        fun emit(event: AiEvent) {
            _events.tryEmit(event)
        }
    }
}
