package com.tgmon.app.data.models

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DockerContainerTest {

    private val gson = Gson()

    @Test
    fun testRestartResponse_withContainerId() {
        val json = """{"success":true,"container_id":"abc12345","message":"Restarted"}"""
        val response = gson.fromJson(json, RestartResponse::class.java)
        assertTrue(response.success)
        assertEquals("abc12345", response.containerId)
        assertEquals("Restarted", response.message)
    }

    @Test
    fun testRestartResponse_withoutContainerId_doesNotThrow() {
        val json = """{"success":true,"message":"Deleted container"}"""
        val response = gson.fromJson(json, RestartResponse::class.java)
        assertTrue(response.success)
        assertNull(response.containerId)
        assertEquals("Deleted container", response.message)
    }

    @Test
    fun testRestartResponse_minimal() {
        val json = """{"success":true}"""
        val response = gson.fromJson(json, RestartResponse::class.java)
        assertTrue(response.success)
        assertNull(response.containerId)
        assertNull(response.message)
    }

    @Test
    fun testDiskCalculationPrecedence() {
        val diskUsedGb = 20.0
        val diskFreeGb = 80.0
        val diskTotalGb: Double? = null

        // Correct precedence: diskTotalGb ?: ((diskUsedGb ?: 0.0) + (diskFreeGb ?: 0.0))
        val diskTotal = diskTotalGb ?: ((diskUsedGb ?: 0.0) + (diskFreeGb ?: 0.0))
        assertEquals(100.0, diskTotal, 0.001)

        val diskPercent = MetricHistory.calculateDiskPercent(diskUsedGb, diskTotal)
        assertEquals(20.0, diskPercent, 0.001)
    }

    @Test
    fun testDiskCalculation_whenTotalGiven() {
        val diskUsedGb = 25.0
        val diskFreeGb = 75.0
        val diskTotalGb = 200.0

        val diskTotal = diskTotalGb ?: ((diskUsedGb ?: 0.0) + (diskFreeGb ?: 0.0))
        assertEquals(200.0, diskTotal, 0.001)

        val diskPercent = MetricHistory.calculateDiskPercent(diskUsedGb, diskTotal)
        assertEquals(12.5, diskPercent, 0.001)
    }

    @Test
    fun testDockerContainer_fullPropertiesDeserialization() {
        val json = """
            {
                "id": "1234567890abcdef",
                "name": "nginx-proxy",
                "status": "running",
                "image": "nginx:latest",
                "cpu_percent": 5.4,
                "memory_mb": 128.0,
                "memory_percent": 1.25,
                "uptime_seconds": 86400,
                "health_status": "healthy",
                "ports": [
                    {
                        "host_port": 8080,
                        "container_port": 80,
                        "protocol": "tcp"
                    }
                ]
            }
        """.trimIndent()

        val container = gson.fromJson(json, DockerContainer::class.java)
        assertNotNull(container)
        assertEquals("1234567890abcdef", container.id)
        assertEquals("nginx-proxy", container.name)
        assertEquals("running", container.status)
        assertEquals("nginx:latest", container.image)
        assertEquals(5.4, container.cpuPercent, 0.001)
        assertEquals(128.0, container.memoryMb, 0.001)
        assertEquals(1.25, container.memoryPercent, 0.001)
        assertEquals(86400L, container.uptimeSeconds)
        assertEquals("healthy", container.healthStatus)
        assertEquals(8080, container.firstPort)
        assertEquals(8080, container.firstHttpPort)
        assertTrue(container.isRunning)
        assertTrue(container.isHealthy)
    }

    @Test
    fun testDockerContainer_nullAndDefaults() {
        val json = """{"id": "abc"}"""
        val container = gson.fromJson(json, DockerContainer::class.java)
        assertNotNull(container)
        assertEquals("abc", container.id)
        assertEquals(0.0, container.cpuPercent, 0.001)
        assertEquals(0.0, container.memoryMb, 0.001)
        assertEquals(0L, container.uptimeSeconds)
    }
}
