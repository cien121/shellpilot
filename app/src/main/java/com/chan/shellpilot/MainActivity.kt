package com.chan.shellpilot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.chan.shellpilot.ui.ShellPilotApp
import com.chan.shellpilot.ui.theme.ShellPilotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShellPilotTheme {
                ShellPilotApp()
            }
        }
    }
}
