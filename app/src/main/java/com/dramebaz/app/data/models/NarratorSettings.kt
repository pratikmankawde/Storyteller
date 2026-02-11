package com.dramebaz.app.data.models

/**
 * NARRATOR-001: Settings for the Narrator voice.
 * Persisted in AppSettings key-value store.
 *
 * Default speed is 0.75 (noticeably slower than normal 1.0) for better clarity
 * and comfortable listening pace during narration.
 */
data class NarratorSettings(
    /** Speaker ID for narrator voice (model-dependent) */
    val speakerId: Int = 0,
    /** Speech speed (0.5-2.0), default 0.75 for comfortable narration pace */
    val speed: Float = DEFAULT_SPEED,
    /** Speech energy/intensity (0.5-1.5) */
    val energy: Float = 1.0f
) {
    companion object {
        /** Default narrator speed - slower than normal for comfortable listening */
        const val DEFAULT_SPEED = 0.75f
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f
        const val MIN_ENERGY = 0.5f
        const val MAX_ENERGY = 1.5f
    }
}

