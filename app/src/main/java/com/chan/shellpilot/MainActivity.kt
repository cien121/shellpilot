package com.chan.shellpilot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chan.shellpilot.data.Server
import com.chan.shellpilot.terminal.TerminalScreen
import com.chan.shellpilot.ui.ConnectState
import com.chan.shellpilot.ui.ServerListViewModel
import com.chan.shellpilot.ui.ShellPilotApp
import com.chan.shellpilot.ui.TerminalViewModel
import com.chan.shellpilot.ui.theme.ShellPilotTheme
import com.chan.shellpilot.util.SpLog

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
                // Activity 重建时（如切后台后返回），若后台还有活着的连接，
                // 直接回到终端页，而不是服务器列表。
                var currentServer by remember {
                    mutableStateOf(app.sshSession?.server)
                }
                val connectState by terminalViewModel.state.collectAsState()

                val bridge = (connectState as? ConnectState.Connected)?.bridge

                if (currentServer != null && bridge != null) {
                    TerminalScreen(
                        title = currentServer!!.name,
                        bridge = bridge,
                        onBack = {
                            terminalViewModel.disconnect()
                            currentServer = null
                        },
                    )
                } else {
                    // 连接没了但还停在终端页（极端情况）→ 回列表
                    if (currentServer != null && bridge == null &&
                        connectState !is ConnectState.Connecting
                    ) {
                        currentServer = null
                    }
                    ShellPilotApp(
                        listViewModel = listViewModel,
                        terminalViewModel = terminalViewModel,
                        onOpenTerminal = { server -> currentServer = server },
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
