package com.dramebaz.app.data.models

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for Dialog and ProsodyHints data classes.
 * Tests dialog properties, confidence handling, and prosody hints.
 */
class DialogTest {

    // Default values tests

    @Test
    fun `Dialog has correct default values`() {
        val dialog = Dialog()
        assertEquals("unknown", dialog.speaker)
        assertEquals("", dialog.dialog)
        assertEquals("neutral", dialog.emotion)
        assertEquals(0.5f, dialog.intensity, 0.01f)
        assertNull(dialog.prosody)
        assertEquals(1.0f, dialog.confidence, 0.01f)
    }

    @Test
    fun `Dialog stores all properties correctly`() {
        val prosody = ProsodyHints(
            pitchVariation = "high",
            speed = "fast",
            stressPattern = "emphasized"
        )
        val dialog = Dialog(
            speaker = "Alice",
            dialog = "Hello, world!",
            emotion = "happy",
            intensity = 0.8f,
            prosody = prosody,
            confidence = 0.9f
        )

        assertEquals("Alice", dialog.speaker)
        assertEquals("Hello, world!", dialog.dialog)
        assertEquals("happy", dialog.emotion)
        assertEquals(0.8f, dialog.intensity, 0.01f)
        assertNotNull(dialog.prosody)
        assertEquals(0.9f, dialog.confidence, 0.01f)
    }

    // isLowConfidence tests

    @Test
    fun `isLowConfidence returns true for confidence below 0_6`() {
        val dialog = Dialog(confidence = 0.5f)
        assertTrue(dialog.isLowConfidence())
    }

    @Test
    fun `isLowConfidence returns true for confidence at 0_59`() {
        val dialog = Dialog(confidence = 0.59f)
        assertTrue(dialog.isLowConfidence())
    }

    @Test
    fun `isLowConfidence returns false for confidence at 0_6`() {
        val dialog = Dialog(confidence = 0.6f)
        assertFalse(dialog.isLowConfidence())
    }

    @Test
    fun `isLowConfidence returns false for high confidence`() {
        val dialog = Dialog(confidence = 0.9f)
        assertFalse(dialog.isLowConfidence())
    }

    @Test
    fun `isLowConfidence returns false for default confidence`() {
        val dialog = Dialog()
        assertFalse(dialog.isLowConfidence())
    }

    // Edge cases

    @Test
    fun `isLowConfidence handles zero confidence`() {
        val dialog = Dialog(confidence = 0.0f)
        assertTrue(dialog.isLowConfidence())
    }

    @Test
    fun `isLowConfidence handles max confidence`() {
        val dialog = Dialog(confidence = 1.0f)
        assertFalse(dialog.isLowConfidence())
    }
}

/**
 * Unit tests for ProsodyHints data class.
 */
class ProsodyHintsTest {

    @Test
    fun `ProsodyHints has correct default values`() {
        val hints = ProsodyHints()
        assertEquals("normal", hints.pitchVariation)
        assertEquals("normal", hints.speed)
        assertEquals("", hints.stressPattern)
    }

    @Test
    fun `ProsodyHints stores all properties correctly`() {
        val hints = ProsodyHints(
            pitchVariation = "high",
            speed = "fast",
            stressPattern = "emphasized"
        )
        assertEquals("high", hints.pitchVariation)
        assertEquals("fast", hints.speed)
        assertEquals("emphasized", hints.stressPattern)
    }

    @Test
    fun `ProsodyHints handles empty strings`() {
        val hints = ProsodyHints(
            pitchVariation = "",
            speed = "",
            stressPattern = ""
        )
        assertEquals("", hints.pitchVariation)
        assertEquals("", hints.speed)
        assertEquals("", hints.stressPattern)
    }
}

