package com.chan.shellpilot.ssh

import android.content.Context
import android.content.SharedPreferences
import com.chan.shellpilot.util.SpLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * known_hosts：服务器主机密钥指纹存储。
 *
 * 指纹本身是公开信息，不需要加密，用普通 SharedPreferences 即可。
 * key：`host:port`，value：`keyType fingerprint`（如 `ssh-ed25519 SHA256:xxx`）。
 */
class KnownHostsStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences("shellpilot_known_hosts", Context.MODE_PRIVATE)
    }

    suspend fun get(host: String, port: Int): String? = withContext(Dispatchers.IO) {
        prefs.getString(keyOf(host, port), null)
    }

    suspend fun save(host: String, port: Int, keyType: String, fingerprint: String) =
        withContext(Dispatchers.IO) {
            prefs.edit().putString(keyOf(host, port), "$keyType $fingerprint").apply()
            SpLog.i("KnownHosts", "saved $host:$port $keyType $fingerprint")
        }

    suspend fun clear(host: String, port: Int) = withContext(Dispatchers.IO) {
        prefs.edit().remove(keyOf(host, port)).apply()
    }

    private fun keyOf(host: String, port: Int) = "$host:$port"
}
