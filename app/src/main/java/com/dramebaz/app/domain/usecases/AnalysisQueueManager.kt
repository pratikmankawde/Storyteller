package com.dramebaz.app.domain.usecases

import android.content.Context
import com.dramebaz.app.ai.llm.services.AnalysisForegroundService
import com.dramebaz.app.audio.SegmentAudioGenerator
import com.dramebaz.app.data.db.AppDatabase
import com.dramebaz.app.data.repositories.BookRepository
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages queues of analysis jobs and delegates execution to workflows.
 *
 * Responsibilities:
 * - Managing job queues (full book analysis, first-chapter analysis)
 * - Processing jobs from queues
 * - Coordinating with foreground service for wake lock and notification
 * - Delegating actual analysis work to BookAnalysisWorkflow
 *
 * Does NOT contain workflow logic - that's in BookAnalysisWorkflow.
 */
object AnalysisQueueManager {
    private const val TAG = "AnalysisQueueManager"

    // Job queues
    private val fullBookQueue = AnalysisJobQueue("full_book")
    private val firstChapterQueue = AnalysisJobQueue("first_chapter")

    // Processing jobs
    private var fullBookProcessingJob: Job? = null
    private var firstChapterProcessingJob: Job? = null

    // Dependencies
    private var bookRepository: BookRepository? = null
    private var database: AppDatabase? = null
    private var appContext: Context? = null
    private var segmentAudioGenerator: SegmentAudioGenerator? = null

    // Workflow instance (created after initialization)
    private var bookAnalysisWorkflow: BookAnalysisWorkflow? = null

    // Currently processing book
    private var currentlyAnalyzingBookId: Long? = null

    // Status tracking per book (combines workflow status with queue status)
    private val _analysisStatus = MutableStateFlow<Map<Long, AnalysisStatus>>(emptyMap())
    val analysisStatus: StateFlow<Map<Long, AnalysisStatus>> = _analysisStatus.asStateFlow()

    // Compatibility aliases for existing code
    enum class AnalysisState { PENDING, ANALYZING, COMPLETE, FAILED }

    data class AnalysisStatus(
        val state: AnalysisState,
        val progress: Int = 0,
        val message: String = "",
        val analyzedChapters: Int = 0,
        val totalChapters: Int = 0
    )

    fun initialize(context: Context, repository: BookRepository, db: AppDatabase) {
        appContext = context.applicationContext
        bookRepository = repository
        database = db

        // Create workflow instance
        bookAnalysisWorkflow = BookAnalysisWorkflow(
            context = context.applicationContext,
            bookRepository = repository,
            database = db
        )

        AppLogger.d(TAG, "AnalysisQueueManager initialized with workflow")
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
                // Check if this was a first-chapter-only analysis or full analysis
                if (book.analyzedChapterCount == 0) {
                    // No chapters analyzed yet - resume with first-chapter analysis
                    AppLogger.i(TAG, "Resuming FIRST-CHAPTER analysis for book ${book.id}: ${book.title}")
                    enqueueFirstChapter(book.id)
                } else {
                    // Some chapters were already analyzed - resume full analysis
                    AppLogger.i(TAG, "Resuming FULL analysis for book ${book.id}: ${book.title}")
                    enqueueBook(book.id)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to resume incomplete analysis", e)
        }
    }

    /**
     * Set the segment audio generator for initial page audio generation.
     */
    fun setSegmentAudioGenerator(generator: SegmentAudioGenerator) {
        segmentAudioGenerator = generator
        bookAnalysisWorkflow?.segmentAudioGenerator = generator
        AppLogger.d(TAG, "SegmentAudioGenerator set")
    }

    /**
     * Start processing any queued jobs. Called from SplashActivity after LLM is loaded
     * to ensure we're in a visible activity context when starting foreground services.
     */
    fun startProcessing() {
        if (fullBookQueue.isNotEmpty()) {
            AppLogger.i(TAG, "Starting full book queue processing (${fullBookQueue.getSize()} jobs)")
            startFullBookProcessing()
        }
        if (firstChapterQueue.isNotEmpty()) {
            AppLogger.i(TAG, "Starting first chapter queue processing (${firstChapterQueue.getSize()} jobs)")
            startFirstChapterProcessing()
        }
    }

    /**
     * Pause analysis when LLM settings are being changed.
     */
    fun pauseAnalysis() {
        AppLogger.i(TAG, "⏸️ Pausing analysis for LLM settings change...")

        // Pause queues
        fullBookQueue.pause()
        firstChapterQueue.pause()

        // Pause workflow
        bookAnalysisWorkflow?.pause()

        // Cancel processing jobs
        fullBookProcessingJob?.cancel()
        fullBookProcessingJob = null
        firstChapterProcessingJob?.cancel()
        firstChapterProcessingJob = null

        // Update status for currently analyzing book
        currentlyAnalyzingBookId?.let { bookId ->
            updateStatus(bookId, AnalysisStatus(AnalysisState.PENDING, 0, "Paused - LLM settings changing"))
        }

        AppLogger.i(TAG, "⏸️ Analysis paused")
    }

    /**
     * Resume analysis after LLM settings have been updated and model reloaded.
     */
    fun resumeAnalysis() {
        AppLogger.i(TAG, "▶️ Resuming analysis after LLM settings change...")

        // Resume queues
        fullBookQueue.resume()
        firstChapterQueue.resume()

        // Resume workflow
        bookAnalysisWorkflow?.resume()

        currentlyAnalyzingBookId = null

        // Restart processing if there are queued jobs
        if (fullBookQueue.isNotEmpty()) {
            startFullBookProcessing()
        }
        if (firstChapterQueue.isNotEmpty()) {
            startFirstChapterProcessing()
        }

        AppLogger.i(TAG, "▶️ Analysis resumed")
    }

    /**
     * Check if analysis is currently paused.
     */
    fun isAnalysisPaused(): Boolean = fullBookQueue.isPaused()

    /**
     * Cancel analysis for a specific book.
     */
    fun cancelAnalysisForBook(bookId: Long) {
        AppLogger.i(TAG, "❌ Cancelling analysis for book $bookId")

        // Cancel in workflow
        bookAnalysisWorkflow?.cancelForBook(bookId)

        // Remove from queues
        fullBookQueue.cancelForBook(bookId)
        firstChapterQueue.cancelForBook(bookId)

        // Update status
        updateStatus(bookId, AnalysisStatus(AnalysisState.FAILED, 0, "Cancelled"))

        // If this was the currently analyzing book, stop the service
        if (currentlyAnalyzingBookId == bookId) {
            currentlyAnalyzingBookId = null
            appContext?.let { ctx ->
                AnalysisForegroundService.stop(ctx)
            }
        }

        // Mark as failed in database
        CoroutineScope(Dispatchers.IO).launch {
            bookRepository?.markAnalysisFailed(bookId, "Cancelled by user")
        }

        AppLogger.i(TAG, "❌ Analysis cancelled for book $bookId")
    }

    /**
     * Check if analysis for a book has been cancelled.
     */
    fun isBookCancelled(bookId: Long): Boolean {
        return fullBookQueue.isBookCancelled(bookId) ||
                firstChapterQueue.isBookCancelled(bookId) ||
                (bookAnalysisWorkflow?.isBookCancelled(bookId) ?: false)
    }

    /**
     * Clear the cancelled state for a book (e.g., when re-queuing).
     */
    fun clearCancelledBook(bookId: Long) {
        fullBookQueue.clearCancellation(bookId)
        firstChapterQueue.clearCancellation(bookId)
        bookAnalysisWorkflow?.clearCancelledBook(bookId)
    }

    /**
     * Get the book ID currently being analyzed.
     */
    fun getCurrentlyAnalyzingBookId(): Long? = currentlyAnalyzingBookId

    /**
     * Enqueue a book for full analysis after import.
     */
    fun enqueueBook(bookId: Long) {
        if (bookId <= 0) return

        // Skip if already being processed
        val currentStatus = _analysisStatus.value[bookId]
        if (currentStatus?.state == AnalysisState.ANALYZING) {
            AppLogger.d(TAG, "Book $bookId already being analyzed, skipping")
            return
        }

        val job = AnalysisJob.fullBookAnalysis(bookId)
        if (fullBookQueue.enqueue(job)) {
            updateStatus(bookId, AnalysisStatus(AnalysisState.PENDING, 0, "Queued for analysis"))
            AppLogger.i(TAG, "Book $bookId queued for full analysis. Queue size: ${fullBookQueue.getSize()}")
            startFullBookProcessing()
        }
    }

    /**
     * Enqueue a book for first-chapter-only analysis.
     */
    fun enqueueFirstChapter(bookId: Long) {
        if (bookId <= 0) return

        // Skip if already being processed
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

            val job = AnalysisJob.firstChapterAnalysis(bookId)
            if (firstChapterQueue.enqueue(job)) {
                updateStatus(bookId, AnalysisStatus(AnalysisState.PENDING, 0, "Queued for first chapter analysis"))
                AppLogger.i(TAG, "Book $bookId queued for first-chapter analysis")
                startFirstChapterProcessing()
            }
        }
    }

    /**
     * Get the analysis status for a specific book.
     */
    fun getBookStatus(bookId: Long): AnalysisStatus? = _analysisStatus.value[bookId]

    /**
     * Check if a book has been analyzed (all chapters).
     */
    suspend fun isBookAnalyzed(bookId: Long): Boolean {
        return bookAnalysisWorkflow?.isBookAnalyzed(bookId) ?: false
    }

    /**
     * Check if a book is partially analyzed (first chapter).
     */
    suspend fun isBookPartiallyAnalyzed(bookId: Long): Boolean {
        return bookAnalysisWorkflow?.isBookPartiallyAnalyzed(bookId) ?: false
    }

    // ==================== Job Processing ====================

    /**
     * Start processing full book analysis jobs from the queue.
     */
    private fun startFullBookProcessing() {
        if (fullBookProcessingJob?.isActive == true) return

        fullBookProcessingJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            // Start foreground service for background processing
            appContext?.let { ctx ->
                AnalysisForegroundService.startSimple(ctx, "Analyzing book...")
            }

            try {
                while (fullBookQueue.isNotEmpty()) {
                    val job = fullBookQueue.dequeue() ?: continue
                    val bookId = job.bookId

                    // Skip if cancelled
                    if (fullBookQueue.isBookCancelled(bookId)) {
                        AppLogger.d(TAG, "Skipping cancelled job for book $bookId")
                        continue
                    }

                    currentlyAnalyzingBookId = bookId

                    try {
                        val success = bookAnalysisWorkflow?.analyzeAllChapters(
                            bookId = bookId,
                            progressCallback = createProgressCallback()
                        ) ?: false

                        if (success) {
                            AppLogger.i(TAG, "Full book analysis complete for book $bookId")
                        } else {
                            AppLogger.w(TAG, "Full book analysis failed for book $bookId")
                            bookRepository?.markAnalysisFailed(bookId, "Analysis failed")
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Analysis failed for book $bookId", e)
                        updateStatus(bookId, AnalysisStatus(AnalysisState.FAILED, 0, "Analysis failed: ${e.message}"))
                        bookRepository?.markAnalysisFailed(bookId, e.message ?: "Unknown error")
                    }

                    currentlyAnalyzingBookId = null
                }

                AppLogger.d(TAG, "Full book queue processing complete")
            } finally {
                currentlyAnalyzingBookId = null
                appContext?.let { ctx ->
                    AnalysisForegroundService.stop(ctx)
                }
            }
        }
    }

    /**
     * Start processing first chapter analysis jobs from the queue.
     */
    private fun startFirstChapterProcessing() {
        if (firstChapterProcessingJob?.isActive == true) return

        firstChapterProcessingJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            // Start foreground service
            appContext?.let { ctx ->
                AnalysisForegroundService.startSimple(ctx, "Analyzing first chapter...")
            }

            try {
                while (firstChapterQueue.isNotEmpty()) {
                    val job = firstChapterQueue.dequeue() ?: continue
                    val bookId = job.bookId

                    // Skip if cancelled
                    if (firstChapterQueue.isBookCancelled(bookId)) {
                        AppLogger.d(TAG, "Skipping cancelled first chapter job for book $bookId")
                        continue
                    }

                    currentlyAnalyzingBookId = bookId

                    try {
                        val success = bookAnalysisWorkflow?.analyzeFirstChapter(
                            bookId = bookId,
                            progressCallback = createProgressCallback()
                        ) ?: false

                        if (success) {
                            AppLogger.i(TAG, "First chapter analysis complete for book $bookId")
                        } else {
                            AppLogger.w(TAG, "First chapter analysis failed for book $bookId")
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "First chapter analysis failed for book $bookId", e)
                        updateStatus(bookId, AnalysisStatus(AnalysisState.FAILED, 0, "Analysis failed: ${e.message}"))
                        bookRepository?.markAnalysisFailed(bookId, e.message ?: "Unknown error")
                    }

                    currentlyAnalyzingBookId = null
                }

                AppLogger.d(TAG, "First chapter queue processing complete")
            } finally {
                currentlyAnalyzingBookId = null
                // Only stop service if full book queue is also empty
                if (fullBookQueue.isEmpty()) {
                    appContext?.let { ctx ->
                        AnalysisForegroundService.stop(ctx)
                    }
                }
            }
        }
    }

    /**
     * Create a progress callback for the workflow.
     */
    private fun createProgressCallback(): BookAnalysisWorkflow.ProgressCallback {
        return object : BookAnalysisWorkflow.ProgressCallback {
            override fun onProgress(bookId: Long, status: BookAnalysisWorkflow.AnalysisStatus) {
                // Map workflow status to queue manager status
                val state = when (status.state) {
                    BookAnalysisWorkflow.AnalysisState.PENDING -> AnalysisState.PENDING
                    BookAnalysisWorkflow.AnalysisState.ANALYZING -> AnalysisState.ANALYZING
                    BookAnalysisWorkflow.AnalysisState.COMPLETE -> AnalysisState.COMPLETE
                    BookAnalysisWorkflow.AnalysisState.FAILED -> AnalysisState.FAILED
                }
                val mappedStatus = AnalysisStatus(
                    state = state,
                    progress = status.progress,
                    message = status.message,
                    analyzedChapters = status.analyzedChapters,
                    totalChapters = status.totalChapters
                )
                updateStatus(bookId, mappedStatus)
            }
        }
    }

    /**
     * Update status for a book and notify foreground service.
     */
    private fun updateStatus(bookId: Long, status: AnalysisStatus) {
        _analysisStatus.value = _analysisStatus.value + (bookId to status)

        // Update foreground service notification
        val ctx = appContext ?: return
        when (status.state) {
            AnalysisState.PENDING -> {
                AnalysisForegroundService.updateProgress(ctx, "Queued for analysis", 0)
            }
            AnalysisState.ANALYZING -> {
                AnalysisForegroundService.updateProgress(ctx, status.message, status.progress)
            }
            AnalysisState.COMPLETE, AnalysisState.FAILED -> {
                // Service is stopped when queues are empty in processing methods
            }
        }
    }
}