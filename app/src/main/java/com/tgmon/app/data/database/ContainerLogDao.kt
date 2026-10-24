package com.tgmon.app.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tgmon.app.data.models.ContainerLog
import kotlinx.coroutines.flow.Flow

@Dao
interface ContainerLogDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ContainerLog)
    
    @Query("SELECT * FROM container_logs WHERE container_id = :containerId ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun getLatestLog(containerId: String): ContainerLog?
    
    @Query("SELECT * FROM container_logs WHERE container_id = :containerId ORDER BY timestamp DESC, id DESC LIMIT 1")
    fun getLatestLogFlow(containerId: String): Flow<ContainerLog?>
    
    @Query("DELETE FROM container_logs WHERE timestamp < :beforeTime")
    suspend fun deleteOldLogs(beforeTime: Long)
    
    @Query("DELETE FROM container_logs WHERE container_id = :containerId")
    suspend fun deleteLogsForContainer(containerId: String)
    
    @Query("DELETE FROM container_logs")
    suspend fun clearAll()
}
