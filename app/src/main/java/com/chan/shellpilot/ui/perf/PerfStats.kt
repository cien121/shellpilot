package com.chan.shellpilot.ui.perf

import com.chan.shellpilot.ssh.SshConnectionManager
import kotlinx.coroutines.delay

/**
 * 性能数据采集（共享）：独立性能页与主页嵌入式性能区共用。
 * 数据经 SSH 在远端执行命令采集（/proc 系列）。
 */
data class PerfStats(
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
suspend fun collectStats(mgr: SshConnectionManager): PerfStats {
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

fun parseCpu(s1: String?, s2: String?): Pair<Float, String> {
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

fun parseMem(meminfo: String?): Triple<Float, String, String> {
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

fun parseDisk(dfLine: String?): Triple<Float, String, String> {
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

fun parseNet(n1: String?, n2: String?): Pair<String, String> {
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

fun fmtKb(kb: Long): String = when {
    kb < 1024 -> "$kb KB"
    kb < 1024 * 1024 -> "%.1f MB".format(kb / 1024.0)
    else -> "%.2f GB".format(kb / (1024.0 * 1024))
}

fun fmtBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024L * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / (1024.0 * 1024))
    else -> "%.2f GB".format(b / (1024.0 * 1024 * 1024))
}

fun fmtRate(bps: Long): String = fmtBytes(bps.coerceAtLeast(0)) + "/s"
