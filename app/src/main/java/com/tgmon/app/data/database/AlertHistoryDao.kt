package com.tgmon.app.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tgmon.app.data.models.AlertHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alert: AlertHistory)

    @Query("SELECT * FROM alert_history ORDER BY timestamp DESC, id DESC LIMIT :limit OFFSET :offset")
    fun getAll(limit: Int = 100, offset: Int = 0): Flow<List<AlertHistory>>

    @Query("SELECT * FROM alert_history WHERE timestamp > :since ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getSince(since: Long, limit: Int): List<AlertHistory>

    @Query("DELETE FROM alert_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM alert_history WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM alert_history")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM alert_history WHERE timestamp > :since")
    suspend fun countSince(since: Long): Int
}
