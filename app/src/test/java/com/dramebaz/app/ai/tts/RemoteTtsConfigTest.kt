package com.dramebaz.app.ai.tts

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for RemoteTtsConfig.
 * Tests configuration creation, URL generation, and validation.
 */
class RemoteTtsConfigTest {

    // Default values tests

    @Test
    fun `default config has expected values`() {
        val config = RemoteTtsConfig.DEFAULT
        assertEquals(RemoteTtsConfig.DEFAULT_HOST, config.host)
        assertEquals(RemoteTtsConfig.DEFAULT_PORT, config.port)
        assertEquals(RemoteTtsConfig.DEFAULT_MODEL_ID, config.modelId)
        assertEquals(RemoteTtsConfig.DEFAULT_CONNECT_TIMEOUT_MS, config.connectTimeoutMs)
        assertEquals(RemoteTtsConfig.DEFAULT_READ_TIMEOUT_MS, config.readTimeoutMs)
        assertFalse(config.useTls)
    }

    @Test
    fun `localhost config has correct host`() {
        val config = RemoteTtsConfig.LOCALHOST
        assertEquals("127.0.0.1", config.host)
        assertEquals(RemoteTtsConfig.DEFAULT_PORT, config.port)
    }

    // URL generation tests

    @Test
    fun `baseUrl uses http when useTls is false`() {
        val config = RemoteTtsConfig(host = "example.com", port = 8080, useTls = false)
        assertEquals("http://example.com:8080", config.baseUrl)
    }

    @Test
    fun `baseUrl uses https when useTls is true`() {
        val config = RemoteTtsConfig(host = "example.com", port = 443, useTls = true)
        assertEquals("https://example.com:443", config.baseUrl)
    }

    @Test
    fun `ttsUrl returns correct endpoint`() {
        val config = RemoteTtsConfig(host = "192.168.1.100", port = 8080)
        assertEquals("http://192.168.1.100:8080/api/v1/tts", config.ttsUrl)
    }

    @Test
    fun `modelsUrl returns correct endpoint`() {
        val config = RemoteTtsConfig(host = "192.168.1.100", port = 8080)
        assertEquals("http://192.168.1.100:8080/api/v1/models", config.modelsUrl)
    }

    @Test
    fun `healthUrl returns correct endpoint`() {
        val config = RemoteTtsConfig(host = "192.168.1.100", port = 8080)
        assertEquals("http://192.168.1.100:8080/health", config.healthUrl)
    }

    // Validation tests

    @Test
    fun `isValid returns true for valid config`() {
        val config = RemoteTtsConfig(
            host = "192.168.1.100",
            port = 8080,
            modelId = "piper-tts",
            connectTimeoutMs = 10000,
            readTimeoutMs = 60000
        )
        assertTrue(config.isValid())
    }

    @Test
    fun `isValid returns false for blank host`() {
        val config = RemoteTtsConfig(host = "", port = 8080)
        assertFalse(config.isValid())
    }

    @Test
    fun `isValid returns false for invalid port zero`() {
        val config = RemoteTtsConfig(host = "localhost", port = 0)
        assertFalse(config.isValid())
    }

    @Test
    fun `isValid returns false for invalid port too high`() {
        val config = RemoteTtsConfig(host = "localhost", port = 65536)
        assertFalse(config.isValid())
    }

    @Test
    fun `isValid returns false for blank modelId`() {
        val config = RemoteTtsConfig(host = "localhost", port = 8080, modelId = "")
        assertFalse(config.isValid())
    }

    @Test
    fun `isValid returns false for zero connectTimeout`() {
        val config = RemoteTtsConfig(host = "localhost", port = 8080, connectTimeoutMs = 0)
        assertFalse(config.isValid())
    }

    @Test
    fun `isValid returns false for zero readTimeout`() {
        val config = RemoteTtsConfig(host = "localhost", port = 8080, readTimeoutMs = 0)
        assertFalse(config.isValid())
    }

    // Edge cases

    @Test
    fun `config with minimum valid port is valid`() {
        val config = RemoteTtsConfig(host = "localhost", port = 1)
        assertTrue(config.isValid())
    }

    @Test
    fun `config with maximum valid port is valid`() {
        val config = RemoteTtsConfig(host = "localhost", port = 65535)
        assertTrue(config.isValid())
    }

    @Test
    fun `config preserves custom values`() {
        val config = RemoteTtsConfig(
            host = "custom.server.com",
            port = 9000,
            modelId = "custom-tts-model",
            connectTimeoutMs = 5000,
            readTimeoutMs = 180000,
            useTls = true
        )

        assertEquals("custom.server.com", config.host)
        assertEquals(9000, config.port)
        assertEquals("custom-tts-model", config.modelId)
        assertEquals(5000, config.connectTimeoutMs)
        assertEquals(180000, config.readTimeoutMs)
        assertTrue(config.useTls)
    }
}

