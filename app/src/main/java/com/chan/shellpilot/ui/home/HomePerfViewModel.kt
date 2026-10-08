package com.chan.shellpilot.ui.home

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chan.shellpilot.data.Server
import com.chan.shellpilot.ssh.HostKeyChangedException
import com.chan.shellpilot.ssh.KeyStore
import com.chan.shellpilot.ssh.KnownHostsStore
import com.chan.shellpilot.ssh.PasswordStore
import com.chan.shellpilot.ssh.SshConnectionManager
import com.chan.shellpilot.ssh.UnknownHostKeyException
import com.chan.shellpilot.ui.perf.PerfStats
import com.chan.shellpilot.ui.perf.collectStats
import com.chan.shellpilot.util.SpLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 主页嵌入式性能监控。
 * - 用户选一台已保存凭据的服务器，后台自动连上（独立连接，不干扰终端会话）；
 * - CPU / 内存 / 硬盘 / 网络四张小卡片，每 3 秒刷新；
 * - 主页不可见时暂停采集、保留连接，回来即恢复。
 */
class HomePerfViewModel(app: Application) : AndroidViewModel(app) {

    enum class ConnState { IDLE, CONNECTING, CONNECTED, FAILED }

    data class HostKeyInfo(
        val host: String,
        val port: Int,
        val keyType: String,
        val fingerprint: String,
        val changed: Boolean,
        val oldFingerprint: String?,
    )

    data class UiState(
        val servers: List<Server> = emptyList(),
        /** 有记住凭据（密码/私钥）的服务器 id，可自动连接。 */
        val eligibleIds: Set<Long> = emptySet(),
        val selectedId: Long? = null,
        val connState: ConnState = ConnState.IDLE,
        val connError: String? = null,
        val hostKeyInfo: HostKeyInfo? = null,
        val stats: PerfStats = PerfStats(),
        val firstLoad: Boolean = true,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val passwordStore = PasswordStore(app)
    private val keyStore = KeyStore(app)
    private val knownHosts = KnownHostsStore(app)
    private val prefs = app.getSharedPreferences("home_perf", Context.MODE_PRIVATE)

    private var mgr: SshConnectionManager? = null
    private var collectJob: Job? = null
    private var homeVisible = false
    /** 正在连接/已连接的服务器 id（避免重复拨号）。 */
    private var connectingFor: Long? = null

    init {
        val saved = prefs.getLong(KEY_SELECTED, -1L).takeIf { it > 0 }
        _uiState.value = _uiState.value.copy(selectedId = saved)
    }

    /** 主页传入最新服务器列表；过滤出有记住凭据的，修正已选。 */
    fun setServers(servers: List<Server>) {
        viewModelScope.launch {
            val eligible = servers.filter { s ->
                if (s.authType == "key") keyStore.has(s.id) else passwordStore.has(s.id)
            }.map { it.id }.toSet()
            var sel = _uiState.value.selectedId
            if (sel != null && sel !in eligible) sel = null
            if (sel == null) {
                sel = eligible.firstOrNull()?.also {
                    prefs.edit().putLong(KEY_SELECTED, it).apply()
                }
            }
            _uiState.value = _uiState.value.copy(
                servers = servers, eligibleIds = eligible, selectedId = sel,
            )
            if (homeVisible && sel != null && sel != connectingFor) ensureConnected()
        }
    }

    fun selectServer(id: Long) {
        if (id == _uiState.value.selectedId) return
        prefs.edit().putLong(KEY_SELECTED, id).apply()
        _uiState.value = _uiState.value.copy(
            selectedId = id, firstLoad = true, stats = PerfStats(),
            hostKeyInfo = null, connError = null,
        )
        if (homeVisible) ensureConnected()
    }

    fun onHomeVisible() {
        homeVisible = true
        if (_uiState.value.selectedId != null) ensureConnected()
    }

    fun onHomeHidden() {
        homeVisible = false
        collectJob?.cancel()
        collectJob = null
    }

    fun retry() = ensureConnected()

    /** 指纹确认：记入 known_hosts 后重连。 */
    fun confirmHostKey() {
        val info = _uiState.value.hostKeyInfo ?: return
        viewModelScope.launch {
            knownHosts.save(info.host, info.port, info.keyType, info.fingerprint)
            _uiState.value = _uiState.value.copy(hostKeyInfo = null)
            ensureConnected()
        }
    }

    fun dismissHostKey() {
        _uiState.value = _uiState.value.copy(
            hostKeyInfo = null,
            connState = ConnState.FAILED,
            connError = "未确认服务器指纹",
        )
    }

    private fun ensureConnected() {
        val st = _uiState.value
        val id = st.selectedId ?: return
        if (id !in st.eligibleIds) {
            _uiState.value = st.copy(
                connState = ConnState.FAILED, connError = "该服务器未记住密码/私钥",
            )
            return
        }
        if (connectingFor == id &&
            (st.connState == ConnState.CONNECTING || st.connState == ConnState.CONNECTED)
        ) return
        // 换服务器：断开旧连接
        collectJob?.cancel()
        collectJob = null
        runCatching { mgr?.close() }
        mgr = null
        connectingFor = id
        _uiState.value = st.copy(
            connState = ConnState.CONNECTING, connError = null, hostKeyInfo = null,
        )
        viewModelScope.launch {
            val server = _uiState.value.servers.find { it.id == id } ?: run {
                connectingFor = null
                _uiState.value = _uiState.value.copy(
                    connState = ConnState.FAILED, connError = "服务器已删除",
                )
                return@launch
            }
            var password = ""
            var keyPem: String? = null
            var keyPass: String? = null
            if (server.authType == "key") {
                val k = keyStore.get(id)
                if (k == null) {
                    connectingFor = null
                    _uiState.value = _uiState.value.copy(
                        connState = ConnState.FAILED, connError = "未找到私钥",
                    )
                    return@launch
                }
                keyPem = k.first
                keyPass = k.second
            } else {
                password = passwordStore.get(id) ?: run {
                    connectingFor = null
                    _uiState.value = _uiState.value.copy(
                        connState = ConnState.FAILED, connError = "未记住密码",
                    )
                    return@launch
                }
            }
            val m = SshConnectionManager(getApplication<Application>().cacheDir)
            val r = m.connect(server, password, keyPem, keyPass, knownHosts)
            val err = r.exceptionOrNull()
            when {
                err is UnknownHostKeyException -> {
                    m.close()
                    connectingFor = null
                    _uiState.value = _uiState.value.copy(
                        connState = ConnState.FAILED,
                        hostKeyInfo = HostKeyInfo(
                            server.host, server.port,
                            err.keyType, err.fingerprint,
                            changed = false, oldFingerprint = null,
                        ),
                    )
                }
                err is HostKeyChangedException -> {
                    m.close()
                    connectingFor = null
                    _uiState.value = _uiState.value.copy(
                        connState = ConnState.FAILED,
                        hostKeyInfo = HostKeyInfo(
                            server.host, server.port,
                            err.keyType, err.newFingerprint,
                            changed = true, oldFingerprint = err.oldFingerprint,
                        ),
                    )
                }
                r.isFailure -> {
                    m.close()
                    connectingFor = null
                    _uiState.value = _uiState.value.copy(
                        connState = ConnState.FAILED,
                        connError = err?.message?.take(80) ?: "连接失败",
                    )
                }
                else -> {
                    mgr = m
                    _uiState.value = _uiState.value.copy(
                        connState = ConnState.CONNECTED, firstLoad = true,
                    )
                    SpLog.i("HomePerf", "background connected to ${server.name}")
                    startCollect()
                }
            }
        }
    }

    private fun startCollect() {
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            val m = mgr ?: return@launch
            while (isActive) {
                try {
                    val s = collectStats(m)
                    _uiState.value = _uiState.value.copy(stats = s, firstLoad = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SpLog.e("HomePerf", "collect failed: ${e.message}")
                    // 连接断了：标失败；下次主页可见时自动重连
                    runCatching { mgr?.close() }
                    mgr = null
                    connectingFor = null
                    _uiState.value = _uiState.value.copy(
                        connState = ConnState.FAILED, connError = "连接已断开",
                    )
                    return@launch
                }
                delay(3000)
            }
        }
    }

    override fun onCleared() {
        collectJob?.cancel()
        runCatching { mgr?.close() }
        mgr = null
        super.onCleared()
    }

    companion object {
        private const val KEY_SELECTED = "selected_server_id"
    }
}
