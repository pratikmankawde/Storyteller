package com.dramebaz.app.integration

import com.dramebaz.app.data.db.Book
import com.dramebaz.app.data.db.Chapter
import com.dramebaz.app.data.db.BookDao
import com.dramebaz.app.data.db.ChapterDao
import com.dramebaz.app.data.repositories.BookRepository
import com.dramebaz.app.domain.usecases.ImportBookUseCase
import com.dramebaz.app.pdf.PdfChapterDetector
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Integration tests for Book Import & Chapter Loading workflow.
 * Tests:
 * - PDF import and chapter extraction
 * - Database persistence of book metadata
 * - Chapter content loading and caching
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookImportIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()
    
    private lateinit var bookDao: BookDao
    private lateinit var chapterDao: ChapterDao
    private lateinit var bookRepository: BookRepository
    private lateinit var importUseCase: ImportBookUseCase

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.d(any(), any(), any()) } returns 0
        every { android.util.Log.i(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.println(any(), any(), any()) } returns 0

        bookDao = mockk(relaxed = true)
        chapterDao = mockk(relaxed = true)
        bookRepository = BookRepository(bookDao, chapterDao)
        importUseCase = ImportBookUseCase(bookRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(android.util.Log::class)
    }

    // ==================== Book Repository Tests ====================

    @Test
    fun `insertBook returns book ID`() = runTest {
        val book = Book(title = "Test Book", filePath = "/test/path.pdf", format = "pdf")
        every { bookDao.insert(any()) } returns 1L

        val bookId = bookRepository.insertBook(book)

        assertEquals(1L, bookId)
        verify { bookDao.insert(book) }
    }

    @Test
    fun `insertChapters persists all chapters`() = runTest {
        val chapters = listOf(
            Chapter(bookId = 1L, title = "Chapter 1", body = "Content 1", orderIndex = 0),
            Chapter(bookId = 1L, title = "Chapter 2", body = "Content 2", orderIndex = 1)
        )
        every { chapterDao.insertAll(any()) } returns listOf(1L, 2L)

        bookRepository.insertChapters(chapters)

        verify { chapterDao.insertAll(chapters) }
    }

    @Test
    fun `getBook returns book from database`() = runTest {
        val expectedBook = Book(id = 1L, title = "Test", filePath = "/path", format = "pdf")
        coEvery { bookDao.getById(1L) } returns expectedBook

        val book = bookRepository.getBook(1L)

        assertEquals(expectedBook, book)
    }

    @Test
    fun `chapters flow emits chapter list`() = runTest {
        val chapters = listOf(
            Chapter(id = 1L, bookId = 1L, title = "Ch1", body = "Body1", orderIndex = 0)
        )
        every { chapterDao.getByBookId(1L) } returns flowOf(chapters)

        val result = bookRepository.chapters(1L)
        
        result.collect { chapterList ->
            assertEquals(1, chapterList.size)
            assertEquals("Ch1", chapterList[0].title)
        }
    }

    // ==================== PDF Chapter Detection Tests ====================

    @Test
    fun `PdfChapterDetector detects chapter boundaries`() {
        val pages = listOf(
            "Introduction to the story",
            "Chapter 1\nThe adventure begins with our hero.",
            "The hero travels far and wide.",
            "Chapter 2\nA new challenge arises.",
            "The challenge is overcome."
        )

        val chapters = PdfChapterDetector.detectChaptersWithPages(pages, "Test Book")

        assertTrue("Should detect at least one chapter", chapters.isNotEmpty())
    }

    @Test
    fun `PdfChapterDetector handles empty pages`() {
        val pages = listOf("", "", "")

        val chapters = PdfChapterDetector.detectChaptersWithPages(pages, "Empty Book")

        // Should handle gracefully
        assertNotNull(chapters)
    }

    @Test
    fun `PdfChapterDetector preserves page content`() {
        val pageContent = "Chapter 1\nThis is the content of chapter one."
        val pages = listOf(pageContent)

        val chapters = PdfChapterDetector.detectChaptersWithPages(pages, "Single Chapter")

        assertTrue(chapters.isNotEmpty())
        assertTrue(chapters[0].body.contains("content"))
    }

    @Test
    fun `PdfChapterDetector detects section keywords like Prologue and Epilogue`() {
        val pages = listOf(
            "Title Page Content",
            "Prologue\nThe story begins long ago.",
            "Some prologue content continues here.",
            "Chapter 1\nThe main story starts.",
            "More chapter 1 content.",
            "Epilogue\nThe conclusion of our tale."
        )

        val chapters = PdfChapterDetector.detectChaptersWithPages(pages, "Test Book")

        // Should detect: Introduction/Title, Prologue, Chapter 1, Epilogue
        assertTrue("Should detect multiple chapters", chapters.size >= 3)
        val titles = chapters.map { it.title }
        assertTrue("Should detect Prologue", titles.any { it.contains("Prologue", ignoreCase = true) })
        assertTrue("Should detect Chapter 1", titles.any { it.contains("Chapter", ignoreCase = true) })
        assertTrue("Should detect Epilogue", titles.any { it.contains("Epilogue", ignoreCase = true) })
    }

    @Test
    fun `PdfChapterDetector detects chapters with decorative characters`() {
        // Simulates "◆ CHAPTER ONE ◆" format (common in many PDFs)
        val pages = listOf(
            "Front matter content",
            "* CHAPTER ONE *\nThe boy who lived.",
            "Story content page 2.",
            "~ CHAPTER TWO ~\nThe vanishing glass."
        )

        val chapters = PdfChapterDetector.detectChaptersWithPages(pages, "Harry Potter")

        // Should strip decorative chars and detect chapters
        assertTrue("Should detect chapters with decorative chars", chapters.size >= 2)
    }

    @Test
    fun `PdfChapterDetector detects expanded section keywords`() {
        val pages = listOf(
            "Title Page",
            "Table of Contents\n1. Introduction\n2. Chapter 1",
            "Preface\nAuthor's thoughts on writing this book.",
            "Introduction\nWelcome to this guide.",
            "Glossary\nTerm definitions.",
            "Appendix\nAdditional materials."
        )

        val chapters = PdfChapterDetector.detectChaptersWithPages(pages, "Technical Book")

        val titles = chapters.map { it.title.lowercase() }
        // Check for expanded keywords detection
        assertTrue("Should detect Table of Contents", titles.any { it.contains("contents") || it.contains("table") })
        assertTrue("Should detect Preface", titles.any { it.contains("preface") })
        assertTrue("Should detect Introduction", titles.any { it.contains("introduction") })
        assertTrue("Should detect Glossary", titles.any { it.contains("glossary") })
        assertTrue("Should detect Appendix", titles.any { it.contains("appendix") })
    }

    @Test
    fun `PdfChapterDetector handles PART sections`() {
        val pages = listOf(
            "Book Title",
            "PART ONE\nThe Beginning",
            "Chapter content for part one.",
            "Part Two\nThe Middle",
            "Chapter content for part two."
        )

        val chapters = PdfChapterDetector.detectChaptersWithPages(pages, "Epic Novel")

        val titles = chapters.map { it.title }
        assertTrue("Should detect PART ONE", titles.any { it.contains("PART", ignoreCase = true) && it.contains("ONE", ignoreCase = true) })
        assertTrue("Should detect Part Two", titles.any { it.contains("Part", ignoreCase = true) && it.contains("Two", ignoreCase = true) })
    }

    // ==================== Book Metadata Tests ====================

    @Test
    fun `Book entity stores all metadata fields`() {
        val book = Book(
            id = 1L,
            title = "Space Story",
            filePath = "/path/to/SpaceStory.pdf",
            format = "pdf",
            isFavorite = true,
            analysisStatus = "COMPLETE"
        )

        assertEquals(1L, book.id)
        assertEquals("Space Story", book.title)
        assertEquals("pdf", book.format)
        assertTrue(book.isFavorite)
        assertEquals("COMPLETE", book.analysisStatus)
    }
}

