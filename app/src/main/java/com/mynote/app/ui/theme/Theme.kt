package com.mynote.app.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun MyNoteTheme(
    darkTheme: Boolean,
    dynamicColor: Boolean,
    preset: ThemePreset,
    content: @Composable () -> Unit
) {
    val base = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> preset.dark
        else -> preset.light
    }
    val colorScheme = if (darkTheme) base.paperDark() else base.paperLight()
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}

private fun ColorScheme.paperLight(): ColorScheme = copy(
    background = PaperLightBackground,
    onBackground = PaperLightOnSurface,
    surface = PaperLightSurface,
    onSurface = PaperLightOnSurface,
    surfaceVariant = PaperLightSurfaceVariant,
    onSurfaceVariant = PaperLightOnSurfaceVariant,
    surfaceContainerLowest = PaperLightSurface,
    surfaceContainerLow = PaperLightSurfaceContainerLow,
    surfaceContainer = PaperLightSurfaceContainer,
    surfaceContainerHigh = PaperLightSurfaceContainerHigh,
    surfaceContainerHighest = PaperLightSurfaceContainerHigh,
    outline = PaperLightOutline,
    outlineVariant = PaperLightOutlineVariant
)

private fun ColorScheme.paperDark(): ColorScheme = copy(
    background = PaperDarkBackground,
    onBackground = PaperDarkOnSurface,
    surface = PaperDarkSurface,
    onSurface = PaperDarkOnSurface,
    surfaceVariant = PaperDarkSurfaceVariant,
    onSurfaceVariant = PaperDarkOnSurfaceVariant,
    surfaceContainerLowest = PaperDarkSurface,
    surfaceContainerLow = PaperDarkSurfaceContainerLow,
    surfaceContainer = PaperDarkSurfaceContainer,
    surfaceContainerHigh = PaperDarkSurfaceContainerHigh,
    surfaceContainerHighest = PaperDarkSurfaceContainerHigh,
    outline = PaperDarkOutline,
    outlineVariant = PaperDarkOutlineVariant
)
