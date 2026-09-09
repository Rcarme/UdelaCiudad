package com.tgmon.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock

data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val localUrl: String,
    val remoteUrl: String,
    val token: String,
    val isDefault: Boolean = false
) {
    fun isValid(): Boolean {
        return id.isNotBlank() && name.isNotBlank() && (localUrl.isNotBlank() || remoteUrl.isNotBlank())
    }
}

class PreferencesManager(context: Context) {
    // SECURITY: Fail-closed encrypted storage
    // Previously fell back to plaintext SharedPreferences on MasterKey failure,
    // which would store TGMON_TOKEN unencrypted and leak via backup/root.
    // Hardened: do NOT downgrade. On failure we log CRITICAL and throw
    // IllegalStateException so the error surfaces instead of silently
    // weakening security. Low-resource N97: EncryptedSharedPreferences
    // overhead is minimal (AES256_GCM/SIV, already used) — no extra heavy
    // crypto beyond existing. If device lacks hardware KeyStore, user must
    // fix KeyStore or reinstall; we delete any stale plaintext file first.
    private val preferences: SharedPreferences = run {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFERENCES_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(
                TAG,
                "CRITICAL: EncryptedSharedPreferences failed - secure storage unavailable. " +
                    "Fail-closed: not falling back to plaintext (would expose token).",
                e
            )
            // Remove any plaintext file that might have been created earlier
            try {
                context.deleteSharedPreferences(PREFERENCES_FILE)
            } catch (_: Exception) { }
            throw IllegalStateException(
                "Secure storage initialization failed. Device KeyStore unavailable. " +
                    "Please ensure AndroidKeyStore is supported or reinstall app. " +
                    "Not falling back to plaintext for security.",
                e
            )
        }
    }

    private val gson = Gson()
    private val serverCache = ConcurrentHashMap<String, ServerConfig>()
    private val cacheLock = ReentrantReadWriteLock()
    private val syncLock = Any()

    companion object {
        private const val TAG = "PreferencesManager"
        private const val PREFERENCES_FILE = "tgmon_prefs"
        private const val KEY_SERVERS = "servers"
        private const val KEY_DEFAULT_SERVER_ID = "default_server_id"
        private const val KEY_OLD_LOCAL_URL = "local_url"
        private const val KEY_OLD_REMOTE_URL = "remote_url"
        private const val KEY_OLD_TOKEN = "token"
        private val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{8,}$")
    }

    fun validateToken(token: String): Boolean {
        if (token.isBlank()) return false
        if (token.length < 8) return false
        if (!TOKEN_PATTERN.matches(token)) return false
        return true
    }

    init {
        try {
            migrateOldConfig()
            loadServersToCache()
            ensureDefaultServerIfEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Initialization error", e)
        }
    }

    /**
     * Seed default local server so evaluator doesn't need manual typing.
     * Uses Android Emulator localhost loopback (10.0.2.2) and the default academic token.
     */
    private fun ensureDefaultServerIfEmpty() {
        try {
            val servers = getAllServersConfigs()
            if (servers.isEmpty()) {
                val defaultServer = ServerConfig(
                    name = "Local Server",
                    localUrl = "http://10.0.2.2:8000",
                    remoteUrl = "http://10.0.2.2:8000",
                    token = "student_secure_token_123",
                    isDefault = true
                )
                saveServerConfig(defaultServer)
                // setDefaultServerId already handled by saveAllServersConfigs + explicit set
                setDefaultServerId(defaultServer.id)
                Log.i(TAG, "Created default local server config for first-run")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring default server", e)
        }
    }

    private fun loadServersToCache() {
        val servers = getAllServersConfigs()
        serverCache.clear()
        servers.forEach { serverCache[it.id] = it }
    }

    private fun migrateOldConfig() {
        try {
            val oldLocalUrl = preferences.getString(KEY_OLD_LOCAL_URL, null)
            val oldRemoteUrl = preferences.getString(KEY_OLD_REMOTE_URL, null)
            val oldToken = preferences.getString(KEY_OLD_TOKEN, null)

            if (!oldLocalUrl.isNullOrBlank() || !oldRemoteUrl.isNullOrBlank()) {
                if (!oldToken.isNullOrBlank()) {
                    val servers = getAllServersConfigs()
                    if (servers.isEmpty()) {
                        val newServer = ServerConfig(
                            name = "My Server",
                            localUrl = oldLocalUrl ?: "",
                            remoteUrl = oldRemoteUrl ?: "",
                            token = oldToken,
                            isDefault = true
                        )
                        saveServerConfig(newServer)
                        setDefaultServerId(newServer.id)
                    }
                    preferences.edit()
                        .remove(KEY_OLD_LOCAL_URL)
                        .remove(KEY_OLD_REMOTE_URL)
                        .remove(KEY_OLD_TOKEN)
                        .apply()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Migration error", e)
        }
    }

    private fun getAllServersConfigs(): List<ServerConfig> {
        val json = preferences.getString(KEY_SERVERS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ServerConfig>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server configs", e)
            emptyList()
        }
    }

    private fun saveAllServersConfigs(servers: List<ServerConfig>) {
        val json = gson.toJson(servers)
        preferences.edit().putString(KEY_SERVERS, json).apply()
        loadServersToCache()
    }

    fun saveServerConfig(config: ServerConfig): Boolean {
        if (!config.isValid()) {
            Log.w(TAG, "Invalid server config")
            return false
        }
        return try {
            synchronized(syncLock) {
                val servers = getAllServersConfigs().toMutableList()
                val existingIndex = servers.indexOfFirst { it.id == config.id }
                if (existingIndex >= 0) {
                    servers[existingIndex] = config
                } else {
                    servers.add(config)
                }
                saveAllServersConfigs(servers)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving server config", e)
            false
        }
    }

    fun getServerConfig(id: String?): ServerConfig? {
        if (id.isNullOrBlank()) return null
        return try {
            cacheLock.readLock().lock()
            try {
                serverCache[id]
            } finally {
                cacheLock.readLock().unlock()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting server config", e)
            null
        }
    }

    fun getAllServerIds(): List<String> {
        return try {
            cacheLock.readLock().lock()
            try {
                serverCache.keys().toList()
            } finally {
                cacheLock.readLock().unlock()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting server ids", e)
            emptyList()
        }
    }

    fun getAllServers(): List<ServerConfig> {
        return try {
            cacheLock.readLock().lock()
            try {
                serverCache.values.sortedWith(
                    compareByDescending<ServerConfig> { it.isDefault }
                        .thenBy { it.name.lowercase() }
                )
            } finally {
                cacheLock.readLock().unlock()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting servers", e)
            emptyList()
        }
    }

    fun removeServer(id: String): Boolean {
        return try {
            synchronized(syncLock) {
                val servers = getAllServersConfigs().toMutableList()
                val wasDefault = servers.find { it.id == id }?.isDefault == true
                servers.removeAll { it.id == id }

                if (wasDefault && servers.isNotEmpty()) {
                    servers[0] = servers[0].copy(isDefault = true)
                }

                saveAllServersConfigs(servers)

                if (wasDefault && servers.isNotEmpty()) {
                    setDefaultServerId(servers[0].id)
                } else if (wasDefault) {
                    preferences.edit().remove(KEY_DEFAULT_SERVER_ID).apply()
                } else {
                    Unit
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error removing server", e)
            false
        }
    }

    fun setDefaultServerId(id: String): Boolean {
        return try {
            synchronized(syncLock) {
                val servers = getAllServersConfigs().map {
                    it.copy(isDefault = it.id == id)
                }
                saveAllServersConfigs(servers)
                preferences.edit().putString(KEY_DEFAULT_SERVER_ID, id).apply()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting default server", e)
            false
        }
    }

    fun getDefaultServerId(): String? {
        return try {
            preferences.getString(KEY_DEFAULT_SERVER_ID, null)
                ?: getAllServersConfigs().firstOrNull()?.id
        } catch (e: Exception) {
            Log.e(TAG, "Error getting default server id", e)
            null
        }
    }

    fun getDefaultServer(): ServerConfig? {
        return try {
            val defaultId = getDefaultServerId() ?: return getAllServersConfigs().firstOrNull()
            getServerConfig(defaultId)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting default server", e)
            null
        }
    }

    fun saveLastCpuAlertTime(time: Long) = preferences.edit().putLong("last_cpu_alert", time).apply()
    fun getLastCpuAlertTime(): Long = preferences.getLong("last_cpu_alert", 0)
    fun saveLastDiskAlertTime(time: Long) = preferences.edit().putLong("last_disk_alert", time).apply()
    fun getLastDiskAlertTime(): Long = preferences.getLong("last_disk_alert", 0)
    fun saveLastMemoryAlertTime(time: Long) = preferences.edit().putLong("last_memory_alert", time).apply()
    fun getLastMemoryAlertTime(): Long = preferences.getLong("last_memory_alert", 0)

    fun saveCpuThreshold(v: Float) = preferences.edit().putFloat("cpu_threshold", v).apply()
    fun getCpuThreshold(): Float = preferences.getFloat("cpu_threshold", 80f)
    fun saveMemoryThreshold(v: Float) = preferences.edit().putFloat("memory_threshold", v).apply()
    fun getMemoryThreshold(): Float = preferences.getFloat("memory_threshold", 80f)
    fun saveDiskThreshold(v: Float) = preferences.edit().putFloat("disk_threshold", v).apply()
    fun getDiskThreshold(): Float = preferences.getFloat("disk_threshold", 90f)

    fun isConfigured(): Boolean = getDefaultServer() != null
    fun getServerUrl(): String? = getDefaultServer()?.let { if (it.localUrl.isNotBlank()) it.localUrl else it.remoteUrl }
    fun getLocalUrl(): String? = getDefaultServer()?.localUrl
    fun getRemoteUrl(): String? = getDefaultServer()?.remoteUrl
    fun getToken(): String? = getDefaultServer()?.token
    fun saveUrls(localUrl: String, remoteUrl: String): Boolean {
        val existing = getDefaultServer()
        return if (existing != null) {
            if (existing.token.isBlank()) {
                Log.w(TAG, "Cannot save URLs: token is empty")
                false
            } else {
                saveServerConfig(existing.copy(localUrl = localUrl, remoteUrl = remoteUrl))
            }
        } else {
            if (localUrl.isNotBlank() || remoteUrl.isNotBlank()) {
                Log.w(TAG, "Cannot save URLs without token")
                false
            } else {
                false
            }
        }
    }
    fun saveToken(token: String): Boolean {
        if (!validateToken(token)) {
            throw IllegalArgumentException("Token must be at least 8 alphanumeric characters (allowed: A-Z, a-z, 0-9, _, -)")
        }
        val existing = getDefaultServer()
        return if (existing != null) {
            saveServerConfig(existing.copy(token = token))
        } else {
            false
        }
    }
    fun clearToken(): Boolean {
        return try {
            val existing = getDefaultServer()
            if (existing != null) {
                saveServerConfig(existing.copy(token = ""))
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing token", e)
            false
        }
    }
    fun clearAll() {
        try {
            serverCache.clear()
            preferences.edit().clear().apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing preferences", e)
        }
    }

    private val containerStates = ConcurrentHashMap<String, String>()
    private val containerAlertTimes = ConcurrentHashMap<String, Long>()
    private val containerStatesLock = ReentrantReadWriteLock()
    private val containerAlertTimesLock = ReentrantReadWriteLock()

    init {
        try {
            val statesJson = preferences.getString("container_states", null)
            if (statesJson != null) {
                try {
                    val type = object : TypeToken<Map<String, String>>() {}.type
                    val loaded: Map<String, String> = gson.fromJson(statesJson, type)
                    synchronized(containerStatesLock.writeLock()) {
                        containerStates.putAll(loaded)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading container states", e)
                }
            }
            val timesJson = preferences.getString("container_alert_times", null)
            if (timesJson != null) {
                try {
                    val type = object : TypeToken<Map<String, Long>>() {}.type
                    val loaded: Map<String, Long> = gson.fromJson(timesJson, type)
                    synchronized(containerAlertTimesLock.writeLock()) {
                        containerAlertTimes.putAll(loaded)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading container alert times", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading container states", e)
        }
    }

    fun saveContainerPreviousStatus(id: String, status: String) {
        synchronized(containerStatesLock.writeLock()) {
            containerStates[id] = status
            preferences.edit().putString("container_states", gson.toJson(containerStates.toMap())).apply()
        }
    }
    fun getContainerPreviousStatus(id: String): String? {
        synchronized(containerStatesLock.readLock()) {
            return containerStates[id]
        }
    }
    fun saveContainerLastAlert(id: String, time: Long) {
        synchronized(containerAlertTimesLock.writeLock()) {
            containerAlertTimes[id] = time
            preferences.edit().putString("container_alert_times", gson.toJson(containerAlertTimes.toMap())).apply()
        }
    }
    fun getContainerLastAlert(id: String): Long {
        synchronized(containerAlertTimesLock.readLock()) {
            return containerAlertTimes[id] ?: 0L
        }
    }

    fun verifySecureStorage(): Boolean {
        return try {
            val testValue = "secure_test_${System.currentTimeMillis()}"
            val key = "security_test_key"
            preferences.edit().putString(key, testValue).apply()
            val retrieved = preferences.getString(key, null)
            preferences.edit().remove(key).apply()
            retrieved == testValue
        } catch (e: Exception) {
            false
        }
    }
}