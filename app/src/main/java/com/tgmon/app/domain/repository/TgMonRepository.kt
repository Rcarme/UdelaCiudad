package com.tgmon.app.domain.repository

import android.util.Log
import com.tgmon.app.data.api.TgMonApi
import com.tgmon.app.data.database.AppDatabase
import com.tgmon.app.data.models.HealthResponse
import com.tgmon.app.data.models.DockerContainer
import com.tgmon.app.data.models.ContainerCache
import com.tgmon.app.data.models.StatusResponse
import com.tgmon.app.data.models.ContainerDetails
import com.tgmon.app.data.models.MetricHistory
import com.tgmon.app.data.models.MetricHistoryDto
import com.tgmon.app.data.models.ForecastResponse
import com.tgmon.app.data.models.ServiceUrlsResponse
import com.tgmon.app.data.models.VerifyAuthResponse
import com.tgmon.app.data.preferences.PreferencesManager
import com.tgmon.app.data.preferences.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.google.gson.JsonElement

class TgMonRepository(
    private val preferencesManager: PreferencesManager,
    private val serverId: String? = null,
    private val database: AppDatabase? = null
) {
    companion object {
        private const val TAG = "TgMonRepository"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1000L

        private val metricCache = ConcurrentHashMap<String, Any>()
        private var cacheTimestamp = 0L
        private const val cacheValidityMs = 30_000L
    }

    private fun getServerConfig(): ServerConfig? {
        return try {
            if (!serverId.isNullOrBlank()) {
                preferencesManager.getServerConfig(serverId)
            } else {
                preferencesManager.getDefaultServer()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting server config", e)
            null
        }
    }

    suspend fun validateAndSaveConfig(
        name: String,
        localUrl: String,
        remoteUrl: String,
        token: String
    ): Result<HealthResponse> {
        return try {
            if (localUrl.isBlank() && remoteUrl.isBlank()) {
                return Result.failure(Exception("You must enter at least one URL"))
            }
            if (token.isBlank()) {
                return Result.failure(Exception("Token cannot be empty"))
            }

            var successResponse: HealthResponse? = null
            var lastException: Exception? = null

            if (localUrl.isNotBlank()) {
                try {
                    successResponse = checkHealthWithToken(localUrl, token)
                } catch (e: Exception) {
                    Log.w(TAG, "Local URL health check failed", e)
                    lastException = e
                }
            }

            if (successResponse == null && remoteUrl.isNotBlank()) {
                try {
                    successResponse = checkHealthWithToken(remoteUrl, token)
                } catch (e: Exception) {
                    Log.w(TAG, "Remote URL health check failed", e)
                    lastException = e
                }
            }

            if (successResponse != null) {
                var detectedRole = "admin"
                val workingUrl = if (localUrl.isNotBlank() && lastException == null) localUrl else remoteUrl
                if (workingUrl.isNotBlank()) {
                    val authRes = checkVerifyWithToken(workingUrl, token)
                    if (authRes != null && authRes.role.isNotBlank()) {
                        detectedRole = authRes.role
                    }
                }
                val newServer = ServerConfig(
                    name = name.takeIf { it.isNotBlank() } ?: "Mi Servidor",
                    localUrl = localUrl.trim(),
                    remoteUrl = remoteUrl.trim(),
                    token = token.trim(),
                    isDefault = true,
                    role = detectedRole
                )
                preferencesManager.saveServerConfig(newServer)
                preferencesManager.setDefaultServerId(newServer.id)
                Result.success(successResponse)
            } else {
                Result.failure(lastException ?: Exception("Network error connecting to server"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error validating config", e)
            Result.failure(e)
        }
    }

    private fun getApi(baseUrl: String, timeoutSeconds: Long): TgMonApi {
        return com.tgmon.app.data.api.RetrofitClient.createApi(baseUrl, timeoutSeconds)
    }

    private suspend fun checkHealthWithToken(baseUrl: String, token: String): HealthResponse? {
        return try {
            val normalized = if (baseUrl.endsWith("/")) baseUrl else "${baseUrl}/"
            val client = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    val original = chain.request()
                    val req = original.newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                    chain.proceed(req)
                })
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(normalized)
                .client(client)
                // Reuse app Gson with ContainerListResponse adapter for consistency; no extra allocations
                .addConverterFactory(GsonConverterFactory.create(com.tgmon.app.data.api.RetrofitClient.tgmonGson))
                .build()

            val api = retrofit.create(TgMonApi::class.java)
            api.getHealth()
        } catch (e: Exception) {
            throw e
        }
    }

    private suspend fun checkVerifyWithToken(baseUrl: String, token: String): VerifyAuthResponse? {
        return try {
            val normalized = if (baseUrl.endsWith("/")) baseUrl else "${baseUrl}/"
            val client = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    val original = chain.request()
                    val req = original.newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                    chain.proceed(req)
                })
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(normalized)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create(com.tgmon.app.data.api.RetrofitClient.tgmonGson))
                .build()

            val api = retrofit.create(TgMonApi::class.java)
            api.verifyAuth()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun <T> executeWithRetry(apiCall: suspend (TgMonApi) -> T): Result<T> {
        val config = getServerConfig()

        if (config == null) return Result.failure(Exception("No saved configuration"))

        var lastException: Exception? = null
        var attempt = 0
        val slowResponseThreshold = 10_000L

        while (attempt < MAX_RETRY_ATTEMPTS) {
            attempt++
            try {
                val attemptStartTime = System.currentTimeMillis()
                
                if (config.localUrl.isNotBlank()) {
                    try {
                        val localTimeout = if (attempt > 1) (attempt * 2).toLong() else 2L
                        val api = getApi(config.localUrl, localTimeout)
                        val result = apiCall(api)
                        val elapsed = System.currentTimeMillis() - attemptStartTime
                        if (elapsed > slowResponseThreshold) {
                            Log.w(TAG, "Slow local response: ${elapsed}ms")
                        }
                        return Result.success(result)
                    } catch (e: Exception) {
                        Log.w(TAG, "Local API call failed (attempt $attempt)", e)
                        lastException = e
                    }
                }
                if (config.remoteUrl.isNotBlank()) {
                    try {
                        val remoteTimeout = if (attempt > 1) (attempt * 10).toLong() else 30L
                        val api = getApi(config.remoteUrl, remoteTimeout)
                        val result = apiCall(api)
                        val elapsed = System.currentTimeMillis() - attemptStartTime
                        if (elapsed > slowResponseThreshold) {
                            Log.w(TAG, "Slow remote response: ${elapsed}ms")
                        }
                        return Result.success(result)
                    } catch (e: Exception) {
                        Log.w(TAG, "Remote API call failed (attempt $attempt)", e)
                        lastException = e
                    }
                }
            } catch (e: Exception) {
                lastException = e
            }
            
            if (attempt < MAX_RETRY_ATTEMPTS) {
                kotlinx.coroutines.delay(RETRY_DELAY_MS * attempt)
            }
        }
        
        Log.e(TAG, "All retry attempts exhausted", lastException)
        return Result.failure(lastException ?: Exception("No configured URLs"))
    }

    private suspend fun <T> executeWithFallback(apiCall: suspend (TgMonApi) -> T): Result<T> {
        val config = getServerConfig()

        if (config == null) return Result.failure(Exception("No saved configuration"))

        var lastException: Exception? = null
        val slowResponseThreshold = 10_000L

        if (config.localUrl.isNotBlank()) {
            try {
                val startTime = System.currentTimeMillis()
                val api = getApi(config.localUrl, 2)
                val result = apiCall(api)
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed > slowResponseThreshold) {
                    Log.w(TAG, "Slow local response: ${elapsed}ms")
                }
                return Result.success(result)
            } catch (e: Exception) {
                Log.w(TAG, "Local API call failed, retrying before fallback", e)
                lastException = e
                kotlinx.coroutines.delay(500)
            }
        }

        if (config.localUrl.isNotBlank()) {
            try {
                val startTime = System.currentTimeMillis()
                val api = getApi(config.localUrl, 3)
                val result = apiCall(api)
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed > slowResponseThreshold) {
                    Log.w(TAG, "Slow local retry: ${elapsed}ms")
                }
                return Result.success(result)
            } catch (e: Exception) {
                Log.w(TAG, "Local API retry failed, trying fallback", e)
                lastException = e
            }
        }

        if (config.remoteUrl.isNotBlank()) {
            try {
                val startTime = System.currentTimeMillis()
                val api = getApi(config.remoteUrl, 30)
                val result = apiCall(api)
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed > slowResponseThreshold) {
                    Log.w(TAG, "Slow remote response: ${elapsed}ms")
                }
                return Result.success(result)
            } catch (e: Exception) {
                Log.w(TAG, "Remote API call failed", e)
                lastException = e
            }
        }
        return Result.failure(lastException ?: Exception("No configured URLs"))
    }

    fun <T> isCacheValid(key: String): Boolean {
        val timestamp = cacheTimestamp
        return System.currentTimeMillis() - timestamp < cacheValidityMs && metricCache.containsKey(key)
    }

    fun getFromCache(key: String): Any? {
        @Suppress("UNCHECKED_CAST")
        val timestamp = cacheTimestamp
        return if (System.currentTimeMillis() - timestamp < cacheValidityMs && metricCache.containsKey(key)) metricCache[key] else null
    }

    fun <T> putToCache(key: String, value: T) {
        metricCache[key] = value as Any
        cacheTimestamp = System.currentTimeMillis()
    }

    fun clearCache() {
        metricCache.clear()
        cacheTimestamp = 0L
    }

    suspend fun getHealth(): Result<HealthResponse> {
        return executeWithRetry { it.getHealth() }
    }

    suspend fun getStatus(): Result<StatusResponse> {
        return executeWithRetry { it.getStatus() }
    }

    suspend fun listContainers(): Result<List<DockerContainer>> {
        return executeWithRetry { api ->
            // Server wraps list as {"containers": [...], "cache_timestamp":..., "cache_age_seconds":...}
            // TgMonApi returns ContainerListResponse; unwrap to List for callers.
            // Backward compat: deserializer also handles bare [...] -> wrapper with containers.
            // No extra allocations: directly return unwrapped list (reuses server list reference).
            val response = api.listContainers()
            response.containers
        }
    }

    suspend fun restartContainer(containerId: String): Result<Boolean> {
        clearCache()
        return executeWithRetry { it.restartContainer(containerId).success }
    }

    suspend fun stopContainer(containerId: String): Result<Boolean> {
        clearCache()
        return executeWithRetry { it.stopContainer(containerId).success }
    }

    suspend fun startContainer(containerId: String): Result<Boolean> {
        clearCache()
        return executeWithRetry { it.startContainer(containerId).success }
    }

    suspend fun removeContainer(containerId: String, force: Boolean = false): Result<Boolean> {
        clearCache()
        return executeWithRetry { it.removeContainer(containerId, force).success }
    }

    suspend fun getContainerLogs(containerId: String): Result<String> {
        return executeWithRetry { it.getContainerLogs(containerId, 100).logs }
    }

    suspend fun getContainerDetails(containerId: String): Result<ContainerDetails> {
        return executeWithRetry { it.getContainerDetails(containerId) }
    }

    suspend fun pauseContainer(containerId: String): Result<Boolean> {
        clearCache()
        return executeWithRetry { it.pauseContainer(containerId).success }
    }

    suspend fun unpauseContainer(containerId: String): Result<Boolean> {
        clearCache()
        return executeWithRetry { it.unpauseContainer(containerId).success }
    }

    suspend fun getServiceUrls(): Result<ServiceUrlsResponse> {
        return executeWithRetry { it.getServiceUrls() }
    }

    suspend fun getHistory(hours: Int = 24): Result<List<MetricHistoryDto>> {
        return executeWithRetry { it.getHistory(hours) }
    }

    suspend fun syncHistoryFromServer(hours: Int = 24): Result<Int> {
        val db = database ?: return Result.failure(Exception("Database not available"))
        return getHistory(hours).map { dtoList ->
            if (dtoList.isEmpty()) return@map 0
            val dao = db.metricHistoryDao()
            val minTimestamp = dtoList.minOf { it.timestamp }
            val existingTimestamps = dao.getExistingTimestamps(minTimestamp).toSet()
            val newEntities = dtoList.filter { it.timestamp !in existingTimestamps }.map { dto ->
                MetricHistory(
                    timestamp = dto.timestamp,
                    hostname = "",
                    cpuPercent = dto.cpuPercent,
                    memoryPercent = dto.memoryPercent,
                    diskUsedGb = dto.diskUsedGb,
                    diskFreeGb = dto.diskFreeGb,
                    diskTotalGb = dto.diskTotalGb,
                    diskPercent = dto.diskPercent
                )
            }
            if (newEntities.isNotEmpty()) {
                dao.insertAll(newEntities)
            }
            newEntities.size
        }
    }

    suspend fun getHistoryCsv(hours: Int = 24): Result<String> {
        return executeWithRetry { it.getHistoryCsv(hours).string() }
    }


    // IA: Obtener predicciones de picos de CPU y RAM
    suspend fun getAiPrediction(useLlm: Boolean = false): Result<JsonElement> {
        return executeWithRetry { api ->
            api.getAiPrediction(useLlm)
        }
    }

    // IA: Pronóstico de CPU/RAM (regresión lineal en el servicio Python)
    suspend fun getForecast(
        windowMinutes: Int = 60,
        horizonMinutes: Int = 30
    ): Result<ForecastResponse> {
        return executeWithRetry { api ->
            api.getForecast(windowMinutes, horizonMinutes)
        }
    }

    // IA: Obtener recomendaciones sobre contenedores
    suspend fun getAiRecommendations(useLlm: Boolean = false): Result<JsonElement> {
        return executeWithRetry { api ->
            api.getAiRecommendations(useLlm)
        }
    }

    suspend fun verifyAuth(): Result<Boolean> {
        return executeWithRetry { api ->
            val res = api.verifyAuth()
            val config = getServerConfig()
            if (config != null && config.role != res.role) {
                preferencesManager.saveServerConfig(config.copy(role = res.role))
            }
            res.valid
        }
    }

    fun isCurrentServerAdmin(): Boolean {
        return getServerConfig()?.isAdmin ?: true
    }

    suspend fun invalidateAuth(): Result<Boolean> {
        return executeWithRetry {
            val res = it.invalidateAuth()
            res["success"] == true
        }
    }

    fun getServerUrl(): String? {
        val config = getServerConfig() ?: return null
        return config.localUrl.takeIf { it.isNotBlank() } ?: config.remoteUrl.takeIf { it.isNotBlank() }
    }

    fun getLocalUrl(): String? = getServerConfig()?.localUrl
    fun getRemoteUrl(): String? = getServerConfig()?.remoteUrl
    fun getToken(): String? = getServerConfig()?.token
    fun getServerName(): String? = getServerConfig()?.name
    fun isConfigured(): Boolean = getServerConfig() != null
    fun clearConfig() {
        clearCache()
        val config = getServerConfig()
        config?.let { preferencesManager.removeServer(it.id) }
    }

    fun recreate() {
        clearCache()
        com.tgmon.app.data.api.RetrofitClient.resetClient()
    }
    
    suspend fun saveContainersToCache(containers: List<DockerContainer>, context: android.content.Context): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val dao = com.tgmon.app.data.database.AppDatabase.getInstance(context).containerCacheDao()
                val cacheList = containers.map { container ->
                    ContainerCache(
                        id = container.id,
                        name = container.name,
                        status = container.status,
                        image = container.image,
                        cpuPercent = container.cpuPercent,
                        memoryMb = container.memoryMb,
                        memoryPercent = container.memoryPercent,
                        uptimeSeconds = container.uptimeSeconds,
                        healthStatus = container.healthStatus,
                        networkRxMb = container.networkRxMb,
                        networkTxMb = container.networkTxMb,
                        timestamp = System.currentTimeMillis()
                    )
                }
                dao.insertAll(cacheList)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error saving containers to cache", e)
                Result.failure(e)
            }
        }
    }
    
    suspend fun getCachedContainers(context: android.content.Context): List<ContainerCache>? {
        return withContext(Dispatchers.IO) {
            try {
                val dao = com.tgmon.app.data.database.AppDatabase.getInstance(context).containerCacheDao()
                dao.getAllContainersList()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting cached containers", e)
                null
            }
        }
    }
    
    suspend fun getCachedContainersFlow(context: android.content.Context): kotlinx.coroutines.flow.Flow<List<ContainerCache>>? {
        return try {
            val dao = com.tgmon.app.data.database.AppDatabase.getInstance(context).containerCacheDao()
            dao.getAllContainers()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting cached containers flow", e)
            null
        }
    }
    
    suspend fun clearContainerCache(context: android.content.Context) {
        withContext(Dispatchers.IO) {
            try {
                val dao = com.tgmon.app.data.database.AppDatabase.getInstance(context).containerCacheDao()
                dao.clearAll()
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing container cache", e)
            }
        }
    }
}
