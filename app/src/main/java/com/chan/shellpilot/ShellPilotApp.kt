package com.chan.shellpilot

import android.app.Application
import com.chan.shellpilot.data.Server
import com.chan.shellpilot.ssh.ShellSession
import com.chan.shellpilot.ssh.SshConnectionManager
import com.chan.shellpilot.terminal.TerminalBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * 进程级 SSH 会话持有者。
 *
 * 之前连接对象放在 Activity 级 ViewModel 里，Activity 一销毁（切后台被回收、
 * 旋转屏幕等）就断线。现在放到 Application 里：
 * - Activity 重建时 ViewModel 可复用已有连接，不断线；
 * - 配合前台 SshService 保活，切后台不断线。
 */
data class ActiveSshSession(
    val manager: SshConnectionManager,
    val shell: ShellSession,
    val bridge: TerminalBridge,
    val server: Server,
)

class ShellPilotApp : Application() {

    /** 进程级协程域：给 TerminalBridge 用，不随 Activity 销毁。 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 当前 SSH 会话（null = 未连接）。 */
    @Volatile
    var sshSession: ActiveSshSession? = null

    override fun onCreate() {
        super.onCreate()
        // Remove the crippled Android BC first to avoid confusion, then insert ours at #1.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}
