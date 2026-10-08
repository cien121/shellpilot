package com.chan.shellpilot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chan.shellpilot.data.Server
import com.chan.shellpilot.data.ShellPilotDatabase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Room-backed server list. Add / delete servers.
 * Passwords are held in memory only for the connect flow (not persisted).
 */
class ServerListViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = ShellPilotDatabase.get(app).serverDao()

    val servers = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addServer(
        name: String,
        host: String,
        port: Int,
        username: String,
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
            onDone(server.copy(id = id))
        }
    }

    fun deleteServer(server: Server) {
        viewModelScope.launch { dao.delete(server) }
    }
}
