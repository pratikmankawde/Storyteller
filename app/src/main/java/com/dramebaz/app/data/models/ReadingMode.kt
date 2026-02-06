package com.dramebaz.app.data.models

/**
 * READ-001: Reading Mode Toggle
 * Defines the three reading modes supported by the reader:
 * - TEXT: Standard reading, no audio auto-play
 * - AUDIO: Auto-play audio, minimal UI, focus on listening
 * - MIXED: Text with synchronized audio and karaoke highlighting
 */
enum class ReadingMode(
    val displayName: String,
    val iconResName: String,
    val description: String
) {
    TEXT(
        displayName = "Text",
        iconResName = "ic_menu_view",
        description = "Read without audio"
    ),
    AUDIO(
        displayName = "Audio",
        iconResName = "ic_lock_silent_mode_off",
        description = "Listen with minimal UI"
    ),
    MIXED(
        displayName = "Mixed",
        iconResName = "ic_menu_sort_by_size",
        description = "Read with synchronized audio"
    );

    companion object {
        /**
         * Convert from legacy string mode to ReadingMode enum.
         * Maps old string values: "reading" -> TEXT, "listening" -> AUDIO, "mixed" -> MIXED
         */
        fun fromLegacyString(mode: String?): ReadingMode = when (mode?.lowercase()) {
            "reading" -> TEXT
            "listening" -> AUDIO
            "mixed" -> MIXED
            "text" -> TEXT
            "audio" -> AUDIO
            else -> MIXED // Default to mixed mode
        }

        /**
         * Convert ReadingMode to legacy string for database storage.
         */
        fun toLegacyString(mode: ReadingMode): String = when (mode) {
            TEXT -> "reading"
            AUDIO -> "listening"
            MIXED -> "mixed"
        }

        /**
         * Get the next mode in the cycle (TEXT -> AUDIO -> MIXED -> TEXT)
         */
        fun next(current: ReadingMode): ReadingMode = when (current) {
            TEXT -> AUDIO
            AUDIO -> MIXED
            MIXED -> TEXT
        }

        /**
         * Get all modes in order.
         */
        fun all(): List<ReadingMode> = listOf(TEXT, AUDIO, MIXED)
    }
}

