package com.tgmon.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tgmon.app.domain.repository.TgMonRepository
import kotlinx.coroutines.runBlocking

class ServiceUrlManager(private val context: Context) {
    // SECURITY: Service URLs stored via EncryptedSharedPreferences (not MODE_PRIVATE plaintext)
    // Spec notes low risk (host:port not secret) but we encrypt for defense-in-depth and
    // to avoid backup leakage. Overhead minimal: reuses same MasterKey/AES256_GCM as main prefs.
    // Migration: if legacy plaintext file exists, migrate to encrypted and delete old.
    private val prefs: SharedPreferences = run {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val enc = EncryptedSharedPreferences.create(
                context,
                "service_urls_encrypted",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            // One-time migration from plaintext if needed
            try {
                val legacy = context.getSharedPreferences("service_urls", Context.MODE_PRIVATE)
                if (legacy.contains(KEY) && !enc.contains(KEY)) {
                    val json = legacy.getString(KEY, null)
                    if (json != null) {
                        enc.edit().putString(KEY, json).apply()
                        Log.i(TAG, "Migrated service_urls from plaintext to encrypted")
                    }
                    // Delete legacy plaintext file via clear + delete file if possible
                    try { legacy.edit().clear().apply() } catch (_: Exception) {}
                    try { context.deleteSharedPreferences("service_urls") } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "ServiceUrl migration check failed", e)
            }
            enc
        } catch (e: Exception) {
            // Low-risk fallback: service_urls are host:port, not secrets (unlike token).
            // Per spec we encrypt ideally, but if KeyStore fails we log WARNING and use plaintext
            // with migration note rather than crash (fail-open for low-risk vs fail-closed for token).
            Log.w(TAG, "EncryptedSharedPreferences for service_urls failed, falling back to plaintext with warning (low-risk data)", e)
            context.getSharedPreferences("service_urls", Context.MODE_PRIVATE)
        }
    }
    private val gson = Gson()

    data class ServiceUrl(val container: String, val publicPort: Int, val protocol: String)

    fun saveServiceUrls(list: List<ServiceUrl>) {
        prefs.edit().putString(KEY, gson.toJson(list)).apply()
    }

    fun getServiceUrls(): List<ServiceUrl> {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ServiceUrl>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) { emptyList() }
    }

    fun getServiceUrl(containerName: String): ServiceUrl? {
        val cleanName = containerName.removePrefix("/")
        return getServiceUrls().find { it.container == cleanName || it.container == "/$cleanName" }
    }

    suspend fun fetchFromServer(repository: TgMonRepository): Result<Int> {
        return repository.getServiceUrls().map { response ->
            val urls = response.serviceUrls.map { ServiceUrl(it.container, it.publicPort, it.protocol) }
            saveServiceUrls(urls)
            urls.size
        }
    }

    companion object {
        private const val TAG = "ServiceUrlManager"
        private const val KEY = "cached_service_urls"
    }
}
