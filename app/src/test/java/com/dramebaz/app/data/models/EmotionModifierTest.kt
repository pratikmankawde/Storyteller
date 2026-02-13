package com.dramebaz.app.data.models

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for EmotionModifier.
 * Tests emotion-based TTS modulation parameters.
 */
class EmotionModifierTest {

    // forEmotion tests

    @Test
    fun `forEmotion returns neutral for null`() {
        val modifier = EmotionModifier.forEmotion(null)
        assertEquals(1.0f, modifier.speedMultiplier, 0.01f)
        assertEquals(1.0f, modifier.pitchMultiplier, 0.01f)
        assertEquals(1.0f, modifier.volumeMultiplier, 0.01f)
    }

    @Test
    fun `forEmotion returns neutral for blank string`() {
        val modifier = EmotionModifier.forEmotion("")
        assertEquals(1.0f, modifier.speedMultiplier, 0.01f)
    }

    @Test
    fun `forEmotion returns neutral for whitespace`() {
        val modifier = EmotionModifier.forEmotion("   ")
        assertEquals(1.0f, modifier.speedMultiplier, 0.01f)
    }

    @Test
    fun `forEmotion returns sad modifiers`() {
        val modifier = EmotionModifier.forEmotion("sad")
        assertEquals(0.8f, modifier.speedMultiplier, 0.01f)
        assertEquals(0.9f, modifier.pitchMultiplier, 0.01f)
        assertEquals(1.0f, modifier.volumeMultiplier, 0.01f)
    }

    @Test
    fun `forEmotion returns angry modifiers`() {
        val modifier = EmotionModifier.forEmotion("angry")
        assertEquals(1.2f, modifier.speedMultiplier, 0.01f)
        assertEquals(1.2f, modifier.pitchMultiplier, 0.01f)
        assertEquals(1.2f, modifier.volumeMultiplier, 0.01f)
    }

    @Test
    fun `forEmotion returns fear modifiers`() {
        val modifier = EmotionModifier.forEmotion("fear")
        assertEquals(1.1f, modifier.speedMultiplier, 0.01f)
        assertEquals(1.3f, modifier.pitchMultiplier, 0.01f)
    }

    @Test
    fun `forEmotion returns whisper modifiers`() {
        val modifier = EmotionModifier.forEmotion("whisper")
        assertEquals(0.9f, modifier.speedMultiplier, 0.01f)
        assertEquals(0.5f, modifier.volumeMultiplier, 0.01f)
    }

    @Test
    fun `forEmotion returns happy modifiers`() {
        val modifier = EmotionModifier.forEmotion("happy")
        assertEquals(1.1f, modifier.speedMultiplier, 0.01f)
        assertEquals(1.1f, modifier.pitchMultiplier, 0.01f)
    }

    @Test
    fun `forEmotion is case insensitive`() {
        val lower = EmotionModifier.forEmotion("happy")
        val upper = EmotionModifier.forEmotion("HAPPY")
        val mixed = EmotionModifier.forEmotion("HaPpY")
        
        assertEquals(lower.speedMultiplier, upper.speedMultiplier, 0.01f)
        assertEquals(lower.speedMultiplier, mixed.speedMultiplier, 0.01f)
    }

    @Test
    fun `forEmotion handles compound emotions`() {
        // Should use first part
        val modifier = EmotionModifier.forEmotion("angry_whisper")
        assertEquals(EmotionModifier.forEmotion("angry").speedMultiplier, modifier.speedMultiplier, 0.01f)
    }

    @Test
    fun `forEmotion handles hyphen-separated compound`() {
        val modifier = EmotionModifier.forEmotion("sad-fear")
        assertEquals(EmotionModifier.forEmotion("sad").speedMultiplier, modifier.speedMultiplier, 0.01f)
    }

    @Test
    fun `forEmotion returns neutral for unknown emotion`() {
        val modifier = EmotionModifier.forEmotion("nonexistent_emotion")
        assertEquals(1.0f, modifier.speedMultiplier, 0.01f)
    }

    // apply tests

    @Test
    fun `apply returns adjusted values for neutral`() {
        val (speed, pitch, volume) = EmotionModifier.apply(1.0f, 1.0f, 1.0f, "neutral")
        assertEquals(1.0f, speed, 0.01f)
        assertEquals(1.0f, pitch, 0.01f)
        assertEquals(1.0f, volume, 0.01f)
    }

    @Test
    fun `apply multiplies base values correctly`() {
        val (speed, pitch, volume) = EmotionModifier.apply(1.0f, 1.0f, 1.0f, "angry")
        assertEquals(1.2f, speed, 0.01f)
        assertEquals(1.2f, pitch, 0.01f)
        assertEquals(1.2f, volume, 0.01f)
    }

    @Test
    fun `apply clamps speed to valid range`() {
        val (speed, _, _) = EmotionModifier.apply(2.0f, 1.0f, 1.0f, "angry")
        assertTrue(speed <= 2.0f)
        
        val (slowSpeed, _, _) = EmotionModifier.apply(0.3f, 1.0f, 1.0f, "sad")
        assertTrue(slowSpeed >= 0.5f)
    }

    @Test
    fun `apply clamps pitch to valid range`() {
        val (_, pitch, _) = EmotionModifier.apply(1.0f, 1.8f, 1.0f, "fear")
        assertTrue(pitch <= 2.0f)
    }

    @Test
    fun `apply clamps volume to valid range`() {
        val (_, _, volume) = EmotionModifier.apply(1.0f, 1.0f, 0.2f, "whisper")
        assertTrue(volume >= 0.3f)
        
        val (_, _, loudVolume) = EmotionModifier.apply(1.0f, 1.0f, 1.5f, "angry")
        assertTrue(loudVolume <= 1.5f)
    }

    // Extended emotions tests

    @Test
    fun `forEmotion returns joy modifiers`() {
        val modifier = EmotionModifier.forEmotion("joy")
        assertEquals(1.15f, modifier.speedMultiplier, 0.01f)
    }

    @Test
    fun `forEmotion returns melancholy modifiers`() {
        val modifier = EmotionModifier.forEmotion("melancholy")
        assertEquals(0.75f, modifier.speedMultiplier, 0.01f)
        assertEquals(0.85f, modifier.pitchMultiplier, 0.01f)
    }

    @Test
    fun `EMOTION_MODIFIERS contains all expected emotions`() {
        val expectedEmotions = listOf(
            "neutral", "sad", "angry", "fear", "whisper", "happy",
            "joy", "excitement", "surprise", "calm", "anxious",
            "tired", "confident", "sarcastic", "tender", "stern"
        )
        
        for (emotion in expectedEmotions) {
            assertTrue("Missing emotion: $emotion", 
                EmotionModifier.EMOTION_MODIFIERS.containsKey(emotion))
        }
    }
}

