package com.chan.shellpilot.ssh

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.chan.shellpilot.MainActivity
import com.chan.shellpilot.R
import com.chan.shellpilot.util.SpLog

/**
 * 前台保活服务：SSH 连接期间保持进程存活，防止切后台被系统杀掉或断网。
 *
 * 连接对象本身放在 [com.chan.shellpilot.ShellPilotApp.sshSession]（进程级持有，
 * Activity 重建可复用）；本服务只负责：
 * 1. startForeground 常驻通知，让系统把进程优先级提高；
 * 2. 通知点按可回到 App。
 *
 * 断开连接时必须调用 [stop]，否则通知会一直挂着。
 */
class SshService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val name = intent.getStringExtra(EXTRA_NAME) ?: "SSH"
                SpLog.i("SshService", "startForeground: $name")
                startForeground(NOTIF_ID, buildNotification(name))
            }
            ACTION_STOP -> {
                SpLog.i("SshService", "stop")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // 被系统杀掉后尽量重建（连接对象在 Application 里，ViewModel 会复用）
        return START_STICKY
    }

    private fun buildNotification(serverName: String): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "SSH 连接保活",
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ShellPilot 已连接")
            .setContentText(serverName)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val ACTION_START = "com.chan.shellpilot.ssh.START"
        private const val ACTION_STOP = "com.chan.shellpilot.ssh.STOP"
        private const val EXTRA_NAME = "server_name"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "ssh_keepalive"

        /** 开始保活（连接成功后调用）。 */
        fun start(ctx: Context, serverName: String) {
            val i = Intent(ctx, SshService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_NAME, serverName)
            }
            ContextCompat.startForegroundService(ctx, i)
        }

        /** 停止保活（断开连接时调用）。 */
        fun stop(ctx: Context) {
            ctx.startService(
                Intent(ctx, SshService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
