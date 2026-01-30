package com.dramebaz.app.playback.mixer

/**
 * T8.1 / T8.2: Mood-based playback themes.
 */
enum class PlaybackTheme(
    val sfxVolumeMultiplier: Float,
    val ambienceVolumeMultiplier: Float,
    val prosodyIntensityScaling: Float,
    val voiceVariationLevel: Float
) {
    CINEMATIC(1.2f, 1.0f, 1.1f, 1.0f),
    RELAXED(0.6f, 0.8f, 0.8f, 0.9f),
    IMMERSIVE(1.0f, 1.2f, 1.2f, 1.1f),
    CLASSIC(0.8f, 0.5f, 1.0f, 1.0f)
}
