package com.chan.shellpilot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chan.shellpilot.ActiveSshSession
import com.chan.shellpilot.ShellPilotApp
import com.chan.shellpilot.data.Server
import com.chan.shellpilot.ssh.HostKeyChangedException
import com.chan.shellpilot.ssh.KnownHostsStore
import com.chan.shellpilot.ssh.SshConnectionManager
import com.chan.shellpilot.ssh.SshService
import com.chan.shellpilot.ssh.UnknownHostKeyException
import com.chan.shellpilot.terminal.TerminalBridge
import com.chan.shellpilot.util.SpLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 连接状态机：Idle → Connecting → Connected(error?) → Disconnected */
sealed interface ConnectState {
    data object Idle : ConnectState
    data object Connecting : ConnectState
    data class Connected(val bridge: TerminalBridge) : ConnectState
    data class Failed(val message: String) : ConnectState

    /**
     * 主机密钥待确认：首次连接的服务器（changed=false），
     * 或密钥与记录不一致（changed=true，疑似中间人攻击，红色警告）。
     * 确认后记入 known_hosts 并用原凭据重连。
     */
    data class HostKeyPrompt(
        val server: Server,
        val password: String,
        val keyPem: String?,
        val keyPassphrase: String?,
        val keyType: String,
        val fingerprint: String,
        val changed: Boolean,
        val oldFingerprint: String?,
    ) : ConnectState
}

class TerminalViewModel(app: Application) : AndroidViewModel(app) {

    private val pilotApp get() = getApplication<ShellPilotApp>()
    private val knownHosts = KnownHostsStore(app)
    private val cacheDir = app.cacheDir

    private val _state = MutableStateFlow<ConnectState>(ConnectState.Idle)
    val state: StateFlow<ConnectState> = _state.asStateFlow()

    init {
        // Activity 重建（如旋转屏幕、进程被回收后返回）时，
        // 若 Application 里还有活着的连接，直接复用，不断线。
        val sess = pilotApp.sshSession
        if (sess != null) {
            if (sess.manager.isConnected) {
                SpLog.i("TerminalVM", "reattach live session: ${sess.server.name}")
                _state.value = ConnectState.Connected(sess.bridge)
            } else {
                SpLog.w("TerminalVM", "stale session found, dropping")
                pilotApp.sshSession = null
            }
        }
        // 健康检查：每 15s 看一眼连接是否还活着。
        // pump 的 read() 在半开连接上可能永远阻塞，靠这个把"假活"揪出来，
        // 避免用户面对一个没反应的终端以为"卡死"。
        viewModelScope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_MS)
                val cur = _state.value
                if (cur is ConnectState.Connected) {
                    val sess2 = pilotApp.sshSession
                    val alive = sess2?.manager?.isConnected == true
                    SpLog.d(
                        "TerminalVM",
                        "health check: alive=$alive " +
                            "bridgeConnected=${cur.bridge.connected.value}",
                    )
                    if (!alive) {
                        SpLog.w("TerminalVM", "connection died, marking disconnected")
                        markDisconnected()
                    }
                }
            }
        }
    }

    /**
     * 连接指定服务器。密码认证传 password；私钥认证传 keyPem（+keyPassphrase 可空）。
     * 遇到未知/变更的主机密钥时进入 HostKeyPrompt 状态，等用户确认。
     */
    fun connect(
        server: Server,
        password: String = "",
        keyPem: String? = null,
        keyPassphrase: String? = null,
    ) {
        if (_state.value is ConnectState.Connecting) return
        // 已有连接先断开（换服务器的场景）
        if (_state.value is ConnectState.Connected) disconnect()
        _state.value = ConnectState.Connecting
        SpLog.i("TerminalVM", "connecting ${server.name} ...")
        viewModelScope.launch {
            val mgr = SshConnectionManager(cacheDir)
            val connResult = mgr.connect(
                server, password, keyPem, keyPassphrase, knownHosts,
            )
            val err = connResult.exceptionOrNull()
            when {
                err is UnknownHostKeyException -> {
                    mgr.close()
                    SpLog.i("TerminalVM", "unknown host key, asking user")
                    _state.value = ConnectState.HostKeyPrompt(
                        server = server,
                        password = password,
                        keyPem = keyPem,
                        keyPassphrase = keyPassphrase,
                        keyType = err.keyType,
                        fingerprint = err.fingerprint,
                        changed = false,
                        oldFingerprint = null,
                    )
                    return@launch
                }
                err is HostKeyChangedException -> {
                    mgr.close()
                    SpLog.w("TerminalVM", "host key CHANGED, warning user")
                    _state.value = ConnectState.HostKeyPrompt(
                        server = server,
                        password = password,
                        keyPem = keyPem,
                        keyPassphrase = keyPassphrase,
                        keyType = err.keyType,
                        fingerprint = err.newFingerprint,
                        changed = true,
                        oldFingerprint = err.oldFingerprint,
                    )
                    return@launch
                }
                connResult.isFailure -> {
                    mgr.close()
                    _state.value = ConnectState.Failed(
                        err?.message?.take(160) ?: "连接失败"
                    )
                    return@launch
                }
            }
            val shellResult = mgr.openShell()
            if (shellResult.isFailure) {
                mgr.close()
                _state.value = ConnectState.Failed(
                    shellResult.exceptionOrNull()?.message?.take(120)
                        ?: "打开 shell 失败"
                )
                return@launch
            }
            // bridge 挂在进程级 scope 下，不随 ViewModel/Activity 销毁
            val shell = shellResult.getOrThrow()
            val bridge = TerminalBridge(shell, pilotApp.appScope)
            bridge.start()
            pilotApp.sshSession = ActiveSshSession(mgr, shell, bridge, server)
            // 前台服务保活：切后台进程不被杀、不断网
            SshService.start(pilotApp, server.name)
            SpLog.i("TerminalVM", "connected ${server.name}")
            _state.value = ConnectState.Connected(bridge)
        }
    }

    /** 用户确认主机密钥：记入 known_hosts，用原凭据重连。 */
    fun confirmHostKey() {
        val prompt = _state.value as? ConnectState.HostKeyPrompt ?: return
        viewModelScope.launch {
            knownHosts.save(
                prompt.server.host, prompt.server.port,
                prompt.keyType, prompt.fingerprint,
            )
            _state.value = ConnectState.Idle
            connect(
                prompt.server, prompt.password,
                prompt.keyPem, prompt.keyPassphrase,
            )
        }
    }

    /** 用户拒绝主机密钥：回到空闲。 */
    fun dismissHostKeyPrompt() {
        if (_state.value is ConnectState.HostKeyPrompt) {
            _state.value = ConnectState.Idle
        }
    }

    /** 把当前会话标记为已断开（健康检查或 pump 发现连接死亡时调用）。 */
    fun markDisconnected() {
        val sess = pilotApp.sshSession
        pilotApp.sshSession = null
        runCatching { sess?.bridge?.close() }
        runCatching { sess?.manager?.close() }
        SshService.stop(pilotApp)
        _state.value = ConnectState.Idle
    }

    fun disconnect() {
        SpLog.i("TerminalVM", "disconnect (user)")
        markDisconnected()
    }

    fun resetError() {
        if (_state.value is ConnectState.Failed) _state.value = ConnectState.Idle
    }

    override fun onCleared() {
        // 注意：这里不再主动断开连接！
        // 连接由 Application 持有 + 前台服务保活，Activity 销毁（切后台、旋转屏幕）
        // 不断线。只有用户点返回/断开，或健康检查发现真死了，才会断。
        SpLog.i("TerminalVM", "onCleared (connection kept alive)")
        super.onCleared()
    }

    companion object {
        private const val HEALTH_CHECK_MS = 15_000L
    }
}
