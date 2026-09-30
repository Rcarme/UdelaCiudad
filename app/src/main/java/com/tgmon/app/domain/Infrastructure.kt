package com.tgmon.app.domain

import android.content.Context
import com.tgmon.app.data.database.AppDatabase
import com.tgmon.app.data.models.AlertHistory
import com.tgmon.app.data.models.HealthResponse
import com.tgmon.app.data.models.MetricHistory
import com.tgmon.app.data.preferences.PreferencesManager
import com.tgmon.app.presentation.notifications.TgMonNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class RoomMetricCache(context: Context) : MetricCache {
    private val dao = AppDatabase.getInstance(context).metricHistoryDao()

    override suspend fun getLastMetric() = dao.getLastMetric()
    override suspend fun insertMetric(metric: MetricHistory) = dao.insert(metric)
    override fun getLastNMetricsFlow(count: Int): Flow<List<MetricHistory>>? = dao.getLastNMetrics(count)
    override suspend fun deleteOldMetrics(beforeTime: Long) = dao.deleteOldMetrics(beforeTime)
    override suspend fun clearAll() = dao.clearAll()
}

class PreferencesAlertManager(
    private val context: Context,
    private val preferencesManager: PreferencesManager
) : AlertManager {
    private val notificationManager = TgMonNotificationManager(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun checkAndNotify(response: HealthResponse) {
        val now = System.currentTimeMillis()
        val thirtyMinutes = 30 * 60 * 1000L

        if (response.cpuPercent > preferencesManager.getCpuThreshold().toDouble()) {
            val lastAlert = preferencesManager.getLastCpuAlertTime()
            if (now - lastAlert > thirtyMinutes) {
                notificationManager.notifyCpuHigh(response.cpuPercent.toFloat())
                preferencesManager.saveLastCpuAlertTime(now)
                scope.launch {
                    try {
                        val dao = AppDatabase.getInstance(context).alertHistoryDao()
                        dao.insert(AlertHistory(
                            type = "CPU_HIGH",
                            message = "CPU: ${String.format(java.util.Locale.US, "%.1f", response.cpuPercent)}%",
                            value = response.cpuPercent.toFloat(),
                            serverId = preferencesManager.getDefaultServerId() ?: "unknown"
                        ))
                    } catch (e: Exception) { }
                }
            }
        } else {
            notificationManager.dismissCpuNotification()
            if (preferencesManager.getLastCpuAlertTime() > 0) {
                preferencesManager.saveLastCpuAlertTime(0)
            }
        }

        val defaultDiskTotal = response.diskTotalGb ?: (response.diskUsedGb + response.diskFreeGb)
        val defaultDiskPercent = if (defaultDiskTotal > 0.0) (response.diskUsedGb / defaultDiskTotal) * 100.0 else 0.0
        val highestDisk = response.disks?.filter { it.totalGb > 0.0 }?.maxByOrNull { it.percent }
        val diskPercent = highestDisk?.percent ?: defaultDiskPercent
        val diskLabel = highestDisk?.device?.takeIf { it.isNotBlank() } ?: highestDisk?.mountpoint
        val diskMessage = if (diskLabel != null) "Disco ($diskLabel): ${String.format(java.util.Locale.US, "%.1f", diskPercent)}%" else "Disco: ${String.format(java.util.Locale.US, "%.1f", diskPercent)}%"

        if (diskPercent > preferencesManager.getDiskThreshold().toDouble()) {
            val lastAlert = preferencesManager.getLastDiskAlertTime()
            if (now - lastAlert > thirtyMinutes) {
                notificationManager.notifyDiskHigh(diskPercent.toFloat())
                preferencesManager.saveLastDiskAlertTime(now)
                scope.launch {
                    try {
                        val dao = AppDatabase.getInstance(context).alertHistoryDao()
                        dao.insert(AlertHistory(
                            type = "DISK_HIGH",
                            message = diskMessage,
                            value = diskPercent.toFloat(),
                            serverId = preferencesManager.getDefaultServerId() ?: "unknown"
                        ))
                    } catch (e: Exception) { }
                }
            }
        } else {
            notificationManager.dismissDiskNotification()
            if (preferencesManager.getLastDiskAlertTime() > 0) {
                preferencesManager.saveLastDiskAlertTime(0)
            }
        }

        if (response.memoryPercent > preferencesManager.getMemoryThreshold().toDouble()) {
            val lastAlert = preferencesManager.getLastMemoryAlertTime()
            if (now - lastAlert > thirtyMinutes) {
                notificationManager.notifyMemoryHigh(response.memoryPercent.toFloat())
                preferencesManager.saveLastMemoryAlertTime(now)
                scope.launch {
                    try {
                        val dao = AppDatabase.getInstance(context).alertHistoryDao()
                        dao.insert(AlertHistory(
                            type = "MEMORY_HIGH",
                            message = "Memoria: ${String.format(java.util.Locale.US, "%.1f", response.memoryPercent)}%",
                            value = response.memoryPercent.toFloat(),
                            serverId = preferencesManager.getDefaultServerId() ?: "unknown"
                        ))
                    } catch (e: Exception) { }
                }
            }
        }
    }

    override fun dismissAll() {
        notificationManager.dismissCpuNotification()
        notificationManager.dismissDiskNotification()
        notificationManager.dismissMemoryNotification()
    }
}
