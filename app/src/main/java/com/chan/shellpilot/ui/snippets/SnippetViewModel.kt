package com.chan.shellpilot.ui.snippets

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chan.shellpilot.data.ShellPilotDatabase
import com.chan.shellpilot.data.Snippet
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 代码片段增删改查。 */
class SnippetViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = ShellPilotDatabase.get(app).snippetDao()

    val snippets = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val groups = dao.observeGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun save(title: String, command: String, group: String, id: Long = 0) {
        viewModelScope.launch {
            dao.upsert(
                Snippet(
                    id = id,
                    title = title.trim(),
                    command = command.trim(),
                    group = group.trim(),
                )
            )
        }
    }

    fun delete(snippet: Snippet) {
        viewModelScope.launch { dao.delete(snippet) }
    }
}
