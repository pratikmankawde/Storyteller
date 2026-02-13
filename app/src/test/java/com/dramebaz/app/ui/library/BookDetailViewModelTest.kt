package com.dramebaz.app.ui.library

import com.dramebaz.app.data.db.Book
import com.dramebaz.app.data.db.Chapter
import com.dramebaz.app.data.repositories.BookRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for BookDetailViewModel.
 * Tests book retrieval, chapter navigation, and favorite toggling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var bookRepository: BookRepository
    private lateinit var viewModel: BookDetailViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        bookRepository = mockk(relaxed = true)
        viewModel = BookDetailViewModel(bookRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(android.util.Log::class)
    }

    // ==================== getBook Tests ====================

    @Test
    fun `getBook returns book from repository`() = runTest {
        val expectedBook = Book(id = 1L, title = "Test Book", filePath = "/path", format = "pdf")
        coEvery { bookRepository.getBook(1L) } returns expectedBook

        val result = viewModel.getBook(1L)

        assertEquals(expectedBook, result)
        coVerify { bookRepository.getBook(1L) }
    }

    @Test
    fun `getBook returns null when book not found`() = runTest {
        coEvery { bookRepository.getBook(999L) } returns null

        val result = viewModel.getBook(999L)

        assertNull(result)
    }

    // ==================== firstChapterId Tests ====================

    @Test
    fun `firstChapterId returns smallest orderIndex chapter id`() = runTest {
        val chapters = listOf(
            Chapter(id = 3L, bookId = 1L, title = "Chapter 3", body = "", orderIndex = 2),
            Chapter(id = 1L, bookId = 1L, title = "Chapter 1", body = "", orderIndex = 0),
            Chapter(id = 2L, bookId = 1L, title = "Chapter 2", body = "", orderIndex = 1)
        )
        every { bookRepository.chapters(1L) } returns flowOf(chapters)

        val result = viewModel.firstChapterId(1L)

        assertEquals(1L, result) // Chapter with orderIndex 0
    }

    @Test
    fun `firstChapterId returns null when no chapters exist`() = runTest {
        every { bookRepository.chapters(1L) } returns flowOf(emptyList())

        val result = viewModel.firstChapterId(1L)

        assertNull(result)
    }

    @Test
    fun `firstChapterId handles single chapter`() = runTest {
        val chapters = listOf(
            Chapter(id = 5L, bookId = 1L, title = "Only Chapter", body = "", orderIndex = 0)
        )
        every { bookRepository.chapters(1L) } returns flowOf(chapters)

        val result = viewModel.firstChapterId(1L)

        assertEquals(5L, result)
    }

    // ==================== toggleFavorite Tests ====================

    @Test
    fun `toggleFavorite returns new favorite status true`() = runTest {
        coEvery { bookRepository.toggleFavorite(1L) } returns true

        val result = viewModel.toggleFavorite(1L)

        assertTrue(result)
        coVerify { bookRepository.toggleFavorite(1L) }
    }

    @Test
    fun `toggleFavorite returns new favorite status false`() = runTest {
        coEvery { bookRepository.toggleFavorite(1L) } returns false

        val result = viewModel.toggleFavorite(1L)

        assertFalse(result)
    }

    // ==================== Factory Tests ====================

    @Test
    fun `Factory creates BookDetailViewModel instance`() {
        val factory = BookDetailViewModel.Factory(bookRepository)
        
        val createdViewModel = factory.create(BookDetailViewModel::class.java)
        
        assertNotNull(createdViewModel)
        assertTrue(createdViewModel is BookDetailViewModel)
    }
}

