package com.chan.shellpilot.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.chan.shellpilot.ssh.ShellSession
import kotlinx.coroutines.CoroutineScope
import java.io.Closeable

/**
 * STUB: termlib is disabled until a version compatible with compileSdk 34 exists
 * (0.2.1/0.3.11 require compileSdk 37). This stub keeps the skeleton compiling;
 * real terminal emulation wiring lands in a later milestone.
 *
 * TODO: re-enable termlib and restore full TerminalBridge implementation.
 */
class TerminalBridge(
    private val shell: ShellSession,
    private val scope: CoroutineScope,
) : Closeable {
    fun start() { /* stub */ }
    override fun close() { runCatching { shell.close() } }
}

/** Compose screen hosting the terminal. Placeholder until termlib is re-enabled. */
@Composable
fun TerminalScreen(
    bridge: TerminalBridge?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Text("终端占位（termlib 待接入）", color = Color.Gray)
    }
}
