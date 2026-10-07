package com.chan.shellpilot.ssh

import net.schmizz.sshj.connection.channel.direct.Session
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * A running interactive shell: wire [input] to the terminal emulator's
 * onKeyboardInput and pump [output] into the emulator via writeInput().
 */
class ShellSession(
    private val session: Session,
    private val shell: Session.Shell,
) : Closeable {
    val input: OutputStream get() = shell.outputStream
    val output: InputStream get() = shell.inputStream

    /** Send a snippet / command line to the remote shell. */
    fun sendCommand(command: String) {
        val line = if (command.endsWith("\n")) command else "$command\n"
        input.write(line.toByteArray())
        input.flush()
    }

    override fun close() {
        runCatching { shell.close() }
        runCatching { session.close() }
    }
}
