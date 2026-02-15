package com.dramebaz.app.data.repositories

import com.dramebaz.app.data.db.AnalysisState
import com.dramebaz.app.data.db.Book
import com.dramebaz.app.data.db.BookDao
import com.dramebaz.app.data.db.Chapter
import com.dramebaz.app.data.db.ChapterDao
import com.dramebaz.app.data.db.ChapterSummary
import com.dramebaz.app.data.db.ChapterWithAnalysis
import kotlinx.coroutines.flow.Flow

class BookRepository(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao
) {
    fun allBooks(): Flow<List<Book>> = bookDao.getAll()
    suspend fun getBook(id: Long) = bookDao.getById(id)
    suspend fun findBookByTitle(title: String) = bookDao.getByTitle(title)
    suspend fun insertBook(book: Book): Long = bookDao.insert(book)
    /** SUMMARY-002: Update book (for series linking). */
    suspend fun updateBook(book: Book) = bookDao.update(book)
    fun chapters(bookId: Long): Flow<List<Chapter>> = chapterDao.getByBookId(bookId)
    suspend fun getChapter(id: Long) = chapterDao.getById(id)
    suspend fun insertChapters(chapters: List<Chapter>) = chapterDao.insertAll(chapters)
    suspend fun updateChapter(chapter: Chapter) = chapterDao.update(chapter)

    /**
     * BLOB-FIX: Get lightweight chapter summaries for list views.
     * Use this for UI that only needs metadata (id, title, wordCount, isAnalyzed).
     */
    fun chapterSummaries(bookId: Long): Flow<List<ChapterSummary>> = chapterDao.getSummariesByBookId(bookId)

    /**
     * BLOB-FIX: Get lightweight chapter summaries as a list.
     */
    suspend fun chapterSummariesList(bookId: Long): List<ChapterSummary> = chapterDao.getSummariesList(bookId)

    /**
     * BLOB-FIX: Get first chapter ID without loading full chapter data.
     */
    suspend fun getFirstChapterId(bookId: Long): Long? = chapterDao.getFirstChapterId(bookId)

    /**
     * BLOB-FIX: Get chapters with analysis data (summaryJson, fullAnalysisJson).
     * Excludes body to avoid CursorWindow overflow.
     */
    suspend fun getChaptersWithAnalysis(bookId: Long): List<ChapterWithAnalysis> =
        chapterDao.getChaptersWithAnalysis(bookId)

    /** SUMMARY-002: Get all books in a series. */
    fun booksInSeries(seriesId: Long): Flow<List<Book>> = bookDao.getBySeriesId(seriesId)

    /**
     * Delete a book and all its chapters by book ID.
     */
    suspend fun deleteBookWithChapters(bookId: Long) {
        chapterDao.deleteByBookId(bookId)
        bookDao.deleteById(bookId)
    }

    /**
     * Delete a book and all its chapters by title.
     */
    suspend fun deleteBookByTitle(title: String) {
        val book = bookDao.getByTitle(title)
        if (book != null) {
            chapterDao.deleteByBookId(book.id)
            bookDao.deleteById(book.id)
        }
    }

    // ============ AUTO-ANALYSIS: Analysis progress update methods ============

    /**
     * Update the analysis progress for a book.
     * @param bookId Book ID to update
     * @param state Current analysis state
     * @param progress Progress percentage (0-100)
     * @param analyzedCount Number of chapters analyzed so far
     * @param totalChapters Total chapters to analyze
     * @param message Optional message for UI display
     */
    suspend fun updateAnalysisProgress(
        bookId: Long,
        state: AnalysisState,
        progress: Int,
        analyzedCount: Int,
        totalChapters: Int,
        message: String? = null
    ) {
        val book = bookDao.getById(bookId) ?: return
        bookDao.update(book.copy(
            analysisStatus = state.name,
            analysisProgress = progress.coerceIn(0, 100),
            analyzedChapterCount = analyzedCount,
            totalChaptersToAnalyze = totalChapters,
            analysisMessage = message
        ))
    }

    /**
     * Initialize analysis state when starting auto-analysis after book import.
     */
    suspend fun initializeAnalysisState(bookId: Long, totalChapters: Int) {
        updateAnalysisProgress(
            bookId = bookId,
            state = AnalysisState.ANALYZING,
            progress = 0,
            analyzedCount = 0,
            totalChapters = totalChapters,
            message = "Starting analysis..."
        )
    }

    /**
     * Mark analysis as complete for a book.
     */
    suspend fun markAnalysisComplete(bookId: Long, totalChapters: Int) {
        updateAnalysisProgress(
            bookId = bookId,
            state = AnalysisState.COMPLETED,
            progress = 100,
            analyzedCount = totalChapters,
            totalChapters = totalChapters,
            message = "Analysis complete"
        )
    }

    /**
     * Mark analysis as failed for a book.
     */
    suspend fun markAnalysisFailed(bookId: Long, errorMessage: String) {
        val book = bookDao.getById(bookId) ?: return
        bookDao.update(book.copy(
            analysisStatus = AnalysisState.FAILED.name,
            analysisMessage = errorMessage
        ))
    }

    // ============ LIBRARY-001: Library organization methods ============

    /** Get favorite books. */
    fun getFavorites(): Flow<List<Book>> = bookDao.getFavorites()

    /** Get recently read books (not finished). */
    fun getLastRead(): Flow<List<Book>> = bookDao.getLastRead()

    /** Get finished books. */
    fun getFinished(): Flow<List<Book>> = bookDao.getFinished()

    /** Get recently added books. */
    fun getRecentlyAdded(): Flow<List<Book>> = bookDao.getRecentlyAdded()

    /** Get unread books (never opened). */
    fun getUnread(): Flow<List<Book>> = bookDao.getUnread()

    /** Toggle favorite status for a book. */
    suspend fun toggleFavorite(bookId: Long): Boolean {
        val book = bookDao.getById(bookId) ?: return false
        val newFavoriteStatus = !book.isFavorite
        bookDao.updateFavorite(bookId, newFavoriteStatus)
        return newFavoriteStatus
    }

    /** Set favorite status for a book. */
    suspend fun setFavorite(bookId: Long, isFavorite: Boolean) {
        bookDao.updateFavorite(bookId, isFavorite)
    }

    /** Update last read timestamp for a book. */
    suspend fun updateLastReadAt(bookId: Long, timestamp: Long = System.currentTimeMillis()) {
        bookDao.updateLastReadAt(bookId, timestamp)
    }

    /** Update reading progress for a book (0.0 to 1.0). */
    suspend fun updateReadingProgress(bookId: Long, progress: Float) {
        bookDao.updateReadingProgress(bookId, progress.coerceIn(0f, 1f))
    }

    /** Mark a book as finished. */
    suspend fun markAsFinished(bookId: Long) {
        bookDao.updateFinished(bookId, true, System.currentTimeMillis())
    }

    /** Mark a book as not finished (to continue reading). */
    suspend fun markAsUnfinished(bookId: Long) {
        bookDao.updateFinished(bookId, false, System.currentTimeMillis())
    }

	    // ============ COVER-001: Genre & placeholder cover handling ============

	    /**
	     * Update the detected genre and placeholder cover path for a book.
	     *
	     * This method:
	     * - Never overwrites an embedded cover (if embeddedCoverPath is non-null).
	     * - Avoids unnecessary writes when the values are unchanged.
	     */
	    suspend fun updateGenreAndPlaceholderCover(
	        bookId: Long,
	        genre: String?,
	        coverPath: String?
	    ) {
	        val book = bookDao.getById(bookId) ?: return

	        // If the book has an embedded cover, we never apply a placeholder cover.
	        if (!book.embeddedCoverPath.isNullOrBlank()) {
	            return
	        }

	        // Compute desired final values, keeping existing ones when nulls are passed.
	        val desiredGenre = genre ?: book.detectedGenre
	        val desiredCover = coverPath ?: book.placeholderCoverPath

	        // No-op if nothing would change.
	        if (desiredGenre == book.detectedGenre && desiredCover == book.placeholderCoverPath) {
	            return
	        }

	        bookDao.updateGenreAndPlaceholderCover(
	            bookId = bookId,
	            genre = desiredGenre,
	            coverPath = desiredCover
	        )
	    }
}
