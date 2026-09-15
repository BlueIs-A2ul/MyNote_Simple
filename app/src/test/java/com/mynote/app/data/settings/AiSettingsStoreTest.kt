package com.mynote.app.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.ai.AiProvider
import com.mynote.app.data.ai.DeepSeekModels
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
class AiSettingsStoreTest {

    /** 假 cipher：可标记加密失败，模拟损坏密文。 */
    private class FakeCipher(val failEncrypt: Boolean = false) : ApiKeyCipher {
        override fun encrypt(plain: String): String? =
            if (failEncrypt) null else "enc:$plain"

        override fun decrypt(stored: String): String? =
            stored.removePrefix("enc:").takeIf { stored.startsWith("enc:") }
    }

    private lateinit var context: Context
    private lateinit var store: AiSettingsStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit().clear().commit()
        store = AiSettingsStore(context, FakeCipher())
    }

    @Test
    fun apiKeyRoundTripsAndTrims() {
        assertFalse(store.hasApiKey())

        assertTrue(store.setApiKey("  sk-123  "))
        assertEquals("sk-123", store.apiKey())
        assertTrue(store.hasApiKey())
    }

    @Test
    fun blankKeyClearsStoredValue() {
        store.setApiKey("sk-123")

        assertTrue(store.setApiKey("   "))

        assertNull(store.apiKey())
        assertFalse(store.hasApiKey())
    }

    @Test
    fun encryptFailureKeepsStoredValueUnchanged() {
        val failing = AiSettingsStore(context, FakeCipher(failEncrypt = true))

        assertFalse(failing.setApiKey("sk-123"))
        assertFalse(store.hasApiKey())
    }

    @Test
    fun corruptCiphertextIsClearedAndReturnsNull() {
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
            .edit().putString("api_key_encrypted", "garbage").commit()

        val fresh = AiSettingsStore(context, FakeCipher())

        assertNull(fresh.apiKey())
        assertFalse(fresh.hasApiKey())
        // 第二次读取依然安全
        assertNull(fresh.apiKey())
    }

    @Test
    fun modelsRoundTripAndPersistAcrossInstances() {
        assertEquals(DeepSeekModels.all, store.models())

        store.setModels(listOf("deepseek-flash", "deepseek-x"))

        assertEquals(listOf("deepseek-flash", "deepseek-x"), store.models())
        assertEquals(
            listOf("deepseek-flash", "deepseek-x"),
            AiSettingsStore(context, FakeCipher()).models()
        )
    }

    @Test
    fun setModelsFiltersBlankAndDuplicates() {
        store.setModels(listOf(" deepseek-flash ", "", "deepseek-flash", "   ", "deepseek-x"))

        assertEquals(listOf("deepseek-flash", "deepseek-x"), store.models())
    }

    @Test
    fun modelsFallBackToBuiltinWhenEmptyOrBlank() {
        store.setModels(emptyList())
        assertEquals(DeepSeekModels.all, store.models())

        store.setModels(listOf("deepseek-x"))
        store.setModels(listOf("   ", ""))
        assertEquals(DeepSeekModels.all, store.models())
    }

    @Test
    fun modelsFallBackToBuiltinWhenStoredValueCorrupt() {
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
            .edit().putString("api_models", "\n \ndeepseek-x\n").commit()
        assertEquals(listOf("deepseek-x"), store.models())

        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
            .edit().putString("api_models", "\n \n").commit()
        assertEquals(DeepSeekModels.all, store.models())
    }

    @Test
    fun modelDefaultsToFlashAndRejectsUnknownIds() {
        assertEquals(DeepSeekModels.FLASH, store.model())

        store.setModel("whatever")
        assertEquals(DeepSeekModels.FLASH, store.model())

        store.setModel(DeepSeekModels.V4_PRO)
        assertEquals(DeepSeekModels.V4_PRO, store.model())

        // 新语义：模型合法性以 models() 列表为准
        store.setModels(listOf("deepseek-x"))
        assertEquals(DeepSeekModels.DEFAULT, store.model())

        store.setModel("deepseek-x")
        assertEquals("deepseek-x", store.model())

        store.setModel(DeepSeekModels.V4_PRO)
        assertEquals("deepseek-x", store.model())
    }

    @Test
    fun modelFallsBackWhenStoredIdDropsOutOfList() {
        store.setModels(listOf("deepseek-x", "deepseek-y"))
        store.setModel("deepseek-x")
        assertEquals("deepseek-x", store.model())

        store.setModels(listOf(DeepSeekModels.V4_PRO))

        assertEquals(DeepSeekModels.DEFAULT, store.model())
    }

    @Test
    fun deepThinkingDefaultsOff() {
        assertFalse(store.deepThinking())

        store.setDeepThinking(true)
        assertTrue(store.deepThinking())
    }

    @Test
    fun privacyAcceptanceIsPerService() {
        assertFalse(store.isPrivacyAccepted("deepseek-api"))
        store.acceptPrivacy("deepseek-api")
        assertTrue(store.isPrivacyAccepted("deepseek-api"))
        assertFalse(store.isPrivacyAccepted("deepseek"))
    }

    @Test
    fun providerDefaultsToDeepSeekAndRoundTrips() {
        assertEquals(AiProvider.DEEPSEEK, store.provider())

        store.setProvider(AiProvider.SILICON_FLOW)

        assertEquals(AiProvider.SILICON_FLOW, store.provider())
        assertEquals(AiProvider.SILICON_FLOW, AiSettingsStore(context, FakeCipher()).provider())
    }

    @Test
    fun providerFallsBackWhenStoredValueUnknown() {
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
            .edit().putString("ai_provider", "legacy-web").commit()

        assertEquals(AiProvider.DEEPSEEK, store.provider())
    }

    @Test
    fun keysAreScopedPerProviderAndDeepSeekKeepsLegacyKeyName() {
        store.setApiKey("sk-deepseek", AiProvider.DEEPSEEK)
        store.setApiKey("sk-silicon", AiProvider.SILICON_FLOW)

        val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
        assertEquals("enc:sk-deepseek", prefs.getString("api_key_encrypted", null))
        assertEquals("enc:sk-silicon", prefs.getString("api_key_encrypted_siliconflow-api", null))

        assertEquals("sk-deepseek", store.apiKey(AiProvider.DEEPSEEK))
        assertEquals("sk-silicon", store.apiKey(AiProvider.SILICON_FLOW))

        store.setApiKey("", AiProvider.SILICON_FLOW)
        assertEquals("sk-deepseek", store.apiKey(AiProvider.DEEPSEEK))
        assertNull(store.apiKey(AiProvider.SILICON_FLOW))
    }

    @Test
    fun legacyDeepSeekKeyIsReadByDefaultProvider() {
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
            .edit().putString("api_key_encrypted", "enc:legacy-key").commit()

        assertEquals("legacy-key", store.apiKey())
        assertTrue(store.hasApiKey())
    }

    @Test
    fun modelsAreScopedPerProviderWithOwnDefaults() {
        assertEquals(DeepSeekModels.all, store.models(AiProvider.DEEPSEEK))
        assertEquals(AiProvider.SILICON_FLOW.defaultModels, store.models(AiProvider.SILICON_FLOW))

        store.setModels(listOf("deepseek-ai/DeepSeek-V4"), AiProvider.SILICON_FLOW)

        assertEquals(listOf("deepseek-ai/DeepSeek-V4"), store.models(AiProvider.SILICON_FLOW))
        assertEquals("DeepSeek 列表不受影响", DeepSeekModels.all, store.models(AiProvider.DEEPSEEK))
    }

    @Test
    fun modelAndThinkingAreScopedPerProvider() {
        store.setModel(DeepSeekModels.V4_PRO, AiProvider.DEEPSEEK)
        store.setDeepThinking(true, AiProvider.DEEPSEEK)

        assertEquals(DeepSeekModels.V4_PRO, store.model(AiProvider.DEEPSEEK))
        assertTrue(store.deepThinking(AiProvider.DEEPSEEK))

        assertEquals(AiProvider.SILICON_FLOW.defaultModels.first(), store.model(AiProvider.SILICON_FLOW))
        assertFalse(store.deepThinking(AiProvider.SILICON_FLOW))
    }

    @Test
    fun customProviderAllowsFreeModelInput() {
        store.setProvider(AiProvider.CUSTOM)
        assertEquals("", store.model())

        store.setModel("gpt-4o-mini", AiProvider.CUSTOM)

        assertEquals("gpt-4o-mini", store.model(AiProvider.CUSTOM))
        assertEquals("gpt-4o-mini", store.model())

        store.setModel("   ", AiProvider.CUSTOM)
        assertEquals("gpt-4o-mini", store.model(AiProvider.CUSTOM))
    }

    @Test
    fun customBaseUrlNormalizesAndClears() {
        assertEquals("", store.customBaseUrl())

        store.setCustomBaseUrl("  https://example.com/v1/  ")
        assertEquals("https://example.com/v1", store.customBaseUrl())

        store.setCustomBaseUrl("   ")
        assertEquals("", store.customBaseUrl())
    }
}
