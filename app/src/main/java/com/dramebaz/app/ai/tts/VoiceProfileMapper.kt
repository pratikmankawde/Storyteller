package com.dramebaz.app.ai.tts

import com.dramebaz.app.data.models.EmotionModifier
import com.dramebaz.app.data.models.VoiceProfile

/**
 * T1.3: Mapping layer VoiceProfile -> SherpaTTS parameters.
 * AUDIO-002: Enhanced to apply emotion modifiers for speed/pitch/volume adjustments.
 */
object VoiceProfileMapper {

    data class TtsParams(
        val pitch: Float,
        val speed: Float,
        val energy: Float,
        val emotionPreset: String,
        val speakerId: Int? = null  // T11.1: Optional speaker ID (0-108 for VCTK)
    ) {
        /**
         * AUDIO-002: Apply emotion modifiers to these TTS parameters.
         * Returns new TtsParams with adjusted speed, pitch, and energy based on emotion.
         */
        fun withEmotionModifiers(emotion: String? = null): TtsParams {
            val emotionToApply = emotion ?: emotionPreset
            val (adjustedSpeed, adjustedPitch, adjustedEnergy) = EmotionModifier.apply(
                baseSpeed = speed,
                basePitch = pitch,
                baseVolume = energy,
                emotion = emotionToApply
            )
            return copy(
                speed = adjustedSpeed,
                pitch = adjustedPitch,
                energy = adjustedEnergy,
                emotionPreset = emotionToApply
            )
        }
    }

    fun toTtsParams(profile: VoiceProfile?): TtsParams {
        if (profile == null) return TtsParams(1f, 1f, 1f, "neutral")
        val bias = profile.emotionBias
        val dominant = bias.maxByOrNull { it.value }?.key ?: "neutral"
        return TtsParams(
            pitch = profile.pitch.coerceIn(0.5f, 1.5f),
            speed = profile.speed.coerceIn(0.5f, 1.5f),
            energy = profile.energy.coerceIn(0.5f, 1.5f),
            emotionPreset = dominant
        )
    }

    /**
     * AUDIO-002: Create TTS params from voice profile with emotion modifiers applied.
     * Convenience method that combines profile mapping with emotion modification.
     */
    fun toTtsParamsWithEmotion(profile: VoiceProfile?, emotion: String?): TtsParams {
        return toTtsParams(profile).withEmotionModifiers(emotion)
    }
}
