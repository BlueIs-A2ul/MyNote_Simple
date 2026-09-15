package com.mynote.app.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
    fun modelDefaultsToFlashAndRejectsUnknownIds() {
        assertEquals(DeepSeekModels.FLASH, store.model())

        store.setModel("whatever")
        assertEquals(DeepSeekModels.FLASH, store.model())

        store.setModel(DeepSeekModels.V4_PRO)
        assertEquals(DeepSeekModels.V4_PRO, store.model())
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
}
