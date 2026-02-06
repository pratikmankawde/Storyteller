package com.dramebaz.app.domain.usecases

import android.content.Context
import com.dramebaz.app.ai.llm.LlmService
import com.dramebaz.app.audio.SegmentAudioGenerator
import com.dramebaz.app.data.db.AnalysisState as BookAnalysisState
import com.dramebaz.app.data.db.AppDatabase
import com.dramebaz.app.data.db.Chapter
import com.dramebaz.app.data.models.Dialog
import com.dramebaz.app.data.repositories.BookRepository
import com.dramebaz.app.pdf.PdfChapterDetector
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * AUTO-ANALYSIS: Manages a queue of book analysis tasks.
 * When a book is imported, ALL chapters are automatically queued for analysis.
 * Analysis runs in the background with foreground service notification.
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

    // Pause/Resume state for LLM settings changes
    @Volatile
    private var isPaused = false
    private val pausedBookIds = mutableListOf<Long>()
    private var currentlyAnalyzingBookId: Long? = null

    // Track cancelled book IDs to prevent saving results after deletion
    private val cancelledBookIds = mutableSetOf<Long>()

    // Status tracking per book
    private val _analysisStatus = MutableStateFlow<Map<Long, AnalysisStatus>>(emptyMap())
    val analysisStatus: StateFlow<Map<Long, AnalysisStatus>> = _analysisStatus.asStateFlow()

    enum class AnalysisState { PENDING, ANALYZING, COMPLETE, FAILED }

    data class AnalysisStatus(
        val state: AnalysisState,
        val progress: Int = 0, // 0-100
        val message: String = "",
        val analyzedChapters: Int = 0,
        val totalChapters: Int = 0
    )

    fun initialize(context: Context, repository: BookRepository, db: AppDatabase) {
        appContext = context.applicationContext
        bookRepository = repository
        database = db
        AppLogger.d(TAG, "AnalysisQueueManager initialized")
    }

    /**
     * Resume incomplete analysis on app startup.
     * Finds books with PENDING or ANALYZING state and re-queues them.
     * Should be called after LLM model is loaded.
     */
    suspend fun resumeIncompleteAnalysis() {
        val db = database ?: return

        try {
            val incompleteBooks = db.bookDao().getBooksWithIncompleteAnalysis()
            if (incompleteBooks.isEmpty()) {
                AppLogger.d(TAG, "No incomplete analysis to resume")
                return
            }

            AppLogger.i(TAG, "▶️ Resuming analysis for ${incompleteBooks.size} books")
            for (book in incompleteBooks) {
                if (!analysisQueue.contains(book.id)) {
                    analysisQueue.add(book.id)
                    updateStatus(book.id, AnalysisStatus(
                        AnalysisState.PENDING,
                        book.analysisProgress,
                        "Resuming analysis...",
                        book.analyzedChapterCount,
                        book.totalChaptersToAnalyze
                    ))
                    AppLogger.d(TAG, "Re-queued book ${book.id}: ${book.title}")
                }
            }

            // Start processing
            if (analysisQueue.isNotEmpty()) {
                startProcessingIfNeeded()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to resume incomplete analysis", e)
        }
    }

    /**
     * AUG-043: Set the segment audio generator for initial page audio generation.
     */
    fun setSegmentAudioGenerator(generator: SegmentAudioGenerator) {
        segmentAudioGenerator = generator
        AppLogger.d(TAG, "SegmentAudioGenerator set")
    }

    /**
     * Pause analysis when LLM settings are being changed.
     * Saves current state so analysis can resume after model reload.
     */
    fun pauseAnalysis() {
        if (isPaused) {
            AppLogger.d(TAG, "Analysis already paused")
            return
        }

        isPaused = true
        AppLogger.i(TAG, "⏸️ Pausing analysis for LLM settings change...")

        // Save books that need to be re-queued
        pausedBookIds.clear()

        // Add currently analyzing book if any
        currentlyAnalyzingBookId?.let { bookId ->
            pausedBookIds.add(bookId)
            updateStatus(bookId, AnalysisStatus(AnalysisState.PENDING, 0, "Paused - LLM settings changing"))
            AppLogger.d(TAG, "Saved currently analyzing book: $bookId")
        }

        // Add all queued books
        while (analysisQueue.isNotEmpty()) {
            analysisQueue.poll()?.let { bookId ->
                if (!pausedBookIds.contains(bookId)) {
                    pausedBookIds.add(bookId)
                }
            }
        }

        // Cancel the processing job
        processingJob?.cancel()
        processingJob = null

        AppLogger.i(TAG, "⏸️ Analysis paused. ${pausedBookIds.size} books saved for resume")
    }

    /**
     * Resume analysis after LLM settings have been updated and model reloaded.
     */
    fun resumeAnalysis() {
        if (!isPaused) {
            AppLogger.d(TAG, "Analysis not paused, nothing to resume")
            return
        }

        isPaused = false
        AppLogger.i(TAG, "▶️ Resuming analysis after LLM settings change...")

        // Re-enqueue all paused books
        for (bookId in pausedBookIds) {
            analysisQueue.add(bookId)
            updateStatus(bookId, AnalysisStatus(AnalysisState.PENDING, 0, "Queued for analysis"))
            AppLogger.d(TAG, "Re-queued book: $bookId")
        }

        val count = pausedBookIds.size
        pausedBookIds.clear()
        currentlyAnalyzingBookId = null

        // Restart processing
        if (analysisQueue.isNotEmpty()) {
            startProcessingIfNeeded()
            AppLogger.i(TAG, "▶️ Analysis resumed. $count books re-queued")
        } else {
            AppLogger.i(TAG, "▶️ Analysis resumed. No books to process")
        }
    }

    /**
     * Check if analysis is currently paused.
     */
    fun isAnalysisPaused(): Boolean = isPaused

    /**
     * Cancel analysis for a specific book.
     * Removes from queue if pending, cancels if currently analyzing.
     * Called when user deletes a book mid-analysis.
     *
     * Note: This adds the book ID to a cancelled set to prevent saving results
     * from any in-flight LLM inference that may still be running in native code.
     */
    fun cancelAnalysisForBook(bookId: Long) {
        AppLogger.i(TAG, "🛑 Cancelling analysis for book $bookId")

        // Add to cancelled set FIRST - this prevents any in-flight LLM results from being saved
        cancelledBookIds.add(bookId)
        AppLogger.d(TAG, "Added book $bookId to cancelled set")

        // Remove from queue if pending
        val wasQueued = analysisQueue.removeIf { it == bookId }
        if (wasQueued) {
            AppLogger.d(TAG, "Removed book $bookId from analysis queue")
        }

        // Remove from paused list if present
        val wasPaused = pausedBookIds.remove(bookId)
        if (wasPaused) {
            AppLogger.d(TAG, "Removed book $bookId from paused list")
        }

        // Cancel if currently analyzing this book
        if (currentlyAnalyzingBookId == bookId) {
            AppLogger.i(TAG, "Book $bookId is currently being analyzed, cancelling job")
            processingJob?.cancel()
            processingJob = null
            currentlyAnalyzingBookId = null
        }

        // Clear status for this book
        _analysisStatus.value = _analysisStatus.value - bookId

        // Stop foreground service if no more books in queue
        if (analysisQueue.isEmpty()) {
            appContext?.let { ctx ->
                BookAnalysisForegroundService.stop(ctx)
            }
        }

        AppLogger.i(TAG, "🛑 Analysis cancelled for book $bookId")
    }

    /**
     * Check if a book has been cancelled (deleted while analysis was running).
     * Used by analysis methods to avoid saving results for deleted books.
     */
    fun isBookCancelled(bookId: Long): Boolean = cancelledBookIds.contains(bookId)

    /**
     * Clear a book from the cancelled set. Called after book is fully deleted.
     */
    fun clearCancelledBook(bookId: Long) {
        cancelledBookIds.remove(bookId)
    }

    /**
     * Get the book ID currently being analyzed, if any.
     */
    fun getCurrentlyAnalyzingBookId(): Long? = currentlyAnalyzingBookId

    /**
     * AUTO-ANALYSIS: Enqueue a book for full analysis after import.
     * All chapters will be analyzed sequentially.
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
        AppLogger.i(TAG, "Book $bookId queued for full analysis. Queue size: ${analysisQueue.size}")

        // Start processing if not already running
        startProcessingIfNeeded()
    }

    /**
     * Enqueue a book for first-chapter-only analysis on book load.
     * Only the first chapter will be analyzed to provide quick initial insights.
     */
    fun enqueueFirstChapter(bookId: Long) {
        if (bookId <= 0) return

        // Skip if already analyzed or being processed
        val currentStatus = _analysisStatus.value[bookId]
        if (currentStatus?.state == AnalysisState.ANALYZING) {
            AppLogger.d(TAG, "Book $bookId already being analyzed, skipping first chapter analysis")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            // Check if first chapter is already analyzed
            if (isBookPartiallyAnalyzed(bookId)) {
                AppLogger.d(TAG, "Book $bookId first chapter already analyzed, skipping")
                return@launch
            }

            AppLogger.i(TAG, "Book $bookId queued for first-chapter analysis")
            updateStatus(bookId, AnalysisStatus(AnalysisState.PENDING, 0, "Queued for first chapter analysis"))

            try {
                analyzeFirstChapterOnly(bookId)
            } catch (e: Exception) {
                AppLogger.e(TAG, "First chapter analysis failed for book $bookId", e)
                updateStatus(bookId, AnalysisStatus(AnalysisState.FAILED, 0, "Analysis failed: ${e.message}"))
                bookRepository?.markAnalysisFailed(bookId, e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Analyze only the first chapter of a book.
     * Provides quick initial analysis on book load without processing entire book.
     */
    private suspend fun analyzeFirstChapterOnly(bookId: Long) {
        val repo = bookRepository ?: return
        val ctx = appContext ?: return

        currentlyAnalyzingBookId = bookId

        val chapters = repo.chapters(bookId).first().sortedBy { it.orderIndex }
        val totalChapters = chapters.size

        if (chapters.isEmpty()) {
            currentlyAnalyzingBookId = null
            updateStatus(bookId, AnalysisStatus(AnalysisState.FAILED, 0, "No chapters found"))
            repo.markAnalysisFailed(bookId, "No chapters found")
            return
        }

        // Initialize analysis state in book entity
        repo.initializeAnalysisState(bookId, totalChapters)

        val firstChapter = chapters.first()

        // Skip if already analyzed or too short
        if (!firstChapter.fullAnalysisJson.isNullOrBlank()) {
            currentlyAnalyzingBookId = null
            AppLogger.d(TAG, "Book $bookId first chapter already analyzed")
            updateStatus(bookId, AnalysisStatus(AnalysisState.COMPLETE, 100, "First chapter analyzed", 1, totalChapters))
            return
        }

        if (firstChapter.body.length <= 50) {
            currentlyAnalyzingBookId = null
            AppLogger.d(TAG, "Book $bookId first chapter too short, skipping analysis")
            updateStatus(bookId, AnalysisStatus(AnalysisState.COMPLETE, 0, "First chapter too short", 0, totalChapters))
            return
        }

        val chapterLabel = firstChapter.title.ifBlank { "Chapter 1" }
        AppLogger.i(TAG, "FIRST-CHAPTER-ANALYSIS: Starting for book $bookId - $chapterLabel")
        updateStatus(bookId, AnalysisStatus(AnalysisState.ANALYZING, 0, "Analyzing $chapterLabel (1/$totalChapters)", 0, totalChapters))

        // Update book entity progress
        repo.updateAnalysisProgress(
            bookId = bookId,
            state = BookAnalysisState.ANALYZING,
            progress = 0,
            analyzedCount = 0,
            totalChapters = totalChapters,
            message = "Analyzing $chapterLabel"
        )

        // Analyze the first chapter
        analyzeChapter(bookId, firstChapter, 0, totalChapters)

        currentlyAnalyzingBookId = null

        // Check if cancelled or paused
        if (isBookCancelled(bookId) || isPaused) {
            AppLogger.i(TAG, "First chapter analysis interrupted for book $bookId")
            return
        }

        // Mark as partially complete (1 chapter done)
        updateStatus(bookId, AnalysisStatus(AnalysisState.COMPLETE, 100, "First chapter analyzed", 1, totalChapters))
        repo.updateAnalysisProgress(
            bookId = bookId,
            state = BookAnalysisState.COMPLETED,
            progress = 100,
            analyzedCount = 1,
            totalChapters = totalChapters,
            message = "First chapter analyzed"
        )
        AppLogger.i(TAG, "FIRST-CHAPTER-ANALYSIS: Complete for book $bookId")
    }

    /**
     * Get the analysis status for a specific book.
     */
    fun getBookStatus(bookId: Long): AnalysisStatus? = _analysisStatus.value[bookId]

    /**
     * Check if a book has been analyzed (all chapters have fullAnalysisJson).
     */
    suspend fun isBookAnalyzed(bookId: Long): Boolean {
        val repo = bookRepository ?: return false
        val chapters = repo.chapters(bookId).first()
        if (chapters.isEmpty()) return false
        return chapters.all { it.fullAnalysisJson != null }
    }

    /**
     * Check if a book is partially analyzed (at least first chapter has fullAnalysisJson).
     */
    suspend fun isBookPartiallyAnalyzed(bookId: Long): Boolean {
        val repo = bookRepository ?: return false
        val chapters = repo.chapters(bookId).first()
        return chapters.firstOrNull()?.fullAnalysisJson != null
    }

    private fun startProcessingIfNeeded() {
        if (processingJob?.isActive == true) return

        // Start foreground service for background processing
        appContext?.let { ctx ->
            BookAnalysisForegroundService.start(ctx)
        }

        processingJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            while (analysisQueue.isNotEmpty()) {
                val bookId = analysisQueue.poll() ?: continue
                try {
                    analyzeAllChapters(bookId)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Analysis failed for book $bookId", e)
                    updateStatus(bookId, AnalysisStatus(AnalysisState.FAILED, 0, "Analysis failed: ${e.message}"))
                    // Update book entity with failed state
                    bookRepository?.markAnalysisFailed(bookId, e.message ?: "Unknown error")
                }
            }
            AppLogger.d(TAG, "Analysis queue processing complete")

            // Stop foreground service when queue is empty
            appContext?.let { ctx ->
                BookAnalysisForegroundService.stop(ctx)
            }
        }
    }

    /**
     * AUTO-ANALYSIS: Analyze ALL chapters of a book.
     * Uses real LLM inference to generate proper summaries and key points.
     */
    private suspend fun analyzeAllChapters(bookId: Long) {
        val repo = bookRepository ?: return
        val ctx = appContext ?: return

        // Track currently analyzing book for pause/resume
        currentlyAnalyzingBookId = bookId

        val chapters = repo.chapters(bookId).first().sortedBy { it.orderIndex }
        val totalChapters = chapters.size

        if (chapters.isEmpty()) {
            currentlyAnalyzingBookId = null
            updateStatus(bookId, AnalysisStatus(AnalysisState.FAILED, 0, "No chapters found"))
            repo.markAnalysisFailed(bookId, "No chapters found")
            return
        }

        // Initialize analysis state in book entity
        repo.initializeAnalysisState(bookId, totalChapters)

        // Filter to only chapters needing analysis
        val chaptersToAnalyze = chapters.filter {
            it.fullAnalysisJson.isNullOrBlank() && it.body.length > 50
        }

        if (chaptersToAnalyze.isEmpty()) {
            currentlyAnalyzingBookId = null
            updateStatus(bookId, AnalysisStatus(AnalysisState.COMPLETE, 100, "All chapters already analyzed", totalChapters, totalChapters))
            repo.markAnalysisComplete(bookId, totalChapters)
            AppLogger.d(TAG, "Book $bookId all chapters already analyzed")
            return
        }

        AppLogger.i(TAG, "AUTO-ANALYSIS: Starting analysis for book $bookId, ${chaptersToAnalyze.size}/${totalChapters} chapters need analysis")
        updateStatus(bookId, AnalysisStatus(AnalysisState.ANALYZING, 0, "Starting analysis...", 0, totalChapters))

        var analyzedCount = totalChapters - chaptersToAnalyze.size // Count pre-analyzed chapters

        for ((index, chapter) in chaptersToAnalyze.withIndex()) {
            // Check if paused - exit gracefully
            if (isPaused) {
                AppLogger.i(TAG, "Analysis paused for book $bookId at chapter ${index + 1}/${chaptersToAnalyze.size}")
                return
            }

            // Check if book was cancelled (deleted while analyzing)
            if (isBookCancelled(bookId)) {
                AppLogger.i(TAG, "Analysis cancelled for book $bookId - book was deleted")
                currentlyAnalyzingBookId = null
                return
            }

            try {
                val overallProgress = ((analyzedCount.toFloat() / totalChapters) * 100).toInt()
                val chapterLabel = chapter.title.ifBlank { "Chapter ${chapter.orderIndex + 1}" }

                updateStatus(bookId, AnalysisStatus(
                    AnalysisState.ANALYZING,
                    overallProgress,
                    "Analyzing $chapterLabel (${analyzedCount + 1}/$totalChapters)",
                    analyzedCount,
                    totalChapters
                ))

                // Update book entity progress
                repo.updateAnalysisProgress(
                    bookId = bookId,
                    state = BookAnalysisState.ANALYZING,
                    progress = overallProgress,
                    analyzedCount = analyzedCount,
                    totalChapters = totalChapters,
                    message = "Analyzing $chapterLabel"
                )

                // Analyze this chapter (real LLM analysis)
                analyzeChapter(bookId, chapter, chapters.indexOf(chapter), totalChapters)

                analyzedCount++
                AppLogger.i(TAG, "AUTO-ANALYSIS: Completed chapter ${analyzedCount}/$totalChapters for book $bookId")

            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to analyze chapter ${chapter.title} for book $bookId", e)
                // Continue with next chapter, don't fail entire book
            }
        }

        // Clear tracking since we're done with this book
        currentlyAnalyzingBookId = null

        // Check if book was cancelled (deleted) or analysis paused (model switching)
        // Don't try to update if either condition is true
        if (isBookCancelled(bookId)) {
            AppLogger.i(TAG, "Book $bookId was deleted during analysis - skipping completion")
            return
        }
        if (isPaused) {
            AppLogger.i(TAG, "Analysis was paused (model switching) - skipping completion for book $bookId")
            return
        }

        // Mark analysis complete
        val finalProgress = ((analyzedCount.toFloat() / totalChapters) * 100).toInt()
        updateStatus(bookId, AnalysisStatus(AnalysisState.COMPLETE, finalProgress, "Analysis complete", analyzedCount, totalChapters))
        repo.markAnalysisComplete(bookId, totalChapters)
        AppLogger.i(TAG, "AUTO-ANALYSIS: Complete for book $bookId. Analyzed $analyzedCount/$totalChapters chapters")

        // AUG-008: Global character merge after all chapters analyzed
        // This consolidates character data from all chapters into unified entities
        if (analyzedCount > 1 && !isBookCancelled(bookId) && !isPaused) {
            try {
                val db = database ?: return
                val allChapters = repo.chapters(bookId).first()
                val characterJsonList = allChapters.mapNotNull { chapter ->
                    chapter.fullAnalysisJson?.let { json ->
                        try {
                            val analysis = gson.fromJson(json, com.dramebaz.app.ai.llm.ChapterAnalysisResponse::class.java)
                            analysis.characters?.let { chars -> gson.toJson(chars) }
                        } catch (e: Exception) { null }
                    }
                }
                if (characterJsonList.size > 1) {
                    AppLogger.d(TAG, "Global character merge: combining characters from ${characterJsonList.size} chapters")
                    MergeCharactersUseCase(db.characterDao(), ctx)
                        .mergeAndSave(bookId, characterJsonList)
                    AppLogger.i(TAG, "Global character merge complete for book $bookId")
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Global character merge failed for book $bookId", e)
            }
        }
    }

    /**
     * Analyze a single chapter using real LLM inference.
     * This generates proper summaries, key points, characters, and dialogs.
     */
    private suspend fun analyzeChapter(bookId: Long, chapter: Chapter, chapterIndex: Int, totalChapters: Int) {
        val repo = bookRepository ?: return
        val ctx = appContext ?: return

        AppLogger.d(TAG, "Analyzing chapter: ${chapter.title} (${chapter.body.length} chars)")

        // Use real LLM analysis for proper summaries and key points
        val resp = LlmService.analyzeChapter(chapter.body)

        // CRITICAL: Check if book was cancelled (deleted) or analysis was paused (model switching)
        // while LLM inference was running. If so, discard the results.
        if (isBookCancelled(bookId)) {
            AppLogger.i(TAG, "Book $bookId was deleted during LLM inference - discarding results for chapter: ${chapter.title}")
            return
        }
        if (isPaused) {
            AppLogger.i(TAG, "Analysis was paused during LLM inference (model switching) - discarding results for chapter: ${chapter.title}")
            return
        }

        // Save analysis results
        repo.updateChapter(chapter.copy(
            summaryJson = gson.toJson(resp.chapterSummary),
            fullAnalysisJson = gson.toJson(resp)
        ))

        // Merge characters (check cancellation/pause again before additional DB operations)
        if (!isBookCancelled(bookId) && !isPaused) {
            resp.characters?.let { chars ->
                try {
                    val db = database ?: return@let
                    MergeCharactersUseCase(db.characterDao(), ctx)
                        .mergeAndSave(bookId, listOf(gson.toJson(chars)))
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Character merge failed for chapter ${chapter.title}", e)
                }
            }
        }

        // Generate audio for first page of first chapter only
        if (chapterIndex == 0 && !isBookCancelled(bookId) && !isPaused) {
            try {
                generateInitialAudio(bookId, chapter, resp.dialogs)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Initial audio generation failed", e)
            }
        }

        AppLogger.d(TAG, "Chapter analysis complete: ${chapter.title}, dialogs=${resp.dialogs?.size}, characters=${resp.characters?.size}")
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
        // Update foreground service notification
        val ctx = appContext ?: return
        when (status.state) {
            AnalysisState.PENDING -> {
                BookAnalysisForegroundService.updateProgress(ctx, "Queued for analysis", 0)
            }
            AnalysisState.ANALYZING -> {
                BookAnalysisForegroundService.updateProgress(ctx, status.message, status.progress)
            }
            AnalysisState.COMPLETE, AnalysisState.FAILED -> {
                // Foreground service is stopped when queue is empty in startProcessingIfNeeded
            }
        }
    }
}
