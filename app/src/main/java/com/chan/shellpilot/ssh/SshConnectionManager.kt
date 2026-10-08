package com.chan.shellpilot.ssh

import com.chan.shellpilot.data.Server
import com.chan.shellpilot.util.SpLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.Closeable
import java.util.concurrent.TimeUnit

/**
 * SSHJ-based connection manager.
 *
 * 保活策略（解决切后台断线）：
 * 1. SSH keepalive：每 25 秒发心跳包，防止 NAT/防火墙掐掉空闲连接。
 *    注意：必须在 connect() 之前设置，否则 keepalive 线程不会启动。
 * 2. 配合前台 SshService，进程在后台不被杀。
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
                    // SSH 心跳：必须在 connect() 之前设置！
                    // 每 25s 发 SSH_MSG_IGNORE，NAT/防火墙看到有流量就不会掐连接；
                    // 若底层 socket 已死，写心跳会抛异常，transport 被标记死亡，
                    // 上层 isConnected 变 false，可及时发现断线而不是"卡死"。
                    ssh.connection.keepAlive.keepAliveInterval = KEEPALIVE_SECONDS
                    ssh.connectTimeout = 10_000
                    ssh.timeout = 10_000
                    SpLog.i("SSH", "connecting ${server.host}:${server.port} ...")
                    ssh.connect(server.host, server.port)
                    when (server.authType) {
                        "key" -> {
                            // TODO: load private key from Identity Store.
                            throw UnsupportedOperationException("key auth not implemented yet")
                        }
                        else -> ssh.authPassword(server.username, password)
                    }
                    client = ssh
                    SpLog.i(
                        "SSH",
                        "connected ${server.host}:${server.port}, " +
                            "keepalive=${KEEPALIVE_SECONDS}s",
                    )
                }
            }.onFailure {
                SpLog.e("SSH", "connect failed: ${it.message}", it)
            }
        }

    /** Open an interactive shell session. Caller owns the returned session. */
    suspend fun openShell(): Result<ShellSession> = withContext(Dispatchers.IO) {
        runCatching {
            val ssh = client ?: throw IllegalStateException("not connected")
            val session = ssh.startSession()
            // xterm-256color：让服务端发 256 色 ANSI 序列，终端解析器负责渲染
            session.allocatePTY("xterm-256color", 80, 24, 0, 0, emptyMap<PTYMode, Int>())
            val shell = session.startShell()
            SpLog.i("SSH", "shell opened")
            ShellSession(session, shell)
        }.onFailure {
            SpLog.e("SSH", "openShell failed: ${it.message}", it)
        }
    }

    val isConnected: Boolean
        get() = client?.isConnected == true && client?.isAuthenticated == true

    override fun close() {
        SpLog.i("SSH", "disconnect")
        runCatching { client?.disconnect() }
        client = null
    }

    companion object {
        /** SSH 心跳间隔（秒）：小于常见 NAT 超时（60s），大于 0 才会启动心跳线程。 */
        const val KEEPALIVE_SECONDS = 25
    }
}
