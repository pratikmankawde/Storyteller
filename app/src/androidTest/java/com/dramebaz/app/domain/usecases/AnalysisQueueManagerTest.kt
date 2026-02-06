package com.dramebaz.app.domain.usecases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.ai.llm.LlmService
import com.dramebaz.app.data.db.AppDatabase
import com.dramebaz.app.data.db.Book
import com.dramebaz.app.data.db.Chapter
import com.dramebaz.app.data.repositories.BookRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for AnalysisQueueManager functionality.
 * 
 * Tests the following features:
 * 1. Book analysis queue management
 * 2. isBookAnalyzed() check for incomplete analysis
 * 3. Re-triggering analysis for incomplete books
 * 4. Fast analysis mode with reduced input
 */
@RunWith(AndroidJUnit4::class)
class AnalysisQueueManagerTest {

    private lateinit var context: Context
    private lateinit var app: DramebazApplication
    private lateinit var db: AppDatabase
    private lateinit var bookRepository: BookRepository

    companion object {
        private const val TAG = "AnalysisQueueManagerTest"
        private const val TEST_BOOK_TITLE = "Test Analysis Book"
    }

    @Before
    fun setup() {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            app = context.applicationContext as DramebazApplication
            db = app.db
            bookRepository = app.bookRepository

            // Initialize LLM service
            LlmService.initialize(context)

            // Clean up any existing test book
            db.bookDao().deleteByTitle(TEST_BOOK_TITLE)
        }
    }

    @After
    fun teardown() {
        runBlocking {
            // Clean up test book
            db.bookDao().deleteByTitle(TEST_BOOK_TITLE)
        }
    }

    @Test
    fun testAnalysisQueueManagerInitialized() {
        // Verify AnalysisQueueManager is initialized
        assertNotNull("AnalysisQueueManager should be initialized", AnalysisQueueManager)
        android.util.Log.d(TAG, "AnalysisQueueManager initialized successfully")
    }

    @Test
    fun testIsBookAnalyzedReturnsFalseForNonExistentBook() {
        runBlocking {
            // Non-existent book should return false
            val result = AnalysisQueueManager.isBookAnalyzed(-1)
            assertFalse("Non-existent book should not be analyzed", result)
            android.util.Log.d(TAG, "isBookAnalyzed(-1) = $result")
        }
    }

    @Test
    fun testIsBookAnalyzedReturnsFalseForBookWithNoChapters() {
        runBlocking {
            // Create a book with no chapters
            val bookId = db.bookDao().insert(
                Book(
                    title = TEST_BOOK_TITLE,
                    format = "pdf",
                    filePath = "/test/path.pdf"
                )
            )

            val result = AnalysisQueueManager.isBookAnalyzed(bookId)
            assertFalse("Book with no chapters should not be analyzed", result)
            android.util.Log.d(TAG, "isBookAnalyzed($bookId) with no chapters = $result")
        }
    }

    @Test
    fun testIsBookAnalyzedReturnsFalseForBookWithUnanalyzedChapters() {
        runBlocking {
            // Create a book with chapters but no analysis
            val bookId = db.bookDao().insert(
                Book(
                    title = TEST_BOOK_TITLE,
                    format = "pdf",
                    filePath = "/test/path.pdf"
                )
            )

            // Add a chapter without analysis
            db.chapterDao().insert(
                Chapter(
                    bookId = bookId,
                    title = "Test Chapter",
                    body = "This is test chapter content.",
                    orderIndex = 0,
                    summaryJson = null,
                    fullAnalysisJson = null
                )
            )

            val result = AnalysisQueueManager.isBookAnalyzed(bookId)
            assertFalse("Book with unanalyzed chapters should return false", result)
            android.util.Log.d(TAG, "isBookAnalyzed($bookId) with unanalyzed chapter = $result")
        }
    }

    @Test
    fun testIsBookAnalyzedReturnsTrueForFullyAnalyzedBook() {
        runBlocking {
            // Create a book with analyzed chapters
            val bookId = db.bookDao().insert(
                Book(
                    title = TEST_BOOK_TITLE,
                    format = "pdf",
                    filePath = "/test/path.pdf"
                )
            )

            // Add a chapter with analysis
            db.chapterDao().insert(
                Chapter(
                    bookId = bookId,
                    title = "Test Chapter",
                    body = "This is test chapter content.",
                    orderIndex = 0,
                    summaryJson = """{"summary": "Test summary"}""",
                    fullAnalysisJson = """{"chapterSummary": {"summary": "Test"}}"""
                )
            )

            val result = AnalysisQueueManager.isBookAnalyzed(bookId)
            assertTrue("Fully analyzed book should return true", result)
            android.util.Log.d(TAG, "isBookAnalyzed($bookId) with analyzed chapter = $result")
        }
    }

    @Test
    fun testIsBookPartiallyAnalyzed() {
        runBlocking {
            // Create a book with 2 chapters, only first analyzed
            val bookId = db.bookDao().insert(
                Book(
                    title = TEST_BOOK_TITLE,
                    format = "pdf",
                    filePath = "/test/path.pdf"
                )
            )

            // First chapter with analysis
            db.chapterDao().insert(
                Chapter(
                    bookId = bookId,
                    title = "Chapter 1",
                    body = "First chapter content.",
                    orderIndex = 0,
                    summaryJson = """{"summary": "Test"}""",
                    fullAnalysisJson = """{"chapterSummary": {"summary": "Test"}}"""
                )
            )

            // Second chapter without analysis
            db.chapterDao().insert(
                Chapter(
                    bookId = bookId,
                    title = "Chapter 2",
                    body = "Second chapter content.",
                    orderIndex = 1,
                    summaryJson = null,
                    fullAnalysisJson = null
                )
            )

            val isPartial = AnalysisQueueManager.isBookPartiallyAnalyzed(bookId)
            val isFull = AnalysisQueueManager.isBookAnalyzed(bookId)

            assertTrue("Book should be partially analyzed", isPartial)
            assertFalse("Book should NOT be fully analyzed", isFull)
            android.util.Log.d(TAG, "Partial=$isPartial, Full=$isFull for book with 1/2 chapters analyzed")
        }
    }

    @Test
    fun testEnqueueBookAddsToQueue() {
        runBlocking {
            // Create a test book
            val bookId = db.bookDao().insert(
                Book(
                    title = TEST_BOOK_TITLE,
                    format = "pdf",
                    filePath = "/test/path.pdf"
                )
            )

            // Add a chapter
            db.chapterDao().insert(
                Chapter(
                    bookId = bookId,
                    title = "Test Chapter",
                    body = "This is test chapter content for analysis.",
                    orderIndex = 0,
                    summaryJson = null,
                    fullAnalysisJson = null
                )
            )

            // Enqueue the book
            AnalysisQueueManager.enqueueBook(bookId)

            // Check status
            delay(100) // Give time for status update
            val status = AnalysisQueueManager.getBookStatus(bookId)
            assertNotNull("Book should have a status after enqueue", status)
            android.util.Log.d(TAG, "Book $bookId status after enqueue: ${status?.state}")
        }
    }

    @Test
    fun testGetBookStatusReturnsNullForUnknownBook() {
        val status = AnalysisQueueManager.getBookStatus(-999)
        assertNull("Unknown book should have null status", status)
        android.util.Log.d(TAG, "Status for unknown book: $status")
    }
}

