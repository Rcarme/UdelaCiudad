package com.tgmon.app.data.models

import com.google.gson.annotations.SerializedName

data class DockerContainer(
    @SerializedName("id")
    val id: String = "",
    
    @SerializedName("name")
    val name: String = "",
    
    // status/image made nullable-default for robustness: server may omit on cache-cold or future fields added;
    // Gson ignores unknown fields, defaults prevent crash without extra allocations (N97 friendly)
    @SerializedName("status")
    val status: String = "unknown",
    
    @SerializedName("image")
    val image: String = "unknown",
    
    @SerializedName("cpu_percent")
    val cpuPercent: Double = 0.0,
    
    @SerializedName("memory_mb")
    val memoryMb: Double = 0.0,
    
    @SerializedName("memory_percent")
    val memoryPercent: Double = 0.0,
    
    @SerializedName("uptime_seconds")
    val uptimeSeconds: Long = 0L,
    @SerializedName("uptime_hms")
    val uptimeHms: String = "",
    @SerializedName("health_status")
    val healthStatus: String = "none",
    
    @SerializedName("network_rx_mb")
    val networkRxMb: Double = 0.0,
    
    @SerializedName("network_tx_mb")
    val networkTxMb: Double = 0.0,
    
    @SerializedName("ports")
    val ports: List<ContainerPort>? = null
) {
    // Counts: isRunning includes restarting (server counts same); totals via list.size, no hardcode
    val isRunning: Boolean get() = status == "running" || status == "restarting"
    val displayName: String get() = name.removePrefix("/")
    val isPaused: Boolean get() = status == "paused"
    val isHealthy: Boolean get() = healthStatus == "healthy"
    val isUnhealthy: Boolean get() = healthStatus == "unhealthy"
    val firstPort: Int get() = ports?.firstOrNull()?.hostPort ?: 0
    val firstHttpPort: Int get() = ports?.find { it.containerPort == 80 || it.containerPort == 443 || it.containerPort == 8080 || it.containerPort == 8443 }?.hostPort ?: ports?.firstOrNull()?.hostPort ?: 0
}

data class ContainerPort(
    @SerializedName("host_port")
    val hostPort: Int,
    @SerializedName("container_port")
    val containerPort: Int,
    @SerializedName("protocol")
    val protocol: String = "tcp"
)

data class ContainerLogs(
    @SerializedName("container_id")
    val containerId: String,
    @SerializedName("logs")
    val logs: String
)

data class RestartResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("container_id")
    val containerId: String? = null,
    @SerializedName("message")
    val message: String? = null
)
