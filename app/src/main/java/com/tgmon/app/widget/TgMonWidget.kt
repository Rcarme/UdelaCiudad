package com.tgmon.app.widget

import android.content.Context
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.util.concurrent.TimeUnit

class TgMonWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                WidgetContent()
            }
        }
    }

    @Composable
    private fun WidgetContent() {
        val prefs = currentState<androidx.datastore.preferences.core.Preferences>()
        val hostname = prefs[WidgetPreferences.hostnameKey] ?: "Unknown"
        val isOnline = prefs[WidgetPreferences.isOnlineKey] ?: false
        val cpuPercent = prefs[WidgetPreferences.cpuKey] ?: 0.0
        val memPercent = prefs[WidgetPreferences.memKey] ?: 0.0
        val diskPercent = prefs[WidgetPreferences.diskKey] ?: 0.0
        val loadAvg = prefs[WidgetPreferences.loadAvgKey] ?: "N/A"
        val lastUpdateTimestamp = prefs[WidgetPreferences.lastUpdateTimestampKey] ?: 0L
        val cpuThreshold = prefs[WidgetPreferences.cpuThresholdKey] ?: 80.0
        val memThreshold = prefs[WidgetPreferences.memThresholdKey] ?: 80.0
        val diskThreshold = prefs[WidgetPreferences.diskThresholdKey] ?: 90.0

        val timeSinceUpdate = if (lastUpdateTimestamp > 0) {
            val diff = System.currentTimeMillis() - lastUpdateTimestamp
            val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
            when {
                minutes < 1 -> "<1 min ago"
                minutes < 60 -> "$minutes min ago"
                else -> "${minutes / 60}h ${minutes % 60}m ago"
            }
        } else {
            "Not updated"
        }

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.surface)
                .padding(10.dp)
                .clickable(actionRunCallback<RefreshAction>()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusDot(isOnline = isOnline)
                Spacer(modifier = GlanceModifier.width(8.dp))
                Text(
                    text = hostname,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = GlanceModifier.defaultWeight()
                )
            }

            Spacer(modifier = GlanceModifier.height(8.dp))

            if (!isOnline) {
                Text(
                    text = "Offline",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                )
            } else {
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MetricBox(
                        title = "CPU",
                        value = "${cpuPercent.toInt()}%",
                        color = getMetricColor(cpuPercent, cpuThreshold),
                        modifier = GlanceModifier.defaultWeight()
                    )
                    Spacer(modifier = GlanceModifier.width(4.dp))
                    MetricBox(
                        title = "RAM",
                        value = "${memPercent.toInt()}%",
                        color = getMetricColor(memPercent, memThreshold),
                        modifier = GlanceModifier.defaultWeight()
                    )
                    Spacer(modifier = GlanceModifier.width(4.dp))
                    MetricBox(
                        title = "DISK",
                        value = "${diskPercent.toInt()}%",
                        color = getMetricColor(diskPercent, diskThreshold),
                        modifier = GlanceModifier.defaultWeight()
                    )
                    Spacer(modifier = GlanceModifier.width(4.dp))
                    MetricBox(
                        title = "LOAD",
                        value = loadAvg,
                        color = GlanceTheme.colors.primary,
                        modifier = GlanceModifier.defaultWeight()
                    )
                }

                Spacer(modifier = GlanceModifier.height(6.dp))

                Text(
                    text = timeSinceUpdate,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                )
            }

            Spacer(modifier = GlanceModifier.height(4.dp))

            Text(
                text = "Tap to refresh",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 9.sp
                )
            )
        }
    }

    @Composable
    private fun StatusDot(isOnline: Boolean) {
        val dotColor = if (isOnline) ColorProvider(android.graphics.Color.parseColor("#4CAF50")) else ColorProvider(android.graphics.Color.parseColor("#F44336"))
        Box(
            modifier = GlanceModifier
                .size(10.dp)
                .background(dotColor),
            content = {
                Text(text = "", style = TextStyle(fontSize = 1.sp))
            }
        )
    }

    @Composable
    private fun getMetricColor(percent: Double, threshold: Double): ColorProvider {
        val warningThreshold = threshold * 0.75
        return when {
            percent < warningThreshold -> GlanceTheme.colors.primary
            percent < threshold -> GlanceTheme.colors.onSurface
            else -> GlanceTheme.colors.error
        }
    }

    @Composable
    private fun MetricBox(title: String, value: String, color: ColorProvider, modifier: GlanceModifier = GlanceModifier) {
        Column(
            modifier = modifier
                .background(GlanceTheme.colors.surfaceVariant)
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 9.sp
                )
            )
            Text(
                text = value,
                style = TextStyle(
                    color = color,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}
