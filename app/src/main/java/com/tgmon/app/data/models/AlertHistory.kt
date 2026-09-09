package com.tgmon.app.data.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "alert_history", indices = [Index(value = ["timestamp"]), Index(value = ["server_id", "timestamp"])])
data class AlertHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val type: String = "",
    val message: String = "",
    val value: Float = 0.0f,
    @ColumnInfo(name = "server_id") val serverId: String = "",
    @ColumnInfo(name = "container_id") val containerId: String? = null
) {
    companion object {
        @JvmStatic
        fun create(
            timestamp: Long = System.currentTimeMillis(),
            type: String,
            message: String,
            value: Float,
            serverId: String,
            containerId: String? = null
        ): AlertHistory {
            return AlertHistory(
                timestamp = timestamp,
                type = type,
                message = message,
                value = value,
                serverId = serverId,
                containerId = containerId
            )
        }
    }
}
