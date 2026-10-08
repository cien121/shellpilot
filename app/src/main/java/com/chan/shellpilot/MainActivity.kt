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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chan.shellpilot.data.Server
import com.chan.shellpilot.terminal.TerminalScreen
import com.chan.shellpilot.ui.ConnectState
import com.chan.shellpilot.ui.ServerListViewModel
import com.chan.shellpilot.ui.ShellPilotApp
import com.chan.shellpilot.ui.TerminalViewModel
import com.chan.shellpilot.ui.theme.ShellPilotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShellPilotTheme {
                val listViewModel: ServerListViewModel = viewModel()
                val terminalViewModel: TerminalViewModel = viewModel()
                var currentServer by remember { mutableStateOf<Server?>(null) }
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
                    ShellPilotApp(
                        listViewModel = listViewModel,
                        terminalViewModel = terminalViewModel,
                        onOpenTerminal = { server -> currentServer = server },
                    )
                }
            }
        }
    }
}
