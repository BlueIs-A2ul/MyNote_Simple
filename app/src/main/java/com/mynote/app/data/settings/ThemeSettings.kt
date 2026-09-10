package com.mynote.app.data.settings

enum class DarkMode { SYSTEM, LIGHT, DARK }

data class ThemeSettings(
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val themeColorIndex: Int = 0
)
