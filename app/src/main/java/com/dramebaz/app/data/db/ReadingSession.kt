package com.dramebaz.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * T6.1: ReadingSession – current book, chapter, paragraph index, playback position, mode.
 * SUMMARY-001: Added lastReadTimestamp for time-aware recaps.
 * AUDIO-REGEN-001: Added lastPageIndex for audio regeneration targeting.
 */
@Entity(tableName = "reading_sessions")
data class ReadingSession(
    @PrimaryKey val id: Long = 1L, // single row
    val bookId: Long = 0L,
    val chapterId: Long = 0L,
    val paragraphIndex: Int = 0,
    val playbackPositionMs: Long = 0L,
    val mode: String = "mixed", // "reading" | "listening" | "mixed"
    /** SUMMARY-001: Timestamp of when the user last read this book (milliseconds since epoch) */
    val lastReadTimestamp: Long = System.currentTimeMillis(),
    /** AUDIO-REGEN-001: Last page index the user was on (1-based page number). Used for targeted audio regeneration. */
    val lastPageIndex: Int = 1
)
