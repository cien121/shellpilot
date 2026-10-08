package com.chan.shellpilot.ui.perf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chan.shellpilot.ssh.SshConnectionManager
import com.chan.shellpilot.util.SpLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 性能监视器：卡片窗口化，CPU / 内存 / 硬盘 / 网络各一块。
 * 数据经 SSH 在远端执行命令采集（/proc 系列），每 3 秒刷新。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerfMonitorScreen(
    manager: SshConnectionManager?,
    serverName: String,
    onBack: () -> Unit,
) {
    var stats by remember { mutableStateOf(PerfStats()) }
    var firstLoad by remember { mutableStateOf(true) }

    LaunchedEffect(manager) {
        if (manager == null) return@LaunchedEffect
        while (isActive) {
            runCatching { stats = collectStats(manager) }
                .onFailure { SpLog.e("Perf", "collect failed: ${it.message}") }
            firstLoad = false
            delay(3000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("性能监视器", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
            )
        },
    ) { padding ->
        if (manager == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "暂无活跃连接",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "请先在首页连接一台服务器",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                serverName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (firstLoad) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            } else {
                // 网络文案形如 "↓1.2MB/s ↑300KB/s"，拆成表盘中央两行
                val netParts = stats.netText.split(" ", limit = 2)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GaugeCard(
                            icon = Icons.Filled.Speed,
                            title = "CPU",
                            accent = Color(0xFF4FC3F7),
                            centerTop = stats.cpuText,
                            fraction = stats.cpuFraction,
                            detail = stats.cpuDetail,
                            modifier = Modifier.weight(1f),
                        )
                        GaugeCard(
                            icon = Icons.Filled.Memory,
                            title = "内存",
                            accent = Color(0xFFBA68C8),
                            centerTop = stats.memText,
                            fraction = stats.memFraction,
                            detail = stats.memDetail,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GaugeCard(
                            icon = Icons.Filled.Storage,
                            title = "硬盘",
                            accent = Color(0xFFFFB74D),
                            centerTop = stats.diskText,
                            fraction = stats.diskFraction,
                            detail = stats.diskDetail,
                            modifier = Modifier.weight(1f),
                        )
                        GaugeCard(
                            icon = Icons.Filled.NetworkCheck,
                            title = "网络",
                            accent = Color(0xFF81C784),
                            centerTop = netParts.getOrElse(0) { "--" },
                            centerBottom = netParts.getOrElse(1) { "" },
                            fraction = null,
                            detail = stats.netDetail,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
