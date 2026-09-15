package com.mynote.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mynote.app.data.ai.DeepSeekApiClient
import com.mynote.app.data.db.NoteSortMode
import com.mynote.app.data.settings.AiSettingsStore
import com.mynote.app.data.settings.DarkMode
import com.mynote.app.data.settings.NoteSortStore
import com.mynote.app.data.settings.ThemeSettings
import com.mynote.app.data.settings.ThemeSettingsStore
import com.mynote.app.data.settings.TrashRetentionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(
    private val store: ThemeSettingsStore,
    private val sortStore: NoteSortStore,
    private val trashStore: TrashRetentionStore,
    private val aiStore: AiSettingsStore,
    private val apiClient: DeepSeekApiClient
) : ViewModel() {

    val settings: StateFlow<ThemeSettings> = store.settings

    /** 通用分区：列表默认排序（读写 NoteSortStore）。 */
    val defaultSort: StateFlow<NoteSortMode> = sortStore.mode

    /** 通用分区：回收站保留期（天）。 */
    val retentionDays: StateFlow<Int> = trashStore.retentionDays

    /** AI 助手：模型 id（deepseek-flash / deepseek-v4-pro）。 */
    private val _aiModel = MutableStateFlow(aiStore.model())
    val aiModel: StateFlow<String> = _aiModel

    /** AI 助手：深度思考开关（默认关，按输出计费更高）。 */
    private val _deepThinking = MutableStateFlow(aiStore.deepThinking())
    val deepThinking: StateFlow<Boolean> = _deepThinking

    /** AI 助手：是否已保存 API Key。 */
    private val _apiKeyConfigured = MutableStateFlow(aiStore.hasApiKey())
    val apiKeyConfigured: StateFlow<Boolean> = _apiKeyConfigured

    fun setDarkMode(mode: DarkMode) = store.setDarkMode(mode)

    fun setDynamicColor(enabled: Boolean) = store.setDynamicColor(enabled)

    fun setThemeColorIndex(index: Int) = store.setThemeColorIndex(index)

    fun setDefaultSort(mode: NoteSortMode) = sortStore.setMode(mode)

    fun setRetentionDays(days: Int) = trashStore.setRetentionDays(days)

    fun setAiModel(id: String) {
        aiStore.setModel(id)
        _aiModel.value = aiStore.model()
    }

    fun setDeepThinking(enabled: Boolean) {
        aiStore.setDeepThinking(enabled)
        _deepThinking.value = enabled
    }

    /** 保存 API Key；false 表示加密失败（系统密钥库不可用）。 */
    fun saveApiKey(key: String): Boolean {
        val ok = aiStore.setApiKey(key)
        if (ok) _apiKeyConfigured.value = aiStore.hasApiKey()
        return ok
    }

    fun savedApiKey(): String? = aiStore.apiKey()

    fun clearApiKey() {
        aiStore.setApiKey("")
        _apiKeyConfigured.value = false
    }

    /** 测试连接：null 表示成功，否则为错误文案。 */
    suspend fun testConnection(key: String): String? = apiClient.verifyApiKey(key)

    companion object {
        fun factory(
            store: ThemeSettingsStore,
            sortStore: NoteSortStore,
            trashStore: TrashRetentionStore,
            aiStore: AiSettingsStore,
            apiClient: DeepSeekApiClient
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(store, sortStore, trashStore, aiStore, apiClient) }
        }
    }
}
