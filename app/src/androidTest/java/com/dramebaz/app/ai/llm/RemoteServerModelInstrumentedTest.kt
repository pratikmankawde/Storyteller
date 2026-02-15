package com.dramebaz.app.ai.llm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dramebaz.app.ai.llm.models.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for RemoteServerModel.
 * Tests actual network behavior and model lifecycle on device.
 * 
 * Note: These tests may fail if no remote server is available.
 * Tests are designed to verify graceful error handling.
 */
@RunWith(AndroidJUnit4::class)
class RemoteServerModelInstrumentedTest {

    // ==================== Lifecycle Tests ====================

    @Test
    fun testRemoteServerModelCreation() {
        val config = RemoteServerConfig(
            host = "127.0.0.1",
            port = 9999  // Non-existent server
        )
        val model = RemoteServerModel(config)
        
        // Model should be created without throwing
        assertNotNull(model)
        assertFalse("Model should not be loaded initially", model.isModelLoaded())
    }

    @Test
    fun testRemoteServerModelLoadWithUnreachableServer() = runBlocking {
        val config = RemoteServerConfig(
            host = "192.168.255.255",  // Non-routable IP
            port = 8080,
            connectTimeoutMs = 1000  // Short timeout for test
        )
        val model = RemoteServerModel(config)
        
        // Should gracefully handle connection failure
        val loaded = model.loadModel()
        assertFalse("Model should not load when server is unreachable", loaded)
        assertFalse("isModelLoaded should return false", model.isModelLoaded())
    }

    @Test
    fun testRemoteServerModelReleaseIsIdempotent() {
        val model = RemoteServerModel()
        
        // Multiple releases should not throw
        model.release()
        model.release()
        model.release()
        
        assertFalse("Model should not be loaded after release", model.isModelLoaded())
    }

    // ==================== Configuration Tests ====================

    @Test
    fun testRemoteServerModelDefaultParams() {
        val model = RemoteServerModel()
        
        val params = model.getDefaultParams()
        assertNotNull(params)
        assertTrue("Max tokens should be positive", params.maxTokens > 0)
        assertTrue("Temperature should be valid", params.temperature in 0f..2f)
    }

    @Test
    fun testRemoteServerModelUpdateParams() {
        val model = RemoteServerModel()
        
        val newParams = GenerationParams(maxTokens = 512, temperature = 0.5f)
        model.updateDefaultParams(newParams)
        
        val updated = model.getDefaultParams()
        assertEquals(512, updated.maxTokens)
        assertEquals(0.5f, updated.temperature, 0.01f)
    }

    @Test
    fun testRemoteServerModelSessionParams() {
        val model = RemoteServerModel()
        
        val sessionParams = SessionParams(repeatPenalty = 1.2f)
        val success = model.updateSessionParams(sessionParams)
        
        assertTrue("Session params update should succeed", success)
        assertEquals(1.2f, model.getSessionParams().repeatPenalty, 0.01f)
    }

    @Test
    fun testRemoteServerModelSessionParamsSupport() {
        val model = RemoteServerModel()
        
        val support = model.getSessionParamsSupport()
        assertTrue("Should support repeat penalty", support.supportsRepeatPenalty)
        // Remote server doesn't support these (handled by server)
        assertFalse("Should not support GPU layers", support.supportsGpuLayers)
    }

    // ==================== Model Info Tests ====================

    @Test
    fun testRemoteServerModelInfo() {
        val config = RemoteServerConfig(
            host = "test.server.com",
            port = 8080,
            modelId = "test-model"
        )
        val model = RemoteServerModel(config)
        
        val info = model.getModelInfo()
        assertEquals(ModelFormat.REMOTE, info.format)
        assertTrue("Model name should contain remote", info.name.contains("Remote"))
        assertTrue("Model name should contain model ID", info.name.contains("test-model"))
    }

    @Test
    fun testRemoteServerModelCapabilities() {
        val model = RemoteServerModel()
        
        val capabilities = model.getModelCapabilities()
        assertFalse("Remote model doesn't support image", capabilities.supportsImage)
        assertFalse("Remote model doesn't support audio", capabilities.supportsAudio)
        assertTrue("Max context should be positive", capabilities.maxContextLength > 0)
    }

    @Test
    fun testRemoteServerModelExecutionProvider() {
        val config = RemoteServerConfig(host = "example.com", port = 8080)
        val model = RemoteServerModel(config)
        
        val provider = model.getExecutionProvider()
        assertTrue("Provider should mention remote", provider.contains("Remote"))
        assertTrue("Provider should mention host", provider.contains("example.com"))
    }

    // ==================== Generation Tests (No Server) ====================

    @Test
    fun testGenerateResponseWithoutConnection() = runBlocking {
        val config = RemoteServerConfig(
            host = "192.168.255.255",
            port = 8080,
            connectTimeoutMs = 500
        )
        val model = RemoteServerModel(config)
        
        // Should return empty string when not connected
        val response = model.generateResponse(
            systemPrompt = "You are a helpful assistant.",
            userPrompt = "Hello!",
            params = GenerationParams.DEFAULT
        )
        
        assertEquals("Should return empty string when not connected", "", response)
    }
}

