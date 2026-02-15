package com.dramebaz.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Lightweight chapter summary for list views - excludes large body/JSON fields
 * to avoid SQLiteBlobTooBigException when loading many chapters.
 */
data class ChapterSummary(
    val id: Long,
    val bookId: Long,
    val title: String,
    val orderIndex: Int,
    /** Word count of the body (computed at insert time or via subquery) */
    val wordCount: Int,
    /** Whether fullAnalysisJson is present */
    val isAnalyzed: Boolean
)

/**
 * BLOB-FIX: Chapter analysis projection for dialog counting.
 * Only includes fullAnalysisJson, excludes body and other large fields.
 */
data class ChapterAnalysisOnly(
    val id: Long,
    val fullAnalysisJson: String?
)

/**
 * BLOB-FIX: Chapter with analysis for display in UI (summaries card, insights).
 * Includes title, summaryJson, analysisJson, and fullAnalysisJson but excludes body.
 */
data class ChapterWithAnalysis(
    val id: Long,
    val title: String,
    val orderIndex: Int,
    val summaryJson: String?,
    val analysisJson: String?,
    val fullAnalysisJson: String?
)

@Dao
interface ChapterDao {
    @Insert fun insert(chapter: Chapter): Long
    @Insert fun insertAll(chapters: List<Chapter>): List<Long>
    @Update fun update(chapter: Chapter): Int
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY orderIndex") fun getByBookId(bookId: Long): Flow<List<Chapter>>
    @Query("SELECT * FROM chapters WHERE id = :id") suspend fun getById(id: Long): Chapter?
    @Query("DELETE FROM chapters WHERE bookId = :bookId") suspend fun deleteByBookId(bookId: Long)

    /** CHAP-001: Delete a single chapter by ID */
    @Query("DELETE FROM chapters WHERE id = :chapterId") suspend fun deleteChapter(chapterId: Long)

    /** Get all chapters for a book as a list (for dialog counting). */
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY orderIndex")
    suspend fun getChaptersList(bookId: Long): List<Chapter>

    /** CROSS-CHAPTER: Get the next chapter after the given orderIndex for a book. */
    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND orderIndex > :currentOrderIndex ORDER BY orderIndex ASC LIMIT 1")
    suspend fun getNextChapter(bookId: Long, currentOrderIndex: Int): Chapter?

    /** CROSS-CHAPTER: Get the previous chapter before the given orderIndex for a book. */
    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND orderIndex < :currentOrderIndex ORDER BY orderIndex DESC LIMIT 1")
    suspend fun getPreviousChapter(bookId: Long, currentOrderIndex: Int): Chapter?

    /** CROSS-CHAPTER: Get the orderIndex of a chapter by its ID. */
    @Query("SELECT orderIndex FROM chapters WHERE id = :chapterId")
    suspend fun getOrderIndex(chapterId: Long): Int?

    /**
     * BLOB-FIX: Get lightweight chapter summaries (excludes body and large JSON fields).
     * Use this for list views to avoid SQLiteBlobTooBigException.
     */
    @Query("""
        SELECT
            id,
            bookId,
            title,
            orderIndex,
            LENGTH(body) - LENGTH(REPLACE(body, ' ', '')) + 1 AS wordCount,
            CASE WHEN fullAnalysisJson IS NOT NULL AND LENGTH(fullAnalysisJson) > 2 THEN 1 ELSE 0 END AS isAnalyzed
        FROM chapters
        WHERE bookId = :bookId
        ORDER BY orderIndex
    """)
    fun getSummariesByBookId(bookId: Long): Flow<List<ChapterSummary>>

    /**
     * BLOB-FIX: Get lightweight chapter summaries as a suspend list.
     */
    @Query("""
        SELECT
            id,
            bookId,
            title,
            orderIndex,
            LENGTH(body) - LENGTH(REPLACE(body, ' ', '')) + 1 AS wordCount,
            CASE WHEN fullAnalysisJson IS NOT NULL AND LENGTH(fullAnalysisJson) > 2 THEN 1 ELSE 0 END AS isAnalyzed
        FROM chapters
        WHERE bookId = :bookId
        ORDER BY orderIndex
    """)
    suspend fun getSummariesList(bookId: Long): List<ChapterSummary>

    /**
     * BLOB-FIX: Get first chapter ID for a book (for navigation without loading full chapter).
     */
    @Query("SELECT id FROM chapters WHERE bookId = :bookId ORDER BY orderIndex ASC LIMIT 1")
    suspend fun getFirstChapterId(bookId: Long): Long?

    /**
     * BLOB-FIX: Get chapter analyses only (for dialog counting).
     * Excludes body and other large fields to avoid CursorWindow overflow.
     */
    @Query("SELECT id, fullAnalysisJson FROM chapters WHERE bookId = :bookId ORDER BY orderIndex")
    suspend fun getAnalysesOnly(bookId: Long): List<ChapterAnalysisOnly>

    /**
     * BLOB-FIX: Get chapters with analysis data for display (UI summaries card).
     * Excludes body to avoid CursorWindow overflow.
     */
    @Query("SELECT id, title, orderIndex, summaryJson, analysisJson, fullAnalysisJson FROM chapters WHERE bookId = :bookId ORDER BY orderIndex")
    suspend fun getChaptersWithAnalysis(bookId: Long): List<ChapterWithAnalysis>
}
