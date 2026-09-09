package com.tgmon.app.data.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "container_logs", indices = [Index(value = ["timestamp"]), Index(value = ["container_id"]), Index(value = ["container_id", "timestamp"])])
data class ContainerLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    @ColumnInfo(name = "container_id")
    val containerId: String = "",
    
    @ColumnInfo(name = "container_name")
    val containerName: String = "",
    
    @ColumnInfo(name = "logs")
    val logs: String = "",
    
    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val MAX_LOGS_SIZE = 65535
        const val DEFAULT_LOGS_SIZE = 50000
        
        @JvmStatic
        fun truncateLogs(logs: String, maxSize: Int = DEFAULT_LOGS_SIZE): String {
            return if (logs.length > maxSize) {
                logs.takeLast(maxSize)
            } else {
                logs
            }
        }
    }
}
