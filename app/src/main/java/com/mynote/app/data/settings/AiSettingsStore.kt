package com.mynote.app.data.settings

import android.content.Context
import com.mynote.app.data.ai.DeepSeekModels

/** AI 助手设置：API Key（密文）+ 模型 + 深度思考 + 隐私确认。 */
class AiSettingsStore(
    context: Context,
    private val cipher: ApiKeyCipher = KeystoreApiKeyCipher()
) {

    private val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    @Volatile
    private var keyLoaded = false

    @Volatile
    private var cachedKey: String? = null

    /** 明文 Key；解密失败返回 null 并清除损坏记录（如换机恢复导致 KeyStore 密钥失效）。 */
    fun apiKey(): String? {
        if (!keyLoaded) {
            val stored = prefs.getString(KEY_API_KEY, null)
            val plain = stored?.let { cipher.decrypt(it) }
            if (stored != null && plain == null) {
                prefs.edit().remove(KEY_API_KEY).apply()
            }
            cachedKey = plain
            keyLoaded = true
        }
        return cachedKey
    }

    fun hasApiKey(): Boolean = !apiKey().isNullOrBlank()

    /** 保存 Key；返回 false 表示加密失败（未保存）。空串等价清除。 */
    fun setApiKey(key: String?): Boolean {
        val trimmed = key?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(KEY_API_KEY).apply()
            cachedKey = null
            keyLoaded = true
            return true
        }
        val encrypted = cipher.encrypt(trimmed) ?: return false
        prefs.edit().putString(KEY_API_KEY, encrypted).apply()
        cachedKey = trimmed
        keyLoaded = true
        return true
    }

    fun model(): String {
        val stored = prefs.getString(KEY_MODEL, null)
        return if (stored != null && DeepSeekModels.isValid(stored)) stored else DeepSeekModels.DEFAULT
    }

    fun setModel(id: String) {
        if (DeepSeekModels.isValid(id)) {
            prefs.edit().putString(KEY_MODEL, id).apply()
        }
    }

    fun deepThinking(): Boolean = prefs.getBoolean(KEY_DEEP_THINKING, false)

    fun setDeepThinking(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEEP_THINKING, enabled).apply()
    }

    fun isPrivacyAccepted(serviceId: String): Boolean =
        prefs.getBoolean(privacyKey(serviceId), false)

    fun acceptPrivacy(serviceId: String) {
        prefs.edit().putBoolean(privacyKey(serviceId), true).apply()
    }

    private fun privacyKey(serviceId: String) = "privacy_accepted_$serviceId"

    private companion object {
        const val KEY_API_KEY = "api_key_encrypted"
        const val KEY_MODEL = "api_model"
        const val KEY_DEEP_THINKING = "api_deep_thinking"
    }
}
