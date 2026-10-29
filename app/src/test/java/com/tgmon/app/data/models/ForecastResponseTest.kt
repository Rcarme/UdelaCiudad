package com.tgmon.app.data.models

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastResponseTest {

    private val gson = Gson()

    @Test
    fun `parse ForecastResponse with rising trend and ETA from JSON`() {
        val json = """
            {
              "available": true,
              "samples_used": 12,
              "window_minutes": 60,
              "horizon_minutes": 30,
              "method": "ols_linear_regression",
              "cpu": {
                "metric": "cpu",
                "slope_per_min": 0.4,
                "r_squared": 0.992,
                "trend": "rising",
                "threshold": 85.0,
                "minutes_to_threshold": 107.5,
                "points": [
                  {"timestamp": 1793232000000, "value": 42.0, "lower": 40.5, "upper": 43.5},
                  {"timestamp": 1793233800000, "value": 54.0, "lower": 51.0, "upper": 57.0}
                ]
              },
              "memory": {
                "metric": "memory",
                "slope_per_min": 0.01,
                "r_squared": 0.45,
                "trend": "stable",
                "threshold": 85.0,
                "minutes_to_threshold": null,
                "points": [
                  {"timestamp": 1793232000000, "value": 27.6, "lower": 27.0, "upper": 28.2},
                  {"timestamp": 1793233800000, "value": 27.9, "lower": 27.1, "upper": 28.7}
                ]
              }
            }
        """.trimIndent()

        val parsed = gson.fromJson(json, ForecastResponse::class.java)
        assertTrue(parsed.available)
        assertEquals(12, parsed.samplesUsed)
        assertEquals("ols_linear_regression", parsed.method)

        val cpu = parsed.cpu
        assertNotNull(cpu)
        assertEquals("rising", cpu!!.trend)
        assertEquals(0.4, cpu.slopePerMin, 0.001)
        assertEquals(0.992, cpu.rSquared, 0.001)
        assertEquals(107.5, cpu.minutesToThreshold ?: 0.0, 0.01)
        assertEquals(2, cpu.points.size)
        assertTrue(cpu.points[0].lower <= cpu.points[0].value && cpu.points[0].value <= cpu.points[0].upper)

        val mem = parsed.memory
        assertNotNull(mem)
        assertEquals("stable", mem!!.trend)
        assertNull(mem.minutesToThreshold)
    }

    @Test
    fun `default ForecastResponse is unavailable and safe`() {
        val empty = ForecastResponse()
        assertEquals(false, empty.available)
        assertNull(empty.cpu)
        assertNull(empty.memory)
    }
}
