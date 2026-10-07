package com.chan.shellpilot.ui

import androidx.lifecycle.ViewModel
import com.chan.shellpilot.data.Server
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Skeleton ViewModel. Room-backed implementation comes with the data layer.
 */
class ServerListViewModel : ViewModel() {
    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers.asStateFlow()
}
