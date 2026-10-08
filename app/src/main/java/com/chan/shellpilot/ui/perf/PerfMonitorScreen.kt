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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
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
                PerfCard(
                    icon = Icons.Filled.Speed,
                    title = "CPU",
                    accent = Color(0xFF4FC3F7),
                    valueText = stats.cpuText,
                    fraction = stats.cpuFraction,
                    detail = stats.cpuDetail,
                )
                PerfCard(
                    icon = Icons.Filled.Memory,
                    title = "内存",
                    accent = Color(0xFFBA68C8),
                    valueText = stats.memText,
                    fraction = stats.memFraction,
                    detail = stats.memDetail,
                )
                PerfCard(
                    icon = Icons.Filled.Storage,
                    title = "硬盘",
                    accent = Color(0xFFFFB74D),
                    valueText = stats.diskText,
                    fraction = stats.diskFraction,
                    detail = stats.diskDetail,
                )
                PerfCard(
                    icon = Icons.Filled.NetworkCheck,
                    title = "网络",
                    accent = Color(0xFF81C784),
                    valueText = stats.netText,
                    fraction = null,
                    detail = stats.netDetail,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun PerfCard(
    icon: ImageVector,
    title: String,
    accent: Color,
    valueText: String,
    fraction: Float?,
    detail: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = accent)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    valueText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = accent,
                )
            }
            if (fraction != null) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = accent,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
