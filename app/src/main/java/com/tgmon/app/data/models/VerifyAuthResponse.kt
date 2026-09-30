package com.tgmon.app.data.models

import com.google.gson.annotations.SerializedName

data class VerifyAuthResponse(
    @SerializedName("valid") val valid: Boolean = false,
    @SerializedName("role") val role: String = "admin"
) {
    val isAdmin: Boolean get() = role.equals("admin", ignoreCase = true)
}
