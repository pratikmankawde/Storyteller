package com.dramebaz.app.ai.audio

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for RemoteSfxConfig.
 * Tests configuration creation, URL generation, and validation.
 */
class RemoteSfxConfigTest {

    // Default values tests

    @Test
    fun `default config has expected values`() {
        val config = RemoteSfxConfig.DEFAULT
        assertEquals(RemoteSfxConfig.DEFAULT_HOST, config.host)
        assertEquals(RemoteSfxConfig.DEFAULT_PORT, config.port)
        assertEquals(RemoteSfxConfig.DEFAULT_MODEL_ID, config.modelId)
        assertEquals(RemoteSfxConfig.DEFAULT_CONNECT_TIMEOUT_MS, config.connectTimeoutMs)
        assertEquals(RemoteSfxConfig.DEFAULT_READ_TIMEOUT_MS, config.readTimeoutMs)
        assertFalse(config.useTls)
    }

    @Test
    fun `localhost config has correct host`() {
        val config = RemoteSfxConfig.LOCALHOST
        assertEquals("127.0.0.1", config.host)
        assertEquals(RemoteSfxConfig.DEFAULT_PORT, config.port)
    }

    // URL generation tests

    @Test
    fun `baseUrl uses http when useTls is false`() {
        val config = RemoteSfxConfig(host = "example.com", port = 8080, useTls = false)
        assertEquals("http://example.com:8080", config.baseUrl)
    }

    @Test
    fun `baseUrl uses https when useTls is true`() {
        val config = RemoteSfxConfig(host = "example.com", port = 443, useTls = true)
        assertEquals("https://example.com:443", config.baseUrl)
    }

    @Test
    fun `audioGenUrl returns correct endpoint`() {
        val config = RemoteSfxConfig(host = "192.168.1.100", port = 8080)
        assertEquals("http://192.168.1.100:8080/api/v1/audio-gen", config.audioGenUrl)
    }

    @Test
    fun `modelsUrl returns correct endpoint`() {
        val config = RemoteSfxConfig(host = "192.168.1.100", port = 8080)
        assertEquals("http://192.168.1.100:8080/api/v1/models", config.modelsUrl)
    }

    @Test
    fun `healthUrl returns correct endpoint`() {
        val config = RemoteSfxConfig(host = "192.168.1.100", port = 8080)
        assertEquals("http://192.168.1.100:8080/health", config.healthUrl)
    }

    // Validation tests

    @Test
    fun `isValid returns true for valid config`() {
        val config = RemoteSfxConfig(
            host = "192.168.1.100",
            port = 8080,
            modelId = "stable-audio",
            connectTimeoutMs = 10000,
            readTimeoutMs = 60000
        )
        assertTrue(config.isValid())
    }

    @Test
    fun `isValid returns false for blank host`() {
        val config = RemoteSfxConfig(host = "", port = 8080)
        assertFalse(config.isValid())
    }

    @Test
    fun `isValid returns false for invalid port zero`() {
        val config = RemoteSfxConfig(host = "localhost", port = 0)
        assertFalse(config.isValid())
    }

    @Test
    fun `isValid returns false for invalid port too high`() {
        val config = RemoteSfxConfig(host = "localhost", port = 65536)
        assertFalse(config.isValid())
    }

    @Test
    fun `isValid returns false for blank modelId`() {
        val config = RemoteSfxConfig(host = "localhost", port = 8080, modelId = "")
        assertFalse(config.isValid())
    }

    @Test
    fun `isValid returns false for zero connectTimeout`() {
        val config = RemoteSfxConfig(host = "localhost", port = 8080, connectTimeoutMs = 0)
        assertFalse(config.isValid())
    }

    @Test
    fun `isValid returns false for zero readTimeout`() {
        val config = RemoteSfxConfig(host = "localhost", port = 8080, readTimeoutMs = 0)
        assertFalse(config.isValid())
    }

    // Edge cases

    @Test
    fun `config with minimum valid port is valid`() {
        val config = RemoteSfxConfig(host = "localhost", port = 1)
        assertTrue(config.isValid())
    }

    @Test
    fun `config with maximum valid port is valid`() {
        val config = RemoteSfxConfig(host = "localhost", port = 65535)
        assertTrue(config.isValid())
    }

    @Test
    fun `config preserves custom values`() {
        val config = RemoteSfxConfig(
            host = "custom.server.com",
            port = 9000,
            modelId = "custom-audio-model",
            connectTimeoutMs = 5000,
            readTimeoutMs = 300000,
            useTls = true
        )

        assertEquals("custom.server.com", config.host)
        assertEquals(9000, config.port)
        assertEquals("custom-audio-model", config.modelId)
        assertEquals(5000, config.connectTimeoutMs)
        assertEquals(300000, config.readTimeoutMs)
        assertTrue(config.useTls)
    }
}

