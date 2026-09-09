package com.tgmon.app.data.database

import android.util.Log
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.tgmon.app.data.models.MetricHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface MetricHistoryDao {

    @Insert
    @Transaction
    suspend fun insert(history: MetricHistory)

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    @Transaction
    suspend fun insertAll(metrics: List<MetricHistory>)

    @Query("SELECT timestamp FROM metric_history WHERE timestamp >= :sinceTimestamp")
    suspend fun getExistingTimestamps(sinceTimestamp: Long): List<Long>

    @Query("SELECT * FROM metric_history ORDER BY timestamp DESC, id DESC LIMIT :count")
    fun getLastNMetrics(count: Int): Flow<List<MetricHistory>>

    @Query("SELECT * FROM metric_history WHERE timestamp > :afterTime ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getMetricsAfter(afterTime: Long, limit: Int): List<MetricHistory>

    @Query("DELETE FROM metric_history WHERE timestamp < :beforeTime")
    @Transaction
    suspend fun deleteOldMetrics(beforeTime: Long)

    @Query("SELECT * FROM metric_history ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun getLastMetric(): MetricHistory?

    @Query("DELETE FROM metric_history")
    @Transaction
    suspend fun clearAll()
}
