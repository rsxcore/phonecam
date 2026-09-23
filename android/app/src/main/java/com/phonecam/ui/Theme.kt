package com.phonecam.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Palette {
    val Black = Color(0xFF000000)
    val Surface = Color(0xFF111113)
    val SurfaceHigh = Color(0xFF1C1C1F)
    val Glass = Color(0xB3141416)
    val Outline = Color(0x33FFFFFF)
    val Text = Color(0xFFF5F5F7)
    val TextDim = Color(0x99F5F5F7)
    val Live = Color(0xFFFF3B30)
    val Accent = Color(0xFFFFD60A)
    val Waiting = Color(0xFFFF9F0A)
    val Ok = Color(0xFF30D158)
}

private val Mono = FontFamily.Monospace

val AppTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    bodyMedium = TextStyle(fontSize = 15.sp),
    bodySmall = TextStyle(fontSize = 13.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.3.sp),
)

@Composable
fun PhoneCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            onPrimary = Palette.Black,
            background = Palette.Black,
            surface = Palette.Surface,
            surfaceContainer = Palette.Surface,
            surfaceContainerHigh = Palette.SurfaceHigh,
            surfaceContainerLow = Palette.Surface,
            onSurface = Palette.Text,
            onSurfaceVariant = Palette.TextDim,
            outline = Palette.Outline,
            error = Palette.Live,
        ),
        typography = AppTypography,
        content = content,
    )
}
