package com.tgmon.app.data.models

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MetricHistoryDtoTest {

    private val gson = Gson()

    @Test
    fun testMetricHistoryDto_deserialization() {
        val json = """
            [
                {
                    "timestamp": 1725700000000,
                    "cpu_percent": 22.5,
                    "memory_percent": 55.4,
                    "disk_used_gb": 120.5,
                    "disk_free_gb": 380.0,
                    "disk_total_gb": 500.5,
                    "disk_percent": 24.1
                },
                {
                    "timestamp": 1725700300000,
                    "cpu_percent": 18.0,
                    "memory_percent": 52.1,
                    "disk_used_gb": 120.6,
                    "disk_free_gb": 379.9,
                    "disk_total_gb": 500.5,
                    "disk_percent": 24.1
                }
            ]
        """.trimIndent()

        val type = object : TypeToken<List<MetricHistoryDto>>() {}.type
        val dtos: List<MetricHistoryDto> = gson.fromJson(json, type)

        assertNotNull(dtos)
        assertEquals(2, dtos.size)

        val first = dtos[0]
        assertEquals(1725700000000L, first.timestamp)
        assertEquals(22.5, first.cpuPercent, 0.001)
        assertEquals(55.4, first.memoryPercent, 0.001)
        assertEquals(120.5, first.diskUsedGb, 0.001)
        assertEquals(380.0, first.diskFreeGb, 0.001)
        assertEquals(500.5, first.diskTotalGb, 0.001)
        assertEquals(24.1, first.diskPercent, 0.001)
    }

    @Test
    fun testMetricHistoryDto_mappingToMetricHistory() {
        val dto = MetricHistoryDto(
            timestamp = 1725700000000L,
            cpuPercent = 45.0,
            memoryPercent = 70.0,
            diskUsedGb = 200.0,
            diskFreeGb = 300.0,
            diskTotalGb = 500.0,
            diskPercent = 40.0
        )

        val entity = MetricHistory(
            timestamp = dto.timestamp,
            hostname = "server-test",
            cpuPercent = dto.cpuPercent,
            memoryPercent = dto.memoryPercent,
            diskUsedGb = dto.diskUsedGb,
            diskFreeGb = dto.diskFreeGb,
            diskTotalGb = dto.diskTotalGb,
            diskPercent = dto.diskPercent
        )

        assertEquals(1725700000000L, entity.timestamp)
        assertEquals("server-test", entity.hostname)
        assertEquals(45.0, entity.cpuPercent, 0.001)
        assertEquals(70.0, entity.memoryPercent, 0.001)
        assertEquals(200.0, entity.diskUsedGb, 0.001)
        assertEquals(300.0, entity.diskFreeGb, 0.001)
        assertEquals(500.0, entity.diskTotalGb, 0.001)
        assertEquals(40.0, entity.diskPercent, 0.001)
    }
}
