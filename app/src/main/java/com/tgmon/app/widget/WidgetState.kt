package com.tgmon.app.widget

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

object WidgetPreferences {
    val hostnameKey = stringPreferencesKey("hostname")
    val isOnlineKey = booleanPreferencesKey("isOnline")
    val cpuKey = doublePreferencesKey("cpu")
    val memKey = doublePreferencesKey("mem")
    val lastUpdateTimestampKey = longPreferencesKey("lastUpdateTimestamp")
    val diskKey = doublePreferencesKey("disk")
    val loadAvgKey = stringPreferencesKey("load_avg")
    val cpuThresholdKey = doublePreferencesKey("cpu_threshold")
    val memThresholdKey = doublePreferencesKey("mem_threshold")
    val diskThresholdKey = doublePreferencesKey("disk_threshold")
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        // Run WorkManager to force update
        val workRequest = OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "widget_update_manual",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }
}
