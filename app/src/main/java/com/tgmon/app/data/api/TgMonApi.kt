package com.tgmon.app.data.api

import com.tgmon.app.data.models.HealthResponse
import com.tgmon.app.data.models.StatusResponse
import com.tgmon.app.data.models.DockerContainer
import com.tgmon.app.data.models.ContainerListResponse
import com.tgmon.app.data.models.ContainerLogs
import com.tgmon.app.data.models.RestartResponse
import com.tgmon.app.data.models.ContainerDetails
import com.tgmon.app.data.models.MetricHistoryDto
import com.tgmon.app.data.models.ServiceUrlsResponse
import com.tgmon.app.data.models.VerifyAuthResponse
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.DELETE
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Header
import com.google.gson.JsonElement

interface TgMonApi {
    @GET("/health")
    suspend fun getHealth(): HealthResponse

    @GET("/status")
    suspend fun getStatus(): StatusResponse

    @GET("/docker/containers")
    suspend fun listContainers(): ContainerListResponse

    @POST("/docker/containers/{container_id}/restart")
    suspend fun restartContainer(
        @Path("container_id") containerId: String
    ): RestartResponse

    @POST("/docker/containers/{container_id}/stop")
    suspend fun stopContainer(
        @Path("container_id") containerId: String
    ): RestartResponse

    @POST("/docker/containers/{container_id}/start")
    suspend fun startContainer(
        @Path("container_id") containerId: String
    ): RestartResponse

    @DELETE("/docker/containers/{container_id}")
    suspend fun removeContainer(
        @Path("container_id") containerId: String,
        @Query("force") force: Boolean = false
    ): RestartResponse

    @GET("/docker/containers/{container_id}/logs")
    suspend fun getContainerLogs(
        @Path("container_id") containerId: String,
        @Query("lines") lines: Int = 50
    ): ContainerLogs

    @POST("/docker/containers/{container_id}/pause")
    suspend fun pauseContainer(
        @Path("container_id") containerId: String
    ): RestartResponse

    @POST("/docker/containers/{container_id}/unpause")
    suspend fun unpauseContainer(
        @Path("container_id") containerId: String
    ): RestartResponse

    @GET("/docker/containers/{container_id}/details")
    suspend fun getContainerDetails(
        @Path("container_id") containerId: String
    ): ContainerDetails

    @GET("/docker/service-urls")
    suspend fun getServiceUrls(): ServiceUrlsResponse

    @GET("/history")
    suspend fun getHistory(
        @Query("hours") hours: Int = 24
    ): List<MetricHistoryDto>

    @GET("/history/csv")
    suspend fun getHistoryCsv(
        @Query("hours") hours: Int = 24
    ): okhttp3.ResponseBody

    @GET("/auth/verify")
    suspend fun verifyAuth(): VerifyAuthResponse

    @POST("/auth/invalidate")
    suspend fun invalidateAuth(): Map<String, Any>

    // IA: Predicción de picos de CPU y RAM
    @GET("/ai/predict-spike")
    suspend fun getAiPrediction(
        @Query("use_llm") useLlm: Boolean = false
    ): JsonElement

    // IA: Recomendaciones de contenedores inactivos
    @GET("/ai/recommendations")
    suspend fun getAiRecommendations(
        @Query("use_llm") useLlm: Boolean = false
    ): JsonElement
}