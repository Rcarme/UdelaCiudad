package com.tgmon.app.widget

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tgmon.app.data.database.AppDatabase
import com.tgmon.app.data.models.MetricHistory
import com.tgmon.app.data.preferences.PreferencesManager
import com.tgmon.app.domain.repository.TgMonRepository
import java.util.concurrent.TimeUnit

class SnapshotWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    // N97 low-resource: manual DI via lazy bypasses HiltWorker to avoid Hilt graph init per 15-min periodic work.
    // Keeps binary small and worker startup fast on low-CPU N97. OkHttpClient reuse via RetrofitClient
    // singleton (clients ConcurrentHashMap) ensures no per-worker connection pool allocation — saves RAM.
    private val prefsManager: PreferencesManager by lazy { PreferencesManager(context.applicationContext) }
    private val repository: TgMonRepository by lazy { TgMonRepository(preferencesManager = prefsManager) }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting snapshot work")
        if (!prefsManager.isConfigured()) {
            Log.w(TAG, "App not configured, skipping snapshot")
            return Result.failure()
        }

        val healthResult = repository.getHealth()

        healthResult.onSuccess { health ->
            try {
                val db = AppDatabase.getInstance(context)
                val dao = db.metricHistoryDao()

                val defaultDiskTotal = health.diskTotalGb ?: (health.diskUsedGb + health.diskFreeGb)
                val defaultDiskPercent = MetricHistory.calculateDiskPercent(health.diskUsedGb, defaultDiskTotal)
                val highestDiskPercent = health.disks?.filter { it.totalGb > 0.0 }?.maxOfOrNull { it.percent }
                val diskPercent = highestDiskPercent ?: defaultDiskPercent

                dao.insert(
                    MetricHistory(
                        hostname = health.hostname.ifBlank { "Unknown" },
                        cpuPercent = health.cpuPercent,
                        memoryPercent = health.memoryPercent,
                        diskUsedGb = health.diskUsedGb,
                        diskFreeGb = health.diskFreeGb,
                        diskTotalGb = defaultDiskTotal,
                        diskPercent = diskPercent
                    )
                )

                val oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
                dao.deleteOldMetrics(oneDayAgo)
                Log.d(TAG, "Snapshot saved successfully")

                // Update Glance widget with fresh metrics
                try {
                    val updateRequest = androidx.work.OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build()
                    WorkManager.getInstance(context).enqueue(updateRequest)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to enqueue widget update from snapshot: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Database operation failed", e)
                return Result.failure()
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to get health", e)
        }

        return if (healthResult.isSuccess) {
            Result.success()
        } else {
            val retryCount = inputData.getInt(KEY_RETRY_COUNT, 0) + 1
            if (retryCount >= MAX_RETRIES) {
                Log.e(TAG, "SnapshotWorker failed after $MAX_RETRIES retries, giving up until next scheduled run")
                return Result.failure()
            } else {
                val outputData = Data.Builder()
                    .putInt(KEY_RETRY_COUNT, retryCount)
                    .build()
                Result.failure(outputData)
            }
        }
    }

    companion object {
        private const val TAG = "SnapshotWorker"
        private const val WORK_NAME = "tgmon_snapshot_worker"
        private const val KEY_RETRY_COUNT = "retry_count"
        private const val MAX_RETRIES = 3

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SnapshotWorker>(15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
