package com.tgmon.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgmon.app.data.preferences.PreferencesManager
import com.tgmon.app.data.preferences.ServerConfig
import com.tgmon.app.domain.repository.TgMonRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import android.util.Log

import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

data class ServerWithStatus(
    val config: ServerConfig,
    val isOnline: Boolean?
)

@HiltViewModel
class ServersViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager
) : ViewModel() {
    private val _servers = MutableStateFlow<List<ServerWithStatus>>(emptyList())
    val servers: StateFlow<List<ServerWithStatus>> = _servers

    // isLoading exposed so UI (pull-to-refresh) can observe completion explicitly
    // instead of relying solely on LaunchedEffect(servers) which hangs if loadServers never emits.
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    companion object {
        private const val TAG = "ServersViewModel"
        private const val HEALTH_TIMEOUT_MS = 5000L
    }
    
    init { loadServers() }

    /**
     * Loads all servers and checks health per-server with isolation.
     * Fix: each getHealth is wrapped in withTimeout(5000) + SupervisorJob semantics via supervisorScope,
     * async + awaitAll with per-server try/catch so one slow/failing server doesn't block others.
     * Partial results are still emitted even on timeout/failure.
     */
    fun loadServers() {
        viewModelScope.launch {
            loadServersInternal()
        }
    }

    /**
     * Suspend version for pull-to-refresh explicit await with timeout.
     * Allows UI to do withTimeout { viewModel.refreshSuspend() } and guarantee spinner reset.
     */
    suspend fun refreshSuspend() {
        loadServersInternal()
    }

    private suspend fun loadServersInternal() {
        if (_isLoading.value) return // avoid concurrent loads
        _isLoading.value = true
        try {
            val all = preferencesManager.getAllServers()
            if (all.isEmpty()) {
                _servers.value = emptyList()
                return
            }
            // supervisorScope ensures one child failure doesn't cancel siblings
            val results = supervisorScope {
                all.map { server ->
                    async {
                        // Per-server timeout + catch: never throws to parent, returns offline on failure/timeout
                        try {
                            withTimeout(HEALTH_TIMEOUT_MS) {
                                val specificRepo = TgMonRepository(preferencesManager, server.id)
                                val isOk = specificRepo.getHealth().isSuccess
                                ServerWithStatus(server, isOk)
                            }
                        } catch (e: TimeoutCancellationException) {
                            Log.w(TAG, "Health check timeout for ${server.name} (${server.id})")
                            ServerWithStatus(server, false)
                        } catch (e: Exception) {
                            Log.w(TAG, "Health check failed for ${server.name}: ${e.message}")
                            ServerWithStatus(server, false)
                        }
                    }
                }.awaitAll() // awaitAll is safe because individual async never throws
            }
            // Always emit even on partial failure so UI never hangs on empty
            _servers.value = results
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error loading servers", e)
            // Ensure we still emit at least cached/empty to unblock pull-to-refresh
            if (_servers.value.isEmpty()) {
                try {
                    val fallback = preferencesManager.getAllServers().map { ServerWithStatus(it, false) }
                    if (fallback.isNotEmpty()) _servers.value = fallback
                } catch (_: Exception) {}
            }
        } finally {
            _isLoading.value = false
        }
    }
    
    fun deleteServer(id: String) {
        preferencesManager.removeServer(id)
        loadServers()
    }
    
    fun setDefault(id: String) {
        preferencesManager.setDefaultServerId(id)
        loadServers()
    }
}
