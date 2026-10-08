package com.chan.shellpilot.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chan.shellpilot.data.Server
import com.chan.shellpilot.ssh.KeyStore
import com.chan.shellpilot.ssh.KnownHostsStore
import com.chan.shellpilot.ssh.PasswordStore
import com.chan.shellpilot.ssh.SshConnectionManager
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
 * 服务器卡片性能小字：为每台记住凭据的服务器在后台独立建一条 SSH 连接，
 * 采集 CPU / 内存，每 5 秒刷新。点卡片进终端不受影响。
 * 指纹未知/变更的服务器跳过（先去终端里确认一次指纹）。
 */
class ServerCardPerfViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        /** serverId -> 最新性能数据。 */
        val stats: Map<Long, PerfStats> = emptyMap(),
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val appCtx = app
    private val passwordStore = PasswordStore(app)
    private val keyStore = KeyStore(app)
    private val knownHosts = KnownHostsStore(app)

    private data class Worker(
        val server: Server,
        var mgr: SshConnectionManager? = null,
        var job: Job? = null,
    )

    private val workers = mutableMapOf<Long, Worker>()
    private var visible = false

    /** 服务器列表变化时对账：新增/删除后台 worker。 */
    fun setServers(servers: List<Server>) {
        viewModelScope.launch {
            val ids = servers.map { it.id }.toSet()
            workers.keys.filter { it !in ids }.forEach { removeWorker(it) }
            for (s in servers) {
                if (s.id !in workers && eligible(s)) {
                    workers[s.id] = Worker(s)
                }
            }
            if (visible) startAll()
        }
    }

    private suspend fun eligible(s: Server): Boolean =
        if (s.authType == "key") keyStore.has(s.id) else passwordStore.has(s.id)

    fun onVisible() {
        visible = true
        startAll()
    }

    fun onHidden() {
        visible = false
        workers.values.forEach {
            it.job?.cancel()
            it.job = null
        }
    }

    private fun startAll() {
        for ((id, w) in workers) {
            if (w.job?.isActive == true) continue
            w.job = viewModelScope.launch { runWorker(id, w) }
        }
    }

    private suspend fun runWorker(id: Long, w: Worker) {
        val srv = w.server
        val creds = credentials(srv) ?: return
        val m = SshConnectionManager(appCtx.cacheDir)
        val r = m.connect(srv, creds.first, creds.second, creds.third, knownHosts)
        if (r.isFailure) {
            // 指纹未知/变更、密码错误等：静默跳过，不打扰用户
            SpLog.w("CardPerf", "${srv.name} bg connect failed: ${r.exceptionOrNull()?.message}")
            m.close()
            return
        }
        w.mgr = m
        SpLog.i("CardPerf", "${srv.name} bg connected")
        try {
            while (isActive) {
                try {
                    val s = collectStats(m)
                    _uiState.value = _uiState.value.copy(
                        stats = _uiState.value.stats + (id to s)
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SpLog.w("CardPerf", "${srv.name} collect failed: ${e.message}")
                    break
                }
                delay(5000)
            }
        } finally {
            runCatching { m.close() }
            if (w.mgr === m) w.mgr = null
        }
    }

    private suspend fun credentials(s: Server): Triple<String, String?, String?>? {
        return if (s.authType == "key") {
            val k = keyStore.get(s.id) ?: return null
            Triple("", k.first, k.second)
        } else {
            val p = passwordStore.get(s.id) ?: return null
            Triple(p, null, null)
        }
    }

    private fun removeWorker(id: Long) {
        workers.remove(id)?.let {
            it.job?.cancel()
            runCatching { it.mgr?.close() }
        }
        _uiState.value = _uiState.value.copy(stats = _uiState.value.stats - id)
    }

    override fun onCleared() {
        workers.keys.toList().forEach { removeWorker(it) }
        super.onCleared()
    }
}
