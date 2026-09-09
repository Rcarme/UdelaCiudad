package com.tgmon.app.domain

import android.content.Context
import com.tgmon.app.data.models.HealthResponse
import com.tgmon.app.data.preferences.PreferencesManager
import com.tgmon.app.presentation.notifications.TgMonNotificationManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ContainerAlertManager(
    private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val notificationManager: TgMonNotificationManager
) {
    private val THROTTLE_MS = 5 * 60 * 1000L
    private val gson = Gson()

    data class ContainerEvent(val containerId: String, val containerName: String, val newStatus: String)

    fun checkContainerEvent(event: ContainerEvent) {
        val prev = preferencesManager.getContainerPreviousStatus(event.containerId)
        val now = event.newStatus
        if (prev == now) return

        preferencesManager.saveContainerPreviousStatus(event.containerId, now)

        if (now !in listOf("running", "restarting")) {
            val last = preferencesManager.getContainerLastAlert(event.containerId)
            if (System.currentTimeMillis() - last > THROTTLE_MS) {
                notificationManager.notifyContainerDown(event.containerName)
                preferencesManager.saveContainerLastAlert(event.containerId, System.currentTimeMillis())
            }
        } else if (prev !in listOf("running", "restarting")) {
            notificationManager.notifyContainerUp(event.containerName)
        }
    }
}
