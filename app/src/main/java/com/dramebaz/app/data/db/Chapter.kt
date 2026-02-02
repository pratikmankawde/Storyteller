package com.dramebaz.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AUG-035: Added composite index on (bookId, orderIndex) for optimized chapter ordering queries.
 */
@Entity(
    tableName = "chapters",
    foreignKeys = [ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index("bookId"),
        Index(value = ["bookId", "orderIndex"], name = "idx_chapter_book_order") // AUG-035: Composite index for sorted chapter queries
    ]
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
    val fullAnalysisJson: String? = null,
    /**
     * PDF pages as JSON array: [{"pdfPage": 45, "text": "..."}, {"pdfPage": 46, "text": "..."}]
     * Each entry contains the original PDF page number and its text content.
     * Null for non-PDF books or older imported books.
     */
    val pdfPagesJson: String? = null
)
