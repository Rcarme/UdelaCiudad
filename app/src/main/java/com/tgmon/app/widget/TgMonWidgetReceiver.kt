package com.tgmon.app.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class TgMonWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TgMonWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        val workRequest = OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "widget_update_on_add",
            androidx.work.ExistingWorkPolicy.REPLACE,
            workRequest
        )

        val periodicWorkRequest = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "widget_periodic_update",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWorkRequest
        )
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork("widget_update_on_add")
        WorkManager.getInstance(context).cancelUniqueWork("widget_periodic_update")
    }
}
