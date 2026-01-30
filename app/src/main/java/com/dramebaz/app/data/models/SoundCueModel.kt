package com.dramebaz.app.data.models

import com.google.gson.annotations.SerializedName

data class SoundCueModel(
    val event: String = "",
    @SerializedName("sound_prompt") val soundPrompt: String = "",
    val duration: Float = 2f,
    val category: String = "effect" // "effect" | "ambience"
)
