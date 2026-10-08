package com.chan.shellpilot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chan.shellpilot.data.Server
import com.chan.shellpilot.data.ShellPilotDatabase
import com.chan.shellpilot.ssh.PasswordStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Room-backed server list. Add / delete servers.
 * 记住的密码走 EncryptedSharedPreferences（PasswordStore），不进 Room。
 */
class ServerListViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = ShellPilotDatabase.get(app).serverDao()
    private val passwordStore = PasswordStore(app)

    val servers = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addServer(
        name: String,
        host: String,
        port: Int,
        username: String,
        password: String = "",
        rememberPassword: Boolean = false,
        onDone: (Server) -> Unit = {},
    ) {
        viewModelScope.launch {
            val server = Server(
                name = name.ifBlank { "$username@$host" },
                host = host.trim(),
                port = port,
                username = username.trim(),
                authType = "password",
            )
            val id = dao.upsert(server)
            if (rememberPassword && password.isNotEmpty()) {
                passwordStore.save(id, password)
            }
            onDone(server.copy(id = id))
        }
    }

    fun deleteServer(server: Server) {
        viewModelScope.launch {
            passwordStore.clear(server.id)
            dao.delete(server)
        }
    }

    /** 记住的密码（没有记住返回 null）。 */
    fun storedPassword(serverId: Long): String? = passwordStore.get(serverId)

    fun hasStoredPassword(serverId: Long): Boolean = passwordStore.has(serverId)

    fun savePassword(serverId: Long, password: String) {
        if (password.isNotEmpty()) passwordStore.save(serverId, password)
    }

    fun clearStoredPassword(server: Server) {
        passwordStore.clear(server.id)
    }
}
