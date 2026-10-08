package com.chan.shellpilot.ssh

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.chan.shellpilot.util.SpLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 服务器密码加密存储（EncryptedSharedPreferences，AES256）。
 * key 按服务器 id 隔离：pw_<serverId>。
 *
 * 注意：所有方法都是 suspend 且切到 IO 线程。
 * EncryptedSharedPreferences 首次初始化要走 Android Keystore（可达数百毫秒），
 * 绝不能在主线程调用，否则点"连接"时会卡顿。
 */
class PasswordStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        SpLog.d("PasswordStore", "init EncryptedSharedPreferences (may take a while)")
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "shellpilot_passwords",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** 预热：在后台线程提前初始化，避免首次调用时卡顿。 */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        prefs // 触发 lazy 初始化
        SpLog.d("PasswordStore", "warmed up")
    }

    suspend fun save(serverId: Long, password: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(keyOf(serverId), password).apply()
    }

    /** 没有记住时返回 null。 */
    suspend fun get(serverId: Long): String? = withContext(Dispatchers.IO) {
        prefs.getString(keyOf(serverId), null)?.takeIf { it.isNotEmpty() }
    }

    suspend fun has(serverId: Long): Boolean = get(serverId) != null

    suspend fun clear(serverId: Long) = withContext(Dispatchers.IO) {
        prefs.edit().remove(keyOf(serverId)).apply()
    }

    private fun keyOf(serverId: Long) = "pw_$serverId"
}
