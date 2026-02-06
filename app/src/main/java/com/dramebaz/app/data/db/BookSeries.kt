package com.dramebaz.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * SUMMARY-002: BookSeries entity for grouping books into series.
 * Allows multi-book cross-reference in recaps.
 */
@Entity(tableName = "book_series")
data class BookSeries(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Name of the series (e.g., "Harry Potter", "Lord of the Rings") */
    val name: String,
    /** Optional description of the series */
    val description: String? = null,
    /** Timestamp when the series was created */
    val createdAt: Long = System.currentTimeMillis()
)

