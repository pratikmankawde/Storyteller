package com.dramebaz.app.data.models

import com.google.gson.annotations.SerializedName

/**
 * Mirrors chapter analysis JSON voice_profile.
 * Used for SherpaTTS parameter mapping (T1.2, T1.3).
 */
data class VoiceProfile(
    val pitch: Float = 1.0f,
    val speed: Float = 1.0f,
    val energy: Float = 1.0f,
    @SerializedName("emotion_bias") val emotionBias: Map<String, Float> = emptyMap()
)
