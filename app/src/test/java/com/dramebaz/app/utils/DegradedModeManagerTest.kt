package com.dramebaz.app.utils

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for DegradedModeManager.
 * Tests degraded mode state tracking for LLM and TTS.
 * Note: android.util.Log is mocked at the static level to avoid JVM unit test issues.
 */
class DegradedModeManagerTest {

    @Before
    fun setup() {
        // Mock android.util.Log at the static level
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

        // Reset to initial state before each test
        DegradedModeManager.reset()
    }

    @After
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `initial LLM mode is NOT_INITIALIZED after reset`() {
        assertEquals(DegradedModeManager.LlmMode.NOT_INITIALIZED, DegradedModeManager.llmMode.value)
    }

    @Test
    fun `initial TTS mode is NOT_INITIALIZED after reset`() {
        assertEquals(DegradedModeManager.TtsMode.NOT_INITIALIZED, DegradedModeManager.ttsMode.value)
    }

    @Test
    fun `setLlmMode updates to ONNX_FULL`() {
        DegradedModeManager.setLlmMode(DegradedModeManager.LlmMode.ONNX_FULL)
        assertEquals(DegradedModeManager.LlmMode.ONNX_FULL, DegradedModeManager.llmMode.value)
    }

    @Test
    fun `setLlmMode updates to STUB_FALLBACK`() {
        DegradedModeManager.setLlmMode(DegradedModeManager.LlmMode.STUB_FALLBACK, "Model load failed")
        assertEquals(DegradedModeManager.LlmMode.STUB_FALLBACK, DegradedModeManager.llmMode.value)
    }

    @Test
    fun `setTtsMode updates to FULL`() {
        DegradedModeManager.setTtsMode(DegradedModeManager.TtsMode.FULL)
        assertEquals(DegradedModeManager.TtsMode.FULL, DegradedModeManager.ttsMode.value)
    }

    @Test
    fun `setTtsMode updates to DISABLED`() {
        DegradedModeManager.setTtsMode(DegradedModeManager.TtsMode.DISABLED, "TTS init failed")
        assertEquals(DegradedModeManager.TtsMode.DISABLED, DegradedModeManager.ttsMode.value)
    }

    @Test
    fun `getStatusMessage returns null when both systems are fully operational`() {
        DegradedModeManager.setLlmMode(DegradedModeManager.LlmMode.ONNX_FULL)
        DegradedModeManager.setTtsMode(DegradedModeManager.TtsMode.FULL)

        val message = DegradedModeManager.getStatusMessage()

        assertNull(message)
    }

    @Test
    fun `getStatusMessage returns message when LLM is in fallback mode`() {
        DegradedModeManager.setLlmMode(DegradedModeManager.LlmMode.STUB_FALLBACK)
        DegradedModeManager.setTtsMode(DegradedModeManager.TtsMode.FULL)

        val message = DegradedModeManager.getStatusMessage()

        assertNotNull(message)
        assertTrue(message!!.isNotEmpty())
    }

    @Test
    fun `getStatusMessage returns message when TTS is disabled`() {
        DegradedModeManager.setLlmMode(DegradedModeManager.LlmMode.ONNX_FULL)
        DegradedModeManager.setTtsMode(DegradedModeManager.TtsMode.DISABLED)

        val message = DegradedModeManager.getStatusMessage()

        assertNotNull(message)
        assertTrue(message!!.isNotEmpty())
    }

    @Test
    fun `getLlmDegradedMessage returns descriptive message`() {
        val message = DegradedModeManager.getLlmDegradedMessage()

        assertTrue(message.isNotEmpty())
    }

    @Test
    fun `getTtsDegradedMessage returns descriptive message`() {
        val message = DegradedModeManager.getTtsDegradedMessage()

        assertTrue(message.isNotEmpty())
    }
}
