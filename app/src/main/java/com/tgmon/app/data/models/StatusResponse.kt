package com.tgmon.app.data.models

import com.google.gson.annotations.SerializedName

data class StatusResponse(
    @SerializedName("uptime_seconds")
    val uptimeSeconds: Long,
    @SerializedName("load_avg")
    val loadAvg: List<Double>?,
    @SerializedName("docker_active")
    val dockerActive: Boolean,
    @SerializedName("ssh_active")
    val sshActive: Boolean
)
