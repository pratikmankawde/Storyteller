package com.dramebaz.app.playback.engine

import com.dramebaz.app.ai.tts.VoiceProfileMapper
import com.dramebaz.app.ai.tts.VoiceProfileMapper.TtsParams
import com.dramebaz.app.data.models.Dialog
import com.dramebaz.app.data.models.EmotionalSegment
import com.dramebaz.app.data.models.VoiceProfile

/**
 * T2.2: Adjusts TTS rate, pitch, volume from emotion and intensity.
 * T2.3: For narration, uses chapter emotional_arc for softer prosody.
 */
object ProsodyController {

    fun forDialog(dialog: Dialog, voiceProfile: VoiceProfile?): TtsParams {
        val base = VoiceProfileMapper.toTtsParams(voiceProfile)
        val intensity = dialog.intensity.coerceIn(0f, 1f)
        val speedScale = when (dialog.emotion.lowercase()) {
            "anger", "fear" -> 1f + intensity * 0.2f
            "sad" -> 1f - intensity * 0.15f
            else -> 1f
        }
        val pitchScale = when (dialog.emotion.lowercase()) {
            "anger", "surprise" -> 1f + intensity * 0.1f
            "sad" -> 1f - intensity * 0.05f
            else -> 1f
        }
        return TtsParams(
            pitch = base.pitch * pitchScale,
            speed = base.speed * speedScale,
            energy = base.energy * (0.8f + intensity * 0.2f),
            emotionPreset = dialog.emotion.ifEmpty { base.emotionPreset }
        )
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
