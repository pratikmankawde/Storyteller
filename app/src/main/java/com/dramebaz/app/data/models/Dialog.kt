package com.dramebaz.app.data.models

import com.google.gson.annotations.SerializedName

/** T2.1: Dialog with emotion, intensity, prosody. */
data class Dialog(
    val speaker: String = "unknown",
    val dialog: String = "",
    val emotion: String = "neutral",
    val intensity: Float = 0.5f,
    val prosody: ProsodyHints? = null
)

data class ProsodyHints(
    @SerializedName("pitch_variation") val pitchVariation: String = "normal",
    val speed: String = "normal",
    @SerializedName("stress_pattern") val stressPattern: String = ""
)
