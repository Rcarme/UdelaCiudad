package com.tgmon.app.data.models

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiDiskTest {

    private val gson = Gson()

    @Test
    fun testHealthResponse_multiDiskDeserialization() {
        val json = """
            {
                "hostname": "SHODAN",
                "cpu_percent": 15.2,
                "memory_percent": 48.0,
                "disk_used_gb": 175.22,
                "disk_free_gb": 47.03,
                "disk_total_gb": 222.25,
                "disk_percent": 78.8,
                "disks": [
                    {
                        "device": "C:\\",
                        "mountpoint": "C:\\",
                        "fstype": "NTFS",
                        "total_gb": 222.25,
                        "used_gb": 175.22,
                        "free_gb": 47.03,
                        "percent": 78.8
                    },
                    {
                        "device": "D:\\",
                        "mountpoint": "D:\\",
                        "fstype": "NTFS",
                        "total_gb": 1863.02,
                        "used_gb": 1049.66,
                        "free_gb": 813.36,
                        "percent": 56.3
                    }
                ]
            }
        """.trimIndent()

        val response = gson.fromJson(json, HealthResponse::class.java)
        assertNotNull(response)
        assertEquals("SHODAN", response.hostname)
        assertEquals(175.22, response.diskUsedGb, 0.001)

        val disks = response.disks
        assertNotNull(disks)
        assertEquals(2, disks!!.size)

        val disk1 = disks[0]
        assertEquals("C:\\", disk1.device)
        assertEquals("C:\\", disk1.mountpoint)
        assertEquals("NTFS", disk1.fstype)
        assertEquals(222.25, disk1.totalGb, 0.001)
        assertEquals(175.22, disk1.usedGb, 0.001)
        assertEquals(47.03, disk1.freeGb, 0.001)
        assertEquals(78.8, disk1.percent, 0.001)

        val disk2 = disks[1]
        assertEquals("D:\\", disk2.device)
        assertEquals("D:\\", disk2.mountpoint)
        assertEquals("NTFS", disk2.fstype)
        assertEquals(1863.02, disk2.totalGb, 0.001)
        assertEquals(1049.66, disk2.usedGb, 0.001)
        assertEquals(813.36, disk2.freeGb, 0.001)
        assertEquals(56.3, disk2.percent, 0.001)
    }

    @Test
    fun testHealthResponse_legacyPayloadWithoutDisks() {
        val json = """
            {
                "hostname": "legacy-host",
                "cpu_percent": 10.0,
                "memory_percent": 30.0,
                "disk_used_gb": 50.0,
                "disk_free_gb": 150.0,
                "disk_total_gb": 200.0,
                "disk_percent": 25.0
            }
        """.trimIndent()

        val response = gson.fromJson(json, HealthResponse::class.java)
        assertNotNull(response)
        assertNull(response.disks)
        assertEquals(50.0, response.diskUsedGb, 0.001)
        assertEquals(150.0, response.diskFreeGb, 0.001)
    }

    @Test
    fun testMultiDisk_highestThresholdDetection() {
        val disks = listOf(
            DiskInfo(device = "C:\\", mountpoint = "C:\\", percent = 70.0, totalGb = 250.0),
            DiskInfo(device = "D:\\", mountpoint = "D:\\", percent = 92.5, totalGb = 2000.0) // External HDD critical!
        )

        val threshold = 90.0
        val highestDisk = disks.filter { it.totalGb > 0.0 }.maxByOrNull { it.percent }
        assertNotNull(highestDisk)
        assertEquals("D:\\", highestDisk!!.device)
        assertEquals(92.5, highestDisk.percent, 0.001)
        assertTrue(highestDisk.percent > threshold)

        val label = highestDisk.device.takeIf { it.isNotBlank() } ?: highestDisk.mountpoint
        val message = "Disco ($label): ${String.format(java.util.Locale.US, "%.1f", highestDisk.percent)}%"
        assertEquals("Disco (D:\\): 92.5%", message)
    }

    @Test
    fun testDiskInfo_displayNameFormatting() {
        val rootDisk = DiskInfo(device = "/dev/sda1", mountpoint = "/", totalGb = 500.0)
        assertEquals("System (/)", rootDisk.displayName)

        val winRootDisk = DiskInfo(device = "C:\\", mountpoint = "C:\\", totalGb = 250.0)
        assertEquals("System (C:\\)", winRootDisk.displayName)

        val externalNamedDisk = DiskInfo(name = "MyPassport", device = "/dev/sdb1", mountpoint = "/media/user/MyPassport", totalGb = 1000.0)
        assertEquals("MyPassport", externalNamedDisk.displayName)

        val externalUnamedDisk = DiskInfo(device = "/dev/sdc1", mountpoint = "/mnt/backup", totalGb = 2000.0)
        assertEquals("/mnt/backup (sdc1)", externalUnamedDisk.displayName)
    }

    @Test
    fun testOverview_top4ContainersSorting() {
        val containers = listOf(
            DockerContainer(id = "1", name = "idle-1", status = "running", cpuPercent = 0.1, memoryPercent = 2.0),
            DockerContainer(id = "2", name = "heavy-cpu", status = "running", cpuPercent = 45.0, memoryPercent = 10.0),
            DockerContainer(id = "3", name = "stopped-heavy", status = "exited", cpuPercent = 90.0, memoryPercent = 80.0),
            DockerContainer(id = "4", name = "heavy-ram", status = "running", cpuPercent = 5.0, memoryPercent = 60.0),
            DockerContainer(id = "5", name = "medium-active", status = "running", cpuPercent = 15.0, memoryPercent = 15.0),
            DockerContainer(id = "6", name = "low-active", status = "running", cpuPercent = 1.0, memoryPercent = 3.0)
        )

        val top4 = containers
            .sortedWith(
                compareByDescending<DockerContainer> { it.isRunning }
                    .thenByDescending { it.cpuPercent + it.memoryPercent }
            )
            .take(4)

        assertEquals(4, top4.size)
        // All 4 should be running containers (running takes precedence over stopped, even if stopped had high numbers)
        assertTrue(top4.all { it.isRunning })
        assertEquals("heavy-ram", top4[0].name) // 5.0 + 60.0 = 65.0
        assertEquals("heavy-cpu", top4[1].name) // 45.0 + 10.0 = 55.0
        assertEquals("medium-active", top4[2].name) // 15.0 + 15.0 = 30.0
        assertEquals("low-active", top4[3].name) // 1.0 + 3.0 = 4.0
    }
}
