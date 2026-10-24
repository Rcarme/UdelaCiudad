package com.tgmon.app.data.models

import com.google.gson.annotations.SerializedName

data class MetricHistoryDto(
    @SerializedName("timestamp") val timestamp: Long = 0L,
    @SerializedName("cpu_percent") val cpuPercent: Double = 0.0,
    @SerializedName("memory_percent") val memoryPercent: Double = 0.0,
    @SerializedName("disk_used_gb") val diskUsedGb: Double = 0.0,
    @SerializedName("disk_free_gb") val diskFreeGb: Double = 0.0,
    @SerializedName("disk_total_gb") val diskTotalGb: Double = 0.0,
    @SerializedName("disk_percent") val diskPercent: Double = 0.0
)
