package com.mynote.app.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.ai.BalanceLine
import com.mynote.app.data.ai.BalanceState
import com.mynote.app.data.ai.DeepSeekApiClient
import com.mynote.app.data.ai.DeepSeekModels
import com.mynote.app.data.ai.ProbeResult
import com.mynote.app.data.db.NoteSortMode
import com.mynote.app.data.settings.AiSettingsStore
import com.mynote.app.data.settings.ApiKeyCipher
import com.mynote.app.data.settings.DarkMode
import com.mynote.app.data.settings.NoteSortStore
import com.mynote.app.data.settings.ThemeSettingsStore
import com.mynote.app.data.settings.TrashRetentionStore
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

    private lateinit var context: Context
    private lateinit var store: ThemeSettingsStore
    private lateinit var sortStore: NoteSortStore
    private lateinit var trashStore: TrashRetentionStore
    private lateinit var aiStore: AiSettingsStore
    private lateinit var vm: SettingsViewModel

    @Before
    fun setup() {
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
        vm = SettingsViewModel(store, sortStore, trashStore, aiStore, DeepSeekApiClient())
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
        val message = connectionMessage(ProbeResult.Ok(DeepSeekModels.all))
        assertEquals("连接正常，可用模型：deepseek-flash、deepseek-v4-pro", message)
    }

    @Test
    fun connectionMessageWarnsWhenBuiltinModelsMissing() {
        val message = connectionMessage(ProbeResult.Ok(listOf(DeepSeekModels.FLASH, "deepseek-next")))
        assertEquals(
            "连接正常，可用模型：deepseek-flash、deepseek-next（内置模型与官方不一致，请留意）",
            message
        )
    }

    @Test
    fun connectionMessageReturnsFailureText() {
        val message = connectionMessage(ProbeResult.Failed("API Key 无效，请到设置中检查"))
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
