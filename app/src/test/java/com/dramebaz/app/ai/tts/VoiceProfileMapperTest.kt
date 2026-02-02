package com.dramebaz.app.ai.tts

import com.dramebaz.app.data.models.VoiceProfile
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for VoiceProfileMapper.
 * Tests mapping from VoiceProfile to TTS parameters.
 */
class VoiceProfileMapperTest {

    @Test
    fun `toTtsParams returns neutral defaults for null profile`() {
        val params = VoiceProfileMapper.toTtsParams(null)
        
        assertEquals(1f, params.pitch, 0.01f)
        assertEquals(1f, params.speed, 0.01f)
        assertEquals(1f, params.energy, 0.01f)
        assertEquals("neutral", params.emotionPreset)
        assertNull(params.speakerId)
    }

    @Test
    fun `toTtsParams preserves valid pitch, speed, energy values`() {
        val profile = VoiceProfile(
            pitch = 1.2f,
            speed = 0.9f,
            energy = 1.1f,
            emotionBias = mapOf("happy" to 0.8f)
        )
        
        val params = VoiceProfileMapper.toTtsParams(profile)
        
        assertEquals(1.2f, params.pitch, 0.01f)
        assertEquals(0.9f, params.speed, 0.01f)
        assertEquals(1.1f, params.energy, 0.01f)
    }

    @Test
    fun `toTtsParams clamps pitch to valid range`() {
        // Test pitch below minimum
        val lowPitch = VoiceProfile(pitch = 0.1f)
        assertEquals(0.5f, VoiceProfileMapper.toTtsParams(lowPitch).pitch, 0.01f)
        
        // Test pitch above maximum
        val highPitch = VoiceProfile(pitch = 2.0f)
        assertEquals(1.5f, VoiceProfileMapper.toTtsParams(highPitch).pitch, 0.01f)
    }

    @Test
    fun `toTtsParams clamps speed to valid range`() {
        // Test speed below minimum
        val lowSpeed = VoiceProfile(speed = 0.1f)
        assertEquals(0.5f, VoiceProfileMapper.toTtsParams(lowSpeed).speed, 0.01f)
        
        // Test speed above maximum
        val highSpeed = VoiceProfile(speed = 2.0f)
        assertEquals(1.5f, VoiceProfileMapper.toTtsParams(highSpeed).speed, 0.01f)
    }

    @Test
    fun `toTtsParams clamps energy to valid range`() {
        // Test energy below minimum
        val lowEnergy = VoiceProfile(energy = 0.1f)
        assertEquals(0.5f, VoiceProfileMapper.toTtsParams(lowEnergy).energy, 0.01f)
        
        // Test energy above maximum
        val highEnergy = VoiceProfile(energy = 2.0f)
        assertEquals(1.5f, VoiceProfileMapper.toTtsParams(highEnergy).energy, 0.01f)
    }

    @Test
    fun `toTtsParams selects dominant emotion from bias map`() {
        val profile = VoiceProfile(
            emotionBias = mapOf(
                "happy" to 0.3f,
                "sad" to 0.5f,
                "neutral" to 0.2f
            )
        )
        
        val params = VoiceProfileMapper.toTtsParams(profile)
        assertEquals("sad", params.emotionPreset)
    }

    @Test
    fun `toTtsParams handles empty emotion bias map`() {
        val profile = VoiceProfile(emotionBias = emptyMap())
        
        val params = VoiceProfileMapper.toTtsParams(profile)
        assertEquals("neutral", params.emotionPreset)
    }

    @Test
    fun `toTtsParams uses neutral for profile with default values`() {
        val profile = VoiceProfile()
        
        val params = VoiceProfileMapper.toTtsParams(profile)
        
        assertEquals(1f, params.pitch, 0.01f)
        assertEquals(1f, params.speed, 0.01f)
        assertEquals(1f, params.energy, 0.01f)
        assertEquals("neutral", params.emotionPreset)
    }
}

