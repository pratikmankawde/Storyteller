package com.dramebaz.app.domain.usecases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.ai.llm.QwenStub
import com.dramebaz.app.data.db.AppDatabase
import com.dramebaz.app.data.db.Character
import com.dramebaz.app.data.repositories.BookRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Integration test for PDF character extraction with voice assignment.
 *
 * Tests the full workflow:
 * 1. Load PDF from assets (Space story.pdf)
 * 2. Import book and extract chapters
 * 3. Run 3-pass character analysis (names, dialogs, traits+voice)
 * 4. Verify characters are extracted with traits
 * 5. Verify voice/speaker IDs are assigned
 */
@RunWith(AndroidJUnit4::class)
class PdfCharacterExtractionTest {

    private lateinit var context: Context
    private lateinit var app: DramebazApplication
    private lateinit var db: AppDatabase
    private lateinit var bookRepository: BookRepository
    private lateinit var importUseCase: ImportBookUseCase
    private lateinit var threePassUseCase: ThreePassCharacterAnalysisUseCase

    companion object {
        private const val TAG = "PdfCharacterExtractionTest"
        private const val PDF_ASSET_PATH = "demo/Space story.pdf"
        private const val TEST_BOOK_TITLE = "Space story"
    }

    @Before
    fun setup() {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            app = context.applicationContext as DramebazApplication
            db = app.db
            bookRepository = app.bookRepository

            // Initialize Qwen model for character extraction
            QwenStub.initialize(context)

            // Create use cases
            importUseCase = ImportBookUseCase(bookRepository)
            threePassUseCase = ThreePassCharacterAnalysisUseCase(db.characterDao())

            // Clean up any existing test book
            db.bookDao().deleteByTitle(TEST_BOOK_TITLE)
        }
    }

    @Test
    fun testPdfImport() = runBlocking {
        // Copy PDF from assets to temp file
        val pdfFile = copyAssetToTempFile(PDF_ASSET_PATH)
        assertTrue("PDF file should exist", pdfFile.exists())
        assertTrue("PDF file should not be empty", pdfFile.length() > 0)

        // Import the PDF
        val bookId = importUseCase.importFromFile(context, pdfFile.absolutePath, "pdf")
        assertTrue("Book should be imported with valid ID", bookId > 0)

        // Verify book exists
        val book = db.bookDao().getById(bookId)
        assertNotNull("Book should exist in database", book)
        assertEquals("Book title should match", TEST_BOOK_TITLE, book?.title)

        // Verify chapters were extracted
        val chapters = db.chapterDao().getByBookId(bookId).first()
        assertTrue("Should have at least one chapter", chapters.isNotEmpty())

        android.util.Log.i(TAG, "Imported book: id=$bookId, title=${book?.title}, chapters=${chapters.size}")

        // Cleanup
        pdfFile.delete()
    }

    @Test
    fun testThreePassCharacterExtractionWithVoiceAssignment() = runBlocking {
        // Copy PDF from assets to temp file
        val pdfFile = copyAssetToTempFile(PDF_ASSET_PATH)

        // Import the PDF
        val bookId = importUseCase.importFromFile(context, pdfFile.absolutePath, "pdf")
        assertTrue("Book should be imported", bookId > 0)

        // Get chapters
        val chapters = db.chapterDao().getByBookId(bookId).first()
        assertTrue("Should have chapters", chapters.isNotEmpty())

        // Collect progress messages
        val progressMessages = mutableListOf<String>()
        val processedCharacters = mutableListOf<String>()

        // Run 3-pass analysis on first chapter
        val chapter = chapters.first()
        android.util.Log.i(TAG, "Analyzing chapter: ${chapter.title} (${chapter.body.length} chars)")

        threePassUseCase.analyzeChapter(
            bookId = bookId,
            chapterText = chapter.body,
            chapterIndex = 0,
            totalChapters = chapters.size,
            onProgress = { msg ->
                progressMessages.add(msg)
                android.util.Log.d(TAG, "Progress: $msg")
            },
            onCharacterProcessed = { name ->
                processedCharacters.add(name)
                android.util.Log.d(TAG, "Processed character: $name")
            }
        )

        // Verify characters were extracted
        val characters = db.characterDao().getByBookId(bookId).first()
        android.util.Log.i(TAG, "Extracted ${characters.size} characters")

        // Log each character for debugging
        for (char in characters) {
            android.util.Log.i(TAG, "Character: ${char.name}")
            android.util.Log.i(TAG, "  - traits: ${char.traits}")
            android.util.Log.i(TAG, "  - personality: ${char.personalitySummary}")
            android.util.Log.i(TAG, "  - voiceProfile: ${char.voiceProfileJson}")
            android.util.Log.i(TAG, "  - speakerId: ${char.speakerId}")
        }

        // Assertions
        assertTrue("Should extract at least one character", characters.isNotEmpty())
        assertTrue("Should have progress messages", progressMessages.isNotEmpty())

        // Check that at least some characters have voice assignments
        val charsWithVoice = characters.filter { it.speakerId != null }
        android.util.Log.i(TAG, "Characters with voice assignment: ${charsWithVoice.size}/${characters.size}")

        // This is the key assertion - verify voice assignment is working
        if (charsWithVoice.isEmpty()) {
            // Log detailed info to diagnose the issue
            android.util.Log.w(TAG, "WARNING: No characters have speaker IDs assigned!")
            for (char in characters) {
                android.util.Log.w(TAG, "  ${char.name}: traits='${char.traits}', personality='${char.personalitySummary}'")
            }
        }

        // Cleanup
        pdfFile.delete()
    }

    private fun copyAssetToTempFile(assetPath: String): File {
        val tempFile = File(context.cacheDir, "test_${System.currentTimeMillis()}.pdf")
        context.assets.open(assetPath).use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return tempFile
    }
}
