package com.dramebaz.app.ai.tts

import com.dramebaz.app.data.models.VoiceProfile

/**
 * T1.3: Mapping layer VoiceProfile -> SherpaTTS parameters.
 * Stub returns neutral params; real impl maps pitch/speed/emotionBias to engine controls.
 */
object VoiceProfileMapper {

    data class TtsParams(
        val pitch: Float,
        val speed: Float,
        val energy: Float,
        val emotionPreset: String,
        val speakerId: Int? = null  // T11.1: Optional speaker ID (0-108 for VCTK)
    )

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
}
