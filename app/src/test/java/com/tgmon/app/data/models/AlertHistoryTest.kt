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
}
