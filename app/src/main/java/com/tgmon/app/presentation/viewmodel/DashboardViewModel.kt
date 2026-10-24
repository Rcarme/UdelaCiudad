package com.tgmon.app.presentation.viewmodel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.tgmon.app.data.models.HealthResponse
import com.tgmon.app.data.models.MetricHistory
import com.tgmon.app.domain.AlertManager
import com.tgmon.app.domain.MetricCache
import com.tgmon.app.domain.repository.TgMonRepository
import com.tgmon.app.data.preferences.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import okhttp3.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import com.tgmon.app.data.models.DockerContainer
import com.tgmon.app.data.models.DiskInfo
import com.tgmon.app.data.preferences.ServerConfig
import com.tgmon.app.domain.ContainerAlertManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Qualifier
import javax.inject.Inject


data class AIPredictionState(
    val isLoading: Boolean = false,
    val prediction: com.google.gson.JsonElement? = null,
    val error: String? = null
)

enum class WebSocketState { DISCONNECTED, CONNECTING, CONNECTED }

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WsClient

data class DashboardHealthState(
    val hostname: String = "",
    val cpuPercent: Double = 0.0,
    val memoryPercent: Double = 0.0,
    val diskUsedGb: Double = 0.0,
    val diskFreeGb: Double = 0.0,
    val diskTotalGb: Double = 0.0,
    val diskPercent: Double = 0.0,
    val disks: List<DiskInfo> = emptyList(),
    val netRecvMb: Double = 0.0,
    val netSentMb: Double = 0.0,
    val uptimeSeconds: Long = 0L,
    val uptime: String? = null,
    val loadAvg: String? = null,
    val dockerTotal: Int = 0,
    val dockerRunning: Int = 0,
    val lastUpdateTime: String = "Never"
)

data class DashboardConnectionState(
    val isConnected: Boolean = false,
    val isReconnecting: Boolean = false,
    val isInitialLoading: Boolean = true,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isOffline: Boolean = false,
    val isFromCache: Boolean = false,
    val lastSuccessfulConnection: Long? = null,
    val selectedTab: Int = 0
) {
    val timeSinceLastConnection: String?
        get() = lastSuccessfulConnection?.let {
            val diff = System.currentTimeMillis() - it
            when {
                diff < 60_000L -> "${diff / 1000}s ago"
                diff < 3_600_000L -> "${diff / 60_000}m ago"
                diff < 86_400_000L -> "${diff / 3_600_000}h ago"
                else -> "${diff / 86_400_000}d ago"
            }
        }
}

data class DashboardContainersState(
    val containers: List<DockerContainer> = emptyList(),
    val isLoading: Boolean = false
)

data class DashboardUIState(
    val isLoading: Boolean = false,
    val isInitialLoading: Boolean = true,
    val error: String? = null,
    val health: HealthResponse? = null,
    val containers: List<DockerContainer> = emptyList(),
    val lastUpdateTime: String = "Never",
    val isOffline: Boolean = false,
    val isFromCache: Boolean = false,
    val selectedTab: Int = 0,
    val lastSuccessfulConnection: Long? = null,
    val uptime: String? = null,
    val loadAvg: String? = null
) {
    val timeSinceLastConnection: String?
        get() = lastSuccessfulConnection?.let {
            val diff = System.currentTimeMillis() - it
            when {
                diff < 60_000L -> "${diff / 1000}s ago"
                diff < 3_600_000L -> "${diff / 60_000}m ago"
                diff < 86_400_000L -> "${diff / 3_600_000}h ago"
                else -> "${diff / 86_400_000}d ago"
            }
        }
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: TgMonRepository,
    private val preferencesManager: PreferencesManager,
    private val metricCache: MetricCache,
    private val alertManager: AlertManager,
    private val containerAlertManager: ContainerAlertManager,
    @WsClient private val wsClient: OkHttpClient,
    @ApplicationContext private val appContext: Context
) : ViewModel() {
    
    private val _healthState = MutableStateFlow(DashboardHealthState())
    val healthState: StateFlow<DashboardHealthState> = _healthState

    // Estado de las predicciones de IA
    private val _aiPredictionState = MutableStateFlow(AIPredictionState())
    val aiPredictionState: StateFlow<AIPredictionState> = _aiPredictionState


    private val _connectionState = MutableStateFlow(DashboardConnectionState())
    val connectionState: StateFlow<DashboardConnectionState> = _connectionState

    private val _containersState = MutableStateFlow(DashboardContainersState())
    val containersState: StateFlow<DashboardContainersState> = _containersState

    private val _selectedTab = MutableStateFlow(0)

    val uiState: StateFlow<DashboardUIState> = combine(
        _healthState, _connectionState, _containersState, _selectedTab
    ) { health, conn, containers, tab ->
        val healthResponse = HealthResponse(
            hostname = health.hostname,
            cpuPercent = health.cpuPercent,
            memoryPercent = health.memoryPercent,
            diskUsedGb = health.diskUsedGb,
            diskFreeGb = health.diskFreeGb,
            diskTotalGb = health.diskTotalGb,
            disks = health.disks
        )
        DashboardUIState(
            isLoading = conn.isLoading,
            isInitialLoading = conn.isInitialLoading,
            error = conn.error,
            health = if (health.hostname.isNotEmpty()) healthResponse else null,
            containers = containers.containers,
            lastUpdateTime = health.lastUpdateTime,
            isOffline = conn.isOffline,
            isFromCache = conn.isFromCache,
            selectedTab = tab,
            lastSuccessfulConnection = conn.lastSuccessfulConnection,
            uptime = health.uptime,
            loadAvg = health.loadAvg
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardUIState())

    private val _historicalMetrics = MutableStateFlow<List<MetricHistory>>(emptyList())
    val historicalMetrics: StateFlow<List<MetricHistory>> = _historicalMetrics

    private val _servers = MutableStateFlow<List<ServerConfig>>(emptyList())
    val servers: StateFlow<List<ServerConfig>> = _servers

    private var webSocket: WebSocket? = null
    private val client: OkHttpClient = wsClient
    private var wsRetryCount = 0
    private val maxWsRetries = 10
    private var isWsPaused = false
    private var wsWasConnected = false
    private var lastPingTime = 0L
    private val pingInterval = 25_000L
    private var pingJob: Job? = null
    private var lastSavedMetricTime = 0L

    private val wsMutex = Mutex()
    private var wsState = WebSocketState.DISCONNECTED

    private var autoRefreshJob: Job? = null
    private var wsReconnectJob: Job? = null
    private var lifecycleOwnerRef: LifecycleOwner? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            isWsPaused = true
            autoRefreshJob?.cancel()
            pingJob?.cancel()
            webSocket?.close(1001, "App backgrounded")
        }

        override fun onStart(owner: LifecycleOwner) {
            isWsPaused = false
            pingJob = startPingThread()
            if (wsState != WebSocketState.CONNECTED) connectWebSocket()
        }

        override fun onDestroy(owner: LifecycleOwner) {
            unregisterNetworkCallback()
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                android.util.Log.d("TgMon", "Network callback unregister failed: ${e.message}")
            }
        }
        networkCallback = null
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                android.util.Log.d("TgMon", "Network available, reconnecting WS")
                if (wsState != WebSocketState.CONNECTED && !isWsPaused) {
                    wsRetryCount = 0
                    connectWebSocket()
                }
            }
            override fun onLost(network: Network) {
                android.util.Log.d("TgMon", "Network lost")
                wsState = WebSocketState.DISCONNECTED
            }
        }
        try {
            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            android.util.Log.w("TgMon", "Failed to register network callback: ${e.message}")
        }
    }

    private fun startPingThread(): Job {
        return viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(pingInterval)
                if (wsState == WebSocketState.CONNECTED && !isWsPaused) {
                    webSocket?.send("ping")
                    lastPingTime = System.currentTimeMillis()
                }
            }
        }
    }

    // Reuse singleton Gson with ContainerListResponse adapter — no extra allocations (N97)
    private val gson = com.tgmon.app.data.api.RetrofitClient.tgmonGson

    fun setLifecycleOwner(owner: LifecycleOwner) {
        lifecycleOwnerRef?.lifecycle?.removeObserver(lifecycleObserver)
        lifecycleOwnerRef = owner
        owner.lifecycle.addObserver(lifecycleObserver)
        registerNetworkCallback()
    }

    private var isInitialized = false

    fun initialize() {
        if (isInitialized) return
        isInitialized = true
        loadWithCacheFirst()
        startAutoRefresh()
        loadHistoricalMetrics()
        loadServers()
        connectWebSocket()
        // Consultar la IA al iniciar el Dashboard
        loadAiPrediction()
    }

    fun loadServers() {
        _servers.value = preferencesManager.getAllServers()
    }

    fun onTabSelected(index: Int) {
        _selectedTab.value = index
        if (index == 1) {
            syncHistory()
        }
    }

    fun syncHistory() {
        viewModelScope.launch {
            try {
                repository.syncHistoryFromServer(24)
            } catch (e: Exception) {
                android.util.Log.w("TgMon", "History sync failed: ${e.message}")
            }
        }
    }

    private fun loadWithCacheFirst() {
        viewModelScope.launch {
            val lastMetric = metricCache.getLastMetric()
            if (lastMetric != null) {
                _healthState.update {
                    it.copy(
                        hostname = lastMetric.hostname,
                        cpuPercent = lastMetric.cpuPercent,
                        memoryPercent = lastMetric.memoryPercent,
                        diskUsedGb = lastMetric.diskUsedGb,
                        diskFreeGb = lastMetric.diskFreeGb,
                        diskTotalGb = lastMetric.diskTotalGb,
                        lastUpdateTime = formatCacheTime(lastMetric.timestamp)
                    )
                }
                _connectionState.update {
                    it.copy(
                        isFromCache = true,
                        isLoading = true
                    )
                }
            } else {
                _connectionState.update { it.copy(isLoading = true) }
            }
            refreshFromNetwork()
        }
    }

    private fun formatCacheTime(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000L -> "Cache: ${diff / 1000}s ago"
            diff < 3_600_000L -> "Cache: ${diff / 60_000}m ago"
            else -> "Cache: " + SimpleDateFormat("HH:mm dd/MM", Locale.getDefault()).format(Date(timestamp))
        }
    }
    
    private fun loadHistoricalMetrics() {
        viewModelScope.launch {
            val oneWeekAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
            metricCache.deleteOldMetrics(oneWeekAgo)
            
            // Sincronizar historial con el backend
            launch {
                syncHistory()
            }
            
            // Observar reactivamente el Flow de métricas de Room
            metricCache.getLastNMetricsFlow(1000)?.collect { metrics ->
                _historicalMetrics.value = metrics.reversed()
            }
        }
    }
    
    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                if (wsState != WebSocketState.CONNECTED) {
                    loadHealth()
                }
            }
        }
    }

    fun loadHealth() {
        _connectionState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch { refreshFromNetwork() }
    }


    // Consultar predicciones de IA al servidor
    fun loadAiPrediction() {
        viewModelScope.launch {
            _aiPredictionState.update {
                it.copy(isLoading = true, error = null)
            }

            repository.getAiPrediction()
                .onSuccess { response ->
                    _aiPredictionState.update {
                        it.copy(
                            isLoading = false,
                            prediction = response,
                            error = null
                        )
                    }
                }
                .onFailure { exception ->
                    _aiPredictionState.update {
                        it.copy(
                            isLoading = false,
                            error = exception.message
                                ?: "Error al consultar la IA"
                        )
                    }
                }
        }
    }


    private fun refreshFromNetwork() {
        viewModelScope.launch {
            val healthDeferred = async { repository.getHealth() }
            val containersDeferred = async { repository.listContainers() }
            val healthResult = healthDeferred.await()
            val containersResult = containersDeferred.await()

            healthResult.onSuccess { response ->
                val containerList = containersResult.getOrNull() ?: emptyList()
                val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

                val uptimeFormatted = response.uptimeSeconds?.let { secs ->
                    val days = secs / 86400
                    val hours = (secs % 86400) / 3600
                    val mins = (secs % 3600) / 60
                    when {
                        days > 0 -> "${days}d ${hours}h"
                        hours > 0 -> "${hours}h ${mins}m"
                        else -> "${mins}m"
                    }
                }
                val loadAvgFormatted = response.loadAvg?.joinToString(", ") { String.format("%.2f", it) }

                _healthState.update {
                    it.copy(
                        hostname = response.hostname,
                        cpuPercent = response.cpuPercent,
                        memoryPercent = response.memoryPercent,
                        diskUsedGb = response.diskUsedGb,
                        diskFreeGb = response.diskFreeGb,
                        diskTotalGb = response.diskTotalGb ?: 0.0,
                        disks = response.disks ?: emptyList(),
                        lastUpdateTime = currentTime,
                        uptime = uptimeFormatted,
                        loadAvg = loadAvgFormatted
                    )
                }
                _connectionState.update {
                    it.copy(
                        isLoading = false,
                        isInitialLoading = false,
                        isOffline = false,
                        isFromCache = false,
                        lastSuccessfulConnection = System.currentTimeMillis()
                    )
                }
                _containersState.update {
                    it.copy(containers = containerList)
                }

                saveMetricHistory(response)
                alertManager.checkAndNotify(response)
            }.onFailure { exception ->
                val lastMetric = metricCache.getLastMetric()
                val lastHealth = lastMetric?.let {
                    HealthResponse(
                        hostname = it.hostname,
                        cpuPercent = it.cpuPercent,
                        memoryPercent = it.memoryPercent,
                        diskUsedGb = it.diskUsedGb,
                        diskFreeGb = it.diskFreeGb,
                        diskTotalGb = it.diskTotalGb
                    )
                }

                if (lastHealth != null) {
                    _healthState.update {
                        it.copy(
                            hostname = lastHealth.hostname,
                            cpuPercent = lastHealth.cpuPercent,
                            memoryPercent = lastHealth.memoryPercent,
                            diskUsedGb = lastHealth.diskUsedGb,
                            diskFreeGb = lastHealth.diskFreeGb,
                            diskTotalGb = lastHealth.diskTotalGb ?: 0.0
                        )
                    }
                }
                _connectionState.update {
                    it.copy(
                        isLoading = false,
                        isInitialLoading = false,
                        error = "Offline: ${exception.message ?: "Connection error"}",
                        isOffline = true,
                        isFromCache = true
                    )
                }
            }
        }
    }
    
    private fun saveMetricHistory(response: HealthResponse) {
        val now = System.currentTimeMillis()
        if (now - lastSavedMetricTime < 15_000L) {
            return
        }
        lastSavedMetricTime = now
        viewModelScope.launch {
            val defaultDiskTotal = response.diskTotalGb ?: (response.diskUsedGb + response.diskFreeGb)
            val defaultDiskPercent = if (defaultDiskTotal > 0) {
                (response.diskUsedGb / defaultDiskTotal) * 100.0
            } else 0.0
            val highestDiskPercent = response.disks?.filter { it.totalGb > 0.0 }?.maxOfOrNull { it.percent }
            val diskPercent = highestDiskPercent ?: defaultDiskPercent
            if (defaultDiskTotal > 0) {
                val metric = MetricHistory(
                    hostname = response.hostname.ifBlank { "Unknown" },
                    cpuPercent = response.cpuPercent,
                    memoryPercent = response.memoryPercent,
                    diskUsedGb = response.diskUsedGb,
                    diskFreeGb = response.diskFreeGb,
                    diskTotalGb = defaultDiskTotal,
                    diskPercent = diskPercent
                )
                metricCache.insertMetric(metric)
            }
        }
    }
    
    fun getFilteredMetrics(hours: Int): List<MetricHistory> {
        val timeAgo = System.currentTimeMillis() - (hours * 60 * 60 * 1000)
        return _historicalMetrics.value.filter { it.timestamp >= timeAgo }
    }

    fun clearError() {
        _connectionState.update { it.copy(error = null) }
    }
    
    private fun connectWebSocket() {
        if (isWsPaused) return
        wsReconnectJob?.cancel()
        
        viewModelScope.launch {
            wsMutex.withLock {
                if (wsState == WebSocketState.CONNECTING || wsState == WebSocketState.CONNECTED) {
                    return@launch
                }
                wsState = WebSocketState.CONNECTING
                
                val serverUrl = repository.getLocalUrl() ?: repository.getServerUrl() ?: return@launch
                val token = repository.getToken() ?: return@launch
                
                var wsUrl = serverUrl.replace("http://", "ws://").replace("https://", "wss://")
                    .removeSuffix("/")
                    .removeSuffix("/api")
                if (!wsUrl.endsWith("/ws")) {
                    wsUrl += "/ws"
                }
                wsUrl += "/telemetry"
                    
                val request = Request.Builder().url(wsUrl).build()
                
                webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        wsRetryCount = 0
                        wsWasConnected = true
                        wsState = WebSocketState.CONNECTED
                        val authJson = gson.toJson(mapOf("type" to "auth", "token" to token))
                        webSocket.send(authJson)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text == "pong") {
                            val latency = System.currentTimeMillis() - lastPingTime
                            android.util.Log.d("TgMon", "WS pong received, latency: ${latency}ms")
                            return
                        }
                        viewModelScope.launch(Dispatchers.IO) wsMsg@ {
                            try {
                                val message = gson.fromJson(text, Map::class.java)
                                val messageType = message["type"] as? String
                                when (messageType) {
                                    "auth_ok" -> {
                                        return@wsMsg
                                    }
                                    "telemetry" -> {
                                        val payloadMap = message["payload"] as? Map<*, *>
                                        if (payloadMap == null) {
                                            android.util.Log.w("TgMon", "WS telemetry: missing payload")
                                            return@wsMsg
                                        }
                                        val payloadJson = gson.toJson(payloadMap)
                                        val health = gson.fromJson(payloadJson, HealthResponse::class.java)
                                        
                                        val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                                        _healthState.update {
                                            it.copy(
                                                hostname = health.hostname,
                                                cpuPercent = health.cpuPercent,
                                                memoryPercent = health.memoryPercent,
                                                diskUsedGb = health.diskUsedGb,
                                                diskFreeGb = health.diskFreeGb,
                                                diskTotalGb = health.diskTotalGb ?: 0.0,
                                                disks = health.disks ?: emptyList(),
                                                lastUpdateTime = currentTime
                                            )
                                        }
                                        _connectionState.update {
                                            it.copy(
                                                isLoading = false,
                                                isInitialLoading = false,
                                                isOffline = false,
                                                isFromCache = false,
                                                lastSuccessfulConnection = System.currentTimeMillis()
                                            )
                                        }
                                        saveMetricHistory(health)
                                        alertManager.checkAndNotify(health)
                                    }
                                    "container_event" -> {
                                        val payload = message["payload"] as? Map<*, *>
                                        if (payload == null) {
                                            android.util.Log.w("TgMon", "WS container_event: missing payload")
                                            return@wsMsg
                                        }
                                        val containerId = payload["container_id"] as? String
                                        val containerName = payload["container_name"] as? String
                                        val newStatus = payload["new_status"] as? String
                                        if (containerId == null || containerName == null || newStatus == null) {
                                            android.util.Log.w("TgMon", "WS container_event: invalid payload fields")
                                            return@wsMsg
                                        }
                                        val event = ContainerAlertManager.ContainerEvent(
                                            containerId = containerId,
                                            containerName = containerName,
                                            newStatus = newStatus
                                        )
                                        containerAlertManager.checkContainerEvent(event)
                                        _containersState.update { state ->
                                            val updatedList = state.containers.map { c ->
                                                if (c.id == containerId || c.id.startsWith(containerId) || containerId.startsWith(c.id)) {
                                                    c.copy(status = newStatus)
                                                } else c
                                            }
                                            state.copy(containers = updatedList)
                                        }
                                    }
                                    else -> {
                                        android.util.Log.d("TgMon", "WS unknown message type: $messageType")
                                    }
                                }
                            } catch (e: JsonSyntaxException) {
                                android.util.Log.w("TgMon", "WS parse error (malformed JSON): ${e.message}")
                            } catch (e: Exception) {
                                android.util.Log.w("TgMon", "WS parse error: ${e.message}")
                            }
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        wsState = WebSocketState.DISCONNECTED
                        webSocket.close(1001, null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        wsState = WebSocketState.DISCONNECTED
                        this@DashboardViewModel.webSocket = null
                        android.util.Log.d("TgMon", "WS closed: code=$code reason=$reason")
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        val previouslyConnected = wsState == WebSocketState.CONNECTED
                        wsState = WebSocketState.DISCONNECTED
                        this@DashboardViewModel.webSocket = null
                        if (isWsPaused) return
                        
                        if (previouslyConnected && wsWasConnected) {
                            android.util.Log.w("TgMon", "WS connection lost: ${t.message}")
                        }
                        
                        wsRetryCount++
                        if (wsRetryCount > maxWsRetries) {
                            android.util.Log.w("TgMon", "WS max retries reached, falling back to polling")
                            return
                        }
                        val delayMs = minOf(1000L * (1L shl minOf(wsRetryCount, 6)), 60000L)
                        android.util.Log.d("TgMon", "WS reconnect attempt $wsRetryCount in ${delayMs}ms")
                        wsReconnectJob?.cancel()
                        wsReconnectJob = viewModelScope.launch {
                            kotlinx.coroutines.delay(delayMs)
                            connectWebSocket()
                        }
                    }
                })
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        unregisterNetworkCallback()
        lifecycleOwnerRef?.lifecycle?.removeObserver(lifecycleObserver)
        lifecycleOwnerRef = null
        autoRefreshJob?.cancel()
        wsReconnectJob?.cancel()
        pingJob?.cancel()
        webSocket?.close(1001, "Closing ViewModel")
        webSocket = null
        wsState = WebSocketState.DISCONNECTED
        alertManager.dismissAll()
    }

    fun logout() {
        viewModelScope.launch {
            autoRefreshJob?.cancel()
            wsReconnectJob?.cancel()
            pingJob?.cancel()
            webSocket?.close(1001, "Logout")
            repository.clearConfig()
            metricCache.clearAll()
            com.tgmon.app.data.api.RetrofitClient.resetClient()
        }
    }

    fun switchServer(serverId: String) {
        if (serverId.isNotBlank()) {
            preferencesManager.setDefaultServerId(serverId)
        }
        autoRefreshJob?.cancel()
        wsReconnectJob?.cancel()
        pingJob?.cancel()
        webSocket?.close(1001, "Server switch")
        webSocket = null
        wsState = WebSocketState.DISCONNECTED
        wsWasConnected = false
        wsRetryCount = 0
        _healthState.value = DashboardHealthState()
        _connectionState.value = DashboardConnectionState()
        _containersState.value = DashboardContainersState()
        _selectedTab.value = 0
        _aiPredictionState.value = AIPredictionState()
        repository.recreate()
        loadWithCacheFirst()
        startAutoRefresh()
        connectWebSocket()
        loadAiPrediction()
    }
}

