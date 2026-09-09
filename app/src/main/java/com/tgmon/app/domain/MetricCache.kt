package com.tgmon.app.domain

import com.tgmon.app.data.models.HealthResponse
import com.tgmon.app.data.models.MetricHistory
import kotlinx.coroutines.flow.Flow

interface MetricCache {
    suspend fun getLastMetric(): MetricHistory?
    suspend fun insertMetric(metric: MetricHistory)
    fun getLastNMetricsFlow(count: Int): Flow<List<MetricHistory>>?
    suspend fun deleteOldMetrics(beforeTime: Long)
    suspend fun clearAll()
}
