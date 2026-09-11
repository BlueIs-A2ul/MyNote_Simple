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

    private val bridge = MyNoteJsBridge { json ->
        val event = AiWebEventParser.parse(json)
        if (event != null) {
            val view = webView
            if (view != null) view.post { handleEvent(event) } else handleEvent(event)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun attach(webView: WebView) {
        this.webView = webView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(bridge, BRIDGE_NAME)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                pageReady = true
                _events.tryEmit(AiWebEvent.PageReady)
                driver.parseChatId(url ?: view.url.orEmpty())?.let { _events.tryEmit(AiWebEvent.ChatId(it)) }
                view.evaluateJavascript(BOOTSTRAP_JS, null)
                if (pendingNewChat) {
                    pendingNewChat = false
                    view.evaluateJavascript(driver.newChatJs(), null)
                }
                view.evaluateJavascript(driver.loginCheckJs(), null)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
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
        val url = driver.homeUrl
        desiredUrl = url
        webView?.loadUrl(url)
    }

    override fun openChat(remoteChatId: String) {
        pendingSend = null
        pendingNewChat = false
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
        val view = webView ?: return
        view.evaluateJavascript(driver.stopObservingJs(), null)
        view.evaluateJavascript(driver.stopGeneratingJs(), null)
    }

    override fun release() {
        webView?.removeJavascriptInterface(BRIDGE_NAME)
        webView = null
        pageReady = false
        loggedIn = null
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

    private companion object {
        const val BRIDGE_NAME = "MyNoteJsBridge"
        val BOOTSTRAP_JS = """
            window.__mynote = window.__mynote || {};
            window.__mynote.emit = function (type, payload) {
              try { MyNoteJsBridge.emit(JSON.stringify({ type: type, payload: payload || {} })); } catch (e) {}
            };
        """.trimIndent()
    }
}
