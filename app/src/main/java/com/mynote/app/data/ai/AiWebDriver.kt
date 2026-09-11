package com.mynote.app.data.ai

/**
 * 单个网页 AI 服务的适配器：URL 规则、DOM 选择器与注入脚本都集中在这里。
 * 新增服务 = 新增一个实现 + 在 AiDriverRegistry 注册一行。
 * 所有脚本为纯字符串（可单测），运行环境里 window.__mynote.emit 由 WebViewAiSession 注入。
 */
interface AiWebDriver {
    val id: String
    val displayName: String
    val homeUrl: String

    fun chatUrl(remoteChatId: String): String
    fun parseChatId(url: String): String?

    /** 检测登录态，结果经 emit('loginState', {loggedIn}) 上报。 */
    fun loginCheckJs(): String

    /** 点击页面“新对话”；找不到时自行超时退出。 */
    fun newChatJs(): String

    /** 填入输入框并点发送；找不到输入框时 emit('replyError', {reason})。 */
    fun sendMessageJs(text: String): String

    /** 启动回答观察：emit replyChunk / replyDone / replyError。 */
    fun observeReplyJs(): String

    /** 断开回答观察。 */
    fun stopObservingJs(): String

    /** 点击页面“停止生成”（若存在）。 */
    fun stopGeneratingJs(): String
}
