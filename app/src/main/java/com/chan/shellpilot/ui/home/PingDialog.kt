package com.chan.shellpilot.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chan.shellpilot.util.PingResult

/**
 * ping 结果对话框：主机、收发包、丢包率、延迟统计、每包延迟。
 */
@Composable
fun PingResultDialog(
    host: String,
    loading: Boolean,
    result: PingResult?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("连通性检测", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("主机：", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    Text(
                        host,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (loading) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("正在 ping…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                } else if (result != null) {
                    if (result.success) {
                        PingStatRow("发送 / 接收", "${result.transmitted} / ${result.received}")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("丢包率：", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            Text(
                                "${"%.1f".format(result.lossRate)}%",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (result.lossRate <= 0f) Color(0xFF00C853) else Color(0xFFFF6D00),
                            )
                        }
                        PingStatRow(
                            "延迟 min/avg/max",
                            "${fmtMs(result.minMs)} / ${fmtMs(result.avgMs)} / ${fmtMs(result.maxMs)}",
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "每包延迟",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        result.perPacketMs.forEachIndexed { idx, ms ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "#${idx + 1}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(32.dp),
                                )
                                Text(
                                    if (ms >= 0) "${"%.1f".format(ms)} ms" else "超时",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = if (ms >= 0) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    } else {
                        Text(
                            result.error ?: "ping 失败",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!loading) {
                TextButton(onClick = onRetry) { Text("重试") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun PingStatRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label + "：", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun fmtMs(ms: Float): String =
    if (ms >= 0) "${"%.1f".format(ms)}ms" else "--"
