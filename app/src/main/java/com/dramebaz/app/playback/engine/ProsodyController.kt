package com.dramebaz.app.playback.engine

import com.dramebaz.app.ai.tts.LibrittsSpeakerCatalog
import com.dramebaz.app.ai.tts.VoiceProfileMapper
import com.dramebaz.app.ai.tts.VoiceProfileMapper.TtsParams
import com.dramebaz.app.data.models.Dialog
import com.dramebaz.app.data.models.EmotionalSegment
import com.dramebaz.app.data.models.VoiceProfile

/**
 * T2.2: Adjusts TTS rate, pitch, volume from emotion and intensity.
 * T2.3: For narration, uses chapter emotional_arc for softer prosody.
 * AUG-013: Uses Dialog.prosody hints (speed, pitchVariation, stressPattern) to enhance TTS.
 */
object ProsodyController {

    /**
     * AUG-013: Generate TTS params for dialog with prosody hints support.
     * Since VITS cannot adjust pitch at runtime, we use speaker selection for pitch variation.
     * Returns TtsParams with optional alternate speaker ID for pitch variation.
     * @param enableEmotionModifiers If false, returns neutral prosody without emotion-based adjustments.
     */
    fun forDialog(dialog: Dialog, voiceProfile: VoiceProfile?, baseSpeakerId: Int? = null, enableEmotionModifiers: Boolean = true): TtsParams {
        val base = VoiceProfileMapper.toTtsParams(voiceProfile)

        // AUG-FEATURE: If emotion modifiers disabled, return base params without emotion adjustments
        if (!enableEmotionModifiers) {
            return TtsParams(
                pitch = base.pitch,
                speed = base.speed,
                energy = base.energy,
                emotionPreset = "neutral",
                speakerId = baseSpeakerId
            )
        }

        val intensity = dialog.intensity.coerceIn(0f, 1f)

        // Base speed scale from emotion
        var speedScale = when (dialog.emotion.lowercase()) {
            "anger", "fear" -> 1f + intensity * 0.2f
            "sad" -> 1f - intensity * 0.15f
            else -> 1f
        }

        // Base pitch scale from emotion (used for speaker selection, not direct pitch control)
        var pitchScale = when (dialog.emotion.lowercase()) {
            "anger", "surprise" -> 1f + intensity * 0.1f
            "sad" -> 1f - intensity * 0.05f
            else -> 1f
        }

        // Energy scale from emotion and intensity
        var energyScale = 0.8f + intensity * 0.2f

        // AUG-013: Apply prosody hints from dialog
        val prosody = dialog.prosody
        if (prosody != null) {
            // Apply speed hints
            speedScale *= when (prosody.speed.lowercase()) {
                "fast", "quick", "rapid" -> 1.2f
                "slow", "deliberate" -> 0.8f
                else -> 1f
            }

            // Apply stress pattern hints to energy
            when (prosody.stressPattern.lowercase()) {
                "emphasized", "strong", "intense" -> {
                    energyScale *= 1.2f
                    speedScale *= 1.05f  // Slightly faster for emphasis
                }
                "subdued", "soft", "quiet" -> {
                    energyScale *= 0.8f
                    speedScale *= 0.95f  // Slightly slower for subdued
                }
            }

            // Pitch variation affects speaker selection (handled via speakerId)
            pitchScale *= when (prosody.pitchVariation.lowercase()) {
                "high", "higher", "excited" -> 1.15f
                "low", "lower", "deep" -> 0.85f
                else -> 1f
            }
        }

        // Determine target pitch level for speaker selection (AUG-013)
        val targetPitch = when {
            pitchScale >= 1.1f -> LibrittsSpeakerCatalog.PitchLevel.HIGH
            pitchScale <= 0.9f -> LibrittsSpeakerCatalog.PitchLevel.LOW
            else -> LibrittsSpeakerCatalog.PitchLevel.MEDIUM
        }

        // Find alternate speaker for pitch variation if needed
        val alternateSpeakerId = if (baseSpeakerId != null && pitchScale != 1f) {
            val basePitch = LibrittsSpeakerCatalog.getTraits(baseSpeakerId)?.pitchLevel
            if (basePitch != targetPitch) {
                LibrittsSpeakerCatalog.findSpeakerWithDifferentPitch(baseSpeakerId, targetPitch)?.speakerId
            } else null
        } else null

        return TtsParams(
            pitch = base.pitch * pitchScale,  // Kept for metadata, not used by VITS
            speed = (base.speed * speedScale).coerceIn(0.5f, 2.0f),
            energy = (base.energy * energyScale).coerceIn(0.5f, 1.5f),
            emotionPreset = dialog.emotion.ifEmpty { base.emotionPreset },
            speakerId = alternateSpeakerId ?: baseSpeakerId
        )
    }

    /**
     * Get suggested pitch level based on prosody hints.
     * Useful for speaker selection in playback engine.
     */
    fun getPitchLevelFromDialog(dialog: Dialog): LibrittsSpeakerCatalog.PitchLevel {
        val prosody = dialog.prosody ?: return LibrittsSpeakerCatalog.PitchLevel.MEDIUM

        // From prosody hints
        val prosodyPitch = when (prosody.pitchVariation.lowercase()) {
            "high", "higher", "excited" -> LibrittsSpeakerCatalog.PitchLevel.HIGH
            "low", "lower", "deep" -> LibrittsSpeakerCatalog.PitchLevel.LOW
            else -> null
        }
        if (prosodyPitch != null) return prosodyPitch

        // From emotion
        return when (dialog.emotion.lowercase()) {
            "anger", "surprise", "joy", "excited" -> LibrittsSpeakerCatalog.PitchLevel.HIGH
            "sad", "tired", "calm" -> LibrittsSpeakerCatalog.PitchLevel.LOW
            else -> LibrittsSpeakerCatalog.PitchLevel.MEDIUM
        }
    }

    fun forNarration(emotionalArc: List<EmotionalSegment>, segmentIndex: Int, voiceProfile: VoiceProfile?): TtsParams {
        val base = VoiceProfileMapper.toTtsParams(voiceProfile)
        val arc = emotionalArc.getOrNull(segmentIndex)
        val intensity = (arc?.intensity ?: 0.5f) * 0.6f // softer for narration
        return TtsParams(
            pitch = base.pitch,
            speed = base.speed * (0.95f + intensity * 0.1f),
            energy = base.energy * (0.9f + intensity * 0.1f),
            emotionPreset = arc?.emotion ?: "neutral"
        )
    }
}
