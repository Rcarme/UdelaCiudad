package com.tgmon.app.domain

import com.tgmon.app.data.models.HealthResponse

interface AlertManager {
    fun checkAndNotify(response: HealthResponse)
    fun dismissAll()
}
