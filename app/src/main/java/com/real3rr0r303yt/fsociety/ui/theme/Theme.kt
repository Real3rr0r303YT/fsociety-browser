package com.real3rr0r303yt.fsociety.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Fs {
    val Black = Color(0xFF000000)
    val Panel = Color(0xFF050A07)
    val NeonGreen = Color(0xFF00FF66)
    val DimGreen = Color(0xFF00A845)
    val GhostGreen = Color(0xFF0B3D1F)
    val DarkRed = Color(0xFF8B0000)
    val BloodRed = Color(0xFFFF1A3C)
    val Amber = Color(0xFFFFB000)
    val Mono = FontFamily.Monospace
}

private val FsColors = darkColorScheme(
    primary = Fs.NeonGreen,
    onPrimary = Fs.Black,
    secondary = Fs.DarkRed,
    onSecondary = Fs.NeonGreen,
    tertiary = Fs.BloodRed,
    background = Fs.Black,
    onBackground = Fs.NeonGreen,
    surface = Fs.Black,
    onSurface = Fs.NeonGreen,
    surfaceVariant = Fs.Panel,
    onSurfaceVariant = Fs.DimGreen,
    outline = Fs.GhostGreen,
    error = Fs.BloodRed,
    onError = Fs.Black,
)

private fun mono(size: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = Fs.Mono, fontSize = size.sp, fontWeight = weight, color = Fs.NeonGreen)

private val FsTypography = Typography(
    titleLarge = mono(20, FontWeight.Bold),
    titleMedium = mono(16, FontWeight.Bold),
    bodyLarge = mono(15),
    bodyMedium = mono(13),
    bodySmall = mono(11),
    labelLarge = mono(13, FontWeight.Bold),
    labelMedium = mono(12),
    labelSmall = mono(10),
)

@Composable
fun FsocietyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FsColors, typography = FsTypography, content = content)
}
