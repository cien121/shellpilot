package com.chan.shellpilot.ssh

import com.chan.shellpilot.data.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.Closeable
import java.util.concurrent.TimeUnit

/**
 * SSHJ-based connection manager (skeleton).
 *
 * MVP will grow: proper known_hosts verification (currently promiscuous),
 * key auth, port forwarding, SFTP browser.
 */
class SshConnectionManager : Closeable {

    private var client: SSHClient? = null

    /** Connect with password auth, 10s timeout. TODO: key auth, host key verification UI. */
    suspend fun connect(server: Server, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                withTimeout(10_000) {
                    val ssh = SSHClient()
                    // TODO: replace with known_hosts verification + user prompt on unknown key.
                    ssh.addHostKeyVerifier(PromiscuousVerifier())
                    ssh.connectTimeout = 10_000
                    ssh.timeout = 10_000
                    ssh.connect(server.host, server.port)
                    when (server.authType) {
                        "key" -> {
                            // TODO: load private key from Identity Store.
                            throw UnsupportedOperationException("key auth not implemented yet")
                        }
                        else -> ssh.authPassword(server.username, password)
                    }
                    client = ssh
                }
            }
        }

    /** Open an interactive shell session. Caller owns the returned session. */
    suspend fun openShell(): Result<ShellSession> = withContext(Dispatchers.IO) {
        runCatching {
            val ssh = client ?: throw IllegalStateException("not connected")
            val session = ssh.startSession()
            session.allocateDefaultPTY()
            val shell = session.startShell()
            ShellSession(session, shell)
        }
    }

    val isConnected: Boolean
        get() = client?.isConnected == true

    override fun close() {
        runCatching { client?.disconnect() }
        client = null
    }
}
