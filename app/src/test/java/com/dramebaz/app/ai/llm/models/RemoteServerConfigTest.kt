package com.dramebaz.app.ai.llm.models

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for RemoteServerConfig.
 * Tests configuration creation, URL generation, and validation.
 */
class RemoteServerConfigTest {

    // Default values tests

    @Test
    fun `default config has expected values`() {
        val config = RemoteServerConfig.DEFAULT
        assertEquals(RemoteServerConfig.DEFAULT_HOST, config.host)
        assertEquals(RemoteServerConfig.DEFAULT_PORT, config.port)
        assertEquals(RemoteServerConfig.DEFAULT_MODEL_ID, config.modelId)
        assertEquals(RemoteServerConfig.DEFAULT_CONNECT_TIMEOUT_MS, config.connectTimeoutMs)
        assertEquals(RemoteServerConfig.DEFAULT_READ_TIMEOUT_MS, config.readTimeoutMs)
        assertFalse(config.useTls)
    }

    @Test
    fun `localhost config has correct host`() {
        val config = RemoteServerConfig.LOCALHOST
        assertEquals("127.0.0.1", config.host)
        assertEquals(RemoteServerConfig.DEFAULT_PORT, config.port)
    }

    // URL generation tests

    @Test
    fun `baseUrl uses http when useTls is false`() {
        val config = RemoteServerConfig(host = "example.com", port = 8080, useTls = false)
        assertEquals("http://example.com:8080", config.baseUrl)
    }

    @Test
    fun `baseUrl uses https when useTls is true`() {
        val config = RemoteServerConfig(host = "example.com", port = 443, useTls = true)
        assertEquals("https://example.com:443", config.baseUrl)
    }

    @Test
    fun `generateUrl returns correct endpoint`() {
        val config = RemoteServerConfig(host = "192.168.1.100", port = 8080)
        assertEquals("http://192.168.1.100:8080/api/v1/generate", config.generateUrl)
    }

    @Test
    fun `modelsUrl returns correct endpoint`() {
        val config = RemoteServerConfig(host = "192.168.1.100", port = 8080)
        assertEquals("http://192.168.1.100:8080/api/v1/models", config.modelsUrl)
    }

    @Test
    fun `healthUrl returns correct endpoint`() {
        val config = RemoteServerConfig(host = "192.168.1.100", port = 8080)
        assertEquals("http://192.168.1.100:8080/health", config.healthUrl)
    }

    // Validation tests

    @Test
    fun `isValid returns true for valid config`() {
        val config = RemoteServerConfig(
            host = "192.168.1.100",
            port = 8080,
            modelId = "qwen2.5-3b",
            connectTimeoutMs = 10000,
            readTimeoutMs = 60000
        )
        assertTrue(config.isValid())
    }

    @Test
    fun `isValid returns false for blank host`() {
        val config = RemoteServerConfig(host = "", port = 8080)
        assertFalse(config.isValid())
    }

    @Test
    fun `isValid returns false for invalid port zero`() {
        val config = RemoteServerConfig(host = "localhost", port = 0)
        assertFalse(config.isValid())
    }

    @Test
    fun `isValid returns false for invalid port too high`() {
        val config = RemoteServerConfig(host = "localhost", port = 65536)
        assertFalse(config.isValid())
    }

    @Test
    fun `isValid returns false for blank modelId`() {
        val config = RemoteServerConfig(host = "localhost", port = 8080, modelId = "")
        assertFalse(config.isValid())
    }

    @Test
    fun `isValid returns false for zero connectTimeout`() {
        val config = RemoteServerConfig(host = "localhost", port = 8080, connectTimeoutMs = 0)
        assertFalse(config.isValid())
    }

    @Test
    fun `isValid returns false for zero readTimeout`() {
        val config = RemoteServerConfig(host = "localhost", port = 8080, readTimeoutMs = 0)
        assertFalse(config.isValid())
    }

    // Edge cases

    @Test
    fun `config with minimum valid port is valid`() {
        val config = RemoteServerConfig(host = "localhost", port = 1)
        assertTrue(config.isValid())
    }

    @Test
    fun `config with maximum valid port is valid`() {
        val config = RemoteServerConfig(host = "localhost", port = 65535)
        assertTrue(config.isValid())
    }

    @Test
    fun `config preserves custom values`() {
        val config = RemoteServerConfig(
            host = "custom.server.com",
            port = 9000,
            modelId = "custom-model",
            connectTimeoutMs = 5000,
            readTimeoutMs = 120000,
            useTls = true
        )
        
        assertEquals("custom.server.com", config.host)
        assertEquals(9000, config.port)
        assertEquals("custom-model", config.modelId)
        assertEquals(5000, config.connectTimeoutMs)
        assertEquals(120000, config.readTimeoutMs)
        assertTrue(config.useTls)
    }
}

