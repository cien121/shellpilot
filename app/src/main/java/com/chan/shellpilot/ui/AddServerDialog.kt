package com.chan.shellpilot.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.chan.shellpilot.data.Server
import com.chan.shellpilot.util.SpLog

/** 添加/编辑服务器表单的提交数据。 */
data class ServerDraft(
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val authType: String, // "password" | "key"
    val password: String,
    val rememberPassword: Boolean,
    /** 私钥 PEM 文本；编辑模式下用户没重选则为 null（保留原私钥）。 */
    val keyPem: String?,
    val keyName: String?,
    val keyPassphrase: String?,
)

/**
 * 添加/编辑服务器表单。
 * - 认证方式：密码 / 私钥 二选一
 * - 私钥：从文件导入（ed25519/RSA，OpenSSH 或 PKCS#8 格式），口令可选，
 *   导入后经 KeyStore 加密存本地
 * - server 非空 = 编辑模式，预填原有内容
 */
@Composable
fun AddServerDialog(
    server: Server? = null,
    onDismiss: () -> Unit,
    onConfirm: (ServerDraft) -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(server?.name ?: "") }
    var host by remember { mutableStateOf(server?.host ?: "") }
    var portText by remember { mutableStateOf(server?.port?.toString() ?: "22") }
    var username by remember { mutableStateOf(server?.username ?: "root") }
    var authType by remember { mutableStateOf(server?.authType ?: "password") }
    var password by remember { mutableStateOf("") }
    var rememberPassword by remember { mutableStateOf(true) }
    var keyPem by remember { mutableStateOf<String?>(null) }
    var keyName by remember { mutableStateOf(server?.credential?.takeIf { it.isNotBlank() }) }
    var keyPassphrase by remember { mutableStateOf("") }
    var keyError by remember { mutableStateOf<String?>(null) }

    // 私钥文件选择器
    val pickKey = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                val bytes = ins.readBytes()
                if (bytes.size > 64 * 1024) throw IllegalArgumentException("文件过大（>64KB）")
                val text = bytes.toString(Charsets.UTF_8)
                if (!text.contains("PRIVATE KEY")) {
                    throw IllegalArgumentException("不是有效的私钥文件")
                }
                var fname = "key.pem"
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex("_display_name")
                    if (c.moveToFirst() && idx >= 0) {
                        fname = c.getString(idx) ?: fname
                    }
                }
                keyPem = text
                keyName = fname
                keyError = null
                SpLog.i("AddServer", "key imported: $fname (${bytes.size}B)")
            } ?: throw IllegalArgumentException("无法读取文件")
        }.onFailure {
            keyError = it.message ?: "导入失败"
            SpLog.e("AddServer", "key import failed: ${it.message}")
        }
    }

    val hostOk = host.isNotBlank()
    val userOk = username.isNotBlank()
    val authOk = if (authType == "key") {
        // 新增必须选私钥；编辑时可保留原私钥（keyName 非空）
        keyPem != null || (server != null && !keyName.isNullOrBlank())
    } else true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (server == null) "添加服务器" else "编辑服务器") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("别名（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("地址 *") },
                    placeholder = { Text("1.2.3.4 或 example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                    label = { Text("端口") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名 *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 认证方式二选一
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("认证：")
                    FilterChip(
                        selected = authType == "password",
                        onClick = { authType = "password" },
                        label = { Text("密码") },
                    )
                    FilterChip(
                        selected = authType == "key",
                        onClick = { authType = "key" },
                        label = { Text("私钥") },
                    )
                }
                if (authType == "password") {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(if (server == null) "密码" else "密码（留空不改）") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = rememberPassword,
                            onCheckedChange = { rememberPassword = it },
                        )
                        Text("记住密码")
                    }
                } else {
                    OutlinedButton(
                        onClick = { pickKey.launch("*/*") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (keyName != null) "已选：$keyName（点我重选）" else "选择私钥文件")
                    }
                    if (keyError != null) {
                        Text(
                            keyError!!,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "支持 ed25519 / RSA（OpenSSH 或 PKCS#8 格式），加密存本地",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = keyPassphrase,
                        onValueChange = { keyPassphrase = it },
                        label = { Text("私钥口令（无口令留空）") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            val confirmLabel = if (server == null) "保存并连接" else "保存"
            TextButton(
                onClick = {
                    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22
                    onConfirm(
                        ServerDraft(
                            name = name.trim(),
                            host = host.trim(),
                            port = port,
                            username = username.trim(),
                            authType = authType,
                            password = password,
                            rememberPassword = rememberPassword,
                            keyPem = keyPem,
                            keyName = keyName,
                            keyPassphrase = keyPassphrase.takeIf { it.isNotEmpty() },
                        )
                    )
                },
                enabled = hostOk && userOk && authOk,
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        modifier = Modifier.padding(vertical = 16.dp),
    )
}
