package com.tgmon.app.data.models

import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContainerListResponseTest {

    private lateinit var gson: com.google.gson.Gson

    @Before
    fun setUp() {
        gson = GsonBuilder()
            .registerTypeAdapter(ContainerListResponse::class.java, ContainerListResponseDeserializer())
            .create()
    }

    @Test
    fun testDeserialize_wrappedObjectFormat() {
        val json = """
            {
                "containers": [
                    {
                        "id": "abc123def456",
                        "name": "web-server",
                        "status": "running",
                        "image": "nginx:alpine",
                        "cpu_percent": 12.5,
                        "memory_mb": 64.0,
                        "memory_percent": 3.2,
                        "uptime_seconds": 3600
                    }
                ],
                "cache_timestamp": 1725000000.55,
                "cache_age_seconds": 2
            }
        """.trimIndent()

        val response = gson.fromJson(json, ContainerListResponse::class.java)
        assertNotNull(response)
        assertEquals(1, response.containers.size)
        assertEquals("abc123def456", response.containers[0].id)
        assertEquals("web-server", response.containers[0].name)
        assertEquals("running", response.containers[0].status)
        assertEquals(12.5, response.containers[0].cpuPercent, 0.001)
        assertEquals(1725000000L, response.cacheTimestamp)
        assertEquals(2, response.cacheAgeSeconds)
    }

    @Test
    fun testDeserialize_legacyBareArrayFormat() {
        val json = """
            [
                {
                    "id": "111222333444",
                    "name": "redis-cache",
                    "status": "running",
                    "image": "redis:7",
                    "cpu_percent": 1.0,
                    "memory_mb": 32.0,
                    "memory_percent": 1.5,
                    "uptime_seconds": 7200
                },
                {
                    "id": "555666777888",
                    "name": "worker-task",
                    "status": "exited",
                    "image": "worker:latest",
                    "cpu_percent": 0.0,
                    "memory_mb": 0.0,
                    "memory_percent": 0.0,
                    "uptime_seconds": 0
                }
            ]
        """.trimIndent()

        val response = gson.fromJson(json, ContainerListResponse::class.java)
        assertNotNull(response)
        assertEquals(2, response.containers.size)
        assertEquals("redis-cache", response.containers[0].name)
        assertEquals("worker-task", response.containers[1].name)
        assertNull(response.cacheTimestamp)
        assertNull(response.cacheAgeSeconds)
    }

    @Test
    fun testDeserialize_emptyObjectAndNullContainers() {
        val jsonEmpty = "{}"
        val responseEmpty = gson.fromJson(jsonEmpty, ContainerListResponse::class.java)
        assertNotNull(responseEmpty)
        assertTrue(responseEmpty.containers.isEmpty())

        val jsonNull = """{"containers": null}"""
        val responseNull = gson.fromJson(jsonNull, ContainerListResponse::class.java)
        assertNotNull(responseNull)
        assertTrue(responseNull.containers.isEmpty())
    }

    @Test
    fun testDeserialize_cacheTimestampDoubleConvenience() {
        val response = ContainerListResponse(
            containers = emptyList(),
            cacheTimestamp = 1720000000L,
            cacheAgeSeconds = 5
        )
        assertEquals(1720000000.0, response.cacheTimestampDouble!!, 0.001)
    }
}
