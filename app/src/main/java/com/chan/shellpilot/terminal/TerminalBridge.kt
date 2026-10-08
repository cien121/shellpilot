package com.chan.shellpilot.terminal

import android.os.SystemClock
import androidx.compose.ui.text.AnnotatedString
import com.chan.shellpilot.ssh.ShellSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    private val _connected = MutableStateFlow(true)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var pumpJob: Job? = null

    /** 当前终端画面快照（主线程调用）。 */
    fun snapshot(): AnnotatedString = synchronized(lock) { term.snapshot() }

    fun start() {
        pumpJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(8192)
            var lastEmit = 0L
            try {
                while (isActive) {
                    val n = shell.output.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    synchronized(lock) { term.processBytes(buf, 0, n) }
                    val now = SystemClock.uptimeMillis()
                    if (now - lastEmit >= 120) {
                        lastEmit = now
                        withContext(Dispatchers.Main) { _version.value++ }
                    }
                }
            } catch (_: Exception) {
                // Stream closed — session ended.
            } finally {
                withContext(Dispatchers.Main) {
                    _version.value++
                    _connected.value = false
                }
            }
        }
    }

    /** 原样发送文本（UTF-8）。 */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                shell.input.write(text.toByteArray(Charsets.UTF_8))
                shell.input.flush()
            }
        }
    }

    /** 原样发送字节（特殊键/控制字符）。 */
    fun sendBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                shell.input.write(bytes)
                shell.input.flush()
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
