package com.dramebaz.app.domain.usecases

import android.content.Context
import com.dramebaz.app.ai.llm.QwenStub
import com.dramebaz.app.audio.SegmentAudioGenerator
import com.dramebaz.app.data.db.AppDatabase
import com.dramebaz.app.data.db.Chapter
import com.dramebaz.app.data.models.Dialog
import com.dramebaz.app.data.repositories.BookRepository
import com.dramebaz.app.pdf.PdfChapterDetector
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Manages a queue of book analysis tasks. When a book is imported, its first chapter
 * is automatically queued for analysis. Multiple books can be queued.
 */
object AnalysisQueueManager {
    private const val TAG = "AnalysisQueueManager"

    private val gson = Gson()
    private val analysisQueue = ConcurrentLinkedQueue<Long>() // bookIds
    private var processingJob: Job? = null
    private var bookRepository: BookRepository? = null
    private var database: AppDatabase? = null
    private var appContext: Context? = null
    private var segmentAudioGenerator: SegmentAudioGenerator? = null

    // Status tracking per book
    private val _analysisStatus = MutableStateFlow<Map<Long, AnalysisStatus>>(emptyMap())
    val analysisStatus: StateFlow<Map<Long, AnalysisStatus>> = _analysisStatus.asStateFlow()

    enum class AnalysisState { PENDING, ANALYZING, COMPLETE, FAILED }

    data class AnalysisStatus(
        val state: AnalysisState,
        val progress: Int = 0, // 0-100
        val message: String = ""
    )

    fun initialize(context: Context, repository: BookRepository, db: AppDatabase) {
        appContext = context.applicationContext
        bookRepository = repository
        database = db
        AppLogger.d(TAG, "AnalysisQueueManager initialized")
    }

    /**
     * AUG-043: Set the segment audio generator for initial page audio generation.
     */
    fun setSegmentAudioGenerator(generator: SegmentAudioGenerator) {
        segmentAudioGenerator = generator
        AppLogger.d(TAG, "SegmentAudioGenerator set")
    }

    /**
     * Enqueue a book for analysis after import.
     * Analysis runs in background and doesn't block the UI.
     */
    fun enqueueBook(bookId: Long) {
        if (bookId <= 0) return

        // Skip if already in queue or being processed
        val currentStatus = _analysisStatus.value[bookId]
        if (currentStatus?.state == AnalysisState.ANALYZING) {
            AppLogger.d(TAG, "Book $bookId already being analyzed, skipping")
            return
        }

        analysisQueue.add(bookId)
        updateStatus(bookId, AnalysisStatus(AnalysisState.PENDING, 0, "Queued for analysis"))
        AppLogger.i(TAG, "Book $bookId queued for analysis. Queue size: ${analysisQueue.size}")

        // Start processing if not already running
        startProcessingIfNeeded()
    }

    /**
     * Get the analysis status for a specific book.
     */
    fun getBookStatus(bookId: Long): AnalysisStatus? = _analysisStatus.value[bookId]

    /**
     * Check if a book has been analyzed (first chapter has fullAnalysisJson).
     */
    suspend fun isBookAnalyzed(bookId: Long): Boolean {
        val repo = bookRepository ?: return false
        val chapters = repo.chapters(bookId).first()
        return chapters.firstOrNull()?.fullAnalysisJson != null
    }

    private fun startProcessingIfNeeded() {
        if (processingJob?.isActive == true) return

        processingJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            while (analysisQueue.isNotEmpty()) {
                val bookId = analysisQueue.poll() ?: continue
                try {
                    analyzeFirstChapter(bookId)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Analysis failed for book $bookId", e)
                    updateStatus(bookId, AnalysisStatus(AnalysisState.FAILED, 0, "Analysis failed: ${e.message}"))
                }
            }
            AppLogger.d(TAG, "Analysis queue processing complete")
        }
    }

    private suspend fun analyzeFirstChapter(bookId: Long) {
        val repo = bookRepository ?: return
        val ctx = appContext ?: return

        updateStatus(bookId, AnalysisStatus(AnalysisState.ANALYZING, 10, "Loading chapter..."))

        val chapters = repo.chapters(bookId).first().sortedBy { it.orderIndex }
        val chapter = chapters.firstOrNull()

        if (chapter == null) {
            updateStatus(bookId, AnalysisStatus(AnalysisState.FAILED, 0, "No chapters found"))
            return
        }

        // Skip if already analyzed
        if (!chapter.fullAnalysisJson.isNullOrBlank()) {
            updateStatus(bookId, AnalysisStatus(AnalysisState.COMPLETE, 100, "Already analyzed"))
            AppLogger.d(TAG, "Book $bookId first chapter already analyzed, skipping")
            return
        }

        if (chapter.body.length <= 50) {
            updateStatus(bookId, AnalysisStatus(AnalysisState.COMPLETE, 100, "Chapter too short"))
            return
        }

        AppLogger.i(TAG, "Starting auto-analysis for book $bookId, chapter: ${chapter.title}")
        updateStatus(bookId, AnalysisStatus(AnalysisState.ANALYZING, 30, "Analyzing chapter..."))

        // Use stub-only analysis to avoid native crashes during background processing
        val resp = QwenStub.analyzeChapterStubOnly(chapter.body)
        updateStatus(bookId, AnalysisStatus(AnalysisState.ANALYZING, 60, "Extracting themes..."))

        val extendedJson = QwenStub.extendedAnalysisJsonStubOnly(chapter.body)
        updateStatus(bookId, AnalysisStatus(AnalysisState.ANALYZING, 80, "Saving results..."))

        repo.updateChapter(chapter.copy(
            summaryJson = gson.toJson(resp.chapterSummary),
            analysisJson = extendedJson,
            fullAnalysisJson = gson.toJson(resp)
        ))

        // Merge characters
        resp.characters?.let { chars ->
            try {
                val db = database ?: return@let
                MergeCharactersUseCase(db.characterDao(), ctx)
                    .mergeAndSave(bookId, listOf(gson.toJson(chars)))
            } catch (e: Exception) {
                AppLogger.w(TAG, "Character merge failed", e)
            }
        }

        // AUG-043: Generate audio for page 1 after chapter 1 analysis completes
        try {
            generateInitialAudio(bookId, chapter, resp.dialogs)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Initial audio generation failed", e)
        }

        updateStatus(bookId, AnalysisStatus(AnalysisState.COMPLETE, 100, "Analysis complete"))
        AppLogger.i(TAG, "Auto-analysis complete for book $bookId: dialogs=${resp.dialogs?.size}, characters=${resp.characters?.size}")
    }

    /**
     * AUG-043: Generate audio for page 1 of chapter 1 after analysis completes.
     * This ensures audio is ready before user starts reading.
     */
    private suspend fun generateInitialAudio(
        bookId: Long,
        chapter: Chapter,
        dialogs: List<Dialog>?
    ) {
        val generator = segmentAudioGenerator
        if (generator == null) {
            AppLogger.d(TAG, "SegmentAudioGenerator not set, skipping initial audio generation")
            return
        }

        // Get page 1 text from chapter
        val page1Text = getPage1Text(chapter)
        if (page1Text.isNullOrBlank()) {
            AppLogger.d(TAG, "No page 1 text found for chapter ${chapter.id}")
            return
        }

        AppLogger.i(TAG, "Generating initial audio for page 1 of book $bookId")
        val startTime = System.currentTimeMillis()

        // Filter dialogs that appear on page 1
        val page1Dialogs = generator.getDialogsForPage(page1Text, dialogs)

        // Generate audio for page 1
        val generatedFiles = generator.generatePageAudio(
            bookId = bookId,
            chapterId = chapter.id,
            pageNumber = 1,
            pageText = page1Text,
            dialogs = page1Dialogs
        )

        AppLogger.logPerformance(TAG, "Initial audio generation (${generatedFiles.size} segments)",
            System.currentTimeMillis() - startTime)
        AppLogger.i(TAG, "Generated ${generatedFiles.size} audio segments for page 1 of book $bookId")
    }

    /**
     * Get text for page 1 from a chapter.
     * Uses PDF pages if available, otherwise falls back to first portion of chapter body.
     */
    private fun getPage1Text(chapter: Chapter): String? {
        // Try PDF pages first
        if (!chapter.pdfPagesJson.isNullOrBlank()) {
            try {
                val pdfPages = PdfChapterDetector.pdfPagesFromJson(chapter.pdfPagesJson)
                if (pdfPages.isNotEmpty()) {
                    return pdfPages[0].text
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to parse PDF pages", e)
            }
        }

        // Fallback: use first ~3000 characters of chapter body (approximate page size)
        val body = chapter.body.trim()
        if (body.isBlank()) return null

        return if (body.length <= 3000) body else body.substring(0, 3000)
    }

    private fun updateStatus(bookId: Long, status: AnalysisStatus) {
        _analysisStatus.value = _analysisStatus.value + (bookId to status)
    }
}
