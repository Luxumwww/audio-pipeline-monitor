package com.audioprobe.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AccentGood = Color(0xFF4CD07D)
val AccentWarn = Color(0xFFFFB020)
val AccentBad = Color(0xFFFF6B6B)
val AccentInfo = Color(0xFF6FD3FF)

private val DarkScheme = darkColorScheme(
    primary = AccentInfo,
    onPrimary = Color(0xFF00202E),
    secondary = AccentGood,
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Color(0xFF1F2630),
    onSurfaceVariant = Color(0xFFA9B4C2),
    outline = Color(0xFF39424F),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00658F),
    secondary = Color(0xFF1F7A45),
    background = Color(0xFFF5F7FA),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun AudioProbeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}
