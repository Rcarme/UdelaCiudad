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
}
