package com.chan.shellpilot.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chan.shellpilot.data.Server
import kotlinx.coroutines.launch

/**
 * 服务器列表：点一下连接（记住了密码则直连），长按出操作菜单，右下角 + 添加。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ShellPilotApp(
    listViewModel: ServerListViewModel = viewModel(),
    terminalViewModel: TerminalViewModel = viewModel(),
    onOpenTerminal: (Server) -> Unit = {},
) {
    val servers by listViewModel.servers.collectAsState()
    val connectState by terminalViewModel.state.collectAsState()

    var showAdd by remember { mutableStateOf(false) }
    var pendingServer by remember { mutableStateOf<Server?>(null) }
    var pendingPassword by remember { mutableStateOf("") }
    var rememberInPrompt by remember { mutableStateOf(true) }
    var serverMenuTarget by remember { mutableStateOf<Server?>(null) }
    var serverToDelete by remember { mutableStateOf<Server?>(null) }
    // 刚添加的服务器附带的密码（添加时输入的），用于免二次输入直连
    var justAddedPassword by remember { mutableStateOf<Pair<Long, String>?>(null) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 连接成功 → 跳终端页
    LaunchedEffect(connectState) {
        if (connectState is ConnectState.Connected) {
            pendingServer?.let { onOpenTerminal(it) }
            pendingServer = null
            pendingPassword = ""
        }
    }
    // 连接失败 → 提示
    LaunchedEffect(connectState) {
        val s = connectState
        if (s is ConnectState.Failed) {
            snackbar.showSnackbar("连接失败：${s.message}")
            terminalViewModel.resetError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ShellPilot") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "添加服务器")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (servers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Filled.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "还没有服务器",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "点右下角 + 添加第一台",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(servers, key = { it.id }) { server ->
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .combinedClickable(
                                onClick = {
                                    scope.launch {
                                        // 刚添加的带密码直连；否则查记住的密码（挂起走 IO，
                                        // 避免 EncryptedSharedPreferences 初始化卡主线程），
                                        // 有则直连，无才弹密码框
                                        val saved = justAddedPassword
                                        if (saved != null && saved.first == server.id) {
                                            justAddedPassword = null
                                            pendingServer = server
                                            terminalViewModel.connect(server, saved.second)
                                        } else {
                                            val stored = listViewModel.storedPassword(server.id)
                                            if (stored != null) {
                                                pendingServer = server
                                                terminalViewModel.connect(server, stored)
                                            } else {
                                                pendingServer = server
                                                pendingPassword = ""
                                                rememberInPrompt = true
                                            }
                                        }
                                    }
                                },
                                onLongClick = { serverMenuTarget = server },
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        ListItem(
                            headlineContent = { Text(server.name) },
                            supportingContent = { Text("${server.username}@${server.host}:${server.port}") },
                            leadingContent = {
                                Icon(Icons.Filled.Terminal, contentDescription = null)
                            },
                            trailingContent = {
                                if (connectState is ConnectState.Connecting
                                    && pendingServer?.id == server.id
                                ) {
                                    CircularProgressIndicator()
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddServerDialog(
            onDismiss = { showAdd = false },
            onConfirm = { name, host, port, username, password, remember ->
                showAdd = false
                listViewModel.addServer(name, host, port, username, password, remember) { server ->
                    justAddedPassword = server.id to password
                    pendingServer = server
                    terminalViewModel.connect(server, password)
                }
            },
        )
    }

    // 连接时输密码（没记住的场景）
    val target = pendingServer
    if (target != null && connectState !is ConnectState.Connecting
        && connectState !is ConnectState.Connected
    ) {
        // 刚添加走直连，不弹框
        val saved = justAddedPassword
        if (saved == null || saved.first != target.id) {
            PasswordPromptDialog(
                server = target,
                password = pendingPassword,
                onPasswordChange = { pendingPassword = it },
                rememberPassword = rememberInPrompt,
                onRememberChange = { rememberInPrompt = it },
                onDismiss = { pendingServer = null },
                onConfirm = {
                    if (rememberInPrompt) {
                        listViewModel.savePassword(target.id, pendingPassword)
                    }
                    terminalViewModel.connect(target, pendingPassword)
                },
            )
        }
    }

    // 长按操作菜单：清除已存密码 / 删除服务器
    // hasStoredPassword 走 IO 异步加载，避免 Keystore 初始化卡住菜单弹出
    serverMenuTarget?.let { s ->
        var hasPw by remember(s.id) { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(s.id) {
            hasPw = listViewModel.hasStoredPassword(s.id)
        }
        AlertDialog(
            onDismissRequest = { serverMenuTarget = null },
            title = { Text(s.name) },
            text = { Text(if (hasPw == true) "已保存该服务器的密码" else "选择操作") },
            confirmButton = {
                TextButton(onClick = {
                    serverMenuTarget = null
                    serverToDelete = s
                }) { Text("删除服务器") }
            },
            dismissButton = {
                if (hasPw == true) {
                    TextButton(onClick = {
                        listViewModel.clearStoredPassword(s)
                        serverMenuTarget = null
                        scope.launch { snackbar.showSnackbar("已清除 ${s.name} 的已存密码") }
                    }) { Text("清除已存密码") }
                }
                TextButton(onClick = { serverMenuTarget = null }) { Text("取消") }
            },
        )
    }

    // 删除确认
    serverToDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { serverToDelete = null },
            title = { Text("删除服务器") },
            text = { Text("确定删除 ${s.name} 吗？已存密码会一并清除。") },
            confirmButton = {
                TextButton(onClick = {
                    listViewModel.deleteServer(s)
                    serverToDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { serverToDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun PasswordPromptDialog(
    server: Server,
    password: String,
    onPasswordChange: (String) -> Unit,
    rememberPassword: Boolean,
    onRememberChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("连接到 ${server.name}") },
        text = {
            Column {
                Text(
                    "${server.username}@${server.host}:${server.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("密码") },
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
                        onCheckedChange = onRememberChange,
                    )
                    Text("记住密码")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("连接") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
