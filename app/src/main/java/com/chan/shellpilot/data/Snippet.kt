package com.chan.shellpilot.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Saved script / command snippet, runnable against a connected session. */
@Entity(tableName = "snippets")
data class Snippet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val command: String,
    /** Optional grouping label, e.g. "docker", "system". */
    val group: String = "",
    /** Run automatically right after connecting to any server. */
    val autoRunOnConnect: Boolean = false,
    val sortOrder: Int = 0,
)
