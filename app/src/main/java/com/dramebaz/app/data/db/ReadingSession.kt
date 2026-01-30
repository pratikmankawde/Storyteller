package com.dramebaz.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** T6.1: ReadingSession – current book, chapter, paragraph index, playback position, mode. */
@Entity(tableName = "reading_sessions")
data class ReadingSession(
    @PrimaryKey val id: Long = 1L, // single row
    val bookId: Long = 0L,
    val chapterId: Long = 0L,
    val paragraphIndex: Int = 0,
    val playbackPositionMs: Long = 0L,
    val mode: String = "mixed" // "reading" | "listening" | "mixed"
)
