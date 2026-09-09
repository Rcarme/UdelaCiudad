package com.tgmon.app.data.models

import com.google.gson.annotations.SerializedName
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import java.lang.reflect.Type

/**
 * Wrapper for GET /docker/containers response.
 * Server returns {"containers": [...], "cache_timestamp": float, "cache_age_seconds": int|null}
 * Previously app expected bare List<DockerContainer> causing Gson failure -> "no services detected".
 * This wrapper fixes the mismatch. Nullable cache fields tolerate missing/cache-cold responses.
 * Low-resource: no extra allocations beyond response list; defaults avoid null checks in UI.
 * Backward compat: deserializer handles both object wrapper AND bare list if server rolls back.
 */
data class ContainerListResponse(
    @SerializedName("containers")
    val containers: List<DockerContainer> = emptyList(),

    // Server sends time.time() as float (double). We store as Long (seconds truncated)
    // to match spec; deserializer handles Double->Long truncation safely. Null if cache cold.
    @SerializedName("cache_timestamp")
    val cacheTimestamp: Long? = null,

    @SerializedName("cache_age_seconds")
    val cacheAgeSeconds: Int? = null
) {
    // Convenience for callers needing Double precision (e.g., cache age calc)
    val cacheTimestampDouble: Double? get() = cacheTimestamp?.toDouble()
}

/**
 * N97-friendly deserializer: handles both {"containers": [...]} object and bare [...] list.
 * No extra allocations: reuses Gson's default list deserialization for containers.
 */
class ContainerListResponseDeserializer : JsonDeserializer<ContainerListResponse> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): ContainerListResponse {
        try {
            // Backward compat: server might return bare list
            if (json.isJsonArray) {
                val list = context.deserialize<List<DockerContainer>>(
                    json,
                    object : com.google.gson.reflect.TypeToken<List<DockerContainer>>() {}.type
                ) ?: emptyList()
                return ContainerListResponse(containers = list)
            }
            if (json.isJsonObject) {
                val obj = json.asJsonObject
                // Also handle case where server returns {"containers": null}
                val containersEl = obj.get("containers")
                val containers: List<DockerContainer> = when {
                    containersEl == null || containersEl.isJsonNull -> emptyList()
                    containersEl.isJsonArray -> context.deserialize(
                        containersEl,
                        object : com.google.gson.reflect.TypeToken<List<DockerContainer>>() {}.type
                    ) ?: emptyList()
                    else -> emptyList()
                }
                // cache_timestamp may be float, long, or null — read as Long safely (truncates double)
                val ts: Long? = try {
                    val el = obj.get("cache_timestamp")
                    when {
                        el == null || el.isJsonNull -> null
                        el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asLong // truncates double if present
                        else -> null
                    }
                } catch (_: Exception) {
                    // Fallback: try double then truncate
                    try {
                        val el = obj.get("cache_timestamp")
                        if (el != null && !el.isJsonNull && el.isJsonPrimitive && el.asJsonPrimitive.isNumber) el.asDouble.toLong() else null
                    } catch (_: Exception) { null }
                }
                val age: Int? = try {
                    val el = obj.get("cache_age_seconds")
                    when {
                        el == null || el.isJsonNull -> null
                        el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asInt
                        else -> null
                    }
                } catch (_: Exception) { null }
                return ContainerListResponse(containers = containers, cacheTimestamp = ts, cacheAgeSeconds = age)
            }
        } catch (e: Exception) {
            throw JsonParseException("Failed to parse ContainerListResponse: ${e.message}", e)
        }
        throw JsonParseException("Unexpected JSON type for ContainerListResponse")
    }
}
