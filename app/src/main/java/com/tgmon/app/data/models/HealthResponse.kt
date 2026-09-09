package com.tgmon.app.data.models

import com.google.gson.annotations.SerializedName

data class HealthResponse(
    // Hostname is UTF-8 string from server (server ensures env override + bytes decode); default "unknown" avoids hex container ID display
    @SerializedName("hostname")
    val hostname: String = "unknown",
    @SerializedName("cpu_percent")
    val cpuPercent: Double = 0.0,
    @SerializedName("memory_percent")
    val memoryPercent: Double = 0.0,
    @SerializedName("memory_total_gb")
    val memoryTotalGb: Double? = null,
    @SerializedName("disk_used_gb")
    val diskUsedGb: Double = 0.0,
    @SerializedName("disk_free_gb")
    val diskFreeGb: Double = 0.0,
    @SerializedName("disk_total_gb")
    val diskTotalGb: Double? = null,
    @SerializedName("disk_percent")
    val diskPercent: Double? = null,
    @SerializedName("net_recv_mb")
    val netRecvMb: Double? = null,
    @SerializedName("net_sent_mb")
    val netSentMb: Double? = null,
    @SerializedName("disk_read_mb")
    val diskReadMb: Double? = null,
    @SerializedName("disk_write_mb")
    val diskWriteMb: Double? = null,
    @SerializedName("docker")
    val docker: DockerSummary? = null,
    @SerializedName("uptime_seconds")
    val uptimeSeconds: Long? = null,
    @SerializedName("load_avg")
    val loadAvg: List<Double>? = null,
    @SerializedName("disks")
    val disks: List<DiskInfo>? = null
)

data class DiskInfo(
    @SerializedName("device") val device: String = "",
    @SerializedName("mountpoint") val mountpoint: String = "",
    @SerializedName("fstype") val fstype: String = "",
    @SerializedName("total_gb") val totalGb: Double = 0.0,
    @SerializedName("used_gb") val usedGb: Double = 0.0,
    @SerializedName("free_gb") val freeGb: Double = 0.0,
    @SerializedName("percent") val percent: Double = 0.0
)

data class DockerSummary(
    @SerializedName("total")
    val total: Int = 0,
    @SerializedName("running")
    val running: Int = 0,
    @SerializedName("restarting")
    val restarting: Int = 0,
    @SerializedName("stopped")
    val stopped: Int = 0
)
