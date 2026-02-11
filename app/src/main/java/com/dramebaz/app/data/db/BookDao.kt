package com.dramebaz.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Insert fun insert(book: Book): Long
    @Update suspend fun update(book: Book)  // SUMMARY-002: For updating series fields
    @Query("SELECT * FROM books ORDER BY createdAt DESC") fun getAll(): Flow<List<Book>>
    @Query("SELECT * FROM books WHERE id = :id") suspend fun getById(id: Long): Book?
    @Query("SELECT * FROM books WHERE title = :title LIMIT 1") suspend fun getByTitle(title: String): Book?
    @Query("DELETE FROM books WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("DELETE FROM books WHERE title = :title") suspend fun deleteByTitle(title: String)

    /** SUMMARY-002: Get all books in a series ordered by series order. */
    @Query("SELECT * FROM books WHERE seriesId = :seriesId ORDER BY seriesOrder ASC")
    fun getBySeriesId(seriesId: Long): Flow<List<Book>>

    // ============ LIBRARY-001: Library section queries ============

    /** Get all favorite books ordered by title. */
    @Query("SELECT * FROM books WHERE isFavorite = 1 ORDER BY title ASC")
    fun getFavorites(): Flow<List<Book>>

    /** Get recently read books that are not finished, ordered by lastReadAt descending. */
    @Query("SELECT * FROM books WHERE lastReadAt IS NOT NULL AND isFinished = 0 ORDER BY lastReadAt DESC")
    fun getLastRead(): Flow<List<Book>>

    /** Get finished/completed books ordered by lastReadAt descending. */
    @Query("SELECT * FROM books WHERE isFinished = 1 ORDER BY lastReadAt DESC")
    fun getFinished(): Flow<List<Book>>

    /** Get recently added books ordered by createdAt descending. */
    @Query("SELECT * FROM books ORDER BY createdAt DESC")
    fun getRecentlyAdded(): Flow<List<Book>>

    /** Get unread books (never opened) ordered by createdAt descending. */
    @Query("SELECT * FROM books WHERE lastReadAt IS NULL ORDER BY createdAt DESC")
    fun getUnread(): Flow<List<Book>>

    /** LIBRARY-001: Update favorite status for a book. */
    @Query("UPDATE books SET isFavorite = :isFavorite WHERE id = :bookId")
    suspend fun updateFavorite(bookId: Long, isFavorite: Boolean)

    /** LIBRARY-001: Update last read timestamp for a book. */
    @Query("UPDATE books SET lastReadAt = :timestamp WHERE id = :bookId")
    suspend fun updateLastReadAt(bookId: Long, timestamp: Long)

    /** LIBRARY-001: Update reading progress for a book. */
    @Query("UPDATE books SET readingProgress = :progress WHERE id = :bookId")
    suspend fun updateReadingProgress(bookId: Long, progress: Float)

    /** LIBRARY-001: Mark a book as finished. */
    @Query("UPDATE books SET isFinished = :isFinished, lastReadAt = :timestamp WHERE id = :bookId")
    suspend fun updateFinished(bookId: Long, isFinished: Boolean, timestamp: Long)

    /** Get books with incomplete analysis (PENDING or ANALYZING state, not COMPLETED). */
    @Query("SELECT * FROM books WHERE analysisStatus IN ('PENDING', 'ANALYZING') ORDER BY createdAt ASC")
    suspend fun getBooksWithIncompleteAnalysis(): List<Book>

    // ============ NARRATOR-002: Per-book narrator settings ============

    /** Update narrator voice settings for a book. */
    @Query("UPDATE books SET narratorSpeakerId = :speakerId, narratorSpeed = :speed, narratorEnergy = :energy WHERE id = :bookId")
	    suspend fun updateNarratorSettings(bookId: Long, speakerId: Int, speed: Float, energy: Float)

	    // ============ COVER-001: Genre & placeholder cover updates ============

	    /**
	     * Update detected genre and placeholder cover for a book, but only when no embedded cover exists.
	     *
	     * The WHERE clause ensures we never overwrite an embedded cover that may have been
	     * extracted from the book file after import.
	     */
	    @Query("UPDATE books SET detectedGenre = :genre, placeholderCoverPath = :coverPath WHERE id = :bookId AND embeddedCoverPath IS NULL")
	    suspend fun updateGenreAndPlaceholderCover(bookId: Long, genre: String?, coverPath: String?)
}
