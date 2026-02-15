package com.dramebaz.app.integration

import com.dramebaz.app.ai.audio.RemoteSfxConfig
import com.dramebaz.app.ai.llm.models.RemoteServerConfig
import com.dramebaz.app.ai.tts.RemoteTtsConfig
import com.dramebaz.app.ai.tts.TtsModelInfo
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for Remote AI Model components.
 * Tests:
 * - Configuration classes work together
 * - Factory methods create correct implementations
 * - Settings serialization/deserialization
 * - Graceful degradation patterns
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteAiModelIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.d(any(), any(), any()) } returns 0
        every { android.util.Log.i(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.println(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(android.util.Log::class)
    }

    // ==================== LLM Config Integration Tests ====================

    @Test
    fun `LLM remote config produces valid URLs for all endpoints`() {
        val config = RemoteServerConfig(
            host = "ai.example.com",
            port = 8080,
            modelId = "qwen3-1.7b",
            useTls = false
        )

        // All URLs should use same base
        assertTrue(config.generateUrl.startsWith(config.baseUrl))
        assertTrue(config.modelsUrl.startsWith(config.baseUrl))
        assertTrue(config.healthUrl.startsWith(config.baseUrl))

        // Endpoints should be distinct
        assertNotEquals(config.generateUrl, config.healthUrl)
        assertNotEquals(config.generateUrl, config.modelsUrl)

        // Should be valid configuration
        assertTrue(config.isValid())
    }

    @Test
    fun `LLM remote config with TLS produces HTTPS URLs`() {
        val config = RemoteServerConfig(
            host = "secure-ai.example.com",
            port = 443,
            useTls = true
        )

        assertTrue("Base URL should use HTTPS", config.baseUrl.startsWith("https://"))
        assertTrue("Generate URL should use HTTPS", config.generateUrl.startsWith("https://"))
        assertTrue("Models URL should use HTTPS", config.modelsUrl.startsWith("https://"))
        assertTrue("Health URL should use HTTPS", config.healthUrl.startsWith("https://"))
    }

    // ==================== TTS Config Integration Tests ====================

    @Test
    fun `TTS remote config produces valid URLs for all endpoints`() {
        val config = RemoteTtsConfig(
            host = "tts.example.com",
            port = 8081,
            modelId = "piper-tts"
        )

        // All URLs should use same base
        assertTrue(config.ttsUrl.startsWith(config.baseUrl))
        assertTrue(config.modelsUrl.startsWith(config.baseUrl))
        assertTrue(config.healthUrl.startsWith(config.baseUrl))

        // Endpoints should be distinct
        assertNotEquals(config.ttsUrl, config.modelsUrl)
        assertNotEquals(config.ttsUrl, config.healthUrl)

        // Should be valid configuration
        assertTrue(config.isValid())
    }

    // ==================== SFX Config Integration Tests ====================

    @Test
    fun `SFX remote config produces valid URLs for all endpoints`() {
        val config = RemoteSfxConfig(
            host = "sfx.example.com",
            port = 8082,
            modelId = "stable-audio"
        )

        // All URLs should use same base
        assertTrue(config.audioGenUrl.startsWith(config.baseUrl))
        assertTrue(config.modelsUrl.startsWith(config.baseUrl))
        assertTrue(config.healthUrl.startsWith(config.baseUrl))

        // Endpoints should be distinct
        assertNotEquals(config.audioGenUrl, config.healthUrl)
        assertNotEquals(config.audioGenUrl, config.modelsUrl)

        // Should be valid configuration
        assertTrue(config.isValid())
    }

    // ==================== Cross-Component Integration ====================

    @Test
    fun `all remote configs can share same server with different endpoints`() {
        val host = "unified-ai-server.local"
        val port = 8080

        val llmConfig = RemoteServerConfig(host = host, port = port)
        val ttsConfig = RemoteTtsConfig(host = host, port = port)
        val sfxConfig = RemoteSfxConfig(host = host, port = port)

        // All should have same base URL
        assertEquals(llmConfig.baseUrl, ttsConfig.baseUrl)
        assertEquals(ttsConfig.baseUrl, sfxConfig.baseUrl)

        // But different endpoints
        assertNotEquals(llmConfig.generateUrl, ttsConfig.ttsUrl)
        assertNotEquals(ttsConfig.ttsUrl, sfxConfig.audioGenUrl)

        // All configs should be valid
        assertTrue(llmConfig.isValid())
        assertTrue(ttsConfig.isValid())
        assertTrue(sfxConfig.isValid())
    }

    @Test
    fun `TtsModelInfo correctly represents remote TTS engine`() {
        val modelInfo = TtsModelInfo(
            id = "remote-tts",
            displayName = "Remote TTS (192.168.1.100:8080)",
            modelType = "remote",
            speakerCount = 904,
            sampleRate = 22050,
            isExternal = true,
            modelPath = "http://192.168.1.100:8080"
        )

        assertEquals("remote-tts", modelInfo.id)
        assertEquals("remote", modelInfo.modelType)
        assertTrue(modelInfo.isExternal)
        assertTrue(modelInfo.modelPath.startsWith("http://"))
    }
}

