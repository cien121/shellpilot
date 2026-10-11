package com.chan.shellpilot.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ping 连通性检测结果。
 */
data class PingResult(
    val host: String,
    val transmitted: Int = 0,
    val received: Int = 0,
    /** 丢包率 0-100 */
    val lossRate: Float = 100f,
    /** 平均延迟 ms */
    val avgMs: Float = -1f,
    val minMs: Float = -1f,
    val maxMs: Float = -1f,
    /** 每包延迟 ms，-1 表示该包超时 */
    val perPacketMs: List<Float> = emptyList(),
    val success: Boolean = false,
    val error: String? = null,
)

/**
 * 用系统 ping 命令检测主机连通性。
 *
 * @param host IP 或域名
 * @param count 发送包数
 * @param timeoutSec 每包超时秒数
 */
suspend fun ping(host: String, count: Int = 4, timeoutSec: Int = 2): PingResult =
    withContext(Dispatchers.IO) {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty()) {
            return@withContext PingResult(host = host, error = "主机地址为空")
        }
        // 简单校验，防止命令注入：只允许字母数字 . - :（IPv6）
        if (!cleanHost.matches(Regex("[A-Za-z0-9.\\-:]+"))) {
            return@withContext PingResult(host = host, error = "主机地址含非法字符")
        }
        try {
            val proc = withTimeoutOrNull((count * (timeoutSec + 1) + 5) * 1000L) {
                Runtime.getRuntime().exec(arrayOf("/system/bin/ping", "-c", count.toString(), "-W", timeoutSec.toString(), cleanHost))
            } ?: return@withContext PingResult(host = cleanHost, error = "ping 超时")

            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            parsePingOutput(cleanHost, output)
        } catch (e: SecurityException) {
            PingResult(host = cleanHost, error = "无权限执行 ping")
        } catch (e: java.io.IOException) {
            PingResult(host = cleanHost, error = "ping 命令不可用：${e.message}")
        } catch (e: Exception) {
            SpLog.e("Ping", "ping failed: ${e.message}")
            PingResult(host = cleanHost, error = "ping 失败：${e.message}")
        }
    }

/**
 * 解析 ping 输出。
 *
 * 每包行示例：64 bytes from 8.8.8.8: icmp_seq=1 ttl=116 time=23.4 ms
 * 统计行示例：
 *   4 packets transmitted, 4 received, 0% packet loss, time 3005ms
 *   rtt min/avg/max/mdev = 22.1/23.4/25.0/1.2 ms
 */
private fun parsePingOutput(host: String, output: String): PingResult {
    // 每包延迟
    val perPacket = Regex("""time=(\d+(?:\.\d+)?)\s*ms""")
        .findAll(output)
        .map { it.groupValues[1].toFloatOrNull() ?: -1f }
        .toList()

    // 发送/接收/丢包
    val statRegex = Regex("""(\d+)\s+packets transmitted,\s*(\d+)\s+(?:packets\s+)?received,\s*(\d+(?:\.\d+)?)%\s*packet loss""")
    val statMatch = statRegex.find(output)
    val transmitted = statMatch?.groupValues?.get(1)?.toIntOrNull() ?: perPacket.size
    val received = statMatch?.groupValues?.get(2)?.toIntOrNull() ?: perPacket.count { it >= 0 }
    val loss = statMatch?.groupValues?.get(3)?.toFloatOrNull()
        ?: if (transmitted > 0) (transmitted - received) * 100f / transmitted else 100f

    // rtt min/avg/max
    val rttRegex = Regex("""rtt min/avg/max/mdev = ([\d.]+)/([\d.]+)/([\d.]+)/""")
    val rttMatch = rttRegex.find(output)
    val min = rttMatch?.groupValues?.get(1)?.toFloatOrNull()
    val avg = rttMatch?.groupValues?.get(2)?.toFloatOrNull()
    val max = rttMatch?.groupValues?.get(3)?.toFloatOrNull()

    // 兜底：从每包延迟计算
    val valid = perPacket.filter { it >= 0 }
    val calcAvg = avg ?: if (valid.isNotEmpty()) valid.average().toFloat() else -1f
    val calcMin = min ?: valid.minOrNull() ?: -1f
    val calcMax = max ?: valid.maxOrNull() ?: -1f

    // 补齐 perPacket 长度到 transmitted（超时的包记 -1）
    val fullPackets = if (perPacket.size < transmitted) {
        perPacket + List(transmitted - perPacket.size) { -1f }
    } else perPacket

    val ok = received > 0
    return PingResult(
        host = host,
        transmitted = transmitted,
        received = received,
        lossRate = loss,
        avgMs = calcAvg,
        minMs = calcMin,
        maxMs = calcMax,
        perPacketMs = fullPackets,
        success = ok,
        error = if (ok) null else "无法连通（${loss.toInt()}% 丢包）",
    )
}
