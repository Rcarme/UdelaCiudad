package com.tgmon.app.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tgmon.app.data.preferences.PreferencesManager
import com.tgmon.app.domain.repository.TgMonRepository

class WidgetUpdateWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    // N97 low-resource: manual DI via lazy bypasses HiltWorker (androidx.hilt:hilt-work) to keep
    // APK overhead low — HiltWorkerFactory adds reflection/codegen & extra graph init per worker.
    // OkHttpClient is still reused via RetrofitClient singleton (ConcurrentHashMap<String, OkHttpClient>)
    // so this worker does NOT create a new socket pool each run — shared connection pool saves RAM/CPU.
    private val prefsManager: PreferencesManager by lazy { PreferencesManager(context.applicationContext) }
    private val repository: TgMonRepository by lazy { TgMonRepository(preferencesManager = prefsManager) }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting widget update")

        if (!prefsManager.isConfigured()) {
            Log.w(TAG, "App not configured, skipping widget update")
            return Result.failure()
        }

        val healthResult = repository.getHealth()

        val manager = GlanceAppWidgetManager(context)
        val glanceIds = manager.getGlanceIds(TgMonWidget::class.java)

        if (glanceIds.isEmpty()) {
            Log.w(TAG, "No widget instances found")
            return Result.success()
        }

        for (glanceId in glanceIds) {
            try {
                updateAppWidgetState(context, glanceId) { prefs ->
                    healthResult.fold(
                        onSuccess = { health ->
                            prefs[WidgetPreferences.hostnameKey] = health.hostname
                            prefs[WidgetPreferences.isOnlineKey] = true
                            prefs[WidgetPreferences.cpuKey] = health.cpuPercent
                            prefs[WidgetPreferences.memKey] = health.memoryPercent
                            val defaultDiskTotal = health.diskTotalGb ?: (health.diskUsedGb + health.diskFreeGb)
                            val defaultDiskPercent = if (defaultDiskTotal > 0.0) {
                                (health.diskUsedGb / defaultDiskTotal) * 100.0
                            } else 0.0
                            val highestDiskPercent = health.disks?.filter { it.totalGb > 0.0 }?.maxOfOrNull { it.percent }
                            val diskPercent = highestDiskPercent ?: defaultDiskPercent
                            prefs[WidgetPreferences.diskKey] = diskPercent
                            val loadAvg = health.loadAvg?.firstOrNull()?.let { String.format("%.2f", it) } ?: "N/A"
                            prefs[WidgetPreferences.loadAvgKey] = loadAvg
                            prefs[WidgetPreferences.lastUpdateTimestampKey] = System.currentTimeMillis()
                            prefs[WidgetPreferences.cpuThresholdKey] = prefsManager.getCpuThreshold().toDouble()
                            prefs[WidgetPreferences.memThresholdKey] = prefsManager.getMemoryThreshold().toDouble()
                            prefs[WidgetPreferences.diskThresholdKey] = prefsManager.getDiskThreshold().toDouble()
                        },
                        onFailure = {
                            prefs[WidgetPreferences.isOnlineKey] = false
                            prefs[WidgetPreferences.hostnameKey] = ""
                            prefs[WidgetPreferences.cpuKey] = 0.0
                            prefs[WidgetPreferences.memKey] = 0.0
                            prefs[WidgetPreferences.diskKey] = 0.0
                            prefs[WidgetPreferences.loadAvgKey] = ""
                            prefs[WidgetPreferences.lastUpdateTimestampKey] = System.currentTimeMillis()
                        }
                    )
                }
                TgMonWidget().update(context, glanceId)
                Log.d(TAG, "Widget updated successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update widget ${glanceId}", e)
            }
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "WidgetUpdateWorker"
    }
}
