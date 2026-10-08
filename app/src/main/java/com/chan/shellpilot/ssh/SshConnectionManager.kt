package com.chan.shellpilot.ssh

import com.chan.shellpilot.data.Server
import com.chan.shellpilot.util.SpLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/**
 * SSHJ-based connection manager.
 *
 * 主机密钥校验（known_hosts 机制）：
 * - 连接时捕获服务器主机公钥，算 SHA256 指纹；
 * - 已记录且一致 → 直接过；
 * - 从未见过 → 抛 UnknownHostKeyException，上层弹指纹确认框，用户确认后记入再重连；
 * - 记录过但不一致 → 抛 HostKeyChangedException（疑似中间人攻击），上层红色警告。
 * 不再使用 PromiscuousVerifier。
 *
 * 认证方式：密码 / 私钥（ed25519、RSA，OpenSSH 或 PKCS#8 格式，口令可选）。
 * 私钥 PEM 先写临时文件再调 ssh.loadKeys（SSHJ 只认文件路径），认证完即删。
 *
 * 保活策略（解决切后台断线）：
 * 1. SSH keepalive：每 25 秒发心跳包，防止 NAT/防火墙掐掉空闲连接。
 *    注意：必须在 connect() 之前设置，否则 keepalive 线程不会启动。
 * 2. 配合前台 SshService，进程在后台不被杀。
 */
class SshConnectionManager(
    private val cacheDir: File,
) : Closeable {

    private var client: SSHClient? = null

    /**
     * 连接并认证。
     * @param keyPem 私钥 PEM 文本（authType=key 时必填）
     * @param keyPassphrase 私钥口令（可空）
     * @throws UnknownHostKeyException 首次见到的主机密钥
     * @throws HostKeyChangedException 主机密钥与记录不一致
     */
    suspend fun connect(
        server: Server,
        password: String = "",
        keyPem: String? = null,
        keyPassphrase: String? = null,
        knownHosts: KnownHostsStore,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            withTimeout(15_000) {
                val ssh = SSHClient()
                // 捕获主机密钥：verifier 只记录不判定，判定在 connect() 之后自己做，
                // 避免异常穿过 SSHJ 内部线程被包装吞掉。
                var offeredKey: PublicKey? = null
                ssh.addHostKeyVerifier(object : HostKeyVerifier {
                    override fun verify(
                        hostname: String,
                        port: Int,
                        key: PublicKey,
                    ): Boolean {
                        offeredKey = key
                        return true
                    }
                })
                // SSH 心跳：必须在 connect() 之前设置！
                ssh.connection.keepAlive.keepAliveInterval = KEEPALIVE_SECONDS
                ssh.connectTimeout = 10_000
                ssh.timeout = 10_000
                SpLog.i("SSH", "connecting ${server.host}:${server.port} ...")
                ssh.connect(server.host, server.port)

                // ---- 主机密钥校验 ----
                val key = offeredKey
                    ?: throw IllegalStateException("未获取到服务器主机密钥").also {
                        ssh.disconnect()
                    }
                verifyHostKey(ssh, server, key, knownHosts)

                // ---- 认证 ----
                when (server.authType) {
                    "key" -> {
                        val pem = keyPem?.takeIf { it.isNotBlank() }
                            ?: throw IllegalArgumentException("该服务器使用私钥认证，但未找到私钥")
                                .also { ssh.disconnect() }
                        authWithKey(ssh, server.username, pem, keyPassphrase)
                    }
                    else -> {
                        if (password.isEmpty()) {
                            throw IllegalArgumentException("密码为空")
                                .also { ssh.disconnect() }
                        }
                        ssh.authPassword(server.username, password)
                    }
                }
                client = ssh
                SpLog.i(
                    "SSH",
                    "connected ${server.host}:${server.port}, " +
                        "auth=${server.authType}, keepalive=${KEEPALIVE_SECONDS}s",
                )
            }
        }.onFailure {
            SpLog.e("SSH", "connect failed: ${it.message}", it)
        }
    }

    private suspend fun verifyHostKey(
        ssh: SSHClient,
        server: Server,
        key: PublicKey,
        knownHosts: KnownHostsStore,
    ) {
        val keyType = key.algorithm // 如 "ssh-ed25519" / "ssh-rsa"
        val fp = sha256Fingerprint(key)
        val stored = knownHosts.get(server.host, server.port)
        when {
            stored == null -> {
                ssh.disconnect()
                throw UnknownHostKeyException(server.host, server.port, keyType, fp)
            }
            stored != "$keyType $fp" -> {
                ssh.disconnect()
                throw HostKeyChangedException(
                    server.host, server.port, keyType,
                    oldFingerprint = stored.substringAfter(" "),
                    newFingerprint = fp,
                )
            }
            // 一致 → 通过
        }
        SpLog.i("SSH", "host key verified $keyType $fp")
    }

    private fun authWithKey(
        ssh: SSHClient,
        username: String,
        pem: String,
        passphrase: String?,
    ) {
        val tmp = File.createTempFile("spkey_", ".pem", cacheDir)
        try {
            tmp.writeText(pem)
            val provider = if (passphrase.isNullOrEmpty()) {
                ssh.loadKeys(tmp.absolutePath)
            } else {
                ssh.loadKeys(tmp.absolutePath, passphrase)
            }
            ssh.authPublickey(username, provider)
        } finally {
            runCatching { tmp.delete() }
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

    /** 在已连接的传输上执行一条命令并返回 stdout（性能监视器等用）。 */
    suspend fun exec(command: String, timeoutMs: Long = 15_000): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val ssh = client ?: throw IllegalStateException("not connected")
                val session = ssh.startSession()
                try {
                    val cmd = session.exec(command)
                    try {
                        withTimeout(timeoutMs) {
                            cmd.inputStream.readBytes().toString(Charsets.UTF_8)
                        }
                    } finally {
                        runCatching { cmd.close() }
                    }
                } finally {
                    runCatching { session.close() }
                }
            }.onFailure {
                SpLog.e("SSH", "exec failed [$command]: ${it.message}")
            }
        }

    /** 打开 SFTP 客户端（与 shell 共用同一条 SSH 连接）。调用方负责 close。 */
    suspend fun openSftp(): Result<SFTPClient> = withContext(Dispatchers.IO) {
        runCatching {
            val ssh = client ?: throw IllegalStateException("not connected")
            ssh.newSFTPClient().also { SpLog.i("SSH", "sftp opened") }
        }.onFailure {
            SpLog.e("SSH", "openSftp failed: ${it.message}", it)
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

        /** OpenSSH 风格的 SHA256 指纹：`SHA256:xxxx`（去掉 base64 尾部 =）。 */
        fun sha256Fingerprint(key: PublicKey): String {
            val md = MessageDigest.getInstance("SHA256")
            val raw = Base64.getEncoder().encodeToString(md.digest(key.encoded))
                .trimEnd('=')
            return "SHA256:$raw"
        }
    }
}
