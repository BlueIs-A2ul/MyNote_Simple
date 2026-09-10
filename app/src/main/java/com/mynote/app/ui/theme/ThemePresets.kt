package com.mynote.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

data class ThemePreset(
    val label: String,
    val light: ColorScheme,
    val dark: ColorScheme
)

object ThemePresets {

    val all: List<ThemePreset> = listOf(
        ThemePreset(
            label = "靛蓝",
            light = lightColorScheme(
                primary = IndigoPrimary,
                primaryContainer = IndigoContainer,
                secondary = TealAccent
            ),
            dark = darkColorScheme(
                primary = IndigoPrimary,
                primaryContainer = IndigoContainer,
                secondary = TealAccent
            )
        ),
        ThemePreset(
            label = "玫红",
            light = lightColorScheme(
                primary = Color(0xFFC2185B),
                primaryContainer = Color(0xFFF8BBD0),
                secondary = Color(0xFF7B1FA2)
            ),
            dark = darkColorScheme(
                primary = Color(0xFFF48FB1),
                primaryContainer = Color(0xFF880E4F),
                secondary = Color(0xFFCE93D8)
            )
        ),
        ThemePreset(
            label = "翠绿",
            light = lightColorScheme(
                primary = Color(0xFF2E7D32),
                primaryContainer = Color(0xFFC8E6C9),
                secondary = Color(0xFF00695C)
            ),
            dark = darkColorScheme(
                primary = Color(0xFFA5D6A7),
                primaryContainer = Color(0xFF1B5E20),
                secondary = Color(0xFF80CBC4)
            )
        ),
        ThemePreset(
            label = "橙",
            light = lightColorScheme(
                primary = Color(0xFFE65100),
                primaryContainer = Color(0xFFFFE0B2),
                secondary = Color(0xFFBF360C)
            ),
            dark = darkColorScheme(
                primary = Color(0xFFFFB74D),
                primaryContainer = Color(0xFF8F2E00),
                secondary = Color(0xFFFF8A65)
            )
        ),
        ThemePreset(
            label = "紫",
            light = lightColorScheme(
                primary = Color(0xFF6A1B9A),
                primaryContainer = Color(0xFFE1BEE7),
                secondary = Color(0xFF4527A0)
            ),
            dark = darkColorScheme(
                primary = Color(0xFFCE93D8),
                primaryContainer = Color(0xFF4A148C),
                secondary = Color(0xFF9FA8DA)
            )
        ),
        ThemePreset(
            label = "青绿",
            light = lightColorScheme(
                primary = Color(0xFF00695C),
                primaryContainer = Color(0xFFB2DFDB),
                secondary = Color(0xFF00838F)
            ),
            dark = darkColorScheme(
                primary = Color(0xFF80CBC4),
                primaryContainer = Color(0xFF004D40),
                secondary = Color(0xFF80DEEA)
            )
        ),
        ThemePreset(
            label = "天蓝",
            light = lightColorScheme(
                primary = Color(0xFF1565C0),
                primaryContainer = Color(0xFFBBDEFB),
                secondary = Color(0xFF0277BD)
            ),
            dark = darkColorScheme(
                primary = Color(0xFF90CAF9),
                primaryContainer = Color(0xFF0D47A1),
                secondary = Color(0xFF81D4FA)
            )
        ),
        ThemePreset(
            label = "棕",
            light = lightColorScheme(
                primary = Color(0xFF5D4037),
                primaryContainer = Color(0xFFD7CCC8),
                secondary = Color(0xFF8D6E63)
            ),
            dark = darkColorScheme(
                primary = Color(0xFFBCAAA4),
                primaryContainer = Color(0xFF3E2723),
                secondary = Color(0xFFD7CCC8)
            )
        )
    )

    fun resolve(index: Int): ThemePreset = all.getOrElse(index) { all.first() }
}
