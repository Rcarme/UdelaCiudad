package com.tgmon.app.presentation.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.tgmon.app.R

class TgMonNotificationManager(private val context: Context) {
    
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    init {
        createNotificationChannels()
    }
    
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Alert channel
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "System Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts for CPU and Disk thresholds"
                enableVibration(true)
            }
            
            // Info channel
            val infoChannel = NotificationChannel(
                INFO_CHANNEL_ID,
                "System Info",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "General system information"
            }
            
            notificationManager.createNotificationChannel(alertChannel)
            notificationManager.createNotificationChannel(infoChannel)
        }
    }
    
    fun notifyCpuHigh(cpuPercent: Float) {
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setContentTitle("High CPU Usage ⚠️")
            .setContentText("CPU: ${String.format("%.1f", cpuPercent)}%")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(NOTIFICATION_CPU_ID, notification)
    }
    
    fun notifyDiskHigh(diskPercent: Float) {
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setContentTitle("High Disk Usage ⚠️")
            .setContentText("Disk: ${String.format("%.1f", diskPercent)}%")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(NOTIFICATION_DISK_ID, notification)
    }

    fun notifyMemoryHigh(memoryPercent: Float) {
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setContentTitle("High Memory Usage ⚠️")
            .setContentText("Memoria: ${String.format("%.1f", memoryPercent)}%")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_MEMORY_ID, notification)
    }
    
    fun dismissCpuNotification() {
        notificationManager.cancel(NOTIFICATION_CPU_ID)
    }
    
    fun dismissDiskNotification() {
        notificationManager.cancel(NOTIFICATION_DISK_ID)
    }

    fun dismissMemoryNotification() {
        notificationManager.cancel(NOTIFICATION_MEMORY_ID)
    }
    
    fun notifyContainerDown(name: String) {
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setContentTitle("Container Down: $name")
            .setContentText("Container $name ha dejado de funcionar")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_CONTAINER_BASE + name.hashCode() % 100, notification)
    }

    fun notifyContainerUp(name: String) {
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setContentTitle("Container Recovered: $name")
            .setContentText("Container $name ha vuelto a funcionar")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_CONTAINER_BASE + name.hashCode() % 100, notification)
    }
    
    companion object {
        const val ALERT_CHANNEL_ID = "alert_channel"
        const val INFO_CHANNEL_ID = "info_channel"
        const val NOTIFICATION_CPU_ID = 1
        const val NOTIFICATION_DISK_ID = 2
        const val NOTIFICATION_MEMORY_ID = 3
        const val NOTIFICATION_CONTAINER_BASE = 10
    }
}
