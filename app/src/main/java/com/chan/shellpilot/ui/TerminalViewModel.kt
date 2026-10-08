package com.chan.shellpilot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chan.shellpilot.data.Server
import com.chan.shellpilot.ssh.SshConnectionManager
import com.chan.shellpilot.terminal.TerminalBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 连接状态机：Idle → Connecting → Connected(error?) → Disconnected */
sealed interface ConnectState {
    data object Idle : ConnectState
    data object Connecting : ConnectState
    data class Connected(val bridge: TerminalBridge) : ConnectState
    data class Failed(val message: String) : ConnectState
}

class TerminalViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<ConnectState>(ConnectState.Idle)
    val state: StateFlow<ConnectState> = _state.asStateFlow()

    private var manager: SshConnectionManager? = null

    /** 用密码连接指定服务器，成功后打开 shell 并接入终端桥。 */
    fun connect(server: Server, password: String) {
        if (_state.value is ConnectState.Connecting) return
        _state.value = ConnectState.Connecting
        viewModelScope.launch {
            val mgr = SshConnectionManager()
            manager = mgr
            val connResult = mgr.connect(server, password)
            if (connResult.isFailure) {
                mgr.close()
                manager = null
                _state.value = ConnectState.Failed(
                    connResult.exceptionOrNull()?.message?.take(120)
                        ?: "连接失败"
                )
                return@launch
            }
            val shellResult = mgr.openShell()
            if (shellResult.isFailure) {
                mgr.close()
                manager = null
                _state.value = ConnectState.Failed(
                    shellResult.exceptionOrNull()?.message?.take(120)
                        ?: "打开 shell 失败"
                )
                return@launch
            }
            val bridge = TerminalBridge(shellResult.getOrThrow(), this)
            bridge.start()
            _state.value = ConnectState.Connected(bridge)
        }
    }

    fun disconnect() {
        (_state.value as? ConnectState.Connected)?.bridge?.close()
        manager?.close()
        manager = null
        _state.value = ConnectState.Idle
    }

    fun resetError() {
        if (_state.value is ConnectState.Failed) _state.value = ConnectState.Idle
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }
}
