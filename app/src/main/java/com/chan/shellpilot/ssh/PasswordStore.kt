package com.chan.shellpilot.ssh

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 服务器密码加密存储（EncryptedSharedPreferences，AES256）。
 * key 按服务器 id 隔离：pw_<serverId>。
 */
class PasswordStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
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

    fun save(serverId: Long, password: String) {
        prefs.edit().putString(keyOf(serverId), password).apply()
    }

    /** 没有记住时返回 null。 */
    fun get(serverId: Long): String? =
        prefs.getString(keyOf(serverId), null)?.takeIf { it.isNotEmpty() }

    fun has(serverId: Long): Boolean = get(serverId) != null

    fun clear(serverId: Long) {
        prefs.edit().remove(keyOf(serverId)).apply()
    }

    private fun keyOf(serverId: Long) = "pw_$serverId"
}
