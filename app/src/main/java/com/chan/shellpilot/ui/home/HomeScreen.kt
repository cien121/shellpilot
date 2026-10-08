package com.chan.shellpilot.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chan.shellpilot.data.Server

/**
 * 主页：仿 LobiShell 分区式布局。
 * - 顶部：ShellPilot 标题 + 搜索框
 * - 管理：管理连接 / 活跃隧道 / 日志和事件 / 性能监视器
 * - 连接：服务器卡片列表
 * - 工具：代码片段 / 设置
 */
@Composable
fun HomeScreen(
    servers: List<Server>,
    onManageConnections: () -> Unit,
    onTunnels: () -> Unit,
    onEventLog: () -> Unit,
    onPerfMonitor: () -> Unit,
    onSnippets: () -> Unit,
    onSettings: () -> Unit,
    onConnectServer: (Server) -> Unit,
    onAddServer: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = if (query.isBlank()) servers
    else servers.filter {
        it.name.contains(query, ignoreCase = true) ||
            it.host.contains(query, ignoreCase = true)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            // 顶部标题 + 搜索
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "ShellPilot",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索连接", fontSize = 14.sp) },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 管理
        item {
            SectionTitle("管理")
            ManageCard(
                icon = Icons.Filled.Dns,
                title = "管理连接",
                subtitle = "SSH 连接、端口转发和身份",
                onClick = onManageConnections,
            )
            ManageCard(
                icon = Icons.Filled.Cable,
                title = "活跃隧道",
                subtitle = "无活跃隧道",
                onClick = onTunnels,
            )
            ManageCard(
                icon = Icons.Filled.ReceiptLong,
                title = "日志和事件",
                subtitle = "连接和同步事件",
                onClick = onEventLog,
            )
            ManageCard(
                icon = Icons.Filled.ShowChart,
                title = "性能监视器",
                subtitle = "CPU、RAM、磁盘和网络图表",
                onClick = onPerfMonitor,
            )
        }

        // 连接
        item {
            Spacer(Modifier.height(8.dp))
            SectionTitle("连接")
        }
        if (filtered.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable(onClick = onAddServer),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            if (servers.isEmpty()) "还没有服务器" else "没有匹配的连接",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "点这里添加第一台",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        } else {
            items(filtered, key = { it.id }) { server ->
                ServerCard(
                    server = server,
                    onClick = { onConnectServer(server) },
                )
            }
        }

        // 工具
        item {
            Spacer(Modifier.height(8.dp))
            SectionTitle("工具")
            ManageCard(
                icon = Icons.Filled.Code,
                title = "代码片段",
                subtitle = "命令代码片段、模板",
                onClick = onSnippets,
            )
            ManageCard(
                icon = Icons.Filled.Settings,
                title = "设置",
                subtitle = "应用偏好设置、主题和数据",
                onClick = onSettings,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ManageCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ServerCard(
    server: Server,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 圆形头像字母
            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
            ) {
                androidx.compose.foundation.layout.Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Text(
                        server.name.firstOrNull()?.uppercase() ?: "?",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(server.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    "${server.username}@${server.host}:${server.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Filled.StarBorder,
                contentDescription = "收藏",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
