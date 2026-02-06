package com.dramebaz.app.data.models

/**
 * SUMMARY-001: Recap depth levels based on time since last read.
 * 
 * From NovelReaderWeb docs/SUMMARIES.md:
 * - BRIEF: <1 day absence, 1 chapter summary
 * - MEDIUM: 1-7 days absence, 3 chapter summaries
 * - DETAILED: >7 days absence, 5+ chapters with character reminders
 */
enum class RecapDepth(
    val chapterCount: Int,
    val includeCharacters: Boolean,
    val displayName: String,
    val description: String
) {
    BRIEF(
        chapterCount = 1,
        includeCharacters = false,
        displayName = "Quick Recap",
        description = "Just the last chapter"
    ),
    MEDIUM(
        chapterCount = 3,
        includeCharacters = false,
        displayName = "Standard Recap",
        description = "Last few chapters"
    ),
    DETAILED(
        chapterCount = 5,
        includeCharacters = true,
        displayName = "Full Recap",
        description = "Extended summary with character reminders"
    );

    companion object {
        /** Determine recap depth based on days since last read */
        fun fromDaysSinceLastRead(days: Float): RecapDepth = when {
            days < 1f -> BRIEF
            days <= 7f -> MEDIUM
            else -> DETAILED
        }
    }
}

/**
 * SUMMARY-001: Result of time-aware recap generation.
 * Contains the recap text, depth level, and optional character reminders.
 */
data class TimeAwareRecapResult(
    val recapText: String,
    val depth: RecapDepth,
    val daysSinceLastRead: Float,
    val characterReminders: List<CharacterReminder> = emptyList(),
    val chapterCount: Int = 0
) {
    /** Whether this is a first-time read (no previous session) */
    val isFirstRead: Boolean get() = daysSinceLastRead < 0

    /** Human-readable time since last read */
    val timeSinceLastReadText: String get() = when {
        isFirstRead -> "First time reading"
        daysSinceLastRead < 1f -> "Read earlier today"
        daysSinceLastRead < 2f -> "Read yesterday"
        daysSinceLastRead < 7f -> "${daysSinceLastRead.toInt()} days ago"
        daysSinceLastRead < 14f -> "About a week ago"
        daysSinceLastRead < 30f -> "${(daysSinceLastRead / 7).toInt()} weeks ago"
        else -> "${(daysSinceLastRead / 30).toInt()} months ago"
    }
}

/**
 * SUMMARY-001: Character reminder for long absences.
 * Brief description of key characters to help reader remember.
 */
data class CharacterReminder(
    val name: String,
    val description: String,
    val traits: List<String> = emptyList()
)

