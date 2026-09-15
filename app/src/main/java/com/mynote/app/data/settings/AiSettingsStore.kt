package com.mynote.app.data.settings

import android.content.Context
import com.mynote.app.data.ai.AiProvider

/**
 * AI 助手设置：服务商 + API Key（密文）+ 模型（含动态列表）+ 深度思考 + 隐私确认。
 * 除 DeepSeek 沿用历史 key 名（老用户无感升级）外，其余服务商按 serviceId 命名空间隔离，切换互不串数据。
 * 未显式传 [AiProvider] 的方法都作用于当前选中的服务商。
 */
class AiSettingsStore(
    context: Context,
    private val cipher: ApiKeyCipher = KeystoreApiKeyCipher()
) {

    private val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    /** 当前服务商；未设置/损坏回退 DeepSeek。 */
    fun provider(): AiProvider {
        return AiProvider.of(prefs.getString(KEY_PROVIDER, null)) ?: AiProvider.DEEPSEEK
    }

    fun setProvider(provider: AiProvider) {
        prefs.edit().putString(KEY_PROVIDER, provider.serviceId).apply()
    }

    /** 自定义服务商的接口地址（尾斜杠已归一）；未填写返回空串。 */
    fun customBaseUrl(): String = prefs.getString(KEY_CUSTOM_BASE_URL, null).orEmpty()

    /** 保存自定义接口地址；空白等价清除。 */
    fun setCustomBaseUrl(url: String) {
        val cleaned = url.trim().trimEnd('/')
        prefs.edit().apply {
            if (cleaned.isEmpty()) remove(KEY_CUSTOM_BASE_URL) else putString(KEY_CUSTOM_BASE_URL, cleaned)
        }.apply()
    }

    /** 明文 Key；解密失败返回 null 并清除损坏记录（如换机恢复导致 KeyStore 密钥失效）。 */
    fun apiKey(provider: AiProvider = provider()): String? {
        val prefName = scopedKey(KEY_API_KEY, provider)
        val stored = prefs.getString(prefName, null) ?: return null
        val plain = cipher.decrypt(stored)
        if (plain == null) prefs.edit().remove(prefName).apply()
        return plain
    }

    fun hasApiKey(provider: AiProvider = provider()): Boolean = !apiKey(provider).isNullOrBlank()

    /** 保存 Key；返回 false 表示加密失败（未保存）。空串等价清除。 */
    fun setApiKey(key: String?, provider: AiProvider = provider()): Boolean {
        val trimmed = key?.trim().orEmpty()
        val prefName = scopedKey(KEY_API_KEY, provider)
        if (trimmed.isEmpty()) {
            prefs.edit().remove(prefName).apply()
            return true
        }
        val encrypted = cipher.encrypt(trimmed) ?: return false
        prefs.edit().putString(prefName, encrypted).apply()
        return true
    }

    /**
     * 可用模型 id 列表：读取持久化值（`\n` 连接）并过滤空白、去重。
     * 无有效项（未设置/空白/损坏）时回退该服务商的内置默认列表（自定义服务商为空列表）。
     */
    fun models(provider: AiProvider = provider()): List<String> {
        val stored = prefs.getString(scopedKey(KEY_MODELS, provider), null)
        val parsed = stored?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()
        return parsed.ifEmpty { provider.defaultModels }
    }

    /** 保存可用模型列表：过滤空白、去重后按 `\n` 连接持久化；空列表等价清除（[models] 回退内置）。 */
    fun setModels(models: List<String>, provider: AiProvider = provider()) {
        val cleaned = models.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val prefName = scopedKey(KEY_MODELS, provider)
        prefs.edit().apply {
            if (cleaned.isEmpty()) {
                remove(prefName)
            } else {
                putString(prefName, cleaned.joinToString("\n"))
            }
        }.apply()
    }

    /**
     * 当前模型 id：持久化值在 [models] 内（或服务商允许自由填写）时返回它，
     * 否则回退 [AiProvider.defaultModels] 首项（可能为空串）。
     */
    fun model(provider: AiProvider = provider()): String {
        val stored = prefs.getString(scopedKey(KEY_MODEL, provider), null)?.trim()
        if (stored != null && (provider.freeModelInput || stored in models(provider))) return stored
        return provider.defaultModels.firstOrNull() ?: ""
    }

    /** 保存模型 id；预设服务商仅接受 [models] 内的 id，自由填写服务商接受任意非空白 id。 */
    fun setModel(id: String, provider: AiProvider = provider()) {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return
        if (provider.freeModelInput || trimmed in models(provider)) {
            prefs.edit().putString(scopedKey(KEY_MODEL, provider), trimmed).apply()
        }
    }

    fun deepThinking(provider: AiProvider = provider()): Boolean =
        prefs.getBoolean(scopedKey(KEY_DEEP_THINKING, provider), false)

    fun setDeepThinking(enabled: Boolean, provider: AiProvider = provider()) {
        prefs.edit().putBoolean(scopedKey(KEY_DEEP_THINKING, provider), enabled).apply()
    }

    fun isPrivacyAccepted(serviceId: String): Boolean =
        prefs.getBoolean(privacyKey(serviceId), false)

    fun acceptPrivacy(serviceId: String) {
        prefs.edit().putBoolean(privacyKey(serviceId), true).apply()
    }

    private fun privacyKey(serviceId: String) = "privacy_accepted_$serviceId"

    /** DeepSeek 沿用历史 key 名（兼容老用户），其余服务商加 serviceId 后缀。 */
    private fun scopedKey(base: String, provider: AiProvider): String =
        if (provider == AiProvider.DEEPSEEK) base else "${base}_${provider.serviceId}"

    private companion object {
        const val KEY_API_KEY = "api_key_encrypted"
        const val KEY_MODEL = "api_model"
        const val KEY_MODELS = "api_models"
        const val KEY_DEEP_THINKING = "api_deep_thinking"
        const val KEY_PROVIDER = "ai_provider"
        const val KEY_CUSTOM_BASE_URL = "api_custom_base_url"
    }
}
