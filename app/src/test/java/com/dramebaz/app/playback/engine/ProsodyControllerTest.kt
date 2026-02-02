package com.dramebaz.app.playback.engine

import com.dramebaz.app.ai.tts.LibrittsSpeakerCatalog
import com.dramebaz.app.data.models.ChapterSummary
import com.dramebaz.app.data.models.Dialog
import com.dramebaz.app.data.models.EmotionalSegment
import com.dramebaz.app.data.models.ProsodyHints
import com.dramebaz.app.data.models.VoiceProfile
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ProsodyController.
 * Tests TTS parameter adjustment based on emotion and prosody hints.
 */
class ProsodyControllerTest {

    @Test
    fun `forDialog returns base params for neutral dialog`() {
        val dialog = Dialog(
            speaker = "John",
            dialog = "Hello there",
            emotion = "neutral",
            intensity = 0.5f
        )

        val params = ProsodyController.forDialog(dialog, null)

        // For neutral emotion, speed should be close to base
        assertEquals(1f, params.speed, 0.3f)
        assertEquals("neutral", params.emotionPreset)
    }

    @Test
    fun `forDialog increases speed for anger emotion`() {
        val dialog = Dialog(
            speaker = "Villain",
            dialog = "You will pay!",
            emotion = "anger",
            intensity = 1.0f
        )

        val params = ProsodyController.forDialog(dialog, null)

        // Anger should increase speed (1 + intensity * 0.2 = 1.2)
        assertTrue(params.speed >= 1.0f)
        assertEquals("anger", params.emotionPreset)
    }

    @Test
    fun `forDialog decreases speed for sad emotion`() {
        val dialog = Dialog(
            speaker = "Mourner",
            dialog = "I miss him so much",
            emotion = "sad",
            intensity = 1.0f
        )

        val params = ProsodyController.forDialog(dialog, null)

        // Sad should decrease speed (1 - intensity * 0.15 = 0.85)
        assertTrue(params.speed <= 1.0f)
        assertEquals("sad", params.emotionPreset)
    }

    @Test
    fun `forDialog applies fast prosody hint`() {
        val dialog = Dialog(
            speaker = "Excited",
            dialog = "Hurry up!",
            emotion = "neutral",
            intensity = 0.5f,
            prosody = ProsodyHints(speed = "fast")
        )

        val params = ProsodyController.forDialog(dialog, null)

        // Fast speed hint should increase speed by 1.2x
        assertTrue(params.speed >= 1.0f)
    }

    @Test
    fun `forDialog applies slow prosody hint`() {
        val dialog = Dialog(
            speaker = "Thoughtful",
            dialog = "Let me think...",
            emotion = "neutral",
            intensity = 0.5f,
            prosody = ProsodyHints(speed = "slow")
        )

        val params = ProsodyController.forDialog(dialog, null)

        // Slow speed hint should decrease speed by 0.8x
        assertTrue(params.speed <= 1.0f)
    }

    @Test
    fun `forDialog applies emphasized stress pattern`() {
        val dialog = Dialog(
            speaker = "Emphatic",
            dialog = "This is IMPORTANT!",
            emotion = "neutral",
            intensity = 0.5f,
            prosody = ProsodyHints(stressPattern = "emphasized")
        )

        val params = ProsodyController.forDialog(dialog, null)

        // Emphasized should increase energy
        assertTrue(params.energy >= 0.8f)
    }

    @Test
    fun `forDialog clamps speed to valid range`() {
        // Create dialog that would result in very high speed
        val dialog = Dialog(
            speaker = "Fast",
            dialog = "Go go go!",
            emotion = "anger",
            intensity = 1.0f,
            prosody = ProsodyHints(speed = "fast")
        )
        val profile = VoiceProfile(speed = 1.5f)

        val params = ProsodyController.forDialog(dialog, profile)

        // Speed should be clamped to max 2.0
        assertTrue(params.speed <= 2.0f)
    }

    @Test
    fun `getPitchLevelFromDialog returns MEDIUM for neutral emotion without prosody`() {
        // Without prosody, the method returns MEDIUM regardless of emotion
        val dialog = Dialog(emotion = "neutral")

        val pitchLevel = ProsodyController.getPitchLevelFromDialog(dialog)

        assertEquals(LibrittsSpeakerCatalog.PitchLevel.MEDIUM, pitchLevel)
    }

    @Test
    fun `getPitchLevelFromDialog returns HIGH for anger emotion with empty prosody`() {
        // With prosody set, it checks pitch variation first, then emotion
        val dialog = Dialog(
            emotion = "anger",
            prosody = ProsodyHints(pitchVariation = "")  // Empty prosody pitch triggers emotion-based lookup
        )

        val pitchLevel = ProsodyController.getPitchLevelFromDialog(dialog)

        assertEquals(LibrittsSpeakerCatalog.PitchLevel.HIGH, pitchLevel)
    }

    @Test
    fun `getPitchLevelFromDialog returns LOW for sad emotion with empty prosody`() {
        // With prosody set, it checks pitch variation first, then emotion
        val dialog = Dialog(
            emotion = "sad",
            prosody = ProsodyHints(pitchVariation = "")  // Empty prosody pitch triggers emotion-based lookup
        )

        val pitchLevel = ProsodyController.getPitchLevelFromDialog(dialog)

        assertEquals(LibrittsSpeakerCatalog.PitchLevel.LOW, pitchLevel)
    }

    @Test
    fun `getPitchLevelFromDialog respects prosody pitch variation over emotion`() {
        val dialog = Dialog(
            emotion = "neutral",
            prosody = ProsodyHints(pitchVariation = "high")
        )

        val pitchLevel = ProsodyController.getPitchLevelFromDialog(dialog)

        assertEquals(LibrittsSpeakerCatalog.PitchLevel.HIGH, pitchLevel)
    }
}
