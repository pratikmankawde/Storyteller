package com.dramebaz.app.ui.characters

import com.dramebaz.app.data.db.AppDatabase
import com.dramebaz.app.data.db.Chapter
import com.dramebaz.app.data.db.ChapterDao
import com.dramebaz.app.data.db.Character
import com.dramebaz.app.data.db.CharacterDao
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
 * Unit tests for CharactersViewModel.
 * Tests character listing, chapter observation, and dialog count extraction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CharactersViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var characterDao: CharacterDao
    private lateinit var chapterDao: ChapterDao
    private lateinit var viewModel: CharactersViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        characterDao = mockk(relaxed = true)
        chapterDao = mockk(relaxed = true)
        db = mockk(relaxed = true)
        every { db.characterDao() } returns characterDao
        every { db.chapterDao() } returns chapterDao

        viewModel = CharactersViewModel(db)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(android.util.Log::class)
    }

    // ==================== characters Tests ====================

    @Test
    fun `characters returns flow from characterDao`() = runTest {
        val characters = listOf(
            Character(id = 1L, bookId = 1L, name = "Alice", traits = "brave, kind"),
            Character(id = 2L, bookId = 1L, name = "Bob", traits = "loyal")
        )
        every { characterDao.getByBookId(1L) } returns flowOf(characters)

        val result = viewModel.characters(1L)

        result.collect { list ->
            assertEquals(2, list.size)
            assertEquals("Alice", list[0].name)
            assertEquals("Bob", list[1].name)
        }
    }

    @Test
    fun `characters returns empty flow for no characters`() = runTest {
        every { characterDao.getByBookId(1L) } returns flowOf(emptyList())

        val result = viewModel.characters(1L)

        result.collect { list ->
            assertTrue(list.isEmpty())
        }
    }

    // ==================== observeChapterChanges Tests ====================

    @Test
    fun `observeChapterChanges returns flow from chapterDao`() = runTest {
        val chapters = listOf(
            Chapter(id = 1L, bookId = 1L, title = "Ch1", body = "", orderIndex = 0)
        )
        every { chapterDao.getByBookId(1L) } returns flowOf(chapters)

        val result = viewModel.observeChapterChanges(1L)

        result.collect { list ->
            assertEquals(1, list.size)
            assertEquals("Ch1", list[0].title)
        }
    }

    // ==================== getDialogCountsBySpeaker Tests ====================

    @Test
    fun `getDialogCountsBySpeaker extracts dialog counts from chapters`() = runTest {
        val chapters = listOf(
            Chapter(id = 1L, bookId = 1L, title = "Ch1", body = "", orderIndex = 0,
                fullAnalysisJson = """{
                    "dialogs": [
                        {"speaker": "Alice", "dialog": "Hello"},
                        {"speaker": "Bob", "dialog": "Hi"},
                        {"speaker": "Alice", "dialog": "How are you?"}
                    ]
                }""")
        )
        coEvery { chapterDao.getChaptersList(1L) } returns chapters

        val result = viewModel.getDialogCountsBySpeaker(1L)

        assertEquals(2, result["alice"]) // Lowercased
        assertEquals(1, result["bob"])
    }

    @Test
    fun `getDialogCountsBySpeaker aggregates across chapters`() = runTest {
        val chapters = listOf(
            Chapter(id = 1L, bookId = 1L, title = "Ch1", body = "", orderIndex = 0,
                fullAnalysisJson = """{"dialogs": [{"speaker": "Alice", "dialog": "Hi"}]}"""),
            Chapter(id = 2L, bookId = 1L, title = "Ch2", body = "", orderIndex = 1,
                fullAnalysisJson = """{"dialogs": [{"speaker": "Alice", "dialog": "Bye"}]}""")
        )
        coEvery { chapterDao.getChaptersList(1L) } returns chapters

        val result = viewModel.getDialogCountsBySpeaker(1L)

        assertEquals(2, result["alice"])
    }

    @Test
    fun `getDialogCountsBySpeaker handles null fullAnalysisJson`() = runTest {
        val chapters = listOf(
            Chapter(id = 1L, bookId = 1L, title = "Ch1", body = "", orderIndex = 0,
                fullAnalysisJson = null)
        )
        coEvery { chapterDao.getChaptersList(1L) } returns chapters

        val result = viewModel.getDialogCountsBySpeaker(1L)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getDialogCountsBySpeaker handles invalid JSON gracefully`() = runTest {
        val chapters = listOf(
            Chapter(id = 1L, bookId = 1L, title = "Ch1", body = "", orderIndex = 0,
                fullAnalysisJson = "not valid json")
        )
        coEvery { chapterDao.getChaptersList(1L) } returns chapters

        val result = viewModel.getDialogCountsBySpeaker(1L)

        assertTrue(result.isEmpty())
    }
}

