package com.tgmon.app.data.models

import com.google.gson.annotations.SerializedName

/** Punto proyectado por el servicio Python (/ai/forecast), con intervalo de predicción 95%. */
data class ForecastPoint(
    @SerializedName("timestamp") val timestamp: Long = 0L,
    @SerializedName("value") val value: Double = 0.0,
    @SerializedName("lower") val lower: Double = 0.0,
    @SerializedName("upper") val upper: Double = 0.0
)

data class MetricForecast(
    @SerializedName("metric") val metric: String = "",
    @SerializedName("slope_per_min") val slopePerMin: Double = 0.0,
    @SerializedName("r_squared") val rSquared: Double = 0.0,
    @SerializedName("trend") val trend: String = "stable",
    @SerializedName("threshold") val threshold: Double = 85.0,
    @SerializedName("minutes_to_threshold") val minutesToThreshold: Double? = null,
    @SerializedName("points") val points: List<ForecastPoint> = emptyList()
)

data class ForecastResponse(
    @SerializedName("available") val available: Boolean = false,
    @SerializedName("samples_used") val samplesUsed: Int = 0,
    @SerializedName("window_minutes") val windowMinutes: Int = 60,
    @SerializedName("horizon_minutes") val horizonMinutes: Int = 30,
    @SerializedName("method") val method: String = "ols_linear_regression",
    @SerializedName("cpu") val cpu: MetricForecast? = null,
    @SerializedName("memory") val memory: MetricForecast? = null
)
