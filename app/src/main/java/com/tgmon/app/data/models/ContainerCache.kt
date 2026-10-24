package com.tgmon.app.data.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "container_cache")
data class ContainerCache(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    
    @ColumnInfo(name = "name")
    val name: String,
    
    @ColumnInfo(name = "status")
    val status: String,
    
    @ColumnInfo(name = "image")
    val image: String,
    
    @ColumnInfo(name = "cpu_percent")
    val cpuPercent: Double = 0.0,
    
    @ColumnInfo(name = "memory_mb")
    val memoryMb: Double = 0.0,
    
    @ColumnInfo(name = "memory_percent")
    val memoryPercent: Double = 0.0,
    
    @ColumnInfo(name = "uptime_seconds")
    val uptimeSeconds: Long = 0,
    
    @ColumnInfo(name = "health_status")
    val healthStatus: String = "none",
    
    @ColumnInfo(name = "network_rx_mb")
    val networkRxMb: Double = 0.0,
    
    @ColumnInfo(name = "network_tx_mb")
    val networkTxMb: Double = 0.0,
    
    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis()
)