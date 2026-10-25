package com.tgmon.app.data.models

data class AIPredictionResponse(
    val risk_level: String,
    val message: String,
    val cpu_usage: Double? = null,
    val memory_usage: Double? = null
)
