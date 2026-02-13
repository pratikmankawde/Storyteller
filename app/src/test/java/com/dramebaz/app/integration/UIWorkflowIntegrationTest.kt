package com.dramebaz.app.integration

import com.dramebaz.app.data.db.*
import com.dramebaz.app.data.models.CharacterReminder
import com.dramebaz.app.data.models.RecapDepth
import com.dramebaz.app.data.models.TimeAwareRecapResult
import com.dramebaz.app.data.repositories.BookRepository
import com.dramebaz.app.domain.usecases.GetRecapUseCase
import com.dramebaz.app.ui.bookmarks.BookmarksViewModel
import com.dramebaz.app.ui.characters.CharactersViewModel
import com.dramebaz.app.ui.insights.InsightsViewModel
import com.dramebaz.app.ui.library.BookCoverLoader
import com.dramebaz.app.ui.library.BookDetailViewModel
import com.dramebaz.app.ui.reader.ReaderViewModel
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
 * Integration tests for UI workflows.
 * Tests end-to-end interactions between ViewModels, repositories, and data flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UIWorkflowIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var bookDao: BookDao
    private lateinit var chapterDao: ChapterDao
    private lateinit var characterDao: CharacterDao
    private lateinit var bookmarkDao: BookmarkDao
    private lateinit var db: AppDatabase
    private lateinit var bookRepository: BookRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        bookDao = mockk(relaxed = true)
        chapterDao = mockk(relaxed = true)
        characterDao = mockk(relaxed = true)
        bookmarkDao = mockk(relaxed = true)
        db = mockk(relaxed = true)

        every { db.bookDao() } returns bookDao
        every { db.chapterDao() } returns chapterDao
        every { db.characterDao() } returns characterDao
        every { db.bookmarkDao() } returns bookmarkDao

        bookRepository = BookRepository(bookDao, chapterDao)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(android.util.Log::class)
    }

    // ==================== Library to Book Detail Flow ====================

    @Test
    fun `library to book detail navigation retrieves correct book data`() = runTest {
        val book = Book(id = 1L, title = "Fantasy Book", filePath = "/path", format = "pdf",
            analysisStatus = AnalysisState.COMPLETED.name, detectedGenre = "Fantasy")
        val chapters = listOf(
            Chapter(id = 1L, bookId = 1L, title = "Chapter 1", body = "Content", orderIndex = 0)
        )

        coEvery { bookDao.getById(1L) } returns book
        every { chapterDao.getByBookId(1L) } returns flowOf(chapters)

        val viewModel = BookDetailViewModel(bookRepository)

        val retrievedBook = viewModel.getBook(1L)
        val firstChapterId = viewModel.firstChapterId(1L)

        assertEquals("Fantasy Book", retrievedBook?.title)
        assertEquals(1L, firstChapterId)
    }

    // ==================== Book Detail to Insights Flow ====================

    @Test
    fun `book detail to insights extracts themes and vocabulary`() = runTest {
        val chapters = listOf(
            Chapter(id = 1L, bookId = 1L, title = "Ch1", body = "Content", orderIndex = 0,
                analysisJson = """{"themes": ["adventure", "friendship"], "vocabulary": [{"word": "gallant"}]}"""),
            Chapter(id = 2L, bookId = 1L, title = "Ch2", body = "Content", orderIndex = 1,
                analysisJson = """{"themes": ["courage"], "vocabulary": [{"word": "valiant"}]}""")
        )
        every { chapterDao.getByBookId(1L) } returns flowOf(chapters)

        val viewModel = InsightsViewModel(bookRepository)
        val insights = viewModel.insightsForBook(1L)

        assertTrue(insights.themes.contains("adventure"))
        assertTrue(insights.themes.contains("friendship"))
        assertTrue(insights.themes.contains("courage"))
        assertTrue(insights.vocabulary.contains("gallant"))
        assertTrue(insights.vocabulary.contains("valiant"))
    }

    // ==================== Book Detail to Characters Flow ====================

    @Test
    fun `book detail to characters retrieves character list with dialog counts`() = runTest {
        val characters = listOf(
            Character(id = 1L, bookId = 1L, name = "Alice", traits = "brave, kind"),
            Character(id = 2L, bookId = 1L, name = "Bob", traits = "loyal")
        )
        val chapters = listOf(
            Chapter(id = 1L, bookId = 1L, title = "Ch1", body = "", orderIndex = 0,
                fullAnalysisJson = """{"dialogs": [
                    {"speaker": "Alice", "dialog": "Hi"},
                    {"speaker": "Bob", "dialog": "Hello"},
                    {"speaker": "Alice", "dialog": "Bye"}
                ]}""")
        )

        every { characterDao.getByBookId(1L) } returns flowOf(characters)
        coEvery { chapterDao.getChaptersList(1L) } returns chapters

        val viewModel = CharactersViewModel(db)

        var characterList: List<Character>? = null
        viewModel.characters(1L).collect { characterList = it }
        val dialogCounts = viewModel.getDialogCountsBySpeaker(1L)

        assertEquals(2, characterList?.size)
        assertEquals(2, dialogCounts["alice"])
        assertEquals(1, dialogCounts["bob"])
    }

    // ==================== Book Detail to Bookmarks Flow ====================

    @Test
    fun `book detail to bookmarks retrieves bookmark list`() = runTest {
        val bookmarks = listOf(
            Bookmark(id = 1L, bookId = 1L, chapterId = 1L, paragraphIndex = 0, contextSummary = "Important scene"),
            Bookmark(id = 2L, bookId = 1L, chapterId = 2L, paragraphIndex = 5, contextSummary = "Key revelation")
        )
        every { bookmarkDao.getByBookId(1L) } returns flowOf(bookmarks)

        val viewModel = BookmarksViewModel(db)

        var bookmarkList: List<Bookmark>? = null
        viewModel.bookmarks(1L).collect { bookmarkList = it }

        assertEquals(2, bookmarkList?.size)
        assertEquals("Important scene", bookmarkList?.get(0)?.contextSummary)
    }

    // ==================== Cover Slideshow Integration ====================

    @Test
    fun `cover slideshow logic integrates with book analysis state`() {
        val analyzingBook = Book(
            id = 1L, title = "Analyzing", filePath = "/path", format = "pdf",
            analysisStatus = AnalysisState.ANALYZING.name,
            embeddedCoverPath = null, detectedGenre = null, placeholderCoverPath = null
        )
        val completedBook = Book(
            id = 2L, title = "Complete", filePath = "/path", format = "pdf",
            analysisStatus = AnalysisState.COMPLETED.name,
            embeddedCoverPath = null, detectedGenre = "Fantasy", placeholderCoverPath = null
        )

        assertTrue(BookCoverLoader.shouldShowSlideshow(analyzingBook))
        assertFalse(BookCoverLoader.shouldShowSlideshow(completedBook))
    }
}

