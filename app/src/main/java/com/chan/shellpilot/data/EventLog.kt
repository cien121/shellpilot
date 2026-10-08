package com.chan.shellpilot.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 连接/操作事件日志（如：连接到 1.2.3.4:22、连接成功、断开）。 */
@Entity(tableName = "event_logs")
data class EventLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverName: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
)
