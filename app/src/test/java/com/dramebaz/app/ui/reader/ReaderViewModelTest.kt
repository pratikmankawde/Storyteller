package com.dramebaz.app.ui.reader

import com.dramebaz.app.data.db.Chapter
import com.dramebaz.app.data.models.CharacterReminder
import com.dramebaz.app.data.models.RecapDepth
import com.dramebaz.app.data.models.TimeAwareRecapResult
import com.dramebaz.app.data.repositories.BookRepository
import com.dramebaz.app.domain.usecases.GetRecapUseCase
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
 * Unit tests for ReaderViewModel.
 * Tests chapter navigation, recap generation, and display formatting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var bookRepository: BookRepository
    private lateinit var getRecapUseCase: GetRecapUseCase
    private lateinit var viewModel: ReaderViewModel

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
        getRecapUseCase = mockk(relaxed = true)
        viewModel = ReaderViewModel(bookRepository, getRecapUseCase)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(android.util.Log::class)
    }

    // ==================== firstChapterId Tests ====================

    @Test
    fun `firstChapterId finds chapter with Chapter title pattern`() = runTest {
        val chapters = listOf(
            Chapter(id = 1L, bookId = 1L, title = "Introduction", body = "", orderIndex = 0),
            Chapter(id = 2L, bookId = 1L, title = "Chapter 1", body = "", orderIndex = 1),
            Chapter(id = 3L, bookId = 1L, title = "Chapter 2", body = "", orderIndex = 2)
        )
        every { bookRepository.chapters(1L) } returns flowOf(chapters)

        val result = viewModel.firstChapterId(1L)

        assertEquals(2L, result) // "Chapter 1" not "Introduction"
    }

    @Test
    fun `firstChapterId falls back to first chapter if no Chapter title found`() = runTest {
        val chapters = listOf(
            Chapter(id = 1L, bookId = 1L, title = "Introduction", body = "", orderIndex = 0),
            Chapter(id = 2L, bookId = 1L, title = "Prologue", body = "", orderIndex = 1)
        )
        every { bookRepository.chapters(1L) } returns flowOf(chapters)

        val result = viewModel.firstChapterId(1L)

        assertEquals(1L, result)
    }

    @Test
    fun `firstChapterId handles Part titles`() = runTest {
        val chapters = listOf(
            Chapter(id = 1L, bookId = 1L, title = "Preface", body = "", orderIndex = 0),
            Chapter(id = 2L, bookId = 1L, title = "Part 1: Beginning", body = "", orderIndex = 1)
        )
        every { bookRepository.chapters(1L) } returns flowOf(chapters)

        val result = viewModel.firstChapterId(1L)

        assertEquals(2L, result)
    }

    @Test
    fun `firstChapterId returns null for empty chapters`() = runTest {
        every { bookRepository.chapters(1L) } returns flowOf(emptyList())

        val result = viewModel.firstChapterId(1L)

        assertNull(result)
    }

    // ==================== firstChapterIdIncludingIntro Tests ====================

    @Test
    fun `firstChapterIdIncludingIntro returns lowest orderIndex`() = runTest {
        val chapters = listOf(
            Chapter(id = 2L, bookId = 1L, title = "Chapter 1", body = "", orderIndex = 1),
            Chapter(id = 1L, bookId = 1L, title = "Introduction", body = "", orderIndex = 0)
        )
        every { bookRepository.chapters(1L) } returns flowOf(chapters)

        val result = viewModel.firstChapterIdIncludingIntro(1L)

        assertEquals(1L, result) // Introduction with orderIndex 0
    }

    // ==================== formatRecapForDisplay Tests ====================

    @Test
    fun `formatRecapForDisplay returns empty for blank recap`() {
        val result = TimeAwareRecapResult(
            recapText = "",
            depth = RecapDepth.BRIEF,
            daysSinceLastRead = 0f,
            chapterCount = 0
        )

        val formatted = viewModel.formatRecapForDisplay(result)

        assertEquals("", formatted)
    }

    @Test
    fun `formatRecapForDisplay includes time header for non-first reads`() {
        val result = TimeAwareRecapResult(
            recapText = "Last chapter summary",
            depth = RecapDepth.BRIEF,
            daysSinceLastRead = 0.5f,
            chapterCount = 1
        )

        val formatted = viewModel.formatRecapForDisplay(result)

        assertTrue(formatted.contains("📖"))
        assertTrue(formatted.contains("Read earlier today"))
    }

    @Test
    fun `formatRecapForDisplay skips time header for first read`() {
        val result = TimeAwareRecapResult(
            recapText = "Welcome text",
            depth = RecapDepth.BRIEF,
            daysSinceLastRead = -1f, // First read indicator
            chapterCount = 0
        )

        val formatted = viewModel.formatRecapForDisplay(result)

        assertFalse(formatted.contains("📖"))
        assertEquals("Welcome text", formatted)
    }

    @Test
    fun `formatRecapForDisplay includes character reminders when present`() {
        val result = TimeAwareRecapResult(
            recapText = "Story recap",
            depth = RecapDepth.DETAILED,
            daysSinceLastRead = 10f,
            characterReminders = listOf(
                CharacterReminder("Alice", "The protagonist", listOf("brave", "kind")),
                CharacterReminder("Bob", "Her friend", listOf("loyal"))
            ),
            chapterCount = 5
        )

        val formatted = viewModel.formatRecapForDisplay(result)

        assertTrue(formatted.contains("👥 Key Characters:"))
        assertTrue(formatted.contains("Alice"))
        assertTrue(formatted.contains("brave"))
        assertTrue(formatted.contains("Bob"))
    }
}

