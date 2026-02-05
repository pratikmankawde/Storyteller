package com.dramebaz.app.data.models

/**
 * AUDIO-002: Emotion-based TTS modulation parameters.
 * Defines speed, pitch, and volume multipliers for different emotions.
 * 
 * From NovelReaderWeb docs/AI.md - TTS Emotion Modifiers:
 * | Emotion   | Speed | Pitch | Volume |
 * |-----------|-------|-------|--------|
 * | neutral   | 1.0   | 1.0   | 1.0    |
 * | sad       | 0.8   | 0.9   | 1.0    |
 * | angry     | 1.2   | 1.2   | 1.2    |
 * | fear      | 1.1   | 1.3   | 1.0    |
 * | whisper   | 0.9   | 1.0   | 0.5    |
 * | happy     | 1.1   | 1.1   | 1.0    |
 */
data class EmotionModifier(
    val speedMultiplier: Float,
    val pitchMultiplier: Float,
    val volumeMultiplier: Float
) {
    companion object {
        /**
         * Pre-defined emotion modifiers based on NovelReaderWeb AI.md specifications.
         * Keys are lowercase emotion tags from LLM script generation.
         */
        val EMOTION_MODIFIERS: Map<String, EmotionModifier> = mapOf(
            // Core emotions from the Director script
            "neutral" to EmotionModifier(1.0f, 1.0f, 1.0f),
            "sad" to EmotionModifier(0.8f, 0.9f, 1.0f),
            "angry" to EmotionModifier(1.2f, 1.2f, 1.2f),
            "fear" to EmotionModifier(1.1f, 1.3f, 1.0f),
            "whisper" to EmotionModifier(0.9f, 1.0f, 0.5f),
            "happy" to EmotionModifier(1.1f, 1.1f, 1.0f),
            
            // Extended emotions for richer expressiveness
            "joy" to EmotionModifier(1.15f, 1.15f, 1.1f),
            "excitement" to EmotionModifier(1.25f, 1.2f, 1.15f),
            "surprise" to EmotionModifier(1.1f, 1.25f, 1.1f),
            "calm" to EmotionModifier(0.9f, 1.0f, 0.9f),
            "anxious" to EmotionModifier(1.15f, 1.2f, 1.0f),
            "tired" to EmotionModifier(0.75f, 0.9f, 0.8f),
            "confident" to EmotionModifier(1.05f, 1.05f, 1.1f),
            "sarcastic" to EmotionModifier(1.0f, 1.1f, 0.95f),
            "tender" to EmotionModifier(0.85f, 1.0f, 0.8f),
            "stern" to EmotionModifier(0.95f, 0.95f, 1.15f),
            "pleading" to EmotionModifier(0.9f, 1.15f, 0.95f),
            "contempt" to EmotionModifier(0.85f, 0.9f, 1.0f),
            "disgust" to EmotionModifier(0.9f, 0.95f, 1.05f),
            "curious" to EmotionModifier(1.0f, 1.1f, 0.95f),
            "nervous" to EmotionModifier(1.2f, 1.25f, 0.9f),
            "shocked" to EmotionModifier(1.0f, 1.3f, 1.1f),
            "melancholy" to EmotionModifier(0.75f, 0.85f, 0.85f),
            "determined" to EmotionModifier(1.0f, 1.0f, 1.2f)
        )

        /**
         * Get the emotion modifier for a given emotion tag.
         * Falls back to neutral if the emotion is not recognized.
         * 
         * @param emotion The emotion tag (case-insensitive)
         * @return EmotionModifier for the emotion, or neutral fallback
         */
        fun forEmotion(emotion: String?): EmotionModifier {
            if (emotion.isNullOrBlank()) return EMOTION_MODIFIERS["neutral"]!!
            
            val normalized = emotion.lowercase().trim()
            
            // Direct match
            EMOTION_MODIFIERS[normalized]?.let { return it }
            
            // Handle compound emotions (e.g., "angry_whisper" -> prioritize first part)
            val parts = normalized.split("_", "-", " ")
            if (parts.isNotEmpty()) {
                EMOTION_MODIFIERS[parts[0]]?.let { return it }
            }
            
            // Fallback to neutral
            return EMOTION_MODIFIERS["neutral"]!!
        }

        /**
         * Apply emotion modifiers to base TTS parameters.
         * Returns adjusted speed, pitch, and volume.
         * 
         * @param baseSpeed Base speed from voice profile (typically 1.0)
         * @param basePitch Base pitch (for metadata, not used by VITS)
         * @param baseVolume Base volume/energy (typically 1.0)
         * @param emotion Emotion tag from script
         * @return Triple of (adjustedSpeed, adjustedPitch, adjustedVolume)
         */
        fun apply(
            baseSpeed: Float,
            basePitch: Float,
            baseVolume: Float,
            emotion: String?
        ): Triple<Float, Float, Float> {
            val modifier = forEmotion(emotion)
            return Triple(
                (baseSpeed * modifier.speedMultiplier).coerceIn(0.5f, 2.0f),
                (basePitch * modifier.pitchMultiplier).coerceIn(0.5f, 2.0f),
                (baseVolume * modifier.volumeMultiplier).coerceIn(0.3f, 1.5f)
            )
        }
    }
}

