package com.dramebaz.app.domain.usecases

import android.content.Context
import com.dramebaz.app.audio.SegmentAudioGenerator
import com.dramebaz.app.data.db.AppDatabase
import com.dramebaz.app.data.models.Dialog
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * INCREMENTAL-001: Manages incremental audio generation jobs during batched chapter analysis.
 *
 * This manager handles:
 * - Enqueueing audio generation jobs for pages as batches complete
 * - Cancelling pending jobs when speaker changes (to avoid stale audio)
 * - Sequential processing to avoid TTS resource contention
 * - Page-to-dialog mapping for targeted audio generation
 *
 * Key difference from AudioRegenerationManager:
 * - AudioRegenerationManager handles speaker change REgeneration for existing segments
 * - IncrementalAudioJobManager handles INITIAL audio generation during analysis
 */
object IncrementalAudioJobManager {
    private const val TAG = "IncrementalAudioJobManager"

    // Dependencies
    private var appContext: Context? = null
    private var database: AppDatabase? = null
    private var segmentAudioGenerator: SegmentAudioGenerator? = null

    // Job queue and processing
    private val jobQueue = LinkedBlockingQueue<AudioGenerationJob>()
    private val lock = ReentrantLock()
    private var processingJob: Job? = null
    private val processingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track active jobs by book+chapter+page for cancellation
    private val activeJobKeys = ConcurrentHashMap<String, Boolean>()

    // Status tracking
    private val _generationStatus = MutableStateFlow<Map<String, GenerationStatus>>(emptyMap())
    val generationStatus: StateFlow<Map<String, GenerationStatus>> = _generationStatus.asStateFlow()

    /**
     * Initialize the manager with required dependencies.
     */
    fun initialize(
        context: Context,
        db: AppDatabase,
        generator: SegmentAudioGenerator
    ) {
        appContext = context.applicationContext
        database = db
        segmentAudioGenerator = generator
        AppLogger.d(TAG, "IncrementalAudioJobManager initialized")
    }

    /**
     * Enqueue audio generation jobs for a list of pages.
     * Called after each batch completes during incremental analysis.
     *
     * @param bookId Book ID
     * @param chapterId Chapter ID
     * @param pageDataList List of (pageNumber, pageText, dialogs) tuples for audio generation
     */
    fun enqueuePages(
        bookId: Long,
        chapterId: Long,
        pageDataList: List<PageAudioData>
    ) {
        lock.withLock {
            for (pageData in pageDataList) {
                val jobKey = createJobKey(bookId, chapterId, pageData.pageNumber)

                // Skip if already enqueued
                if (activeJobKeys.containsKey(jobKey)) {
                    AppLogger.d(TAG, "Skipping already enqueued page ${pageData.pageNumber}")
                    continue
                }

                val job = AudioGenerationJob(
                    key = jobKey,
                    bookId = bookId,
                    chapterId = chapterId,
                    pageNumber = pageData.pageNumber,
                    pageText = pageData.pageText,
                    dialogs = pageData.dialogs
                )
                jobQueue.add(job)
                activeJobKeys[jobKey] = true
                updateStatus(jobKey, GenerationStatus.PENDING)

                AppLogger.d(TAG, "Enqueued audio job: book=$bookId, chapter=$chapterId, page=${pageData.pageNumber}")
            }

            // Start processing if not already running
            startProcessing()
        }
    }

    /**
     * Cancel all pending audio generation jobs for a specific character in a book.
     * Called when user changes speaker assignment to avoid generating stale audio.
     *
     * @param bookId Book ID
     * @param characterName Character whose audio jobs should be cancelled
     */
    fun cancelForCharacter(bookId: Long, characterName: String) {
        lock.withLock {
            val toRemove = jobQueue.filter { job ->
                job.bookId == bookId && job.dialogs.any { it.speaker == characterName }
            }
            toRemove.forEach { job ->
                jobQueue.remove(job)
                activeJobKeys.remove(job.key)
                updateStatus(job.key, GenerationStatus.CANCELLED)
            }
            if (toRemove.isNotEmpty()) {
                AppLogger.i(TAG, "Cancelled ${toRemove.size} audio jobs for $characterName in book $bookId")
            }
        }
    }

    /**
     * Cancel all pending audio generation jobs for a book.
     */
    fun cancelForBook(bookId: Long) {
        lock.withLock {
            val toRemove = jobQueue.filter { it.bookId == bookId }
            toRemove.forEach { job ->
                jobQueue.remove(job)
                activeJobKeys.remove(job.key)
                updateStatus(job.key, GenerationStatus.CANCELLED)
            }
            AppLogger.i(TAG, "Cancelled ${toRemove.size} jobs for book $bookId")
        }
    }

    /**
     * Cancel all pending jobs for a specific chapter.
     */
    fun cancelForChapter(bookId: Long, chapterId: Long) {
        lock.withLock {
            val toRemove = jobQueue.filter { it.bookId == bookId && it.chapterId == chapterId }
            toRemove.forEach { job ->
                jobQueue.remove(job)
                activeJobKeys.remove(job.key)
                updateStatus(job.key, GenerationStatus.CANCELLED)
            }
            if (toRemove.isNotEmpty()) {
                AppLogger.d(TAG, "Cancelled ${toRemove.size} jobs for chapter $chapterId")
            }
        }
    }

    /**
     * Start processing jobs if not already running.
     */
    private fun startProcessing() {
        if (processingJob?.isActive == true) return

        processingJob = processingScope.launch {
            AppLogger.d(TAG, "Started audio generation processing loop")
            while (isActive) {
                val job = jobQueue.poll() ?: break
                processJob(job)
            }
            AppLogger.d(TAG, "Audio generation processing loop ended")
        }
    }

    /**
     * Process a single audio generation job.
     */
    private suspend fun processJob(job: AudioGenerationJob) {
        val generator = segmentAudioGenerator ?: run {
            AppLogger.w(TAG, "SegmentAudioGenerator not initialized, skipping job")
            updateStatus(job.key, GenerationStatus.FAILED)
            activeJobKeys.remove(job.key)
            return
        }

        // Check if job was cancelled while waiting
        if (!activeJobKeys.containsKey(job.key)) {
            AppLogger.d(TAG, "Job ${job.key} was cancelled, skipping")
            return
        }

        updateStatus(job.key, GenerationStatus.PROCESSING)
        AppLogger.d(TAG, "Processing audio for page ${job.pageNumber} in chapter ${job.chapterId}")

        try {
            // Generate audio for the page using SegmentAudioGenerator
            generator.generatePageAudio(
                bookId = job.bookId,
                chapterId = job.chapterId,
                pageNumber = job.pageNumber,
                pageText = job.pageText,
                dialogs = job.dialogs
            )

            updateStatus(job.key, GenerationStatus.COMPLETED)
            AppLogger.i(TAG, "✅ Audio generated for page ${job.pageNumber}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to generate audio for page ${job.pageNumber}", e)
            updateStatus(job.key, GenerationStatus.FAILED)
        } finally {
            activeJobKeys.remove(job.key)
        }
    }

    private fun createJobKey(bookId: Long, chapterId: Long, pageNumber: Int): String {
        return "audio_$bookId-$chapterId-$pageNumber"
    }

    private fun updateStatus(key: String, status: GenerationStatus) {
        _generationStatus.value = _generationStatus.value + (key to status)
    }

    /**
     * Get pending job count for a book.
     */
    fun getPendingCount(bookId: Long): Int {
        return jobQueue.count { it.bookId == bookId }
    }

    /**
     * Check if there are any pending jobs.
     */
    fun hasPendingJobs(): Boolean = jobQueue.isNotEmpty()

    /**
     * Clear completed/failed/cancelled status entries older than threshold.
     */
    fun clearOldStatus() {
        _generationStatus.value = _generationStatus.value.filterValues { it == GenerationStatus.PENDING || it == GenerationStatus.PROCESSING }
    }

    // ==================== Data Classes ====================

    /**
     * Represents an audio generation job for a single page.
     */
    data class AudioGenerationJob(
        val key: String,
        val bookId: Long,
        val chapterId: Long,
        val pageNumber: Int,
        val pageText: String,
        val dialogs: List<Dialog>
    )

    /**
     * Data class for page audio generation input.
     */
    data class PageAudioData(
        val pageNumber: Int,
        val pageText: String,
        val dialogs: List<Dialog>
    )

    /**
     * Status of an audio generation job.
     */
    enum class GenerationStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
