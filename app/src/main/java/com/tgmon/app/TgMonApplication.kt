package com.tgmon.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.tgmon.app.data.api.RetrofitClient
import com.tgmon.app.data.preferences.PreferencesManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TgMonApplication : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        setupTokenInvalidationCallback()
        verifySecureStorage()
        seedDefaultServerIfNeeded()
    }

    private fun seedDefaultServerIfNeeded() {
        try {
            val prefs = PreferencesManager(this)
            val servers = prefs.getAllServers()
            if (servers.isEmpty() || !prefs.isConfigured()) {
                val alreadyHasDefault = servers.any {
                    it.localUrl == "http://10.0.2.2:8000" &&
                    it.remoteUrl == "http://10.0.2.2:8000" &&
                    it.token == "student_secure_token_123"
                }
                if (!alreadyHasDefault) {
                    val defaultServer = com.tgmon.app.data.preferences.ServerConfig(
                        name = "Local Server",
                        localUrl = "http://10.0.2.2:8000",
                        remoteUrl = "http://10.0.2.2:8000",
                        token = "student_secure_token_123",
                        isDefault = true
                    )
                    prefs.saveServerConfig(defaultServer)
                    prefs.setDefaultServerId(defaultServer.id)
                    android.util.Log.d("TgMon", "Seeded default local server: ${defaultServer.id}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TgMon", "Failed to seed default server: ${e.message}")
        }
    }

    private fun setupTokenInvalidationCallback() {
        RetrofitClient.setTokenInvalidationCallback {
            android.util.Log.w("TgMon", "Token invalidated, clearing session")
            try {
                val prefs = PreferencesManager(this)
                prefs.clearAll()
                RetrofitClient.resetClient()
            } catch (e: Exception) {
                android.util.Log.e("TgMon", "Error clearing session: ${e.message}")
            }
        }
    }

    private fun verifySecureStorage() {
        try {
            val prefs = PreferencesManager(this)
            if (!prefs.verifySecureStorage()) {
                android.util.Log.e("TgMon", "SECURE STORAGE VERIFICATION FAILED")
            }
        } catch (e: Exception) {
            android.util.Log.e("TgMon", "Secure storage check failed: ${e.message}")
        }
    }
}
