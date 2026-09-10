package com.mynote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
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
            MyNoteTheme(
                darkTheme = when (settings.darkMode) {
                    DarkMode.SYSTEM -> isSystemInDarkTheme()
                    DarkMode.LIGHT -> false
                    DarkMode.DARK -> true
                },
                dynamicColor = settings.dynamicColor,
                preset = ThemePresets.resolve(settings.themeColorIndex)
            ) {
                AppNavHost(container)
            }
        }
    }
}
