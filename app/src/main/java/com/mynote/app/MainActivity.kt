package com.mynote.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.mynote.app.data.settings.DarkMode
import com.mynote.app.ui.navigation.AppNavHost
import com.mynote.app.ui.theme.MyNoteTheme
import com.mynote.app.ui.theme.ThemePresets

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MyNoteApp).container
        setContent {
            val settings by container.themeSettingsStore.settings.collectAsState()
            val darkTheme = when (settings.darkMode) {
                DarkMode.SYSTEM -> isSystemInDarkTheme()
                DarkMode.LIGHT -> false
                DarkMode.DARK -> true
            }
            // 启动时清理回收站中已到期（超过设置页所选保留期）的笔记
            LaunchedEffect(Unit) {
                val ttlMs =
                    com.mynote.app.data.settings.TrashRetentionStore.ttlMs(
                        container.trashRetentionStore.retentionDays.value
                    )
                container.noteRepository.purgeExpiredDeletedNotes(ttlMs = ttlMs)
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
                AppNavHost(container)
            }
        }
    }
}
