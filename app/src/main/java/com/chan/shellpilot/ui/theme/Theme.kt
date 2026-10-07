package com.chan.shellpilot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ShellPilot: black-first theme (matches chan's preference from RelayBox:
// pure black background, mixed-color type).
private val BlackColorScheme = darkColorScheme(
    primary = Color(0xFF7C9CFF),
    secondary = Color(0xFF9D7CFF),
    tertiary = Color(0xFF6FE3C1),
    background = Color(0xFF000000),
    surface = Color(0xFF161616),
    surfaceVariant = Color(0xFF1E1E1E),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onTertiary = Color.Black,
    onBackground = Color(0xFFE8E8E8),
    onSurface = Color(0xFFE8E8E8),
    onSurfaceVariant = Color(0xFFB0B0B0),
)

@Composable
fun ShellPilotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Always dark: chan wants black theme only.
    MaterialTheme(
        colorScheme = BlackColorScheme,
        content = content
    )
}
