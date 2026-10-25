package com.tgmon.app.data.models

data class ContainerRecommendation(
    val container_id: String,
    val container_name: String,
    val recommended_action: String,
    val reason: String
)
