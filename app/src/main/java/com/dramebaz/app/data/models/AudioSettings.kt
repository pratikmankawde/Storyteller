package com.dramebaz.app.data.models

import com.dramebaz.app.playback.mixer.PlaybackTheme

/**
 * SETTINGS-001: Audio settings for playback experience.
 * Controls playback speed, theme, and audio behavior.
 */
data class AudioSettings(
    val playbackSpeed: Float = 1.0f,
    val playbackTheme: PlaybackTheme = PlaybackTheme.CLASSIC,
    val autoPlayNextChapter: Boolean = true,
    val pauseOnScreenOff: Boolean = true,
    val enableBackgroundPlayback: Boolean = true
) {
    companion object {
        val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        const val SPEED_MIN = 0.5f
        const val SPEED_MAX = 2.0f
    }
}

