package com.chan.shellpilot.ui.sftp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chan.shellpilot.ShellPilotApp
import com.chan.shellpilot.util.SpLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.SFTPClient
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** SFTP 目录项。 */
data class SftpEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val mtimeSec: Long,
)

data class SftpUiState(
    val ready: Boolean = false, // sftp 已打开
    val path: String = "",
    val entries: List<SftpEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * SFTP 文件管理：目录浏览 / 文本文件打开编辑保存 / 上传 / 下载 / 删除。
 * 与终端共用同一条 SSH 连接（进程级会话里的 manager）。
 * SFTPClient 非线程安全，所有操作经 mutex 串行化。
 */
class SftpViewModel(app: Application) : AndroidViewModel(app) {

    private val pilotApp get() = getApplication<ShellPilotApp>()

    private val _uiState = MutableStateFlow(SftpUiState())
    val uiState: StateFlow<SftpUiState> = _uiState.asStateFlow()

    private var sftp: SFTPClient? = null
    private val mutex = Mutex()
    private var started = false

    /** 打开 SFTP 并定位到家目录（幂等）。 */
    fun start() {
        if (started) return
        started = true
        viewModelScope.launch { openAndList(null) }
    }

    fun refresh() {
        viewModelScope.launch { openAndList(_uiState.value.path.ifBlank { null }) }
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

    private suspend fun openAndList(path: String?) {
        setLoading(true)
        val r = runCatching {
            val target: String
            val items: List<SftpEntry>
            mutex.withLock {
                val client = ensureSftpLocked()
                target = path ?: client.canonicalize(".")
                items = client.ls(target).mapNotNull { info ->
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
            target to items
        }
        r.onSuccess { (target, items) ->
            _uiState.value = _uiState.value.copy(
                ready = true, path = target, entries = items,
                loading = false, error = null,
            )
        }.onFailure {
            SpLog.e("SFTP", "ls failed: ${it.message}", it)
            _uiState.value = _uiState.value.copy(
                loading = false, error = "读取目录失败：${it.message?.take(100)}",
            )
        }
    }

    /** 读取文本文件（>2MB 拒绝）。 */
    suspend fun readText(path: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val c = ensureSftpLocked()
                val attrs = c.stat(path)
                if (attrs.size > 2 * 1024 * 1024) {
                    throw IllegalArgumentException("文件过大（>2MB），请下载查看")
                }
                val buf = ByteArrayOutputStream()
                c.get(path, buf)
                buf.toString(Charsets.UTF_8.name())
            }
        }
    }

    /** 保存文本回服务器（覆盖）。 */
    suspend fun writeText(path: String, content: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                mutex.withLock {
                    val c = ensureSftpLocked()
                    content.byteInputStream(Charsets.UTF_8).use { ins ->
                        c.put(ins, path)
                    }
                }
                SpLog.i("SFTP", "saved $path")
            }
        }

    /** 上传本地文件到当前目录。 */
    suspend fun upload(input: InputStream, fileName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val remote: String
                mutex.withLock {
                    val c = ensureSftpLocked()
                    remote = joinPath(_uiState.value.path, fileName)
                    input.use { ins -> c.put(ins, remote) }
                }
                SpLog.i("SFTP", "uploaded $fileName")
            }.also { refresh() }
        }

    /** 下载到指定输出流。 */
    suspend fun download(entry: SftpEntry, out: OutputStream): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                mutex.withLock {
                    val c = ensureSftpLocked()
                    out.use { o -> c.get(entry.path, o) }
                }
                SpLog.i("SFTP", "downloaded ${entry.path}")
            }
        }

    /** 删除文件或空目录。 */
    suspend fun delete(entry: SftpEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val c = ensureSftpLocked()
                if (entry.isDir) c.rmdir(entry.path) else c.rm(entry.path)
            }
            SpLog.i("SFTP", "deleted ${entry.path}")
        }.also { refresh() }
    }

    /**
     * 取 SFTP 客户端。调用方必须已持有 [mutex]（内部不再加锁，
     * 避免 Mutex 不可重入导致的死锁）。
     */
    private suspend fun ensureSftpLocked(): SFTPClient {
        sftp?.let { return it }
        val mgr = pilotApp.sshSession?.manager
            ?: throw IllegalStateException("SSH 未连接")
        if (!mgr.isConnected) throw IllegalStateException("SSH 已断开")
        val c = mgr.openSftp().getOrThrow()
        sftp = c
        return c
    }

    private fun setLoading(loading: Boolean) {
        _uiState.value = _uiState.value.copy(loading = loading, error = null)
    }

    private fun joinPath(dir: String, name: String): String {
        val d = dir.trimEnd('/')
        return if (d.isEmpty()) "/$name" else "$d/$name"
    }

    override fun onCleared() {
        runCatching { sftp?.close() }
        sftp = null
        super.onCleared()
    }
}
