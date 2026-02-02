package com.dramebaz.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * T7.1: Bookmark – bookid, chapterid, paragraphindex, timestamp, contextsummary, charactersinvolved, emotionsnapshot.
 * AUG-035: Added composite indexes for optimized bookmark queries.
 */
@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Chapter::class, parentColumns = ["id"], childColumns = ["chapterId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [
        Index("bookId"),
        Index("chapterId"),
        Index(value = ["bookId", "chapterId"], name = "idx_bookmark_book_chapter"), // AUG-035: Composite index
        Index(value = ["bookId", "timestamp"], name = "idx_bookmark_book_time") // AUG-035: For sorted bookmark queries
    ]
)
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterId: Long,
    val paragraphIndex: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val contextSummary: String = "",
    val charactersInvolved: String = "",
    val emotionSnapshot: String = ""
)
