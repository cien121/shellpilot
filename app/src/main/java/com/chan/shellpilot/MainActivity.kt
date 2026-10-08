package com.chan.shellpilot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chan.shellpilot.data.Server
import com.chan.shellpilot.terminal.TerminalScreen
import com.chan.shellpilot.ui.AddServerDialog
import com.chan.shellpilot.ui.ConnectState
import com.chan.shellpilot.ui.ServerDraft
import com.chan.shellpilot.ui.ServerListViewModel
import com.chan.shellpilot.ui.TerminalViewModel
import com.chan.shellpilot.ui.events.EventLogScreen
import com.chan.shellpilot.ui.events.EventLogViewModel
import com.chan.shellpilot.ui.home.ServerCardPerfViewModel
import com.chan.shellpilot.ui.home.HomeScreen
import com.chan.shellpilot.ui.home.ManageConnectionsScreen
import com.chan.shellpilot.ui.settings.SettingsScreen
import com.chan.shellpilot.ui.sftp.SftpScreen
import com.chan.shellpilot.ui.snippets.SnippetsScreen
import com.chan.shellpilot.ui.theme.ShellPilotTheme
import com.chan.shellpilot.util.SpLog
import kotlinx.coroutines.launch

/** 应用内页面路由（简单状态机，不引入 Navigation 库）。 */
private enum class Route {
    Home, ManageConnections, Snippets, EventLog, Settings, Terminal,
    Sftp, Placeholder
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpLog.i("MainActivity", "onCreate")
        enableEdgeToEdge()
        setContent {
            ShellPilotTheme {
                val app = LocalContext.current.applicationContext as ShellPilotApp
                val listViewModel: ServerListViewModel = viewModel()
                val terminalViewModel: TerminalViewModel = viewModel()
                val cardPerfVm: ServerCardPerfViewModel = viewModel()
                val eventLogViewModel: EventLogViewModel = viewModel()
                val scope = rememberCoroutineScope()
                var route by remember { mutableStateOf(Route.Home) }
                var placeholderTitle by remember { mutableStateOf("") }
                // 对话框状态
                var showAddDialog by remember { mutableStateOf(false) }
                var editServer by remember { mutableStateOf<Server?>(null) }
                var pwPromptServer by remember { mutableStateOf<Server?>(null) }
                // Activity 重建时（如切后台后返回），若后台还有活着的连接，
                // 直接回到终端页，而不是主页。
                var currentServer by remember {
                    mutableStateOf(app.sshSession?.server)
                }
                val connectState by terminalViewModel.state.collectAsState()
                val servers by listViewModel.servers.collectAsState()
                val cardPerf by cardPerfVm.uiState.collectAsState()

                androidx.compose.runtime.LaunchedEffect(servers) {
                    cardPerfVm.setServers(servers)
                }
                val isHome = route == Route.Home
                androidx.compose.runtime.DisposableEffect(isHome) {
                    if (isHome) cardPerfVm.onVisible()
                    onDispose { cardPerfVm.onHidden() }
                }

                val bridge = (connectState as? ConnectState.Connected)?.bridge

                // 连接成功 → 记日志 + 跳终端页
                androidx.compose.runtime.LaunchedEffect(connectState) {
                    if (connectState is ConnectState.Connected) {
                        currentServer?.let {
                            eventLogViewModel.log(it.name, "连接成功")
                        }
                        route = Route.Terminal
                    }
                }

                /** 解析凭据并发起连接：私钥走 KeyStore，密码走记住的密码或弹框。 */
                fun connectTo(server: Server) {
                    currentServer = server
                    eventLogViewModel.log(
                        server.name,
                        "正在连接 ${server.host}:${server.port}"
                    )
                    scope.launch {
                        if (server.authType == "key") {
                            val k = listViewModel.storedKey(server.id)
                            if (k == null) {
                                eventLogViewModel.log(server.name, "未找到私钥，请重新编辑导入")
                                SpLog.w("MainActivity", "no key for server ${server.id}")
                                return@launch
                            }
                            terminalViewModel.connect(
                                server, keyPem = k.first, keyPassphrase = k.second,
                            )
                        } else {
                            val stored = listViewModel.storedPassword(server.id)
                            if (stored != null) {
                                terminalViewModel.connect(server, password = stored)
                            } else {
                                // 没记住密码：弹密码输入框
                                pwPromptServer = server
                            }
                        }
                    }
                }

                fun submitDraft(draft: ServerDraft, editing: Server?) {
                    if (editing == null) {
                        listViewModel.addServer(
                            name = draft.name,
                            host = draft.host,
                            port = draft.port,
                            username = draft.username,
                            password = draft.password,
                            rememberPassword = draft.rememberPassword,
                            authType = draft.authType,
                            keyPem = draft.keyPem,
                            keyName = draft.keyName,
                            keyPassphrase = draft.keyPassphrase,
                        ) { server ->
                            showAddDialog = false
                            connectTo(server)
                        }
                    } else {
                        listViewModel.updateServer(
                            server = editing,
                            name = draft.name,
                            host = draft.host,
                            port = draft.port,
                            username = draft.username,
                            password = draft.password,
                            rememberPassword = draft.rememberPassword,
                            authType = draft.authType,
                            keyPem = draft.keyPem,
                            keyName = draft.keyName,
                            keyPassphrase = draft.keyPassphrase,
                        ) { editServer = null }
                    }
                }

                when (route) {
                    Route.Terminal -> {
                        val srv = currentServer
                        if (srv != null && bridge != null) {
                            TerminalScreen(
                                title = srv.name,
                                bridge = bridge,
                                onBack = {
                                    // 返回主页不断开：连接在 Application 级保活，
                                    // 用户可去性能监视器/SFTP 等页面，回来继续用。
                                    // 断开请点顶栏的断开按钮。
                                    route = Route.Home
                                },
                                onDisconnect = {
                                    terminalViewModel.disconnect()
                                    currentServer = null
                                    route = Route.Home
                                },
                                onOpenSftp = { route = Route.Sftp },
                            )
                        } else {
                            // 连接没了但还停在终端页（极端情况）→ 回主页
                            if (connectState !is ConnectState.Connecting &&
                                connectState !is ConnectState.HostKeyPrompt
                            ) {
                                currentServer = null
                                route = Route.Home
                            }
                        }
                    }
                    Route.Sftp -> {
                        val srv = currentServer ?: app.sshSession?.server
                        if (srv != null) {
                            SftpScreen(
                                server = srv,
                                onBack = { route = Route.Terminal },
                            )
                        } else {
                            // 极端情况：没有服务器信息，回终端页兜底
                            route = Route.Terminal
                        }
                    }
                    Route.Home -> {
                        HomeScreen(
                            servers = servers,
                            onManageConnections = { route = Route.ManageConnections },
                            onSnippets = { route = Route.Snippets },
                            onSettings = { route = Route.Settings },
                            onConnectServer = {
                                // 已连上这台：直接进终端，不重连
                                if (connectState is ConnectState.Connected &&
                                    currentServer?.id == it.id &&
                                    app.sshSession?.manager?.isConnected == true
                                ) {
                                    route = Route.Terminal
                                } else {
                                    connectTo(it)
                                }
                            },
                            onAddServer = { showAddDialog = true },
                            cardPerf = cardPerf.stats,
                        )
                    }
                    Route.ManageConnections -> {
                        ManageConnectionsScreen(
                            servers = servers,
                            onBack = { route = Route.Home },
                            onConnect = {
                                // 已连上这台：直接进终端，不重连
                                if (connectState is ConnectState.Connected &&
                                    currentServer?.id == it.id &&
                                    app.sshSession?.manager?.isConnected == true
                                ) {
                                    route = Route.Terminal
                                } else {
                                    connectTo(it)
                                }
                            },
                            onAdd = { showAddDialog = true },
                            onEdit = { editServer = it },
                            onDelete = { listViewModel.deleteServer(it) },
                        )
                    }
                    Route.Snippets -> {
                        SnippetsScreen(
                            onBack = { route = Route.Home },
                            onRun = { cmd ->
                                val b = bridge
                                if (b != null) {
                                    b.sendLine(cmd)
                                    route = Route.Terminal
                                    true
                                } else {
                                    false
                                }
                            },
                        )
                    }
                    Route.EventLog -> {
                        EventLogScreen(onBack = { route = Route.Home })
                    }
                    Route.Settings -> {
                        SettingsScreen(onBack = { route = Route.Home })
                    }
                    Route.Placeholder -> {
                        PlaceholderScreen(
                            title = placeholderTitle,
                            onBack = { route = Route.Home },
                        )
                    }
                }

                // ---- 对话框 ----
                if (showAddDialog) {
                    AddServerDialog(
                        onDismiss = { showAddDialog = false },
                        onConfirm = { submitDraft(it, null) },
                    )
                }
                val editing = editServer
                if (editing != null) {
                    AddServerDialog(
                        server = editing,
                        onDismiss = { editServer = null },
                        onConfirm = { submitDraft(it, editing) },
                    )
                }
                val pwSrv = pwPromptServer
                if (pwSrv != null) {
                    PasswordPromptDialog(
                        server = pwSrv,
                        onDismiss = { pwPromptServer = null },
                        onConfirm = { password, remember ->
                            pwPromptServer = null
                            if (remember) listViewModel.savePassword(pwSrv.id, password)
                            terminalViewModel.connect(pwSrv, password = password)
                        },
                    )
                }
                val hkPrompt = connectState as? ConnectState.HostKeyPrompt
                if (hkPrompt != null) {
                    HostKeyDialog(
                        prompt = hkPrompt,
                        onTrust = { terminalViewModel.confirmHostKey() },
                        onReject = { terminalViewModel.dismissHostKeyPrompt() },
                    )
                }
                val failed = connectState as? ConnectState.Failed
                if (failed != null) {
                    AlertDialog(
                        onDismissRequest = { terminalViewModel.resetError() },
                        title = { Text("连接失败") },
                        text = { Text(failed.message) },
                        confirmButton = {
                            TextButton(onClick = { terminalViewModel.resetError() }) {
                                Text("确定")
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        SpLog.i("MainActivity", "onPause (app -> background)")
    }

    override fun onResume() {
        super.onResume()
        SpLog.i("MainActivity", "onResume (app -> foreground)")
    }
}

/** 没记住密码时的密码输入框。 */
@Composable
private fun PasswordPromptDialog(
    server: Server,
    onDismiss: () -> Unit,
    onConfirm: (password: String, remember: Boolean) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("输入密码") },
        text = {
            Column {
                Text(
                    "${server.username}@${server.host}:${server.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = remember, onCheckedChange = { remember = it })
                    Text("记住密码")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotEmpty(),
                onClick = { onConfirm(password, remember) },
            ) { Text("连接") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 主机密钥确认框：首次连接显示指纹；密钥变更时红色警告。 */
@Composable
private fun HostKeyDialog(
    prompt: ConnectState.HostKeyPrompt,
    onTrust: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = {
            Text(
                if (prompt.changed) "警告：主机密钥已变更！" else "确认服务器指纹",
                color = if (prompt.changed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (prompt.changed) {
                    Text(
                        "该服务器的主机密钥与上次记录的不一致，可能是服务器重装，也可能是中间人攻击。请核对后再决定。",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text("旧指纹：${prompt.oldFingerprint ?: "--"}")
                } else {
                    Text("首次连接该服务器，请核对指纹无误后信任：")
                }
                Text("${prompt.server.host}:${prompt.server.port}")
                Text(
                    "${prompt.keyType}\n${prompt.fingerprint}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onTrust) {
                Text(if (prompt.changed) "信任新密钥并连接" else "信任并连接")
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) { Text("取消") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceholderScreen(title: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "「$title」后续版本加入",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
