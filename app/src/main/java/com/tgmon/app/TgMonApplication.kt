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
