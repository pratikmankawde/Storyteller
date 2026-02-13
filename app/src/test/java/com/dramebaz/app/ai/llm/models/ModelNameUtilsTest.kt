package com.dramebaz.app.ai.llm.models

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ModelNameUtils.
 * Tests model name derivation and formatting.
 */
class ModelNameUtilsTest {

    // deriveDisplayName tests

    @Test
    fun `deriveDisplayName formats MediaPipe model correctly`() {
        val result = ModelNameUtils.deriveDisplayName("gemma-3n-E2B-it-int4.task")
        assertTrue(result.contains("Gemma"))
        assertTrue(result.contains("(INT4)"))
    }

    @Test
    fun `deriveDisplayName formats LiteRT-LM model correctly`() {
        val result = ModelNameUtils.deriveDisplayName("Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm")
        assertTrue(result.contains("Qwen2.5"))
        assertTrue(result.contains("(Q8)"))
        assertFalse(result.contains("multi-prefill-seq"))
    }

    @Test
    fun `deriveDisplayName formats GGUF model correctly`() {
        val result = ModelNameUtils.deriveDisplayName("llama-3.2-1b-instruct-q4_k_m.gguf")
        assertTrue(result.contains("Llama"))
        assertTrue(result.contains("3.2"))
        assertTrue(result.uppercase().contains("Q4"))
    }

    @Test
    fun `deriveDisplayName handles phi model`() {
        val result = ModelNameUtils.deriveDisplayName("phi-4-mini-instruct-int4.task")
        assertTrue(result.contains("Phi"))
        assertTrue(result.contains("Mini"))
        assertTrue(result.contains("(INT4)"))
    }

    @Test
    fun `deriveDisplayName handles full path`() {
        val result = ModelNameUtils.deriveDisplayName("/storage/emulated/0/Download/model-name-q8.gguf")
        assertTrue(result.contains("Model"))
        assertTrue(result.uppercase().contains("Q8"))
    }

    @Test
    fun `deriveDisplayName removes multi-prefill-seq suffix`() {
        val result = ModelNameUtils.deriveDisplayName("test_multi-prefill-seq_q4.litertlm")
        assertFalse(result.contains("multi-prefill-seq"))
        assertFalse(result.contains("prefill"))
    }

    @Test
    fun `deriveDisplayName capitalizes words correctly`() {
        val result = ModelNameUtils.deriveDisplayName("simple-model-name.gguf")
        assertTrue(result.contains("Simple"))
        assertTrue(result.contains("Model"))
        assertTrue(result.contains("Name"))
    }

    @Test
    fun `deriveDisplayName preserves version numbers`() {
        val result = ModelNameUtils.deriveDisplayName("model-2.5-1B.gguf")
        assertTrue(result.contains("2.5"))
        assertTrue(result.contains("1B"))
    }

    @Test
    fun `deriveDisplayName handles underscore separators`() {
        val result = ModelNameUtils.deriveDisplayName("model_with_underscores.gguf")
        assertTrue(result.contains("Model"))
        assertTrue(result.contains("With"))
        assertTrue(result.contains("Underscores"))
    }

    @Test
    fun `deriveDisplayName handles model without quantization`() {
        val result = ModelNameUtils.deriveDisplayName("simple-model.gguf")
        assertEquals("Simple Model", result)
    }

    // getDefaultName tests

    @Test
    fun `getDefaultName returns correct name for gguf`() {
        assertEquals("GGUF Model", ModelNameUtils.getDefaultName("gguf"))
        assertEquals("GGUF Model", ModelNameUtils.getDefaultName("GGUF"))
    }

    @Test
    fun `getDefaultName returns correct name for litertlm`() {
        assertEquals("LiteRT-LM Model", ModelNameUtils.getDefaultName("litertlm"))
    }

    @Test
    fun `getDefaultName returns correct name for mediapipe`() {
        assertEquals("MediaPipe Model", ModelNameUtils.getDefaultName("mediapipe"))
    }

    @Test
    fun `getDefaultName returns Unknown Model for unrecognized type`() {
        assertEquals("Unknown Model", ModelNameUtils.getDefaultName("xyz"))
        assertEquals("Unknown Model", ModelNameUtils.getDefaultName(""))
    }

    // Edge cases

    @Test
    fun `deriveDisplayName handles empty filename gracefully`() {
        val result = ModelNameUtils.deriveDisplayName(".gguf")
        // Should not crash, may return empty or minimal string
        assertNotNull(result)
    }

    @Test
    fun `deriveDisplayName handles just quantization suffix`() {
        val result = ModelNameUtils.deriveDisplayName("q8.gguf")
        // Should handle gracefully
        assertNotNull(result)
    }

    @Test
    fun `deriveDisplayName preserves short acronyms`() {
        val result = ModelNameUtils.deriveDisplayName("E2B-GPU-test.task")
        assertTrue(result.contains("E2B"))
        assertTrue(result.contains("GPU"))
    }
}

