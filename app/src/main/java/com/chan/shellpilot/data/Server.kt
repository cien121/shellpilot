package com.chan.shellpilot.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Saved SSH server connection. */
@Entity(tableName = "servers")
data class Server(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    /** "password" or "key" */
    val authType: String = "password",
    /** Password (encrypted at rest in later milestone) or path/alias of private key. */
    val credential: String = "",
    /** Sort order in the list. */
    val sortOrder: Int = 0,
)
