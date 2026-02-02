package com.dramebaz.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks which characters appear on which pages, with their dialog segments.
 * Used for:
 * 1. Per-segment audio generation with character-specific speaker IDs
 * 2. Audio cache invalidation when speaker changes
 * 3. Quick lookup of characters on a page for TTS
 */
@Entity(
    tableName = "character_page_mappings",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Chapter::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("bookId"),
        Index("chapterId"),
        Index(value = ["bookId", "chapterId", "pageNumber"], name = "idx_mapping_book_chapter_page"),
        Index(value = ["bookId", "characterName"], name = "idx_mapping_book_character")
    ]
)
data class CharacterPageMapping(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterId: Long,
    val pageNumber: Int,           // 1-based page number within chapter (PDF page or screen page)
    val segmentIndex: Int,         // 0-based index of this segment on the page
    val characterName: String,     // Character name (or "Narrator" for narration)
    val speakerId: Int?,           // Speaker ID used for TTS (0-108 for VCTK, null for default)
    val dialogText: String,        // The actual text of this segment
    val isDialog: Boolean,         // true for dialog, false for narration
    val firstAppearance: Boolean = false,  // true if this is character's first appearance in book
    val audioGenerated: Boolean = false,   // true if audio file exists for this segment
    val audioPath: String? = null  // Path to generated audio file (relative to app files dir)
)

