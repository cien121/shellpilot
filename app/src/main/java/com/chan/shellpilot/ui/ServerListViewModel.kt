package com.chan.shellpilot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chan.shellpilot.data.Server
import com.chan.shellpilot.data.ShellPilotDatabase
import com.chan.shellpilot.ssh.KeyStore
import com.chan.shellpilot.ssh.PasswordStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Room-backed server list. Add / delete servers.
 * 记住的密码走 EncryptedSharedPreferences（PasswordStore），私钥走 KeyStore，都不进 Room。
 */
class ServerListViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = ShellPilotDatabase.get(app).serverDao()
    private val passwordStore = PasswordStore(app)
    private val keyStore = KeyStore(app)

    val servers = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addServer(
        name: String,
        host: String,
        port: Int,
        username: String,
        password: String = "",
        rememberPassword: Boolean = false,
        authType: String = "password",
        keyPem: String? = null,
        keyName: String? = null,
        keyPassphrase: String? = null,
        onDone: (Server) -> Unit = {},
    ) {
        viewModelScope.launch {
            val server = Server(
                name = name.ifBlank { "$username@$host" },
                host = host.trim(),
                port = port,
                username = username.trim(),
                authType = authType,
                credential = keyName ?: "",
            )
            val id = dao.upsert(server)
            if (authType == "key" && !keyPem.isNullOrBlank()) {
                keyStore.save(id, keyPem, keyPassphrase)
            } else if (rememberPassword && password.isNotEmpty()) {
                passwordStore.save(id, password)
            }
            onDone(server.copy(id = id))
        }
    }

    /** 编辑服务器：更新 Room；认证信息按新选择覆盖。 */
    fun updateServer(
        server: Server,
        name: String,
        host: String,
        port: Int,
        username: String,
        password: String = "",
        rememberPassword: Boolean = false,
        authType: String = "password",
        keyPem: String? = null, // 非空 = 用户重新选了私钥
        keyName: String? = null,
        keyPassphrase: String? = null,
        onDone: () -> Unit = {},
    ) {
        viewModelScope.launch {
            val updated = server.copy(
                name = name.ifBlank { "$username@$host" },
                host = host.trim(),
                port = port,
                username = username.trim(),
                authType = authType,
                credential = if (authType == "key") (keyName ?: server.credential) else "",
            )
            dao.update(updated)
            if (authType == "key") {
                if (!keyPem.isNullOrBlank()) {
                    keyStore.save(server.id, keyPem, keyPassphrase)
                }
                passwordStore.clear(server.id)
            } else {
                keyStore.clear(server.id)
                if (rememberPassword && password.isNotEmpty()) {
                    passwordStore.save(server.id, password)
                } else if (!rememberPassword) {
                    passwordStore.clear(server.id)
                }
            }
            onDone()
        }
    }

    fun deleteServer(server: Server) {
        viewModelScope.launch {
            passwordStore.clear(server.id)
            keyStore.clear(server.id)
            dao.delete(server)
        }
    }

    /** 记住的密码（没有记住返回 null）。suspend：走 IO 线程，避免 Keystore 初始化卡主线程。 */
    suspend fun storedPassword(serverId: Long): String? = passwordStore.get(serverId)

    suspend fun hasStoredPassword(serverId: Long): Boolean = passwordStore.has(serverId)

    fun savePassword(serverId: Long, password: String) {
        if (password.isNotEmpty()) {
            viewModelScope.launch { passwordStore.save(serverId, password) }
        }
    }

    fun clearStoredPassword(server: Server) {
        viewModelScope.launch { passwordStore.clear(server.id) }
    }

    /** 私钥（pem, passphrase?），没导入过返回 null。 */
    suspend fun storedKey(serverId: Long): Pair<String, String?>? = keyStore.get(serverId)

    suspend fun hasStoredKey(serverId: Long): Boolean = keyStore.has(serverId)
}
