package com.tgmon.app.presentation.viewmodel

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgmon.app.data.database.ContainerLogDao
import com.tgmon.app.data.models.ContainerCache
import com.tgmon.app.data.models.ContainerLog
import com.tgmon.app.data.models.DockerContainer
import com.tgmon.app.data.api.RetrofitClient
import com.tgmon.app.domain.repository.TgMonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

data class AIRecommendationsState(
    val isLoading: Boolean = false,
    val recommendations: com.google.gson.JsonElement? = null,
    val error: String? = null
)

data class ContainersUIState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val containers: List<DockerContainer> = emptyList(),
    val isRestarting: String? = null,
    val lastUpdateTime: String = "Never",
    val logsLoading: String? = null,
    val currentLogContent: String? = null,
    val selectedLogContainerName: String? = null,
    val selectedLogContainerId: String? = null
)

@HiltViewModel
class ContainerViewModel @Inject constructor(
    private val repository: TgMonRepository,
    private val logDao: ContainerLogDao,
    @ApplicationContext private val context: Context
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(ContainersUIState())
    val uiState: StateFlow<ContainersUIState> = _uiState

    // Estado de recomendaciones de IA
    private val _aiRecommendationsState = MutableStateFlow(AIRecommendationsState())
    val aiRecommendationsState: StateFlow<AIRecommendationsState> = _aiRecommendationsState

    private var autoRefreshJob: Job? = null
    private var logAutoRefreshJob: Job? = null
    private var isPaused = false
    private var isAutoRefreshing = false
    private var lifecycleOwnerRef: LifecycleOwner? = null
    
    val isAutoRefreshingLogs = MutableStateFlow(false)

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            isPaused = true
            autoRefreshJob?.cancel()
            isAutoRefreshing = false
        }

        override fun onStart(owner: LifecycleOwner) {
            isPaused = false
            startAutoRefresh()
        }
    }

    fun setLifecycleOwner(owner: LifecycleOwner) {
        lifecycleOwnerRef?.lifecycle?.removeObserver(lifecycleObserver)
        lifecycleOwnerRef = owner
        owner.lifecycle.addObserver(lifecycleObserver)
    }

    private var isInitialized = false

    fun initialize() {
        if (isInitialized) return
        isInitialized = true
        loadContainers()
        loadAiRecommendations()
        startAutoRefresh()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val twoHoursAgo = System.currentTimeMillis() - (2 * 60 * 60 * 1000)
                logDao.deleteOldLogs(twoHoursAgo)
            } catch (e: Exception) {
                android.util.Log.e("TgMon", "Failed to delete old logs: ${e.message}")
            }
        }
    }

    private var lastLlmRecommendationTime: Long = 0L

    private fun startAutoRefresh() {
        if (isAutoRefreshing) return
        isAutoRefreshing = true
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                if (!isPaused) {
                    loadContainers()
                    if (System.currentTimeMillis() - lastLlmRecommendationTime > 90_000L) {
                        loadAiRecommendations(useLlm = false)
                    }
                }
            }
        }
    }

    // Consultar recomendaciones de IA (useLlm=true activa Gemini Flash bajo demanda)
    fun loadAiRecommendations(useLlm: Boolean = false) {
        if (useLlm) {
            lastLlmRecommendationTime = System.currentTimeMillis()
        }
        viewModelScope.launch {
            _aiRecommendationsState.update {
                it.copy(isLoading = true, error = null)
            }

            repository.getAiRecommendations(useLlm)
                .onSuccess { response ->
                    _aiRecommendationsState.update {
                        it.copy(
                            isLoading = false,
                            recommendations = response,
                            error = null
                        )
                    }
                }
                .onFailure { exception ->
                    _aiRecommendationsState.update {
                        it.copy(
                            isLoading = false,
                            error = exception.message
                                ?: "Error al consultar recomendaciones de IA"
                        )
                    }
                }
        }
    }

    fun loadContainers() {
        viewModelScope.launch {
            val cachedContainers = repository.getCachedContainers(context)
            val hasCachedData = !cachedContainers.isNullOrEmpty()
            
            if (hasCachedData) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        containers = cachedContainers!!.map { cache -> 
                            DockerContainer(
                                id = cache.id,
                                name = cache.name,
                                status = cache.status,
                                image = cache.image,
                                cpuPercent = cache.cpuPercent,
                                memoryMb = cache.memoryMb,
                                memoryPercent = cache.memoryPercent,
                                uptimeSeconds = cache.uptimeSeconds,
                                healthStatus = cache.healthStatus,
                                networkRxMb = cache.networkRxMb,
                                networkTxMb = cache.networkTxMb,
                                ports = emptyList()
                            )
                        },
                        error = null
                    )
                }
            }
            
            val result = repository.listContainers()
            
            result.onSuccess { containers ->
                repository.saveContainersToCache(containers, context)
                
                val currentTime = java.text.SimpleDateFormat(
                    "HH:mm:ss",
                    java.util.Locale.getDefault()
                ).format(java.util.Date())
                
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        containers = containers,
                        error = null,
                        lastUpdateTime = currentTime
                    )
                }
            }.onFailure { exception ->
                if (!hasCachedData) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            containers = emptyList(),
                            error = exception.message
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = null
                        )
                    }
                }
            }
        }
    }

    fun restartContainer(containerId: String) {
        _uiState.update { it.copy(isRestarting = containerId) }
        
        viewModelScope.launch {
            val result = repository.restartContainer(containerId)
            
            result.onSuccess {
                android.util.Log.d("TgMon", "Container $containerId restarted")
                kotlinx.coroutines.delay(2000)
                loadContainers()
                _uiState.update { it.copy(isRestarting = null) }
            }.onFailure { exception ->
                _uiState.update {
                    it.copy(
                        isRestarting = null,
                        error = "Error restarting: ${exception.message}"
                    )
                }
            }
        }
    }

    fun pauseContainer(id: String) {
        _uiState.update { it.copy(isRestarting = id) }
        viewModelScope.launch {
            repository.pauseContainer(id).onSuccess {
                kotlinx.coroutines.delay(2000)
                loadContainers()
                loadAiRecommendations()
                _uiState.update { it.copy(isRestarting = null) }
            }.onFailure { e ->
                _uiState.update { it.copy(isRestarting = null, error = "Error pausing: ${e.message}") }
            }
        }
    }

    fun unpauseContainer(id: String) {
        _uiState.update { it.copy(isRestarting = id) }
        viewModelScope.launch {
            repository.unpauseContainer(id).onSuccess {
                kotlinx.coroutines.delay(2000)
                loadContainers()
                _uiState.update { it.copy(isRestarting = null) }
            }.onFailure { e ->
                _uiState.update { it.copy(isRestarting = null, error = "Error resuming: ${e.message}") }
            }
        }
    }

    fun openLogs(containerId: String, containerName: String) {
        viewModelScope.launch {
            val cachedLog = logDao.getLatestLog(containerId)
            if (cachedLog != null) {
                _uiState.update {
                    it.copy(
                        selectedLogContainerId = containerId,
                        selectedLogContainerName = containerName,
                        currentLogContent = cachedLog.logs,
                        logsLoading = null
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        selectedLogContainerId = containerId,
                        selectedLogContainerName = containerName,
                        currentLogContent = null,
                        logsLoading = containerId
                    )
                }
            }
            fetchAndSaveLogs(containerId, containerName)
        }
    }

    private suspend fun fetchAndSaveLogs(containerId: String, containerName: String) {
        _uiState.update { it.copy(logsLoading = containerId) }
        
        try {
            val logs = withTimeout(120_000L) {
                repository.getContainerLogs(containerId).getOrThrow()
            }
            logDao.insertLog(
                ContainerLog(
                    containerId = containerId,
                    containerName = containerName,
                    logs = logs,
                    timestamp = System.currentTimeMillis()
                )
            )
            _uiState.update {
                it.copy(
                    currentLogContent = logs,
                    logsLoading = null
                )
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            _uiState.update { state ->
                if (state.selectedLogContainerId == containerId) {
                    state.copy(
                        logsLoading = null,
                        currentLogContent = state.currentLogContent
                            ?: "Error: Timeout fetching logs (server may be slow)"
                    )
                } else state
            }
        } catch (exception: Exception) {
            _uiState.update { state ->
                if (state.selectedLogContainerId == containerId) {
                    state.copy(
                        logsLoading = null,
                        currentLogContent = state.currentLogContent
                            ?: "Error: ${exception.message}"
                    )
                } else state
            }
        }
    }

    fun refreshLogs() {
        val containerId = _uiState.value.selectedLogContainerId ?: return
        val containerName = _uiState.value.selectedLogContainerName ?: return
        viewModelScope.launch {
            fetchAndSaveLogs(containerId, containerName)
        }
    }

    fun closeLogs() {
        _uiState.update {
            it.copy(
                selectedLogContainerId = null,
                selectedLogContainerName = null,
                currentLogContent = null,
                logsLoading = null
            )
        }
        logAutoRefreshJob?.cancel()
        isAutoRefreshingLogs.value = false
    }
    
    fun toggleLogAutoRefresh() {
        if (isAutoRefreshingLogs.value) {
            logAutoRefreshJob?.cancel()
            isAutoRefreshingLogs.value = false
        } else {
            isAutoRefreshingLogs.value = true
            logAutoRefreshJob = viewModelScope.launch {
                while (isAutoRefreshingLogs.value) {
                    val cid = _uiState.value.selectedLogContainerId ?: break
                    val cname = _uiState.value.selectedLogContainerName ?: break
                    fetchAndSaveLogs(cid, cname)
                    kotlinx.coroutines.delay(5_000)
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        lifecycleOwnerRef?.lifecycle?.removeObserver(lifecycleObserver)
        lifecycleOwnerRef = null
        autoRefreshJob?.cancel()
        logAutoRefreshJob?.cancel()
        isAutoRefreshing = false
    }
}
