package com.chan.shellpilot.util

import android.util.Log

/**
 * 统一日志：logcat TAG 为 ShellPilot，方便抓取诊断日志。
 * 用法：SpLog.i("SSH", "connected to $host")
 */
object SpLog {
    private const val TAG = "ShellPilot"

    fun d(tag: String, msg: String) {
        Log.d(TAG, "[$tag] $msg")
    }

    fun i(tag: String, msg: String) {
        Log.i(TAG, "[$tag] $msg")
    }

    fun w(tag: String, msg: String) {
        Log.w(TAG, "[$tag] $msg")
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e(TAG, "[$tag] $msg", tr)
    }
}
