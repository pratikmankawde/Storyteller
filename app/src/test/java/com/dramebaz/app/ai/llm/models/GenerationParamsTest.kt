package com.dramebaz.app.ai.llm.models

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for GenerationParams.
 * Tests parameter creation, validation, and presets.
 */
class GenerationParamsTest {

    // Default values tests

    @Test
    fun `default params have expected values`() {
        val params = GenerationParams.DEFAULT
        assertEquals(1024, params.maxTokens)
        assertEquals(0.7f, params.temperature, 0.01f)
        assertEquals(40, params.topK)
        assertEquals(0.95f, params.topP, 0.01f)
        assertTrue(params.stopSequences.isEmpty())
    }

    // Preset tests

    @Test
    fun `JSON_EXTRACTION preset has low temperature`() {
        val params = GenerationParams.JSON_EXTRACTION
        assertTrue(params.temperature < 0.3f)
        assertEquals(2048, params.maxTokens)
    }

    @Test
    fun `CREATIVE preset has high temperature`() {
        val params = GenerationParams.CREATIVE
        assertTrue(params.temperature >= 0.8f)
    }

    @Test
    fun `SHORT_EXTRACTION preset has low maxTokens`() {
        val params = GenerationParams.SHORT_EXTRACTION
        assertEquals(256, params.maxTokens)
        assertTrue(params.temperature < 0.2f)
    }

    // withMaxTokens tests

    @Test
    fun `withMaxTokens creates copy with new value`() {
        val original = GenerationParams.DEFAULT
        val modified = original.withMaxTokens(2048)
        
        assertEquals(2048, modified.maxTokens)
        assertEquals(original.temperature, modified.temperature, 0.01f)
        assertEquals(original.topK, modified.topK)
    }

    // withTemperature tests

    @Test
    fun `withTemperature creates copy with new value`() {
        val original = GenerationParams.DEFAULT
        val modified = original.withTemperature(0.5f)
        
        assertEquals(0.5f, modified.temperature, 0.01f)
        assertEquals(original.maxTokens, modified.maxTokens)
    }

    // validated tests

    @Test
    fun `validated clamps maxTokens to valid range`() {
        val tooLow = GenerationParams(maxTokens = 0).validated()
        val tooHigh = GenerationParams(maxTokens = 100000).validated()
        
        assertEquals(1, tooLow.maxTokens)
        assertEquals(32768, tooHigh.maxTokens)
    }

    @Test
    fun `validated clamps temperature to valid range`() {
        val tooLow = GenerationParams(temperature = -1f).validated()
        val tooHigh = GenerationParams(temperature = 5f).validated()
        
        assertEquals(0f, tooLow.temperature, 0.01f)
        assertEquals(2f, tooHigh.temperature, 0.01f)
    }

    @Test
    fun `validated clamps topK to valid range`() {
        val tooLow = GenerationParams(topK = 0).validated()
        val tooHigh = GenerationParams(topK = 200).validated()
        
        assertEquals(1, tooLow.topK)
        assertEquals(100, tooHigh.topK)
    }

    @Test
    fun `validated clamps topP to valid range`() {
        val tooLow = GenerationParams(topP = -0.5f).validated()
        val tooHigh = GenerationParams(topP = 2f).validated()
        
        assertEquals(0f, tooLow.topP, 0.01f)
        assertEquals(1f, tooHigh.topP, 0.01f)
    }

    @Test
    fun `validated preserves valid values`() {
        val original = GenerationParams(
            maxTokens = 512,
            temperature = 0.5f,
            topK = 30,
            topP = 0.9f
        )
        val validated = original.validated()
        
        assertEquals(original, validated)
    }
}

/**
 * Unit tests for SessionParams.
 * Tests session-level parameter creation and validation.
 */
class SessionParamsTest {

    @Test
    fun `default params have expected values`() {
        val params = SessionParams.DEFAULT
        assertEquals(1.1f, params.repeatPenalty, 0.01f)
        assertEquals(8192, params.contextLength)
        assertEquals(-1, params.gpuLayers)
        assertEquals("gpu,cpu", params.accelerators)
    }

    @Test
    fun `CPU_ONLY preset has correct values`() {
        val params = SessionParams.CPU_ONLY
        assertEquals(0, params.gpuLayers)
        assertEquals("cpu", params.accelerators)
    }

    @Test
    fun `MAX_GPU preset has correct values`() {
        val params = SessionParams.MAX_GPU
        assertEquals(-1, params.gpuLayers)
        assertEquals("gpu,cpu", params.accelerators)
    }

    @Test
    fun `validated clamps repeatPenalty`() {
        val tooLow = SessionParams(repeatPenalty = 0.5f).validated()
        val tooHigh = SessionParams(repeatPenalty = 3f).validated()
        
        assertEquals(1.0f, tooLow.repeatPenalty, 0.01f)
        assertEquals(2.0f, tooHigh.repeatPenalty, 0.01f)
    }

    @Test
    fun `validated clamps contextLength`() {
        val tooLow = SessionParams(contextLength = 100).validated()
        val tooHigh = SessionParams(contextLength = 100000).validated()
        
        assertEquals(512, tooLow.contextLength)
        assertEquals(32768, tooHigh.contextLength)
    }

    @Test
    fun `validated clamps gpuLayers`() {
        val tooLow = SessionParams(gpuLayers = -10).validated()
        val tooHigh = SessionParams(gpuLayers = 200).validated()
        
        assertEquals(-1, tooLow.gpuLayers)
        assertEquals(100, tooHigh.gpuLayers)
    }
}

/**
 * Unit tests for SessionParamsSupport.
 */
class SessionParamsSupportTest {

    @Test
    fun `NONE has no support`() {
        assertFalse(SessionParamsSupport.NONE.hasAnySupport())
    }

    @Test
    fun `GGUF_FULL has full support`() {
        val support = SessionParamsSupport.GGUF_FULL
        assertTrue(support.supportsRepeatPenalty)
        assertTrue(support.supportsContextLength)
        assertTrue(support.supportsGpuLayers)
        assertTrue(support.supportsAccelerators)
        assertTrue(support.hasAnySupport())
    }
}

