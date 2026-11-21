package com.mynote.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.mynote.app.data.settings.DarkMode
import com.mynote.app.ui.navigation.AppNavHost
import com.mynote.app.ui.theme.MyNoteTheme
import com.mynote.app.ui.theme.ThemePresets
import com.mynote.app.util.deepLinkNoteId
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** 条目 50：待处理的深链目标（消费后清空，避免旋转重复导航）。 */
    private val pendingDeepLink = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 条目 100：先开一次 edge-to-edge，首帧就不会缺 inset（深色模式变化的更新仍由下面的 effect 负责）
        enableEdgeToEdge()
        val container = (application as MyNoteApp).container
        pendingDeepLink.value = deepLinkNoteId(intent?.data)
        setContent {
            val deepLinkId by pendingDeepLink.collectAsStateWithLifecycle()
            val settings by container.themeSettingsStore.settings.collectAsStateWithLifecycle()
            val darkTheme = when (settings.darkMode) {
                DarkMode.SYSTEM -> isSystemInDarkTheme()
                DarkMode.LIGHT -> false
                DarkMode.DARK -> true
            }
            // edge-to-edge：让系统栏/IME insets 派发给 Compose，imePadding() 才生效。
            // 深色检测跟随应用内深色模式设置（而非仅系统配置），避免图标对比度错误；
            // 设置变化时重应用。
            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        Color.TRANSPARENT, Color.TRANSPARENT
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        Color.argb(0xE6, 0xFF, 0xFF, 0xFF),
                        Color.argb(0x80, 0x1B, 0x1B, 0x1B)
                    ) { darkTheme }
                )
            }
            MyNoteTheme(
                darkTheme = darkTheme,
                dynamicColor = settings.dynamicColor,
                preset = ThemePresets.resolve(settings.themeColorIndex)
            ) {
                AppNavHost(
                    container,
                    deepLinkNoteId = deepLinkId,
                    onDeepLinkConsumed = { pendingDeepLink.value = null }
                )
            }
        }
    }

    /** 应用已在运行时从外部链接打开：重新收一次深链。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkNoteId(intent.data)?.let { pendingDeepLink.value = it }
    }
}