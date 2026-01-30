package com.dramebaz.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chapters",
    foreignKeys = [ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")]
)
data class Chapter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val title: String,
    val body: String,
    val orderIndex: Int,
    /** T4.1: chapter summary JSON */
    val summaryJson: String? = null,
    /** T9.2: extended analysis JSON (themes, symbols, vocab) */
    val analysisJson: String? = null,
    /** Full chapter analysis (dialogs, characters, sound_cues) for playback; set when analysis runs. */
    val fullAnalysisJson: String? = null
)
