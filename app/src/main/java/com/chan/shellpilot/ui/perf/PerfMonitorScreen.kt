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
                Text("请先连接服务器", color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private data class PerfStats(
    val cpuText: String = "--",
    val cpuFraction: Float = 0f,
    val cpuDetail: String = "",
    val memText: String = "--",
    val memFraction: Float = 0f,
    val memDetail: String = "",
    val diskText: String = "--",
    val diskFraction: Float = 0f,
    val diskDetail: String = "",
    val netText: String = "--",
    val netDetail: String = "",
)

/** 一次采集：CPU（/proc/stat 两次采样）/ 内存 / 硬盘 / 网络。 */
private suspend fun collectStats(mgr: SshConnectionManager): PerfStats {
    // CPU：两次 /proc/stat，间隔 1s
    val stat1 = mgr.exec("cat /proc/stat | head -n 1").getOrNull()
    delay(1000)
    val stat2 = mgr.exec("cat /proc/stat | head -n 1").getOrNull()
    val (cpuFrac, cpuDetail) = parseCpu(stat1, stat2)

    val meminfo = mgr.exec("cat /proc/meminfo").getOrNull()
    val (memFrac, memText, memDetail) = parseMem(meminfo)

    val df = mgr.exec("df -B1 / | tail -n 1").getOrNull()
    val (diskFrac, diskText, diskDetail) = parseDisk(df)

    val net1 = mgr.exec("cat /proc/net/dev").getOrNull()
    delay(1000)
    val net2 = mgr.exec("cat /proc/net/dev").getOrNull()
    val (netText, netDetail) = parseNet(net1, net2)

    return PerfStats(
        cpuText = if (cpuFrac < 0) "--" else "${(cpuFrac * 100).toInt()}%",
        cpuFraction = cpuFrac.coerceAtLeast(0f),
        cpuDetail = cpuDetail,
        memText = memText,
        memFraction = memFrac,
        memDetail = memDetail,
        diskText = diskText,
        diskFraction = diskFrac,
        diskDetail = diskDetail,
        netText = netText,
        netDetail = netDetail,
    )
}

private fun parseCpu(s1: String?, s2: String?): Pair<Float, String> {
    fun parts(s: String?): List<Long>? {
        if (s == null) return null
        val p = s.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        return p.takeIf { it.size >= 4 }
    }
    val a = parts(s1) ?: return -1f to ""
    val b = parts(s2) ?: return -1f to ""
    val idle1 = a[3] + a[4]
    val idle2 = b[3] + b[4]
    val total1 = a.sum()
    val total2 = b.sum()
    val totalDelta = (total2 - total1).toFloat()
    if (totalDelta <= 0) return -1f to ""
    val frac = 1f - (idle2 - idle1) / totalDelta
    return frac.coerceIn(0f, 1f) to "1 秒采样 · /proc/stat"
}

private fun parseMem(meminfo: String?): Triple<Float, String, String> {
    if (meminfo == null) return Triple(0f, "--", "")
    fun kv(key: String): Long {
        val line = meminfo.lineSequence().firstOrNull { it.startsWith(key) } ?: return 0
        return line.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: 0
    }
    val total = kv("MemTotal:")
    val avail = kv("MemAvailable:")
    if (total <= 0) return Triple(0f, "--", "")
    val used = total - avail
    val frac = used.toFloat() / total
    val text = "${(frac * 100).toInt()}%"
    val detail = "已用 ${fmtKb(used)} / 共 ${fmtKb(total)}"
    return Triple(frac.coerceIn(0f, 1f), text, detail)
}

private fun parseDisk(dfLine: String?): Triple<Float, String, String> {
    if (dfLine == null) return Triple(0f, "--", "")
    val p = dfLine.trim().split(Regex("\\s+"))
    if (p.size < 6) return Triple(0f, "--", "")
    val size = p[1].toLongOrNull() ?: 0L
    val used = p[2].toLongOrNull() ?: 0L
    val pct = p[4].trimEnd('%').toIntOrNull() ?: 0
    if (size <= 0) return Triple(0f, "--", "")
    return Triple(
        (pct / 100f).coerceIn(0f, 1f),
        "$pct%",
        "已用 ${fmtBytes(used)} / 共 ${fmtBytes(size)} · ${p[5]}",
    )
}

private fun parseNet(n1: String?, n2: String?): Pair<String, String> {
    fun totals(s: String?): Map<String, Pair<Long, Long>> {
        if (s == null) return emptyMap()
        return s.lineSequence()
            .map { it.trim() }
            .filter { it.contains(":") && !it.startsWith("Inter") && !it.startsWith("face") }
            .mapNotNull { line ->
                val name = line.substringBefore(":").trim()
                if (name == "lo") return@mapNotNull null
                val nums = line.substringAfter(":").trim()
                    .split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
                if (nums.size < 9) return@mapNotNull null
                name to (nums[0] to nums[8]) // rx bytes, tx bytes
            }.toMap()
    }
    val a = totals(n1)
    val b = totals(n2)
    if (a.isEmpty() || b.isEmpty()) return "--" to ""
    var rx = 0L
    var tx = 0L
    val ifaces = mutableListOf<String>()
    for ((name, bv) in b) {
        val av = a[name] ?: continue
        rx += bv.first - av.first
        tx += bv.second - av.second
        ifaces.add(name)
    }
    // 两次采样间隔约 1s
    val text = "↓${fmtRate(rx)} ↑${fmtRate(tx)}"
    val detail = "1 秒速率 · ${ifaces.take(3).joinToString(", ")}"
    return text to detail
}

private fun fmtKb(kb: Long): String = when {
    kb < 1024 -> "$kb KB"
    kb < 1024 * 1024 -> "%.1f MB".format(kb / 1024.0)
    else -> "%.2f GB".format(kb / (1024.0 * 1024))
}

private fun fmtBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024L * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / (1024.0 * 1024))
    else -> "%.2f GB".format(b / (1024.0 * 1024 * 1024))
}

private fun fmtRate(bps: Long): String = fmtBytes(bps.coerceAtLeast(0)) + "/s"
