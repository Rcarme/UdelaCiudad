package com.tgmon.app.data.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "metric_history", indices = [Index(value = ["timestamp"])])
data class MetricHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "hostname")
    val hostname: String = "",
    
    @ColumnInfo(name = "cpu_percent")
    val cpuPercent: Double = 0.0,
    
    @ColumnInfo(name = "memory_percent")
    val memoryPercent: Double = 0.0,
    
    @ColumnInfo(name = "disk_used_gb")
    val diskUsedGb: Double = 0.0,
    
    @ColumnInfo(name = "disk_free_gb")
    val diskFreeGb: Double = 0.0,
    
    @ColumnInfo(name = "disk_total_gb")
    val diskTotalGb: Double = 0.0,
    
    @ColumnInfo(name = "disk_percent")
    val diskPercent: Double = 0.0
) {
    companion object {
        @JvmStatic
        fun calculateDiskPercent(used: Double, total: Double): Double {
            return if (total > 0.0) (used / total) * 100.0 else 0.0
        }
    }
}
