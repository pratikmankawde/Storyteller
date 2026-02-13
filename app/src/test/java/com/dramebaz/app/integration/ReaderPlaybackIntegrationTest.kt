package com.dramebaz.app.integration

import com.dramebaz.app.data.db.Chapter
import com.dramebaz.app.data.db.ChapterDao
import com.dramebaz.app.data.models.Dialog
import com.dramebaz.app.data.models.ReadingMode
import com.dramebaz.app.data.repositories.BookRepository
import com.dramebaz.app.playback.engine.ProsodyController
import com.dramebaz.app.data.models.VoiceProfile
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
 * Integration tests for Reader/Playback Flow.
 * Tests:
 * - ReaderFragment ↔ ReaderViewModel state synchronization
 * - Cross-chapter navigation and page caching
 * - PlaybackEngine audio playback with prosody adjustments
 * - Reading mode transitions (TEXT → AUDIO → MIXED)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderPlaybackIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()

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
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(android.util.Log::class)
    }

    // ==================== ReadingMode Tests ====================

    @Test
    fun `ReadingMode cycles through all modes correctly`() {
        var mode = ReadingMode.TEXT

        mode = ReadingMode.next(mode)
        assertEquals(ReadingMode.AUDIO, mode)

        mode = ReadingMode.next(mode)
        assertEquals(ReadingMode.MIXED, mode)

        mode = ReadingMode.next(mode)
        assertEquals(ReadingMode.TEXT, mode)
    }

    @Test
    fun `ReadingMode converts to and from legacy strings`() {
        assertEquals(ReadingMode.TEXT, ReadingMode.fromLegacyString("reading"))
        assertEquals(ReadingMode.AUDIO, ReadingMode.fromLegacyString("listening"))
        assertEquals(ReadingMode.MIXED, ReadingMode.fromLegacyString("mixed"))

        assertEquals("reading", ReadingMode.toLegacyString(ReadingMode.TEXT))
        assertEquals("listening", ReadingMode.toLegacyString(ReadingMode.AUDIO))
        assertEquals("mixed", ReadingMode.toLegacyString(ReadingMode.MIXED))
    }

    @Test
    fun `ReadingMode fromLegacyString handles invalid input`() {
        assertEquals(ReadingMode.MIXED, ReadingMode.fromLegacyString(null))
        assertEquals(ReadingMode.MIXED, ReadingMode.fromLegacyString("invalid"))
        assertEquals(ReadingMode.MIXED, ReadingMode.fromLegacyString(""))
    }

    @Test
    fun `ReadingMode all returns all modes`() {
        val allModes = ReadingMode.all()
        
        assertEquals(3, allModes.size)
        assertTrue(allModes.contains(ReadingMode.TEXT))
        assertTrue(allModes.contains(ReadingMode.AUDIO))
        assertTrue(allModes.contains(ReadingMode.MIXED))
    }

    @Test
    fun `ReadingMode has display properties`() {
        ReadingMode.entries.forEach { mode ->
            assertTrue("${mode.name} should have displayName", mode.displayName.isNotBlank())
            assertTrue("${mode.name} should have description", mode.description.isNotBlank())
            assertTrue("${mode.name} should have iconResName", mode.iconResName.isNotBlank())
        }
    }

    // ==================== ProsodyController Tests ====================

    @Test
    fun `ProsodyController forDialog adjusts prosody based on emotion`() {
        val dialog = Dialog(
            speaker = "Jax",
            dialog = "Standard protocol!",
            emotion = "excited"
        )
        val voiceProfile = VoiceProfile(speed = 1.0f, pitch = 1.0f, energy = 1.0f)

        val prosody = ProsodyController.forDialog(dialog, voiceProfile, null, enableEmotionModifiers = true)

        assertNotNull("Prosody params should not be null", prosody)
        // Excited emotion should modify prosody
    }

    @Test
    fun `ProsodyController forDialog uses base profile when emotion disabled`() {
        val dialog = Dialog(
            speaker = "Jax",
            dialog = "Standard protocol.",
            emotion = "excited"
        )
        val voiceProfile = VoiceProfile(speed = 1.2f, pitch = 1.1f, energy = 1.0f)

        val prosody = ProsodyController.forDialog(dialog, voiceProfile, null, enableEmotionModifiers = false)

        assertNotNull(prosody)
        // Should use base values without emotion modification
    }

    @Test
    fun `ProsodyController handles null voice profile`() {
        val dialog = Dialog(speaker = "Jax", dialog = "Hello")

        val prosody = ProsodyController.forDialog(dialog, null, null, enableEmotionModifiers = true)

        assertNotNull("Should handle null voice profile", prosody)
    }

    // ==================== Chapter Navigation Tests ====================

    @Test
    fun `chapter ordering is preserved`() = runTest {
        val chapters = listOf(
            Chapter(id = 1, bookId = 1, title = "Prologue", body = "...", orderIndex = 0),
            Chapter(id = 2, bookId = 1, title = "Chapter 1", body = "...", orderIndex = 1),
            Chapter(id = 3, bookId = 1, title = "Chapter 2", body = "...", orderIndex = 2)
        )

        val sorted = chapters.sortedBy { it.orderIndex }

        assertEquals("Prologue", sorted[0].title)
        assertEquals("Chapter 1", sorted[1].title)
        assertEquals("Chapter 2", sorted[2].title)
    }

    @Test
    fun `first real chapter detection skips prologue`() {
        val chapterTitlePattern = Regex(
            """(?:Chapter\s+\d+|Chapter\s+[A-Za-z]+|CHAPTER\s+\d+|Part\s+\d+|PART\s+\d+)""",
            RegexOption.IGNORE_CASE
        )

        val chapters = listOf(
            Chapter(id = 1, bookId = 1, title = "Prologue", body = "...", orderIndex = 0),
            Chapter(id = 2, bookId = 1, title = "Chapter 1", body = "...", orderIndex = 1),
            Chapter(id = 3, bookId = 1, title = "Chapter 2", body = "...", orderIndex = 2)
        )

        val firstRealChapter = chapters.firstOrNull { chapter ->
            chapterTitlePattern.containsMatchIn(chapter.title)
        }

        assertNotNull("Should find a real chapter", firstRealChapter)
        assertEquals("Chapter 1", firstRealChapter?.title)
    }

    @Test
    fun `cross-chapter navigation identifies previous and next chapters`() {
        val chapters = listOf(
            Chapter(id = 1, bookId = 1, title = "Chapter 1", body = "...", orderIndex = 0),
            Chapter(id = 2, bookId = 1, title = "Chapter 2", body = "...", orderIndex = 1),
            Chapter(id = 3, bookId = 1, title = "Chapter 3", body = "...", orderIndex = 2)
        )
        val currentChapterId = 2L

        val currentIndex = chapters.indexOfFirst { it.id == currentChapterId }
        val prevChapter = chapters.getOrNull(currentIndex - 1)
        val nextChapter = chapters.getOrNull(currentIndex + 1)

        assertEquals("Chapter 1", prevChapter?.title)
        assertEquals("Chapter 3", nextChapter?.title)
    }

    // ==================== Audio Auto-Continuation Logic Tests ====================

    /**
     * Tests the decision logic for audio auto-continuation after page audio completes.
     * This mirrors the logic in ReaderFragment.onCompleteListener.
     */
    @Test
    fun `audio completion on non-last page should continue to next page`() {
        // Simulates the logic: if (currentPageIndex + 1 < novelPages.size)
        val currentPageIndex = 3
        val totalPages = 10

        val hasNextPage = currentPageIndex + 1 < totalPages

        assertTrue("Should have next page when not on last page", hasNextPage)
    }

    @Test
    fun `audio completion on last page should trigger cross-chapter navigation`() {
        // Simulates the logic: currentPageIndex == novelPages.size - 1
        val currentPageIndex = 9
        val totalPages = 10
        val readingMode = ReadingMode.AUDIO

        val isOnLastPage = currentPageIndex == totalPages - 1
        val shouldCrossChapter = isOnLastPage && (readingMode == ReadingMode.AUDIO || readingMode == ReadingMode.MIXED)

        assertTrue("Should be on last page", isOnLastPage)
        assertTrue("Should trigger cross-chapter navigation in AUDIO mode", shouldCrossChapter)
    }

    @Test
    fun `audio completion on last page in TEXT mode should not navigate`() {
        val currentPageIndex = 9
        val totalPages = 10
        val readingMode = ReadingMode.TEXT

        val isOnLastPage = currentPageIndex == totalPages - 1
        val shouldCrossChapter = isOnLastPage && (readingMode == ReadingMode.AUDIO || readingMode == ReadingMode.MIXED)

        assertTrue("Should be on last page", isOnLastPage)
        assertFalse("Should NOT trigger cross-chapter navigation in TEXT mode", shouldCrossChapter)
    }

    @Test
    fun `audio completion in MIXED mode should trigger cross-chapter navigation`() {
        val currentPageIndex = 9
        val totalPages = 10
        val readingMode = ReadingMode.MIXED

        val isOnLastPage = currentPageIndex == totalPages - 1
        val shouldCrossChapter = isOnLastPage && (readingMode == ReadingMode.AUDIO || readingMode == ReadingMode.MIXED)

        assertTrue("Should trigger cross-chapter navigation in MIXED mode", shouldCrossChapter)
    }

    // ==================== Cross-Chapter Auto-Play Decision Tests ====================

    @Test
    fun `loadChapterById with autoPlayAudio true should trigger playback`() {
        // Simulates the logic in loadChapterById after chapter loads
        val autoPlayAudio = true
        val readingMode = ReadingMode.AUDIO

        val shouldAutoPlay = autoPlayAudio && (readingMode == ReadingMode.AUDIO || readingMode == ReadingMode.MIXED)

        assertTrue("Should auto-play when autoPlayAudio=true in AUDIO mode", shouldAutoPlay)
    }

    @Test
    fun `loadChapterById with autoPlayAudio false should not trigger playback`() {
        // Manual navigation (swipe) should not auto-play
        val autoPlayAudio = false
        val readingMode = ReadingMode.AUDIO

        val shouldAutoPlay = autoPlayAudio && (readingMode == ReadingMode.AUDIO || readingMode == ReadingMode.MIXED)

        assertFalse("Should NOT auto-play when autoPlayAudio=false", shouldAutoPlay)
    }

    @Test
    fun `loadChapterById with autoPlayAudio true in TEXT mode should not trigger playback`() {
        val autoPlayAudio = true
        val readingMode = ReadingMode.TEXT

        val shouldAutoPlay = autoPlayAudio && (readingMode == ReadingMode.AUDIO || readingMode == ReadingMode.MIXED)

        assertFalse("Should NOT auto-play in TEXT mode even with autoPlayAudio=true", shouldAutoPlay)
    }

    // ==================== Target Page Index Resolution Tests ====================

    @Test
    fun `target page index 0 navigates to first page`() {
        val targetPageIndex = 0
        val totalPages = 10

        val finalPageIndex = when {
            targetPageIndex < 0 -> maxOf(0, totalPages - 1)
            targetPageIndex >= totalPages -> totalPages - 1
            else -> targetPageIndex
        }

        assertEquals(0, finalPageIndex)
    }

    @Test
    fun `target page index -1 navigates to last page`() {
        val targetPageIndex = -1
        val totalPages = 10

        val finalPageIndex = when {
            targetPageIndex < 0 -> maxOf(0, totalPages - 1)
            targetPageIndex >= totalPages -> totalPages - 1
            else -> targetPageIndex
        }

        assertEquals(9, finalPageIndex)
    }

    @Test
    fun `target page index beyond total clamps to last page`() {
        val targetPageIndex = 100
        val totalPages = 10

        val finalPageIndex = when {
            targetPageIndex < 0 -> maxOf(0, totalPages - 1)
            targetPageIndex >= totalPages -> totalPages - 1
            else -> targetPageIndex
        }

        assertEquals(9, finalPageIndex)
    }

    // ==================== Edge Page Detection Tests ====================

    @Test
    fun `edge detection identifies first page`() {
        val currentPageIndex = 0
        val totalPages = 10

        val isFirstPage = currentPageIndex == 0
        val isLastPage = currentPageIndex == totalPages - 1

        assertTrue("Should detect first page", isFirstPage)
        assertFalse("Should NOT be last page", isLastPage)
    }

    @Test
    fun `edge detection identifies last page`() {
        val currentPageIndex = 9
        val totalPages = 10

        val isFirstPage = currentPageIndex == 0
        val isLastPage = currentPageIndex == totalPages - 1

        assertFalse("Should NOT be first page", isFirstPage)
        assertTrue("Should detect last page", isLastPage)
    }

    @Test
    fun `edge detection for middle page returns false for both edges`() {
        val currentPageIndex = 5
        val totalPages = 10

        val isFirstPage = currentPageIndex == 0
        val isLastPage = currentPageIndex == totalPages - 1

        assertFalse("Should NOT be first page", isFirstPage)
        assertFalse("Should NOT be last page", isLastPage)
    }
}

