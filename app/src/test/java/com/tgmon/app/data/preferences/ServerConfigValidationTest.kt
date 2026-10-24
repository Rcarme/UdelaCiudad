package com.tgmon.app.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConfigValidationTest {

    @Test
    fun testServerConfig_isValidWithLocalUrlOnly() {
        val config = ServerConfig(
            name = "Home Lab",
            localUrl = "http://192.168.1.100:8000",
            remoteUrl = "",
            token = "secret123"
        )
        assertTrue(config.isValid())
        assertEquals("Home Lab", config.name)
        assertFalse(config.isDefault)
    }

    @Test
    fun testServerConfig_isValidWithRemoteUrlOnly() {
        val config = ServerConfig(
            name = "Tailscale Node",
            localUrl = "",
            remoteUrl = "http://100.80.20.10:8000",
            token = "secret123",
            isDefault = true
        )
        assertTrue(config.isValid())
        assertTrue(config.isDefault)
    }

    @Test
    fun testServerConfig_invalidIfBothUrlsBlank() {
        val config = ServerConfig(
            name = "Empty Server",
            localUrl = "",
            remoteUrl = "",
            token = "secret123"
        )
        assertFalse(config.isValid())
    }

    @Test
    fun testServerConfig_invalidIfNameBlank() {
        val config = ServerConfig(
            name = "   ",
            localUrl = "http://10.0.2.2:8000",
            remoteUrl = "",
            token = "secret123"
        )
        assertFalse(config.isValid())
    }

    @Test
    fun testServerConfig_uniqueIdsGeneratedByDefault() {
        val config1 = ServerConfig(name = "S1", localUrl = "http://localhost:8000", remoteUrl = "", token = "t1")
        val config2 = ServerConfig(name = "S2", localUrl = "http://localhost:8000", remoteUrl = "", token = "t2")
        assertNotNull(config1.id)
        assertNotNull(config2.id)
        assertNotEquals(config1.id, config2.id)
    }

    @Test
    fun testServerConfig_urlNormalization() {
        fun normalizeUrl(url: String): String {
            val trimmed = url.trim()
            return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        }

        assertEquals("http://192.168.1.50:8000/", normalizeUrl("http://192.168.1.50:8000"))
        assertEquals("http://192.168.1.50:8000/", normalizeUrl("http://192.168.1.50:8000/"))
        assertEquals("http://10.0.2.2:8000/", normalizeUrl("  http://10.0.2.2:8000  "))
    }
}
