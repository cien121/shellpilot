package com.chan.shellpilot.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chan.shellpilot.data.Server
import com.chan.shellpilot.ui.perf.GaugeCard


/**
 * 主页嵌入式性能区：选服务器 → 后台自动连接 → 四张小卡片 3 秒刷新。
 * 不用先连 VPS，直接在主页看。
 */
@Composable
fun HomePerfSection(
    vm: HomePerfViewModel,
    servers: List<Server>,
    onAddServer: () -> Unit,
) {
    val st by vm.uiState.collectAsState()

    LaunchedEffect(servers) { vm.setServers(servers) }
    DisposableEffect(Unit) {
        vm.onHomeVisible()
        onDispose { vm.onHomeHidden() }
    }

    Column {
        SectionTitle("性能")

        // 没有保存任何服务器
        if (servers.isEmpty()) {
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "添加服务器后可查看性能",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            return@Column
        }

        val eligible = servers.filter { it.id in st.eligibleIds }
        // 有服务器但都没记住凭据
        if (eligible.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "服务器需记住密码/私钥才能自动监控",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            return@Column
        }

        // 服务器选择 chips
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(eligible, key = { it.id }) { s ->
                FilterChip(
                    selected = s.id == st.selectedId,
                    onClick = { vm.selectServer(s.id) },
                    label = { Text(s.name) },
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // 指纹确认
        val hk = st.hostKeyInfo
        if (hk != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (hk.changed) "服务器指纹已变更！" else "首次连接，需确认服务器指纹",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${hk.keyType}  ${hk.fingerprint}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (hk.changed && hk.oldFingerprint != null) {
                        Text(
                            "原指纹：${hk.oldFingerprint}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(onClick = { vm.confirmHostKey() }) {
                            Text("信任并连接")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { vm.dismissHostKey() }) {
                            Text("取消")
                        }
                    }
                }
            }
            return@Column
        }

        when (st.connState) {
            HomePerfViewModel.ConnState.CONNECTING,
            HomePerfViewModel.ConnState.IDLE -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "正在后台连接…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HomePerfViewModel.ConnState.FAILED -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        st.connError ?: "连接失败",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.retry() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("重试")
                    }
                }
            }
            HomePerfViewModel.ConnState.CONNECTED -> {
                if (st.firstLoad) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }
                } else {
                    val s = st.stats
                    val netParts = s.netText.split(" ", limit = 2)
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GaugeCard(
                                icon = Icons.Filled.Speed,
                                title = "CPU",
                                accent = Color(0xFF4FC3F7),
                                centerTop = s.cpuText,
                                fraction = s.cpuFraction,
                                dialSize = 84.dp,
                                modifier = Modifier.weight(1f),
                            )
                            GaugeCard(
                                icon = Icons.Filled.Memory,
                                title = "内存",
                                accent = Color(0xFFBA68C8),
                                centerTop = s.memText,
                                fraction = s.memFraction,
                                dialSize = 84.dp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GaugeCard(
                                icon = Icons.Filled.Storage,
                                title = "硬盘",
                                accent = Color(0xFFFFB74D),
                                centerTop = s.diskText,
                                fraction = s.diskFraction,
                                dialSize = 84.dp,
                                modifier = Modifier.weight(1f),
                            )
                            GaugeCard(
                                icon = Icons.Filled.NetworkCheck,
                                title = "网络",
                                accent = Color(0xFF81C784),
                                centerTop = netParts.getOrElse(0) { "--" },
                                centerBottom = netParts.getOrElse(1) { "" },
                                fraction = null,
                                dialSize = 84.dp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}
