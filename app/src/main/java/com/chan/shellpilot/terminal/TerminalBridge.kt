package com.chan.shellpilot.terminal

import com.chan.shellpilot.ssh.ShellSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * MVP terminal bridge (no termlib): pumps the SSH shell's output stream into
 * a text buffer and forwards typed lines to the shell's input.
 *
 * Full terminal emulation (termlib + libvterm) is a later milestone; this is
 * enough for running commands and reading output on a phone.
 */
class TerminalBridge(
    private val shell: ShellSession,
    private val scope: CoroutineScope,
) : Closeable {

    private val _output = MutableStateFlow(StringBuilder())
    /** Full terminal transcript. */
    val output: StateFlow<StringBuilder> = _output.asStateFlow()

    private val _connected = MutableStateFlow(true)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var pumpJob: Job? = null

    fun start() {
        pumpJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(4096)
            try {
                while (isActive) {
                    val n = shell.output.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    val text = String(buf, 0, n, Charsets.UTF_8)
                    withContext(Dispatchers.Main) {
                        _output.value.append(text)
                        // Cap buffer at ~200KB to avoid runaway memory.
                        if (_output.value.length > 200_000) {
                            _output.value.delete(0, _output.value.length - 200_000)
                        }
                        // Trigger recompose by replacing the reference.
                        _output.value = StringBuilder(_output.value)
                    }
                }
            } catch (_: Exception) {
                // Stream closed — session ended.
            } finally {
                withContext(Dispatchers.Main) { _connected.value = false }
            }
        }
    }

    /** Send a typed line to the remote shell. */
    fun sendLine(line: String) {
        scope.launch(Dispatchers.IO) {
            runCatching { shell.sendCommand(line) }
        }
    }

    override fun close() {
        pumpJob?.cancel()
        runCatching { shell.close() }
    }
}
