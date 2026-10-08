package com.chan.shellpilot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * ShellPilot 主题：深蓝底板（参考 LobiShell / ServerCat）。
 * - 背景：深海军蓝 #0A1628 系
 * - 卡片：稍亮的 #13253F
 * - 点缀：青蓝色 #5BC8F5
 * 终端页保持黑底绿字（TerminalScreen 内单独处理），不受此影响。
 */
private val NavyColorScheme = darkColorScheme(
    primary = Color(0xFF5BC8F5),
    secondary = Color(0xFF7C9CFF),
    tertiary = Color(0xFF6FE3C1),
    background = Color(0xFF0A1628),
    surface = Color(0xFF13253F),
    surfaceVariant = Color(0xFF1A2F4B),
    onPrimary = Color(0xFF0A1628),
    onSecondary = Color(0xFF0A1628),
    onTertiary = Color(0xFF0A1628),
    onBackground = Color(0xFFE8F0FA),
    onSurface = Color(0xFFE8F0FA),
    onSurfaceVariant = Color(0xFF9DB2CC),
    outline = Color(0xFF2A4265),
)

@Composable
fun ShellPilotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Always dark: chan wants dark theme only.
    MaterialTheme(
        colorScheme = NavyColorScheme,
        content = content
    )
}

/** 终端专用色（黑底绿字），不受主页深蓝主题影响。 */
object TerminalColors {
    val Background = Color(0xFF000000)
    val DefaultText = Color(0xFF33FF66)
    val Cursor = Color(0xFF33FF66)
}
