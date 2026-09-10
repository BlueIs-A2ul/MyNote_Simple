package com.mynote.app.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ThemeSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("theme_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<ThemeSettings> = _settings.asStateFlow()

    fun setDarkMode(mode: DarkMode) {
        prefs.edit().putString(KEY_DARK_MODE, mode.name).apply()
        _settings.value = _settings.value.copy(darkMode = mode)
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
        _settings.value = _settings.value.copy(dynamicColor = enabled)
    }

    fun setThemeColorIndex(index: Int) {
        prefs.edit().putInt(KEY_THEME_COLOR_INDEX, index).apply()
        _settings.value = _settings.value.copy(themeColorIndex = index)
    }

    private fun read(): ThemeSettings {
        val darkMode = prefs.getString(KEY_DARK_MODE, null)
            ?.let { runCatching { DarkMode.valueOf(it) }.getOrNull() }
            ?: DarkMode.SYSTEM
        return ThemeSettings(
            darkMode = darkMode,
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, true),
            themeColorIndex = prefs.getInt(KEY_THEME_COLOR_INDEX, 0)
        )
    }

    private companion object {
        const val KEY_DARK_MODE = "dark_mode"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_THEME_COLOR_INDEX = "theme_color_index"
    }
}
