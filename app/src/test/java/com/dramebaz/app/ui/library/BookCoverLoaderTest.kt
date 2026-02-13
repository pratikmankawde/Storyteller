package com.dramebaz.app.ui.library

import com.dramebaz.app.data.db.AnalysisState
import com.dramebaz.app.data.db.Book
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for BookCoverLoader.
 * Tests shouldShowSlideshow logic for determining when to show animated cover.
 */
class BookCoverLoaderTest {

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
    }

    // ==================== shouldShowSlideshow Tests ====================

    @Test
    fun `shouldShowSlideshow returns true for PENDING book with no cover`() {
        val book = Book(
            id = 1L,
            title = "Test",
            filePath = "/path",
            format = "pdf",
            analysisStatus = AnalysisState.PENDING.name,
            embeddedCoverPath = null,
            detectedGenre = null,
            placeholderCoverPath = null
        )

        val result = BookCoverLoader.shouldShowSlideshow(book)

        assertTrue(result)
    }

    @Test
    fun `shouldShowSlideshow returns true for ANALYZING book with no cover`() {
        val book = Book(
            id = 1L,
            title = "Test",
            filePath = "/path",
            format = "pdf",
            analysisStatus = AnalysisState.ANALYZING.name,
            embeddedCoverPath = null,
            detectedGenre = null,
            placeholderCoverPath = null
        )

        val result = BookCoverLoader.shouldShowSlideshow(book)

        assertTrue(result)
    }

    @Test
    fun `shouldShowSlideshow returns false for COMPLETED book`() {
        val book = Book(
            id = 1L,
            title = "Test",
            filePath = "/path",
            format = "pdf",
            analysisStatus = AnalysisState.COMPLETED.name,
            embeddedCoverPath = null,
            detectedGenre = null,
            placeholderCoverPath = null
        )

        val result = BookCoverLoader.shouldShowSlideshow(book)

        assertFalse(result)
    }

    @Test
    fun `shouldShowSlideshow returns false when book has embedded cover`() {
        val book = Book(
            id = 1L,
            title = "Test",
            filePath = "/path",
            format = "pdf",
            analysisStatus = AnalysisState.PENDING.name,
            embeddedCoverPath = "/path/to/cover.jpg",
            detectedGenre = null,
            placeholderCoverPath = null
        )

        val result = BookCoverLoader.shouldShowSlideshow(book)

        assertFalse(result)
    }

    @Test
    fun `shouldShowSlideshow returns false when book has detected genre`() {
        val book = Book(
            id = 1L,
            title = "Test",
            filePath = "/path",
            format = "pdf",
            analysisStatus = AnalysisState.PENDING.name,
            embeddedCoverPath = null,
            detectedGenre = "Fantasy",
            placeholderCoverPath = null
        )

        val result = BookCoverLoader.shouldShowSlideshow(book)

        assertFalse(result)
    }

    @Test
    fun `shouldShowSlideshow returns false when book has placeholder cover`() {
        val book = Book(
            id = 1L,
            title = "Test",
            filePath = "/path",
            format = "pdf",
            analysisStatus = AnalysisState.PENDING.name,
            embeddedCoverPath = null,
            detectedGenre = null,
            placeholderCoverPath = "images/bookcovers/Fantasy.png"
        )

        val result = BookCoverLoader.shouldShowSlideshow(book)

        assertFalse(result)
    }

    @Test
    fun `shouldShowSlideshow returns false for FAILED book`() {
        val book = Book(
            id = 1L,
            title = "Test",
            filePath = "/path",
            format = "pdf",
            analysisStatus = AnalysisState.FAILED.name,
            embeddedCoverPath = null,
            detectedGenre = null,
            placeholderCoverPath = null
        )

        val result = BookCoverLoader.shouldShowSlideshow(book)

        assertFalse(result)
    }

    @Test
    fun `shouldShowSlideshow returns false for CANCELLED book`() {
        val book = Book(
            id = 1L,
            title = "Test",
            filePath = "/path",
            format = "pdf",
            analysisStatus = AnalysisState.CANCELLED.name,
            embeddedCoverPath = null,
            detectedGenre = null,
            placeholderCoverPath = null
        )

        val result = BookCoverLoader.shouldShowSlideshow(book)

        assertFalse(result)
    }
}

