package com.chan.shellpilot.ssh

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.chan.shellpilot.util.SpLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 私钥加密存储（EncryptedSharedPreferences，AES256），与 PasswordStore 同机制。
 * key 按服务器 id 隔离：key_<serverId> 存 PEM 文本，keypw_<serverId> 存口令（可空）。
 *
 * 注意：所有方法都是 suspend 且切到 IO 线程，原因同 PasswordStore。
 */
class KeyStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        SpLog.d("KeyStore", "init EncryptedSharedPreferences (may take a while)")
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "shellpilot_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** 预热：在后台线程提前初始化。 */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        prefs
        SpLog.d("KeyStore", "warmed up")
    }

    suspend fun save(serverId: Long, pem: String, passphrase: String?) =
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putString(keyOf(serverId), pem)
                .putString(pwKeyOf(serverId), passphrase ?: "")
                .apply()
            SpLog.i("KeyStore", "saved key for server $serverId")
        }

    /** 返回 Pair(pem, passphrase?)，没有导入过返回 null。 */
    suspend fun get(serverId: Long): Pair<String, String?>? = withContext(Dispatchers.IO) {
        val pem = prefs.getString(keyOf(serverId), null)?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        val pw = prefs.getString(pwKeyOf(serverId), null)?.takeIf { it.isNotEmpty() }
        pem to pw
    }

    suspend fun has(serverId: Long): Boolean = get(serverId) != null

    suspend fun clear(serverId: Long) = withContext(Dispatchers.IO) {
        prefs.edit().remove(keyOf(serverId)).remove(pwKeyOf(serverId)).apply()
    }

    private fun keyOf(serverId: Long) = "key_$serverId"
    private fun pwKeyOf(serverId: Long) = "keypw_$serverId"
}
