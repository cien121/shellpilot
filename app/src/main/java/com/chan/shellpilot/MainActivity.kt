package com.chan.shellpilot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chan.shellpilot.data.Server
import com.chan.shellpilot.terminal.TerminalScreen
import com.chan.shellpilot.ui.ConnectState
import com.chan.shellpilot.ui.ServerListViewModel
import com.chan.shellpilot.ui.TerminalViewModel
import com.chan.shellpilot.ui.events.EventLogScreen
import com.chan.shellpilot.ui.events.EventLogViewModel
import com.chan.shellpilot.ui.home.HomeScreen
import com.chan.shellpilot.ui.home.ManageConnectionsScreen
import com.chan.shellpilot.ui.settings.SettingsScreen
import com.chan.shellpilot.ui.snippets.SnippetsScreen
import com.chan.shellpilot.ui.theme.ShellPilotTheme
import com.chan.shellpilot.util.SpLog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/** 应用内页面路由（简单状态机，不引入 Navigation 库）。 */
private enum class Route {
    Home, ManageConnections, Snippets, EventLog, Settings, Terminal, Placeholder
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
                val eventLogViewModel: EventLogViewModel = viewModel()
                val scope = rememberCoroutineScope()
                var route by remember { mutableStateOf(Route.Home) }
                var placeholderTitle by remember { mutableStateOf("") }
                // Activity 重建时（如切后台后返回），若后台还有活着的连接，
                // 直接回到终端页，而不是主页。
                var currentServer by remember {
                    mutableStateOf(app.sshSession?.server)
                }
                val connectState by terminalViewModel.state.collectAsState()
                val servers by listViewModel.servers.collectAsState()

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

                fun connectTo(server: Server, password: String) {
                    currentServer = server
                    eventLogViewModel.log(
                        server.name,
                        "正在连接 ${server.host}:${server.port}"
                    )
                    terminalViewModel.connect(server, password)
                }

                when (route) {
                    Route.Terminal -> {
                        val srv = currentServer
                        if (srv != null && bridge != null) {
                            TerminalScreen(
                                title = srv.name,
                                bridge = bridge,
                                onBack = {
                                    terminalViewModel.disconnect()
                                    currentServer = null
                                    route = Route.Home
                                },
                            )
                        } else {
                            // 连接没了但还停在终端页（极端情况）→ 回主页
                            if (connectState !is ConnectState.Connecting) {
                                currentServer = null
                                route = Route.Home
                            }
                        }
                    }
                    Route.Home -> {
                        HomeScreen(
                            servers = servers,
                            onManageConnections = { route = Route.ManageConnections },
                            onTunnels = {
                                placeholderTitle = "活跃隧道"
                                route = Route.Placeholder
                            },
                            onEventLog = { route = Route.EventLog },
                            onPerfMonitor = {
                                placeholderTitle = "性能监视器"
                                route = Route.Placeholder
                            },
                            onSnippets = { route = Route.Snippets },
                            onSettings = { route = Route.Settings },
                            onConnectServer = { server ->
                                scope.launch {
                                    val stored = listViewModel.storedPassword(server.id)
                                    if (stored != null) {
                                        connectTo(server, stored)
                                    } else {
                                        // 没记住密码：走管理连接页处理（简化：直接跳管理页）
                                        route = Route.ManageConnections
                                    }
                                }
                            },
                            onAddServer = { route = Route.ManageConnections },
                        )
                    }
                    Route.ManageConnections -> {
                        ManageConnectionsScreen(
                            servers = servers,
                            onBack = { route = Route.Home },
                            onConnect = { server ->
                                scope.launch {
                                    val stored = listViewModel.storedPassword(server.id)
                                    if (stored != null) {
                                        connectTo(server, stored)
                                    } else {
                                        // TODO: 密码输入框（后续接）
                                        eventLogViewModel.log(server.name, "需要输入密码（待实现）")
                                    }
                                }
                            },
                            onAdd = { /* TODO: 添加服务器对话框 */ },
                            onEdit = { /* TODO: 编辑 */ },
                            onDelete = { listViewModel.deleteServer(it) },
                        )
                    }
                    Route.Snippets -> {
                        SnippetsScreen(onBack = { route = Route.Home })
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
