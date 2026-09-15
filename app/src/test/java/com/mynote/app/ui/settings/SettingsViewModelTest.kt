package com.mynote.app.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.ai.AiEndpoint
import com.mynote.app.data.ai.AiProbe
import com.mynote.app.data.ai.AiProvider
import com.mynote.app.data.ai.BalanceLine
import com.mynote.app.data.ai.BalanceState
import com.mynote.app.data.ai.DeepSeekModels
import com.mynote.app.data.ai.ProbeResult
import com.mynote.app.data.db.NoteSortMode
import com.mynote.app.data.settings.AiSettingsStore
import com.mynote.app.data.settings.ApiKeyCipher
import com.mynote.app.data.settings.DarkMode
import com.mynote.app.data.settings.NoteSortStore
import com.mynote.app.data.settings.ThemeSettingsStore
import com.mynote.app.data.settings.TrashRetentionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
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
class SettingsViewModelTest {

    private class FakeCipher : ApiKeyCipher {
        override fun encrypt(plain: String): String? = "enc:$plain"
        override fun decrypt(stored: String): String? =
            stored.removePrefix("enc:").takeIf { stored.startsWith("enc:") }
    }

    private val dispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var store: ThemeSettingsStore
    private lateinit var sortStore: NoteSortStore
    private lateinit var trashStore: TrashRetentionStore
    private lateinit var aiStore: AiSettingsStore
    private lateinit var vm: SettingsViewModel

    /** 假探测：测试可改 [probeResult]/[balanceState]；记录收到的 Key 与端点。 */
    private var probeResult: ProbeResult = ProbeResult.Failed("未配置")
    private var balanceState: BalanceState = BalanceState.Failed("未配置")
    private var lastProbeKey: String? = null
    private var lastEndpoint: AiEndpoint? = null

    private val fakeProbe = object : AiProbe {
        override suspend fun probe(apiKey: String): ProbeResult {
            lastProbeKey = apiKey
            return probeResult
        }

        override suspend fun fetchBalance(apiKey: String): BalanceState = balanceState
    }

    private val clientFactory: (AiEndpoint) -> AiProbe = { endpoint ->
        lastEndpoint = endpoint
        fakeProbe
    }

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("theme_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("trash_retention_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = ThemeSettingsStore(context)
        sortStore = NoteSortStore(context)
        trashStore = TrashRetentionStore(context)
        aiStore = AiSettingsStore(context, FakeCipher())
        vm = SettingsViewModel(store, sortStore, trashStore, aiStore, clientFactory)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun setDarkModeDelegatesToStore() {
        vm.setDarkMode(DarkMode.DARK)
        assertEquals(DarkMode.DARK, store.settings.value.darkMode)
        assertEquals(DarkMode.DARK, vm.settings.value.darkMode)
    }

    @Test
    fun setDynamicColorDelegatesToStore() {
        vm.setDynamicColor(false)
        assertFalse(store.settings.value.dynamicColor)
    }

    @Test
    fun setThemeColorIndexDelegatesToStore() {
        vm.setThemeColorIndex(5)
        assertEquals(5, store.settings.value.themeColorIndex)
    }

    @Test
    fun setDefaultSortDelegatesToSortStore() {
        vm.setDefaultSort(NoteSortMode.TITLE_ASC)
        assertEquals(NoteSortMode.TITLE_ASC, sortStore.mode.value)
        assertEquals(NoteSortMode.TITLE_ASC, vm.defaultSort.value)
    }

    @Test
    fun setRetentionDaysDelegatesToTrashStore() {
        vm.setRetentionDays(7)
        assertEquals(7, trashStore.retentionDays.value)
        assertEquals(7, vm.retentionDays.value)
    }

    @Test
    fun aiModelDelegatesAndRejectsUnknown() {
        assertEquals(DeepSeekModels.FLASH, vm.aiModel.value)

        vm.setAiModel(DeepSeekModels.V4_PRO)
        assertEquals(DeepSeekModels.V4_PRO, aiStore.model())
        assertEquals(DeepSeekModels.V4_PRO, vm.aiModel.value)

        vm.setAiModel("nope")
        assertEquals(DeepSeekModels.V4_PRO, vm.aiModel.value)
    }

    @Test
    fun availableModelsStartsFromStore() {
        assertEquals(DeepSeekModels.all, vm.availableModels.value)
    }

    @Test
    fun defaultProviderIsDeepSeek() {
        assertEquals(AiProvider.DEEPSEEK, vm.aiProvider.value)
        assertEquals("https://api.deepseek.com", vm.endpoint().baseUrl)
        assertEquals("deepseek-api", vm.endpoint().serviceId)
    }

    @Test
    fun providerSwitchRefreshesScopedState() {
        vm.setAiModel(DeepSeekModels.V4_PRO)
        vm.setDeepThinking(true)
        vm.saveApiKey("deepseek-key")

        vm.setProvider(AiProvider.SILICON_FLOW)

        assertEquals(AiProvider.SILICON_FLOW, vm.aiProvider.value)
        assertEquals(listOf("deepseek-ai/DeepSeek-V4-Flash"), vm.availableModels.value)
        assertEquals("deepseek-ai/DeepSeek-V4-Flash", vm.aiModel.value)
        assertFalse("思考开关按服务商隔离", vm.deepThinking.value)
        assertFalse("Key 按服务商隔离", vm.apiKeyConfigured.value)
        assertEquals("https://api.siliconflow.cn/v1", vm.endpoint().baseUrl)

        vm.setProvider(AiProvider.DEEPSEEK)

        assertEquals(DeepSeekModels.V4_PRO, vm.aiModel.value)
        assertTrue(vm.deepThinking.value)
        assertTrue(vm.apiKeyConfigured.value)
        assertEquals("deepseek-key", vm.savedApiKey())
    }

    @Test
    fun customBaseUrlValidationAndEndpoint() {
        vm.setProvider(AiProvider.CUSTOM)

        assertTrue(vm.setCustomBaseUrl("https://example.com/v1/"))
        assertEquals("https://example.com/v1", vm.customBaseUrl.value)
        assertEquals("https://example.com/v1", vm.endpoint().baseUrl)
        assertEquals("openai-compatible", vm.endpoint().serviceId)
        assertEquals("自定义", vm.endpoint().displayName)

        assertFalse("非 http(s) 地址应被拒绝", vm.setCustomBaseUrl("ftp://example.com"))
        assertEquals("https://example.com/v1", vm.customBaseUrl.value)

        assertTrue("空白等价清除", vm.setCustomBaseUrl("   "))
        assertEquals("", vm.customBaseUrl.value)
    }

    @Test
    fun customProviderAllowsFreeModelInput() {
        vm.setProvider(AiProvider.CUSTOM)
        vm.setCustomBaseUrl("https://example.com/v1")

        vm.setAiModel("gpt-4o-mini")

        assertEquals("gpt-4o-mini", vm.aiModel.value)
        assertEquals("gpt-4o-mini", aiStore.model(AiProvider.CUSTOM))
    }

    @Test
    fun testConnectionUpdatesModelsAndSwitchesStaleModel() = runTest(dispatcher) {
        vm.setAiModel(DeepSeekModels.V4_PRO)
        probeResult = ProbeResult.Ok(listOf(DeepSeekModels.FLASH, "deepseek-x"))

        val message = vm.testConnection("sk-1")

        assertEquals("sk-1", lastProbeKey)
        assertEquals(listOf(DeepSeekModels.FLASH, "deepseek-x"), vm.availableModels.value)
        assertEquals(listOf(DeepSeekModels.FLASH, "deepseek-x"), aiStore.models())
        assertEquals(DeepSeekModels.FLASH, vm.aiModel.value)
        assertEquals(DeepSeekModels.FLASH, aiStore.model())
        assertTrue(message.startsWith("连接正常，可用模型："))
    }

    @Test
    fun testConnectionUsesSelectedProviderEndpoint() = runTest(dispatcher) {
        vm.setProvider(AiProvider.SILICON_FLOW)
        probeResult = ProbeResult.Ok(listOf("deepseek-ai/DeepSeek-V4-Flash"))

        vm.testConnection("sf-key")

        assertEquals("siliconflow-api", lastEndpoint?.serviceId)
        assertEquals("https://api.siliconflow.cn/v1", lastEndpoint?.baseUrl)
        assertEquals(
            listOf("deepseek-ai/DeepSeek-V4-Flash"),
            aiStore.models(AiProvider.SILICON_FLOW)
        )
        assertEquals("DeepSeek 列表不受影响", DeepSeekModels.all, aiStore.models(AiProvider.DEEPSEEK))
    }

    @Test
    fun testConnectionKeepsModelPresentInNewList() = runTest(dispatcher) {
        probeResult = ProbeResult.Ok(listOf(DeepSeekModels.FLASH, "deepseek-x"))

        vm.testConnection("sk-1")

        assertEquals(listOf(DeepSeekModels.FLASH, "deepseek-x"), vm.availableModels.value)
        assertEquals(DeepSeekModels.FLASH, vm.aiModel.value)
    }

    @Test
    fun testConnectionFailureKeepsModelsAndModel() = runTest(dispatcher) {
        vm.setAiModel(DeepSeekModels.V4_PRO)
        val modelsBefore = vm.availableModels.value
        probeResult = ProbeResult.Failed("API Key 无效，请到设置中检查")

        val message = vm.testConnection("sk-1")

        assertEquals("API Key 无效，请到设置中检查", message)
        assertEquals(modelsBefore, vm.availableModels.value)
        assertEquals(DeepSeekModels.V4_PRO, vm.aiModel.value)
        assertEquals(DeepSeekModels.all, aiStore.models())
    }

    @Test
    fun queryBalanceDelegatesToProbe() = runTest(dispatcher) {
        balanceState = BalanceState.Ok(
            isAvailable = true,
            lines = listOf(BalanceLine("CNY", "110.00", "10.00", "100.00"))
        )

        assertEquals("余额 CNY 110.00（赠金 10.00 / 充值 100.00）", vm.queryBalance("sk-1"))
    }

    @Test
    fun modelAfterRefreshKeepsCurrentWhenStillInList() {
        assertEquals(
            "deepseek-x",
            modelAfterRefresh("deepseek-x", listOf("deepseek-y", "deepseek-x"), DeepSeekModels.all)
        )
    }

    @Test
    fun modelAfterRefreshPicksFirstWhenCurrentMissing() {
        assertEquals(
            "deepseek-y",
            modelAfterRefresh("deepseek-v4-pro", listOf("deepseek-y", "deepseek-x"), DeepSeekModels.all)
        )
    }

    @Test
    fun modelAfterRefreshFallsBackToDefaultsOnEmptyList() {
        assertEquals(
            DeepSeekModels.DEFAULT,
            modelAfterRefresh("deepseek-x", emptyList(), DeepSeekModels.all)
        )
        assertEquals(
            "deepseek-ai/DeepSeek-V4-Flash",
            modelAfterRefresh("", emptyList(), AiProvider.SILICON_FLOW.defaultModels)
        )
        assertEquals("", modelAfterRefresh("", emptyList(), emptyList()))
    }

    @Test
    fun deepThinkingDelegates() {
        assertFalse(vm.deepThinking.value)

        vm.setDeepThinking(true)
        assertTrue(aiStore.deepThinking())
        assertTrue(vm.deepThinking.value)
    }

    @Test
    fun apiKeySaveAndClearUpdatesConfiguredFlag() {
        assertFalse(vm.apiKeyConfigured.value)

        assertTrue(vm.saveApiKey("  sk-1  "))
        assertTrue(vm.apiKeyConfigured.value)
        assertEquals("sk-1", vm.savedApiKey())

        vm.clearApiKey()
        assertFalse(vm.apiKeyConfigured.value)
        assertNull(vm.savedApiKey())
    }

    @Test
    fun connectionMessageListsModelsWhenBuiltinModelsPresent() {
        val message = connectionMessage(ProbeResult.Ok(DeepSeekModels.all), AiProvider.DEEPSEEK)
        assertEquals("连接正常，可用模型：deepseek-flash、deepseek-v4-pro", message)
    }

    @Test
    fun connectionMessageWarnsWhenBuiltinModelsMissing() {
        val message = connectionMessage(
            ProbeResult.Ok(listOf(DeepSeekModels.FLASH, "deepseek-next")),
            AiProvider.DEEPSEEK
        )
        assertEquals(
            "连接正常，可用模型：deepseek-flash、deepseek-next（内置模型与官方不一致，请留意）",
            message
        )
    }

    @Test
    fun connectionMessageSkipsWarningWhenProviderHasNoBuiltinModels() {
        val message = connectionMessage(
            ProbeResult.Ok(listOf("gpt-4o-mini")),
            AiProvider.CUSTOM
        )
        assertEquals("连接正常，可用模型：gpt-4o-mini", message)
    }

    @Test
    fun connectionMessageReturnsFailureText() {
        val message = connectionMessage(
            ProbeResult.Failed("API Key 无效，请到设置中检查"),
            AiProvider.DEEPSEEK
        )
        assertEquals("API Key 无效，请到设置中检查", message)
    }

    @Test
    fun balanceMessageFormatsSingleLine() {
        val state = BalanceState.Ok(
            isAvailable = true,
            lines = listOf(BalanceLine("CNY", "110.00", "10.00", "100.00"))
        )
        assertEquals("余额 CNY 110.00（赠金 10.00 / 充值 100.00）", balanceMessage(state))
    }

    @Test
    fun balanceMessageUsesCustomLabels() {
        val state = BalanceState.Ok(
            isAvailable = true,
            lines = listOf(
                BalanceLine("CNY", "22.65", "7.40", "15.25", grantedLabel = "可用")
            )
        )
        assertEquals("余额 CNY 22.65（可用 7.40 / 充值 15.25）", balanceMessage(state))
    }

    @Test
    fun balanceMessagePrefixesWhenUnavailable() {
        val state = BalanceState.Ok(
            isAvailable = false,
            lines = listOf(BalanceLine("CNY", "0.00", "0.00", "0.00"))
        )
        assertEquals("余额不足：余额 CNY 0.00（赠金 0.00 / 充值 0.00）", balanceMessage(state))
    }

    @Test
    fun balanceMessageJoinsMultipleLines() {
        val state = BalanceState.Ok(
            isAvailable = true,
            lines = listOf(
                BalanceLine("CNY", "110.00", "10.00", "100.00"),
                BalanceLine("USD", "1.50", "0.00", "1.50")
            )
        )
        assertEquals(
            "余额 CNY 110.00（赠金 10.00 / 充值 100.00）；余额 USD 1.50（赠金 0.00 / 充值 1.50）",
            balanceMessage(state)
        )
    }

    @Test
    fun balanceMessageReturnsFailureText() {
        val message = balanceMessage(BalanceState.Failed("网络错误，请检查网络后重试"))
        assertEquals("网络错误，请检查网络后重试", message)
    }

    @Test
    fun balanceMessageHandlesEmptyLines() {
        val state = BalanceState.Ok(isAvailable = true, lines = emptyList())
        assertEquals("未返回余额信息", balanceMessage(state))
    }
}
