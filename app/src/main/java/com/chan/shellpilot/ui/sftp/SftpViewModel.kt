package com.chan.shellpilot.ui.sftp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chan.shellpilot.data.Server
import com.chan.shellpilot.ssh.HostKeyChangedException
import com.chan.shellpilot.ssh.KeyStore
import com.chan.shellpilot.ssh.KnownHostsStore
import com.chan.shellpilot.ssh.PasswordStore
import com.chan.shellpilot.ssh.SshConnectionManager
import com.chan.shellpilot.ssh.UnknownHostKeyException
import com.chan.shellpilot.util.SpLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet

/** SFTP 目录项。 */
data class SftpEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val mtimeSec: Long,
)

/** 待确认的主机密钥（首次连接 / 密钥变更）。 */
data class SftpHostKeyInfo(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    val changed: Boolean,
    val oldFingerprint: String?,
)

data class SftpUiState(
    val connecting: Boolean = false, // 正在建连
    val ready: Boolean = false, // sftp 已打开
    val path: String = "",
    val entries: List<SftpEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val hostKeyInfo: SftpHostKeyInfo? = null,
)

/**
 * SFTP 文件管理：目录浏览 / 文本文件打开编辑保存 / 上传 / 下载 / 删除。
 *
 * 连接策略（此前"复用终端连接"在真机上反复失败）：
 * 文件管理自己用保存的密码/私钥独立建一条 SSH 连接，进页面就连，
 * 不依赖终端会话状态。SFTPClient 每次操作新建、用完即关。
 */
class SftpViewModel(app: Application) : AndroidViewModel(app) {

    private val passwordStore = PasswordStore(app)
    private val keyStore = KeyStore(app)
    private val knownHosts = KnownHostsStore(app)

    private val _uiState = MutableStateFlow(SftpUiState())
    val uiState: StateFlow<SftpUiState> = _uiState.asStateFlow()

    private val mutex = Mutex()
    private var mgr: SshConnectionManager? = null
    private var server: Server? = null
    private var startedFor: Long = -1L

    /** 进页面即连（幂等；换服务器或连接断了会重连）。 */
    fun start(server: Server) {
        val m = mgr
        if (startedFor == server.id && m != null && m.isConnected) return
        startedFor = server.id
        this.server = server
        runCatching { mgr?.close() }
        mgr = null
        _uiState.value = SftpUiState()
        viewModelScope.launch { connectAndList() }
    }

    /** 重试按钮：重连。 */
    fun retry() {
        viewModelScope.launch { connectAndList() }
    }

    fun refresh() {
        val m = mgr
        if (m != null && m.isConnected) {
            viewModelScope.launch { openAndList(_uiState.value.path.ifBlank { null }) }
        } else {
            viewModelScope.launch { connectAndList() }
        }
    }

    fun enterDir(entry: SftpEntry) {
        if (!entry.isDir) return
        viewModelScope.launch { openAndList(entry.path) }
    }

    fun goUp() {
        val cur = _uiState.value.path.trimEnd('/')
        if (cur.isBlank() || cur == "/") return
        val parent = cur.substringBeforeLast("/").ifBlank { "/" }
        viewModelScope.launch { openAndList(parent) }
    }

    /** 指纹确认：记入 known_hosts 后重连。 */
    fun confirmHostKey() {
        val info = _uiState.value.hostKeyInfo ?: return
        viewModelScope.launch {
            knownHosts.save(info.host, info.port, info.keyType, info.fingerprint)
            _uiState.value = _uiState.value.copy(hostKeyInfo = null)
            connectAndList()
        }
    }

    fun dismissHostKey() {
        _uiState.value = _uiState.value.copy(
            hostKeyInfo = null,
            connecting = false,
            error = "未确认服务器指纹",
        )
    }

    /** 建连（处理指纹确认流程），成功后列家目录。 */
    private suspend fun connectAndList() {
        val srv = server ?: return
        _uiState.value = _uiState.value.copy(
            connecting = true, error = null, hostKeyInfo = null,
        )
        try {
            val m = SshConnectionManager(getApplication<Application>().cacheDir)
            var password = ""
            var keyPem: String? = null
            var keyPass: String? = null
            if (srv.authType == "key") {
                val k = keyStore.get(srv.id)
                    ?: throw IllegalStateException("未找到该服务器的私钥，请重新编辑导入")
                keyPem = k.first
                keyPass = k.second
            } else {
                password = passwordStore.get(srv.id)
                    ?: throw IllegalStateException("未记住该服务器的密码，请先连接一次并记住密码")
            }
            val r = m.connect(srv, password, keyPem, keyPass, knownHosts)
            val err = r.exceptionOrNull()
            when {
                err is UnknownHostKeyException -> {
                    m.close()
                    _uiState.value = _uiState.value.copy(
                        connecting = false,
                        hostKeyInfo = SftpHostKeyInfo(
                            srv.host, srv.port, err.keyType, err.fingerprint,
                            changed = false, oldFingerprint = null,
                        ),
                    )
                    return
                }
                err is HostKeyChangedException -> {
                    m.close()
                    _uiState.value = _uiState.value.copy(
                        connecting = false,
                        hostKeyInfo = SftpHostKeyInfo(
                            srv.host, srv.port, err.keyType, err.newFingerprint,
                            changed = true, oldFingerprint = err.oldFingerprint,
                        ),
                    )
                    return
                }
                r.isFailure -> throw err ?: IllegalStateException("连接失败")
            }
            runCatching { mgr?.close() }
            mgr = m
            SpLog.i("SFTP", "independent connection established to ${srv.name}")
            _uiState.value = _uiState.value.copy(connecting = false)
            openAndList(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { mgr?.close() }
            mgr = null
            SpLog.e("SFTP", "connect failed: ${e::class.java.simpleName}: ${e.message}", e)
            _uiState.value = _uiState.value.copy(
                connecting = false,
                error = "连接失败：${e::class.java.simpleName}" +
                    (e.message?.let { ": ${it.take(100)}" } ?: ""),
            )
        }
    }

    private suspend fun openAndList(path: String?) {
        setLoading(true)
        try {
            val target = path ?: resolveHomeDir()
            val items = listDir(target)
            _uiState.value = _uiState.value.copy(
                ready = true, path = target, entries = items,
                loading = false, error = null,
            )
        } catch (e: CancellationException) {
            throw e // 协程取消（页面销毁等）不是失败，直接抛
        } catch (e: Exception) {
            setError(e)
        }
    }

    private fun setError(e: Exception) {
        SpLog.e("SFTP", "ls failed: ${e::class.java.simpleName}: ${e.message}", e)
        _uiState.value = _uiState.value.copy(
            loading = false,
            error = "读取目录失败：${e::class.java.simpleName}" +
                (e.message?.let { ": ${it.take(100)}" } ?: ""),
        )
    }

    /**
     * 列目录：SFTP 优先；任何异常都用 exec 解析兜底。
     * 只有 exec 也失败时才抛给 UI。
     */
    private suspend fun listDir(target: String): List<SftpEntry> {
        try {
            return withFreshSftp { c -> sftpLs(c, target) }
        } catch (e: CancellationException) {
            throw e // 协程取消直接抛，不走 exec 兜底
        } catch (e: Exception) {
            SpLog.w(
                "SFTP",
                "sftp ls failed (${e::class.java.simpleName}: ${e.message}), fallback to exec",
            )
        }
        return execLs(target)
    }

    /**
     * 每次新建 SFTPClient、用完即关。
     * mutex 串行化，避免并发建通道给服务端压力。
     */
    private suspend fun <T> withFreshSftp(block: suspend (SFTPClient) -> T): T {
        val m = mgr ?: throw IllegalStateException("SSH 未连接")
        if (!m.isConnected) throw IllegalStateException("SSH 已断开")
        val client = m.openSftp().getOrThrow()
        try {
            return mutex.withLock { block(client) }
        } finally {
            runCatching { client.close() }
        }
    }

    private fun sftpLs(client: SFTPClient, target: String): List<SftpEntry> {
        return client.ls(target).mapNotNull { info ->
            if (info.name == "." || info.name == "..") return@mapNotNull null
            SftpEntry(
                name = info.name,
                path = joinPath(target, info.name),
                isDir = info.isDirectory,
                size = info.attributes.size,
                mtimeSec = info.attributes.mtime,
            )
        }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    /**
     * 取远端家目录：exec pwd。失败回退 "."。
     */
    private suspend fun resolveHomeDir(): String {
        val pwd = runCatching {
            mgr?.exec("pwd")?.getOrNull()?.trim()
        }.getOrNull()
        return if (!pwd.isNullOrEmpty() && pwd.startsWith("/")) pwd else "."
    }

    /**
     * exec 兜底列目录。
     * 方法1：find -printf（\0 分隔，文件名含空格/换行也不怕；GNU find）；
     * 方法2：ls -la 解析（BusyBox 等无 -printf 的环境）。
     */
    private suspend fun execLs(dir: String): List<SftpEntry> {
        val m = mgr ?: throw IllegalStateException("SSH 未连接")
        val q = shQuote(dir)
        val test = m.exec("[ -d $q ] && echo DIR_OK || echo DIR_MISSING")
            .getOrThrow().trim()
        if (test != "DIR_OK") throw IllegalStateException("目录不存在：$dir")

        val findOut = m.exec(
            "find $q -maxdepth 1 -mindepth 1 -printf '%y\\t%s\\t%T@\\t%f\\0' 2>/dev/null; echo \"FIND_EXIT:\$?\""
        ).getOrThrow()
        if (findOut.substringAfterLast("FIND_EXIT:", "x").trim().startsWith("0")) {
            return parseFindPrintf(findOut.substringBeforeLast("FIND_EXIT:"), dir)
        }
        val lsOut = m.exec("ls -la -b --time-style=+%s -- $q 2>/dev/null")
            .getOrThrow()
        return parseLsLa(lsOut, dir)
    }

    private fun parseFindPrintf(output: String, dir: String): List<SftpEntry> {
        return output.split('\u0000')
            .filter { it.isNotEmpty() }
            .mapNotNull { rec ->
                val p = rec.split('\t', limit = 4)
                if (p.size < 4) return@mapNotNull null
                val name = p[3]
                if (name == "." || name == "..") return@mapNotNull null
                SftpEntry(
                    name = name,
                    path = joinPath(dir, name),
                    isDir = p[0] == "d",
                    size = p[1].toLongOrNull() ?: 0L,
                    mtimeSec = p[2].substringBefore('.').toLongOrNull() ?: 0L,
                )
            }
            .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    private val lsLineRegex =
        Regex("""^([bcdlpsD-][rwxsStT-]{9})[.+ ]?\s+\d+\s+\S+\s+\S+\s+(\d+)\s+(\d+)\s+(.+)$""")

    private fun parseLsLa(output: String, dir: String): List<SftpEntry> {
        return output.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotEmpty() && !it.startsWith("total ") }
            .mapNotNull { line ->
                val m = lsLineRegex.matchEntire(line) ?: return@mapNotNull null
                val name = unescapeLs(m.groupValues[4])
                if (name == "." || name == "..") return@mapNotNull null
                SftpEntry(
                    name = name,
                    path = joinPath(dir, name),
                    isDir = m.groupValues[1][0] == 'd',
                    size = m.groupValues[2].toLongOrNull() ?: 0L,
                    mtimeSec = m.groupValues[3].toLongOrNull() ?: 0L,
                )
            }
            .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
            .toList()
    }

    /** 还原 ls -b 的转义（\n、\\ 等）。 */
    private fun unescapeLs(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '\\' -> sb.append('\\')
                    else -> {
                        sb.append('\\')
                        sb.append(s[i + 1])
                    }
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 读取文本文件（>2MB 拒绝）。 */
    suspend fun readText(path: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            withFreshSftp { c ->
                val attrs = c.stat(path)
                if (attrs.size > 2 * 1024 * 1024) {
                    throw IllegalArgumentException("文件过大（>2MB），请下载查看")
                }
                val rf = c.open(path)
                val buf = ByteArrayOutputStream()
                rf.RemoteFileInputStream().use { ins -> ins.copyTo(buf) }
                buf.toString(Charsets.UTF_8.name())
            }
        }
    }

    /** 保存文本回服务器（覆盖）。 */
    suspend fun writeText(path: String, content: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                withFreshSftp { c ->
                    val rf = c.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))
                    rf.RemoteFileOutputStream().use { outs ->
                        content.byteInputStream(Charsets.UTF_8).use { ins ->
                            ins.copyTo(outs)
                        }
                    }
                }
                SpLog.i("SFTP", "saved $path")
            }
        }

    /** 上传本地文件到当前目录。 */
    suspend fun upload(input: InputStream, fileName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                withFreshSftp { c ->
                    val remote = joinPath(_uiState.value.path, fileName)
                    val rf = c.open(remote, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))
                    rf.RemoteFileOutputStream().use { outs ->
                        input.use { ins -> ins.copyTo(outs) }
                    }
                }
                SpLog.i("SFTP", "uploaded $fileName")
            }.also { refresh() }
        }

    /** 下载到指定输出流。 */
    suspend fun download(entry: SftpEntry, out: OutputStream): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                withFreshSftp { c ->
                    val rf = c.open(entry.path)
                    rf.RemoteFileInputStream().use { ins ->
                        out.use { o -> ins.copyTo(o) }
                    }
                }
                SpLog.i("SFTP", "downloaded ${entry.path}")
            }
        }

    /** 删除文件或空目录。 */
    suspend fun delete(entry: SftpEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            withFreshSftp { c ->
                if (entry.isDir) c.rmdir(entry.path) else c.rm(entry.path)
            }
            SpLog.i("SFTP", "deleted ${entry.path}")
        }.also { refresh() }
    }

    private fun setLoading(loading: Boolean) {
        _uiState.value = _uiState.value.copy(loading = loading, error = null)
    }

    private fun joinPath(dir: String, name: String): String {
        val d = dir.trimEnd('/')
        return if (d.isEmpty()) "/$name" else "$d/$name"
    }

    override fun onCleared() {
        runCatching { mgr?.close() }
        mgr = null
        super.onCleared()
    }
}
