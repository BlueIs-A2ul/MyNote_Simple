package com.mynote.app.data.ai

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

internal class MyNoteJsBridge(private val onJson: (String) -> Unit) {
    @JavascriptInterface
    fun emit(json: String) {
        onJson(json)
    }
}

class WebViewAiSession(initialDriver: AiWebDriver) : AiWebSession {

    private val _events = MutableSharedFlow<AiWebEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: SharedFlow<AiWebEvent> = _events

    override var driver: AiWebDriver = initialDriver
        private set

    private var webView: WebView? = null
    private var desiredUrl: String? = null
    private var pendingSend: String? = null
    private var pendingNewChat = false
    private var pageReady = false
    private var loggedIn: Boolean? = null
    private var lastChatId: String? = null

    private val bridge = MyNoteJsBridge { json ->
        val event = AiWebEventParser.parse(json)
        if (event != null) {
            val view = webView
            if (view != null) view.post { handleEvent(event) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun attach(webView: WebView) {
        this.webView = webView
        lastChatId = null
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        // 默认 WebView UA 含 "Android"：DeepSeek 前端按 UA 判定移动端，
        // 移动端分支回车不发送且按钮无文字标签，驱动脚本会全部落空。
        // 固定桌面 Chrome UA，走桌面分支（回车兜底可用 + 结构定位点击发送按钮）。
        webView.settings.userAgentString = DESKTOP_USER_AGENT
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(bridge, BRIDGE_NAME)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                pageReady = true
                _events.tryEmit(AiWebEvent.PageReady)
                emitChatId(driver.parseChatId(url ?: view.url.orEmpty()))
                view.evaluateJavascript(BOOTSTRAP_JS, null)
                if (pendingNewChat) {
                    pendingNewChat = false
                    view.evaluateJavascript(driver.newChatJs(), null)
                }
                view.evaluateJavascript(driver.loginCheckJs(), null)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                emitChatId(driver.parseChatId(url.orEmpty()))
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    pageReady = false
                    loggedIn = null
                    _events.tryEmit(AiWebEvent.PageError(error.description?.toString() ?: "网页加载失败"))
                }
            }
        }
        val start = desiredUrl ?: driver.homeUrl
        desiredUrl = start
        webView.loadUrl(start)
    }

    override fun openNewChat() {
        pendingSend = null
        pendingNewChat = true
        pageReady = false
        loggedIn = null
        val url = driver.homeUrl
        desiredUrl = url
        webView?.loadUrl(url)
    }

    override fun openChat(remoteChatId: String) {
        pendingSend = null
        pendingNewChat = false
        pageReady = false
        loggedIn = null
        val url = driver.chatUrl(remoteChatId)
        desiredUrl = url
        webView?.loadUrl(url)
    }

    override fun send(text: String) {
        pendingSend = text
        val view = webView ?: return
        if (pageReady && loggedIn == true) {
            flushPendingSend()
        } else {
            view.evaluateJavascript(driver.loginCheckJs(), null)
        }
    }

    override fun stop() {
        pendingSend = null
        val view = webView ?: return
        view.evaluateJavascript(driver.stopObservingJs(), null)
        view.evaluateJavascript(driver.stopGeneratingJs(), null)
    }

    override fun release() {
        val view = webView
        if (view != null) {
            view.evaluateJavascript(driver.stopObservingJs(), null)
            view.removeJavascriptInterface(BRIDGE_NAME)
        }
        webView = null
        pageReady = false
        loggedIn = null
        pendingSend = null
        pendingNewChat = false
    }

    private fun emitChatId(id: String?) {
        if (id != null && id != lastChatId) {
            lastChatId = id
            _events.tryEmit(AiWebEvent.ChatId(id))
        }
    }

    private fun handleEvent(event: AiWebEvent) {
        when (event) {
            is AiWebEvent.LoginState -> {
                loggedIn = event.loggedIn
                if (event.loggedIn) flushPendingSend()
            }
            AiWebEvent.PageReady -> pageReady = true
            else -> Unit
        }
        _events.tryEmit(event)
    }

    private fun flushPendingSend() {
        val view = webView ?: return
        val text = pendingSend ?: return
        pendingSend = null
        view.evaluateJavascript(driver.sendMessageJs(text), null)
        view.postDelayed({
            webView?.evaluateJavascript(driver.observeReplyJs(), null)
        }, 300)
    }

    companion object {
        /** 桌面 Chrome UA：避免 DeepSeek 把 WebView 判为移动端（移动端回车不发送）。公开给测试断言。 */
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

        private const val BRIDGE_NAME = "MyNoteJsBridge"

        private val BOOTSTRAP_JS = """
            window.__mynote = window.__mynote || {};
            window.__mynote.emit = function (type, payload) {
              try { MyNoteJsBridge.emit(JSON.stringify({ type: type, payload: payload || {} })); } catch (e) {}
            };
        """.trimIndent()
    }
}
