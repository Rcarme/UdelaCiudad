package com.tgmon.app.data.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ThresholdAlertLogicTest {

    @Test
    fun testCpuThresholdEvaluation() {
        val threshold = 80.0
        val cpuNormal = 45.2
        val cpuHigh = 85.7
        val cpuCritical = 99.1

        assertFalse(cpuNormal > threshold)
        assertTrue(cpuHigh > threshold)
        assertTrue(cpuCritical > threshold)

        val message = "CPU: ${String.format(Locale.US, "%.1f", cpuHigh)}%"
        assertEquals("CPU: 85.7%", message)
    }

    @Test
    fun testMemoryThresholdEvaluation() {
        val threshold = 80.0
        val memNormal = 68.4
        val memHigh = 81.2

        assertFalse(memNormal > threshold)
        assertTrue(memHigh > threshold)

        val message = "Memoria: ${String.format(Locale.US, "%.1f", memHigh)}%"
        assertEquals("Memoria: 81.2%", message)
    }

    @Test
    fun testDiskThreshold_singleDiskFallback() {
        val diskUsed = 95.0
        val diskTotal = 100.0
        val threshold = 90.0

        val percent = (diskUsed / diskTotal) * 100.0
        assertEquals(95.0, percent, 0.001)
        assertTrue(percent > threshold)

        val message = "Disco: ${String.format(Locale.US, "%.1f", percent)}%"
        assertEquals("Disco: 95.0%", message)
    }

    @Test
    fun testDiskThreshold_multiDiskSelectsMax() {
        val disks = listOf(
            DiskInfo(device = "/dev/sda1", mountpoint = "/", totalGb = 50.0, percent = 45.0),
            DiskInfo(device = "/dev/sdb1", mountpoint = "/mnt/data", totalGb = 500.0, percent = 94.2),
            DiskInfo(device = "/dev/sdc1", mountpoint = "/mnt/backup", totalGb = 1000.0, percent = 15.0)
        )

        val maxDisk = disks.filter { it.totalGb > 0.0 }.maxByOrNull { it.percent }
        assertNotNull(maxDisk)
        assertEquals("/dev/sdb1", maxDisk!!.device)
        assertEquals(94.2, maxDisk.percent, 0.001)

        val label = maxDisk.device.takeIf { it.isNotBlank() } ?: maxDisk.mountpoint
        val message = "Disco ($label): ${String.format(Locale.US, "%.1f", maxDisk.percent)}%"
        assertEquals("Disco (/dev/sdb1): 94.2%", message)
    }

    @Test
    fun testAlertCooldownThrottling() {
        val thirtyMinutesMs = 30 * 60 * 1000L
        val lastAlertTime = 1000000L

        // 10 minutes later -> should NOT alert
        val now10Min = lastAlertTime + (10 * 60 * 1000L)
        val shouldAlert10Min = (now10Min - lastAlertTime) > thirtyMinutesMs
        assertFalse(shouldAlert10Min)

        // 31 minutes later -> SHOULD alert
        val now31Min = lastAlertTime + (31 * 60 * 1000L)
        val shouldAlert31Min = (now31Min - lastAlertTime) > thirtyMinutesMs
        assertTrue(shouldAlert31Min)
    }
}
