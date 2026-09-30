package com.tgmon.app.data.models

import com.google.gson.Gson
import com.tgmon.app.data.preferences.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RolePermissionsTest {

    private val gson = Gson()

    @Test
    fun testAdminResponseDeserialization() {
        val json = """{"valid": true, "role": "admin"}"""
        val response = gson.fromJson(json, VerifyAuthResponse::class.java)

        assertTrue(response.valid)
        assertEquals("admin", response.role)
        assertTrue(response.isAdmin)
    }

    @Test
    fun testViewerResponseDeserialization() {
        val json = """{"valid": true, "role": "viewer"}"""
        val response = gson.fromJson(json, VerifyAuthResponse::class.java)

        assertTrue(response.valid)
        assertEquals("viewer", response.role)
        assertFalse(response.isAdmin)
    }

    @Test
    fun testDefaultVerifyAuthResponse() {
        val response = VerifyAuthResponse()
        assertFalse(response.valid)
        assertEquals("admin", response.role)
        assertTrue(response.isAdmin)
    }

    @Test
    fun testServerConfigAdminRole() {
        val config = ServerConfig(
            name = "Prod Server",
            localUrl = "http://192.168.1.10:8000",
            remoteUrl = "",
            token = "admin_secret_token",
            role = "admin"
        )
        assertTrue(config.isAdmin)
        assertEquals("admin", config.role)
    }

    @Test
    fun testServerConfigViewerRole() {
        val config = ServerConfig(
            name = "Readonly Monitor",
            localUrl = "http://192.168.1.10:8000",
            remoteUrl = "",
            token = "viewer_secret_token",
            role = "viewer"
        )
        assertFalse(config.isAdmin)
        assertEquals("viewer", config.role)
    }

    @Test
    fun testServerConfigDefaultRoleIsAdmin() {
        val config = ServerConfig(
            name = "Default Server",
            localUrl = "http://localhost:8000",
            remoteUrl = "",
            token = "some_secret_token"
        )
        assertTrue(config.isAdmin)
        assertEquals("admin", config.role)
    }

    @Test
    fun testServerConfigJsonSerializationRoundTrip() {
        val config = ServerConfig(
            name = "Viewer Server",
            localUrl = "http://10.0.2.2:8000",
            remoteUrl = "http://tailscale:8000",
            token = "viewer_token_abc",
            role = "viewer"
        )
        val json = gson.toJson(config)
        val deserialized = gson.fromJson(json, ServerConfig::class.java)

        assertEquals("viewer", deserialized.role)
        assertFalse(deserialized.isAdmin)
        assertEquals(config.name, deserialized.name)
    }

    @Test
    fun testServerConfigLegacyJsonWithoutRoleDefaultsToAdmin() {
        // Simulates old configs stored prior to RBAC introduction
        val legacyJson = """{"id":"123","name":"Old Config","localUrl":"http://1.1.1.1:8000","remoteUrl":"","token":"secret123","isDefault":true}"""
        val deserialized = gson.fromJson(legacyJson, ServerConfig::class.java)

        // Null role in legacy json should safely resolve isAdmin = true
        assertTrue(deserialized.isAdmin)
    }
}
