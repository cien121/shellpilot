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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chan.shellpilot.data.Server

/**
 * 管理连接页：连接 / 端口转发 / 身份 三个 tab。
 * 连接 tab：服务器卡片（连接按钮、编辑、删除）。
 * 端口转发 / 身份：占位，后续里程碑实现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageConnectionsScreen(
    servers: List<Server>,
    onBack: () -> Unit,
    onConnect: (Server) -> Unit,
    onAdd: () -> Unit,
    onEdit: (Server) -> Unit,
    onDelete: (Server) -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val tabs = listOf("连接", "端口转发", "身份")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("管理", fontWeight = FontWeight.Bold) },
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
        floatingActionButton = {
            if (tab == 0) {
                FloatingActionButton(onClick = onAdd) {
                    Icon(Icons.Filled.Add, contentDescription = "添加")
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            // 搜索框
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索连接", fontSize = 14.sp) },
                leadingIcon = {
                    Icon(Icons.Filled.Search, null, modifier = Modifier.size(20.dp))
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            // Tabs
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, title ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text(title) },
                    )
                }
            }
            when (tab) {
                0 -> {
                    val filtered = if (query.isBlank()) servers
                    else servers.filter {
                        it.name.contains(query, ignoreCase = true) ||
                            it.host.contains(query, ignoreCase = true)
                    }
                    Text(
                        "全部 (${filtered.size})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(filtered, key = { it.id }) { server ->
                            ManageServerCard(
                                server = server,
                                onConnect = { onConnect(server) },
                                onEdit = { onEdit(server) },
                                onDelete = { onDelete(server) },
                            )
                        }
                        item { Spacer(Modifier.height(80.dp)) }
                    }
                }
                1 -> PlaceholderTab("尚未配置端口转发")
                2 -> PlaceholderTab("尚未配置身份")
            }
        }
    }
}

@Composable
private fun ManageServerCard(
    server: Server,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
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
            Button(onClick = onConnect) { Text("连接") }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Row(
                modifier = Modifier.clickable(onClick = onEdit),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Edit,
                    null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(4.dp))
                Text("编辑", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
            }
            Spacer(Modifier.width(24.dp))
            Row(
                modifier = Modifier.clickable(onClick = onDelete),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Delete,
                    null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(4.dp))
                Text("删除", color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun PlaceholderTab(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
