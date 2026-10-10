package com.chan.shellpilot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * ShellPilot 主题：中性深色（去蓝）。
 * - 背景：近黑 #0A0A0A
 * - 卡片：稍亮的 #161616 / #212121
 * - 点缀：青蓝色 #5BC8F5（保留）
 * 终端页保持黑底绿字（TerminalScreen 内单独处理），不受此影响。
 */
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF5BC8F5),
    secondary = Color(0xFF7C9CFF),
    tertiary = Color(0xFF6FE3C1),
    background = Color(0xFF0A0A0A),
    surface = Color(0xFF161616),
    surfaceVariant = Color(0xFF212121),
    onPrimary = Color(0xFF0A0A0A),
    onSecondary = Color(0xFF0A0A0A),
    onTertiary = Color(0xFF0A0A0A),
    onBackground = Color(0xFFEDEDED),
    onSurface = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFFA8A8A8),
    outline = Color(0xFF2E2E2E),
)

@Composable
fun ShellPilotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Always dark: chan wants dark theme only.
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}

/** 终端专用色（黑底绿字），不受主页深蓝主题影响。 */
object TerminalColors {
    val Background = Color(0xFF000000)
    val DefaultText = Color(0xFF33FF66)
    val Cursor = Color(0xFF33FF66)
}
