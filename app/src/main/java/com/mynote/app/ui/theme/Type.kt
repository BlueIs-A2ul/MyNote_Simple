package com.mynote.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Serif = FontFamily.Serif
private val Sans = FontFamily.SansSerif

val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = Serif, fontWeight = FontWeight.Bold,
        fontSize = 20.sp, lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontFamily = Serif, fontWeight = FontWeight.Bold,
        fontSize = 18.sp, lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = Serif, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 24.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 22.sp
    ),
    bodySmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 11.sp, lineHeight = 16.sp
    )
)
