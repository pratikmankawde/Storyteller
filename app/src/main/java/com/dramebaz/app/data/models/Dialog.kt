package com.dramebaz.app.data.models

import com.google.gson.annotations.SerializedName

/**
 * T2.1: Dialog with emotion, intensity, prosody.
 * AUG-032: Added confidence field for attribution confidence scoring.
 */
data class Dialog(
    val speaker: String = "unknown",
    val dialog: String = "",
    val emotion: String = "neutral",
    val intensity: Float = 0.5f,
    val prosody: ProsodyHints? = null,
    /** AUG-032: Confidence score for dialog attribution (0.0-1.0). Low confidence (<0.6) means uncertain speaker. */
    val confidence: Float = 1.0f
) {
    /** AUG-032: Check if dialog attribution has low confidence (< 0.6 threshold) */
    fun isLowConfidence(): Boolean = confidence < 0.6f
}

data class ProsodyHints(
    @SerializedName("pitch_variation") val pitchVariation: String = "normal",
    val speed: String = "normal",
    @SerializedName("stress_pattern") val stressPattern: String = ""
)
