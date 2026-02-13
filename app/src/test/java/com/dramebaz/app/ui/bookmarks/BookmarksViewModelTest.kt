package com.dramebaz.app.ui.bookmarks

import com.dramebaz.app.data.db.AppDatabase
import com.dramebaz.app.data.db.Bookmark
import com.dramebaz.app.data.db.BookmarkDao
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
 * Unit tests for BookmarksViewModel.
 * Tests bookmark listing by book ID.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookmarksViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var bookmarkDao: BookmarkDao
    private lateinit var viewModel: BookmarksViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        bookmarkDao = mockk(relaxed = true)
        db = mockk(relaxed = true)
        every { db.bookmarkDao() } returns bookmarkDao

        viewModel = BookmarksViewModel(db)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(android.util.Log::class)
    }

    // ==================== bookmarks Tests ====================

    @Test
    fun `bookmarks returns flow from bookmarkDao`() = runTest {
        val bookmarks = listOf(
            Bookmark(id = 1L, bookId = 1L, chapterId = 1L, paragraphIndex = 0, contextSummary = "Bookmark 1"),
            Bookmark(id = 2L, bookId = 1L, chapterId = 2L, paragraphIndex = 5, contextSummary = "Bookmark 2")
        )
        every { bookmarkDao.getByBookId(1L) } returns flowOf(bookmarks)

        val result = viewModel.bookmarks(1L)

        result.collect { list ->
            assertEquals(2, list.size)
            assertEquals("Bookmark 1", list[0].contextSummary)
            assertEquals("Bookmark 2", list[1].contextSummary)
        }
    }

    @Test
    fun `bookmarks returns empty flow for no bookmarks`() = runTest {
        every { bookmarkDao.getByBookId(1L) } returns flowOf(emptyList())

        val result = viewModel.bookmarks(1L)

        result.collect { list ->
            assertTrue(list.isEmpty())
        }
    }

    @Test
    fun `bookmarks only returns bookmarks for specified book`() = runTest {
        val book1Bookmarks = listOf(
            Bookmark(id = 1L, bookId = 1L, chapterId = 1L, paragraphIndex = 0, contextSummary = "Book 1 Bookmark")
        )
        val book2Bookmarks = listOf(
            Bookmark(id = 2L, bookId = 2L, chapterId = 1L, paragraphIndex = 0, contextSummary = "Book 2 Bookmark")
        )
        every { bookmarkDao.getByBookId(1L) } returns flowOf(book1Bookmarks)
        every { bookmarkDao.getByBookId(2L) } returns flowOf(book2Bookmarks)

        val result1 = viewModel.bookmarks(1L)
        val result2 = viewModel.bookmarks(2L)

        result1.collect { list ->
            assertEquals(1, list.size)
            assertEquals(1L, list[0].bookId)
        }
        result2.collect { list ->
            assertEquals(1, list.size)
            assertEquals(2L, list[0].bookId)
        }
    }

    // ==================== Factory Tests ====================

    @Test
    fun `Factory creates BookmarksViewModel instance`() {
        val factory = BookmarksViewModel.Factory(db)

        val createdViewModel = factory.create(BookmarksViewModel::class.java)

        assertNotNull(createdViewModel)
        assertTrue(createdViewModel is BookmarksViewModel)
    }
}

