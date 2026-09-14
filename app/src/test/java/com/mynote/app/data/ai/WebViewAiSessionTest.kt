package com.mynote.app.data.ai

import android.content.Context
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebViewAiSessionTest {

    @Test
    fun attachSetsDesktopUserAgentWithoutAndroidKeyword() {
        // DeepSeek 前端按 UA 判移动端（回车不发送），必须固定桌面 UA。
        val session = WebViewAiSession(DeepSeekDriver())
        val webView = WebView(ApplicationProvider.getApplicationContext<Context>())
        try {
            session.attach(webView)
            assertEquals(WebViewAiSession.DESKTOP_USER_AGENT, webView.settings.userAgentString)
            assertFalse(webView.settings.userAgentString.contains("Android"))
        } finally {
            session.release()
            webView.destroy()
        }
    }
}
