package com.dramebaz.app.ui.insights

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
 * Unit tests for InsightsViewModel.
 * Tests theme and vocabulary extraction from chapter analysis JSON.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var bookRepository: BookRepository
    private lateinit var viewModel: InsightsViewModel

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
        viewModel = InsightsViewModel(bookRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(android.util.Log::class)
    }

    // ==================== insightsForBook Tests ====================

    @Test
    fun `insightsForBook extracts themes from analysisJson`() = runTest {
        val chapters = listOf(
            Chapter(
                id = 1L, bookId = 1L, title = "Ch1", body = "Content",
                orderIndex = 0,
                analysisJson = """{"themes": ["love", "adventure"], "vocabulary": []}"""
            )
        )
        every { bookRepository.chapters(1L) } returns flowOf(chapters)

        val result = viewModel.insightsForBook(1L)

        assertTrue(result.themes.contains("love"))
        assertTrue(result.themes.contains("adventure"))
    }

    @Test
    fun `insightsForBook extracts vocabulary from analysisJson`() = runTest {
        val chapters = listOf(
            Chapter(
                id = 1L, bookId = 1L, title = "Ch1", body = "Content",
                orderIndex = 0,
                analysisJson = """{"themes": [], "vocabulary": [{"word": "eloquent"}, {"word": "verbose"}]}"""
            )
        )
        every { bookRepository.chapters(1L) } returns flowOf(chapters)

        val result = viewModel.insightsForBook(1L)

        assertTrue(result.vocabulary.contains("eloquent"))
        assertTrue(result.vocabulary.contains("verbose"))
    }

    @Test
    fun `insightsForBook combines themes from multiple chapters`() = runTest {
        val chapters = listOf(
            Chapter(id = 1L, bookId = 1L, title = "Ch1", body = "Content", orderIndex = 0,
                analysisJson = """{"themes": ["love"], "vocabulary": []}"""),
            Chapter(id = 2L, bookId = 1L, title = "Ch2", body = "Content", orderIndex = 1,
                analysisJson = """{"themes": ["adventure"], "vocabulary": []}""")
        )
        every { bookRepository.chapters(1L) } returns flowOf(chapters)

        val result = viewModel.insightsForBook(1L)

        assertTrue(result.themes.contains("love"))
        assertTrue(result.themes.contains("adventure"))
    }

    @Test
    fun `insightsForBook deduplicates themes`() = runTest {
        val chapters = listOf(
            Chapter(id = 1L, bookId = 1L, title = "Ch1", body = "Content", orderIndex = 0,
                analysisJson = """{"themes": ["love", "friendship"], "vocabulary": []}"""),
            Chapter(id = 2L, bookId = 1L, title = "Ch2", body = "Content", orderIndex = 1,
                analysisJson = """{"themes": ["love", "adventure"], "vocabulary": []}""")
        )
        every { bookRepository.chapters(1L) } returns flowOf(chapters)

        val result = viewModel.insightsForBook(1L)
        val themeList = result.themes.split(", ")

        // "love" should only appear once due to Set usage
        assertEquals(1, themeList.count { it == "love" })
    }

    @Test
    fun `insightsForBook limits vocabulary to 20 items`() = runTest {
        val vocabJson = (1..30).joinToString(", ") { """{"word": "word$it"}""" }
        val chapters = listOf(
            Chapter(id = 1L, bookId = 1L, title = "Ch1", body = "Content", orderIndex = 0,
                analysisJson = """{"themes": [], "vocabulary": [$vocabJson]}""")
        )
        every { bookRepository.chapters(1L) } returns flowOf(chapters)

        val result = viewModel.insightsForBook(1L)
        val vocabList = result.vocabulary.split(", ")

        assertTrue(vocabList.size <= 20)
    }

    @Test
    fun `insightsForBook returns empty for no chapters`() = runTest {
        every { bookRepository.chapters(1L) } returns flowOf(emptyList())

        val result = viewModel.insightsForBook(1L)

        assertTrue(result.themes.isEmpty())
        assertTrue(result.vocabulary.isEmpty())
    }

    @Test
    fun `insightsForBook handles null analysisJson gracefully`() = runTest {
        val chapters = listOf(
            Chapter(id = 1L, bookId = 1L, title = "Ch1", body = "Content", orderIndex = 0,
                analysisJson = null)
        )
        every { bookRepository.chapters(1L) } returns flowOf(chapters)

        val result = viewModel.insightsForBook(1L)

        assertTrue(result.themes.isEmpty())
        assertTrue(result.vocabulary.isEmpty())
    }

    @Test
    fun `insightsForBook handles invalid JSON gracefully`() = runTest {
        val chapters = listOf(
            Chapter(id = 1L, bookId = 1L, title = "Ch1", body = "Content", orderIndex = 0,
                analysisJson = "not valid json")
        )
        every { bookRepository.chapters(1L) } returns flowOf(chapters)

        val result = viewModel.insightsForBook(1L)

        // Should not throw, returns empty results
        assertNotNull(result)
    }
}

