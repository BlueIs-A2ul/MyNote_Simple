package com.mynote.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mynote.app.data.ai.AiApiClient
import com.mynote.app.data.ai.AiEndpoint
import com.mynote.app.data.ai.AiProbe
import com.mynote.app.data.ai.AiProvider
import com.mynote.app.data.ai.BalanceState
import com.mynote.app.data.ai.ProbeResult
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
    private val clientFactory: (AiEndpoint) -> AiProbe = { AiApiClient(it) }
) : ViewModel() {

    val settings: StateFlow<ThemeSettings> = store.settings

    /** 通用分区：列表默认排序（读写 NoteSortStore）。 */
    val defaultSort: StateFlow<NoteSortMode> = sortStore.mode

    /** 通用分区：回收站保留期（天）。 */
    val retentionDays: StateFlow<Int> = trashStore.retentionDays

    /** AI 助手：当前服务商（DeepSeek / 硅基流动 / 自定义）。 */
    private val _aiProvider = MutableStateFlow(aiStore.provider())
    val aiProvider: StateFlow<AiProvider> = _aiProvider

    /** AI 助手：自定义服务商的接口地址。 */
    private val _customBaseUrl = MutableStateFlow(aiStore.customBaseUrl())
    val customBaseUrl: StateFlow<String> = _customBaseUrl

    /** AI 助手：当前服务商的模型 id。 */
    private val _aiModel = MutableStateFlow(aiStore.model())
    val aiModel: StateFlow<String> = _aiModel

    /** AI 助手：可用模型列表（按服务商持久化；测试连接成功后刷新为官方列表）。 */
    private val _availableModels = MutableStateFlow(aiStore.models())
    val availableModels: StateFlow<List<String>> = _availableModels

    /** AI 助手：深度思考开关（仅支持思考参数的服务商展示）。 */
    private val _deepThinking = MutableStateFlow(aiStore.deepThinking())
    val deepThinking: StateFlow<Boolean> = _deepThinking

    /** AI 助手：当前服务商是否已保存 API Key。 */
    private val _apiKeyConfigured = MutableStateFlow(aiStore.hasApiKey())
    val apiKeyConfigured: StateFlow<Boolean> = _apiKeyConfigured

    fun setDarkMode(mode: DarkMode) = store.setDarkMode(mode)

    fun setDynamicColor(enabled: Boolean) = store.setDynamicColor(enabled)

    fun setThemeColorIndex(index: Int) = store.setThemeColorIndex(index)

    fun setDefaultSort(mode: NoteSortMode) = sortStore.setMode(mode)

    fun setRetentionDays(days: Int) = trashStore.setRetentionDays(days)

    /** 切换服务商：持久化并刷新该服务商的模型/思考/Key 状态。 */
    fun setProvider(provider: AiProvider) {
        if (provider == _aiProvider.value) return
        aiStore.setProvider(provider)
        _aiProvider.value = provider
        refreshProviderState()
    }

    /**
     * 保存自定义接口地址；仅接受 http(s) 开头，空白等价清除。
     * 返回 false 表示地址非法（未保存）。
     */
    fun setCustomBaseUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return false
        }
        aiStore.setCustomBaseUrl(trimmed)
        _customBaseUrl.value = aiStore.customBaseUrl()
        return true
    }

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

    /** 当前服务商的端点（含自定义地址）。 */
    fun endpoint(): AiEndpoint = _aiProvider.value.endpoint(aiStore.customBaseUrl())

    /**
     * 测试连接：成功时把官方模型列表写回该服务商设置、刷新 [availableModels]，
     * 当前模型不在新列表内则自动选中校正结果；始终返回可展示文案。
     */
    suspend fun testConnection(key: String): String {
        val provider = _aiProvider.value
        val result = clientFactory(provider.endpoint(aiStore.customBaseUrl())).probe(key)
        if (result is ProbeResult.Ok) {
            aiStore.setModels(result.models)
            val models = aiStore.models()
            _availableModels.value = models
            val next = modelAfterRefresh(_aiModel.value, models, provider.defaultModels)
            if (next != _aiModel.value) {
                aiStore.setModel(next)
                _aiModel.value = aiStore.model()
            }
        }
        return connectionMessage(result, provider)
    }

    /** 查询余额：始终返回可展示文案（总额/赠金/充值，或不足提示）。 */
    suspend fun queryBalance(key: String): String {
        val balance = clientFactory(_aiProvider.value.endpoint(aiStore.customBaseUrl())).fetchBalance(key)
        return balanceMessage(balance)
    }

    private fun refreshProviderState() {
        _aiModel.value = aiStore.model()
        _availableModels.value = aiStore.models()
        _deepThinking.value = aiStore.deepThinking()
        _apiKeyConfigured.value = aiStore.hasApiKey()
    }

    companion object {
        fun factory(
            store: ThemeSettingsStore,
            sortStore: NoteSortStore,
            trashStore: TrashRetentionStore,
            aiStore: AiSettingsStore,
            clientFactory: (AiEndpoint) -> AiProbe = { AiApiClient(it) }
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(store, sortStore, trashStore, aiStore, clientFactory)
            }
        }
    }
}

/** 模型刷新校正：当前模型仍在列表内则保留；否则取列表首项；列表为空回退服务商内置默认首项。 */
internal fun modelAfterRefresh(current: String, models: List<String>, defaults: List<String>): String = when {
    current in models -> current
    models.isNotEmpty() -> models.first()
    else -> defaults.firstOrNull() ?: ""
}

/** 测试连接结果 → 展示文案：成功时列出可用模型，官方列表与内置不一致时追加提醒。 */
internal fun connectionMessage(result: ProbeResult, provider: AiProvider): String = when (result) {
    is ProbeResult.Ok -> {
        val base = "连接正常，可用模型：" + result.models.joinToString("、")
        if (provider.defaultModels.all { it in result.models }) {
            base
        } else {
            "$base（内置模型与官方不一致，请留意）"
        }
    }
    is ProbeResult.Failed -> result.message
}

/** 余额查询结果 → 展示文案：多条按「；」拼接，余额不可用时加前缀；无数据给兜底文案。 */
internal fun balanceMessage(state: BalanceState): String = when (state) {
    is BalanceState.Ok -> {
        val text = state.lines.joinToString("；") {
            "余额 ${it.currency} ${it.total}（${it.grantedLabel} ${it.granted} / ${it.toppedUpLabel} ${it.toppedUp}）"
        }
        when {
            text.isEmpty() -> "未返回余额信息"
            !state.isAvailable -> "余额不足：$text"
            else -> text
        }
    }
    is BalanceState.Failed -> state.message
}
