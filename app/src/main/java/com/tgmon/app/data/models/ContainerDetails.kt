package com.tgmon.app.data.models

import com.google.gson.annotations.SerializedName

data class ContainerDetails(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("image") val image: String,
    @SerializedName("status") val status: String,
    @SerializedName("health_status") val healthStatus: String = "none",
    @SerializedName("created") val created: String = "",
    @SerializedName("restart_count") val restartCount: Int = 0,
    @SerializedName("uptime_seconds") val uptimeSeconds: Long = 0,
    @SerializedName("uptime_hms") val uptimeHms: String = "",
    @SerializedName("ports") val ports: List<PortMapping> = emptyList(),
    @SerializedName("networks") val networks: List<String> = emptyList(),
    @SerializedName("volumes") val volumes: List<String> = emptyList(),
    @SerializedName("env_vars") val envVars: Map<String, String> = emptyMap(),
    @SerializedName("cpu_percent") val cpuPercent: Double = 0.0,
    @SerializedName("memory_mb") val memoryMb: Double = 0.0,
    @SerializedName("memory_percent") val memoryPercent: Double = 0.0,
    @SerializedName("network_rx_mb") val networkRxMb: Double = 0.0,
    @SerializedName("network_tx_mb") val networkTxMb: Double = 0.0
)

data class PortMapping(
    @SerializedName("host_port") val hostPort: Int,
    @SerializedName("container_port") val containerPort: Int,
    @SerializedName("protocol") val protocol: String = "tcp",
    @SerializedName("type") val type: String = "http"
)

data class ServiceUrlsResponse(
    @SerializedName("service_urls") val serviceUrls: List<ServiceUrlDto>
)

data class ServiceUrlDto(
    @SerializedName("container") val container: String,
    @SerializedName("public_port") val publicPort: Int,
    @SerializedName("protocol") val protocol: String
)
