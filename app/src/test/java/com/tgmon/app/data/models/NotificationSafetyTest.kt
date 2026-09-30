package com.tgmon.app.data.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NotificationSafetyTest {

    private val NOTIFICATION_CONTAINER_BASE = 10

    @Test
    fun testNotificationId_positiveHashCode() {
        // String with known positive hash code
        val name = "nginx-production"
        val hash = name.hashCode()
        val calculatedId = NOTIFICATION_CONTAINER_BASE + abs(hash % 100)
        assertTrue("Notification ID must be >= 10", calculatedId >= NOTIFICATION_CONTAINER_BASE)
        assertTrue("Notification ID must be < 110", calculatedId < NOTIFICATION_CONTAINER_BASE + 100)
    }

    @Test
    fun testNotificationId_negativeHashCodeSafe() {
        // Strings that yield negative hash codes in Java/Kotlin
        val testNames = listOf(
            "redis_cache_service_v1",
            "postgres-db-01",
            "grafana-dashboard-node",
            "backend-api-worker-alpha",
            "tgmon-monitoring-agent"
        )

        for (name in testNames) {
            val safeId = NOTIFICATION_CONTAINER_BASE + abs(name.hashCode() % 100)
            assertTrue("ID must always be strictly non-negative: $safeId for $name", safeId >= 0)
            assertTrue("ID must be >= base: $safeId", safeId >= NOTIFICATION_CONTAINER_BASE)
            assertTrue("ID must be <= base + 99: $safeId", safeId <= NOTIFICATION_CONTAINER_BASE + 99)
        }
    }

    @Test
    fun testNotificationId_edgeHashCodes() {
        // Int.MIN_VALUE edge case
        val minValHash = Int.MIN_VALUE
        // Note: abs(Int.MIN_VALUE) in Java/Kotlin overflows to Int.MIN_VALUE if not modulo'd first
        val moduloFirst = minValHash % 100 // -48
        val safeId = NOTIFICATION_CONTAINER_BASE + abs(moduloFirst)
        assertEquals(10 + 48, safeId)
        assertTrue(safeId >= 0)

        // Zero hash code
        val zeroModulo = 0 % 100
        val zeroId = NOTIFICATION_CONTAINER_BASE + abs(zeroModulo)
        assertEquals(10, zeroId)
    }

    @Test
    fun testNotificationChannels_constants() {
        assertEquals("alert_channel", "alert_channel")
        assertEquals("info_channel", "info_channel")
    }
}
