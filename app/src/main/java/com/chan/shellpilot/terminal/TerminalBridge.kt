package com.chan.shellpilot.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.chan.shellpilot.ssh.ShellSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import java.io.Closeable

/**
 * Binds a [ShellSession] (SSHJ) to a termlib [TerminalEmulator]:
 *  - remote output  -> emulator.writeInput()  (display)
 *  - local keyboard -> shell input stream      (via onKeyboardInput)
 *
 * TODO: resize handling (send SIGWINCH / pty resize on layout change),
 *       scrollback persistence, selection toolbar actions.
 */
class TerminalBridge(
    private val shell: ShellSession,
    private val scope: CoroutineScope,
) : Closeable {

    val emulator: TerminalEmulator = TerminalEmulatorFactory.create(
        initialRows = 24,
        initialCols = 80,
        defaultForeground = Color.White,
        defaultBackground = Color.Black,
        onKeyboardInput = { data ->
            runCatching {
                shell.input.write(data)
                shell.input.flush()
            }
        },
    )

    private var pumpJob: Job? = null

    fun start() {
        pumpJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = shell.output.read(buf)
                    if (n < 0) break
                    emulator.writeInput(buf.copyOf(n))
                }
            } catch (_: Exception) {
                // session closed; stop pumping
            }
        }
    }

    override fun close() {
        pumpJob?.cancel()
        runCatching { shell.close() }
    }
}

/** Compose screen hosting the terminal. Placeholder until session wiring lands. */
@Composable
fun TerminalScreen(
    bridge: TerminalBridge?,
    modifier: Modifier = Modifier,
) {
    val emulator = bridge?.emulator ?: remember {
        // Standalone placeholder emulator so the screen renders without a connection.
        TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            defaultForeground = Color.White,
            defaultBackground = Color.Black,
            onKeyboardInput = {},
        )
    }
    DisposableEffect(bridge) {
        bridge?.start()
        onDispose { }
    }
    Terminal(
        terminalEmulator = emulator,
        modifier = modifier,
    )
}
