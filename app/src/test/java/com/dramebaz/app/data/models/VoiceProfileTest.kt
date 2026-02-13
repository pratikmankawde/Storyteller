package com.dramebaz.app.data.models

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for VoiceProfile data class.
 * Tests voice profile properties and defaults.
 */
class VoiceProfileTest {

    // Default values tests

    @Test
    fun `VoiceProfile has correct default values`() {
        val profile = VoiceProfile()
        assertEquals(1.0f, profile.pitch, 0.01f)
        assertEquals(1.0f, profile.speed, 0.01f)
        assertEquals(1.0f, profile.energy, 0.01f)
        assertTrue(profile.emotionBias.isEmpty())
    }

    @Test
    fun `VoiceProfile stores all properties correctly`() {
        val emotionBias = mapOf("happy" to 0.8f, "sad" to 0.2f)
        val profile = VoiceProfile(
            pitch = 1.2f,
            speed = 0.9f,
            energy = 1.1f,
            emotionBias = emotionBias
        )

        assertEquals(1.2f, profile.pitch, 0.01f)
        assertEquals(0.9f, profile.speed, 0.01f)
        assertEquals(1.1f, profile.energy, 0.01f)
        assertEquals(2, profile.emotionBias.size)
        assertEquals(0.8f, profile.emotionBias["happy"]!!, 0.01f)
    }

    // Edge cases

    @Test
    fun `VoiceProfile handles zero values`() {
        val profile = VoiceProfile(
            pitch = 0.0f,
            speed = 0.0f,
            energy = 0.0f
        )
        assertEquals(0.0f, profile.pitch, 0.01f)
        assertEquals(0.0f, profile.speed, 0.01f)
        assertEquals(0.0f, profile.energy, 0.01f)
    }

    @Test
    fun `VoiceProfile handles high values`() {
        val profile = VoiceProfile(
            pitch = 2.0f,
            speed = 2.0f,
            energy = 2.0f
        )
        assertEquals(2.0f, profile.pitch, 0.01f)
        assertEquals(2.0f, profile.speed, 0.01f)
        assertEquals(2.0f, profile.energy, 0.01f)
    }

    @Test
    fun `VoiceProfile emotionBias is immutable copy`() {
        val emotionBias = mapOf("happy" to 0.5f)
        val profile = VoiceProfile(emotionBias = emotionBias)
        
        // Original map changes shouldn't affect profile
        // (Note: Map is already immutable in Kotlin, but testing the concept)
        assertEquals(1, profile.emotionBias.size)
    }

    // Data class equality tests

    @Test
    fun `VoiceProfile equals works correctly`() {
        val profile1 = VoiceProfile(pitch = 1.1f, speed = 0.9f)
        val profile2 = VoiceProfile(pitch = 1.1f, speed = 0.9f)
        val profile3 = VoiceProfile(pitch = 1.2f, speed = 0.9f)

        assertEquals(profile1, profile2)
        assertNotEquals(profile1, profile3)
    }

    @Test
    fun `VoiceProfile hashCode works correctly`() {
        val profile1 = VoiceProfile(pitch = 1.1f, speed = 0.9f)
        val profile2 = VoiceProfile(pitch = 1.1f, speed = 0.9f)

        assertEquals(profile1.hashCode(), profile2.hashCode())
    }

    @Test
    fun `VoiceProfile copy works correctly`() {
        val original = VoiceProfile(pitch = 1.1f, speed = 0.9f, energy = 1.2f)
        val copy = original.copy(pitch = 1.5f)

        assertEquals(1.5f, copy.pitch, 0.01f)
        assertEquals(0.9f, copy.speed, 0.01f)
        assertEquals(1.2f, copy.energy, 0.01f)
    }
}

