package com.chan.shellpilot.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Server::class, Snippet::class], version = 1, exportSchema = false)
abstract class ShellPilotDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun snippetDao(): SnippetDao

    companion object {
        @Volatile
        private var INSTANCE: ShellPilotDatabase? = null

        fun get(context: Context): ShellPilotDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ShellPilotDatabase::class.java,
                    "shellpilot.db"
                ).build().also { INSTANCE = it }
            }
    }
}
