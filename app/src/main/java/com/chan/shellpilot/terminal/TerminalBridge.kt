package com.chan.shellpilot.terminal

import android.os.SystemClock
import androidx.compose.ui.text.AnnotatedString
import com.chan.shellpilot.ssh.ShellSession
import com.chan.shellpilot.util.SpLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * 终端桥：把 SSH shell 的字节流 pump 进 [AnsiTerminal] 解析，
 * 解析后的着色文本经 [snapshot] + [version] 推给 Compose 渲染。
 *
 * 输入走 [sendText]/[sendBytes] 直接写 shell stdin（pty 回显），
 * 不再经底部输入框组装整行。
 */
class TerminalBridge(
    private val shell: ShellSession,
    private val scope: CoroutineScope,
) : Closeable {

    private val term = AnsiTerminal()
    private val lock = Any()

    private val _version = MutableStateFlow(0L)
    /** 输出有更新时递增（节流约 8 次/秒，避免高频 recompose）。 */
    val version: StateFlow<Long> = _version.asStateFlow()

    private val _lines = MutableStateFlow<List<LineSnapshot>>(emptyList())
    /**
     * 行级快照（LazyColumn 按行渲染用）。
     * 在 IO 线程构建好 AnnotatedString 后发布，Compose 只做收集，
     * 不再在主线程全量重建 500 行文本——解决输入延迟。
     */
    val lines: StateFlow<List<LineSnapshot>> = _lines.asStateFlow()

    private val _connected = MutableStateFlow(true)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var pumpJob: Job? = null
    private var trailingEmitJob: Job? = null

    /** 当前终端画面快照（主线程调用）。 */
    fun snapshot(): AnnotatedString = synchronized(lock) { term.snapshot() }

    /**
     * 显示快照 + 光标偏移（单次同步，保证一致）。
     * 只渲染末尾 [MAX_DISPLAY_LINES] 行，避免 `cat` 大文件时全量 2000 行重排卡顿。
     */
    fun snapshotWithCursor(): Pair<AnnotatedString, Int> =
        synchronized(lock) { term.snapshotWithCursor(MAX_DISPLAY_LINES) }

    companion object {
        /** 单次渲染的最大行数：控制 Compose Text 布局开销。 */
        const val MAX_DISPLAY_LINES = 500
        /** 快照推送节流间隔（毫秒）。 */
        const val EMIT_INTERVAL_MS = 120L
    }

    fun start() {
        SpLog.i("TerminalBridge", "pump start")
        pumpJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(8192)
            var lastEmit = 0L
            var totalBytes = 0L
            try {
                while (isActive) {
                    val n = shell.output.read(buf)
                    if (n < 0) {
                        SpLog.w("TerminalBridge", "pump: EOF (n<0), totalBytes=$totalBytes")
                        break
                    }
                    if (n == 0) continue
                    totalBytes += n
                    synchronized(lock) { term.processBytes(buf, 0, n) }
                    val now = SystemClock.uptimeMillis()
                    if (now - lastEmit >= 120) {
                        lastEmit = now
                        trailingEmitJob?.cancel()
                        // 行快照在 IO 线程构建（AnnotatedString 是纯数据类，
                        // 不需要主线程），发布后 UI 线程只做增量重排。
                        val snap = synchronized(lock) {
                            term.snapshotLines(MAX_DISPLAY_LINES)
                        }
                        withContext(Dispatchers.Main) {
                            _lines.value = snap
                            _version.value++
                        }
                    } else {
                        // 兜底推送（修 prompt 丢失）：
                        // 若数据在节流窗口内到达，UI 不会立即更新；
                        // 安排一个延迟推送，确保这段"尾巴数据"最终显示，
                        // 否则 shell prompt 会卡在 buffer 里看不见。
                        // debounce：持续有数据时不断顺延，停顿时触发。
                        trailingEmitJob?.cancel()
                        trailingEmitJob = scope.launch {
                            delay(EMIT_INTERVAL_MS)
                            val snap2 = synchronized(lock) {
                                term.snapshotLines(MAX_DISPLAY_LINES)
                            }
                            withContext(Dispatchers.Main) {
                                _lines.value = snap2
                                _version.value++
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Stream closed — session ended.
                SpLog.w("TerminalBridge", "pump ended: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                trailingEmitJob?.cancel()
                SpLog.i("TerminalBridge", "pump finished, marking disconnected")
                // 断开前推送最终快照，避免尾巴数据丢失
                val finalSnap = synchronized(lock) {
                    term.snapshotLines(MAX_DISPLAY_LINES)
                }
                withContext(Dispatchers.Main) {
                    _lines.value = finalSnap
                    _version.value++
                    _connected.value = false
                }
            }
        }
    }

    /** 原样发送文本（UTF-8）。 */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        SpLog.d("TerminalBridge", "sendText: ${text.length} chars")
        scope.launch(Dispatchers.IO) {
            runCatching {
                shell.input.write(text.toByteArray(Charsets.UTF_8))
                shell.input.flush()
            }.onFailure {
                SpLog.e("TerminalBridge", "sendText failed: ${it.message}")
            }
        }
    }

    /** 原样发送字节（特殊键/控制字符）。 */
    fun sendBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        SpLog.d(
            "TerminalBridge",
            "sendBytes: ${bytes.joinToString(" ") { "0x%02X".format(it) }}",
        )
        scope.launch(Dispatchers.IO) {
            runCatching {
                shell.input.write(bytes)
                shell.input.flush()
            }.onFailure {
                SpLog.e("TerminalBridge", "sendBytes failed: ${it.message}")
            }
        }
    }

    /** 发送一整行命令（供脚本片段调用）。 */
    fun sendLine(line: String) = sendText("$line\r")

    override fun close() {
        pumpJob?.cancel()
        runCatching { shell.close() }
    }
}
