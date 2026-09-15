package com.mynote.app.data.ai

/** 思考（思维链）参数的请求体样式：不同 OpenAI 兼容服务商字段名不同。 */
enum class ThinkingStyle { DEEPSEEK_OBJECT, BOOLEAN_ENABLE, NONE }

/** 余额查询的接口与返回结构样式；NONE 表示不提供余额接口。 */
enum class BalanceStyle { DEEPSEEK, SILICON_FLOW, NONE }

/**
 * 内置服务商预设。新增服务商 = 加一条枚举（必要时补充 [ThinkingStyle]/[BalanceStyle] 分支），
 * 存储命名空间、会话身份、设置页 UI 与错误映射都会自动生效。
 *
 * [serviceId] 会写入 `ai_sessions.serviceId` 并作为隐私确认作用域，已使用的值不可修改
 * （DeepSeek 保持 "deepseek-api"，历史会话与旧确认继续有效）。
 */
enum class AiProvider(
    val serviceId: String,
    val displayName: String,
    /** 预设接口地址；自定义服务商为空，由用户填写。 */
    val defaultBaseUrl: String,
    val defaultModels: List<String>,
    val thinkingStyle: ThinkingStyle,
    val balanceStyle: BalanceStyle,
    /** 拼在 `/models` 后的查询串（如硅基流动的对话模型过滤）。 */
    val modelsQuery: String?,
    /** true = 模型 id 允许用户自由填写（自定义服务商）。 */
    val freeModelInput: Boolean,
    val apiKeyPlaceholder: String,
    val keyHint: String,
    val modelHint: String
) {
    DEEPSEEK(
        serviceId = "deepseek-api",
        displayName = "DeepSeek",
        defaultBaseUrl = "https://api.deepseek.com",
        defaultModels = DeepSeekModels.all,
        thinkingStyle = ThinkingStyle.DEEPSEEK_OBJECT,
        balanceStyle = BalanceStyle.DEEPSEEK,
        modelsQuery = null,
        freeModelInput = false,
        apiKeyPlaceholder = "sk-…",
        keyHint = "在 DeepSeek 开放平台（platform.deepseek.com）创建。",
        modelHint = "deepseek-flash 更快更省；deepseek-v4-pro 更强。测试连接成功后会更新为官方最新模型列表。"
    ),
    SILICON_FLOW(
        serviceId = "siliconflow-api",
        displayName = "硅基流动",
        defaultBaseUrl = "https://api.siliconflow.cn/v1",
        defaultModels = listOf("deepseek-ai/DeepSeek-V4-Flash"),
        thinkingStyle = ThinkingStyle.BOOLEAN_ENABLE,
        balanceStyle = BalanceStyle.SILICON_FLOW,
        modelsQuery = "?sub_type=chat",
        freeModelInput = false,
        apiKeyPlaceholder = "sk-…",
        keyHint = "在硅基流动控制台（cloud.siliconflow.cn）创建。",
        modelHint = "deepseek-ai/DeepSeek-V4-Flash 更快更省；Pro/ 前缀模型更强。测试连接成功后会更新为官方对话模型列表。"
    ),
    CUSTOM(
        serviceId = "openai-compatible",
        displayName = "自定义",
        defaultBaseUrl = "",
        defaultModels = emptyList(),
        thinkingStyle = ThinkingStyle.NONE,
        balanceStyle = BalanceStyle.NONE,
        modelsQuery = null,
        freeModelInput = true,
        apiKeyPlaceholder = "服务商提供的 API Key",
        keyHint = "地址与 Key 由你的服务商提供；Key 只保存在本机，请求直连该地址。",
        modelHint = "兼容 OpenAI 协议（/chat/completions + SSE）；地址需包含版本路径，如 https://example.com/v1。"
    );

    val supportsThinking: Boolean get() = thinkingStyle != ThinkingStyle.NONE

    val supportsBalance: Boolean get() = balanceStyle != BalanceStyle.NONE

    /** 解析运行时端点；[customBaseUrl] 仅自定义服务商使用，空白时回退预设地址（可能为空串）。 */
    fun endpoint(customBaseUrl: String? = null): AiEndpoint {
        val url = defaultBaseUrl.ifEmpty { customBaseUrl.orEmpty() }.trim().trimEnd('/')
        return AiEndpoint(
            serviceId = serviceId,
            displayName = displayName,
            baseUrl = url,
            thinkingStyle = thinkingStyle,
            balanceStyle = balanceStyle,
            modelsQuery = modelsQuery
        )
    }

    companion object {
        /** 按历史落库的 serviceId 反查服务商；未知值返回 null（调用方自行回退）。 */
        fun of(serviceId: String?): AiProvider? =
            entries.firstOrNull { it.serviceId == serviceId }
    }
}

/** 运行时端点配置：请求地址、身份与协议样式，客户端与会话只依赖它。 */
data class AiEndpoint(
    val serviceId: String,
    val displayName: String,
    val baseUrl: String,
    val thinkingStyle: ThinkingStyle,
    val balanceStyle: BalanceStyle,
    val modelsQuery: String?
)
