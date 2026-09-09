package com.mynote.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = IndigoPrimary,
    primaryContainer = IndigoContainer,
    secondary = TealAccent
)
private val DarkColors = darkColorScheme(
    primary = IndigoPrimary,
    primaryContainer = IndigoContainer,
    secondary = TealAccent
)

@Composable
fun MyNoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
