package com.tgmon.app.data.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class AlertHistoryTest {

    @Test
    fun testAlertHistory_creationAndDefaults() {
        val alert = AlertHistory.create(
            timestamp = 1725700000000L,
            type = "CPU_HIGH",
            message = "CPU: 95.0%",
            value = 95.0f,
            serverId = "server-main"
        )

        assertNotNull(alert)
        assertEquals(0L, alert.id)
        assertEquals(1725700000000L, alert.timestamp)
        assertEquals("CPU_HIGH", alert.type)
        assertEquals("CPU: 95.0%", alert.message)
        assertEquals(95.0f, alert.value, 0.001f)
        assertEquals("server-main", alert.serverId)
        assertNull(alert.containerId)
    }

    @Test
    fun testAlertHistory_withContainerId() {
        val alert = AlertHistory.create(
            timestamp = 1725700000000L,
            type = "CONTAINER_EXITED",
            message = "Container nginx exited",
            value = 1.0f,
            serverId = "server-main",
            containerId = "c1234567890ab"
        )

        assertEquals("c1234567890ab", alert.containerId)
        assertEquals("CONTAINER_EXITED", alert.type)
    }

    @Test
    fun testAlertHistory_diskAndMemoryTypes() {
        val diskAlert = AlertHistory(
            id = 5L,
            timestamp = 1725800000000L,
            type = "DISK_HIGH",
            message = "Disco (/dev/sda1): 94.5%",
            value = 94.5f,
            serverId = "server-remote",
            containerId = null
        )
        assertEquals(5L, diskAlert.id)
        assertEquals("DISK_HIGH", diskAlert.type)
        assertEquals(94.5f, diskAlert.value, 0.001f)

        val memAlert = AlertHistory(
            id = 6L,
            timestamp = 1725800001000L,
            type = "MEMORY_HIGH",
            message = "Memoria: 88.0%",
            value = 88.0f,
            serverId = "server-local",
            containerId = null
        )
        assertEquals("MEMORY_HIGH", memAlert.type)
        assertEquals(88.0f, memAlert.value, 0.001f)
    }

    @Test
    fun testAlertHistory_containerRecoveryType() {
        val alert = AlertHistory.create(
            timestamp = 1725900000000L,
            type = "CONTAINER_RECOVERED",
            message = "Container redis recovered",
            value = 0.0f,
            serverId = "server-main",
            containerId = "container-redis-id"
        )
        assertEquals("CONTAINER_RECOVERED", alert.type)
        assertEquals("container-redis-id", alert.containerId)
        assertEquals(0.0f, alert.value, 0.001f)
    }
}
