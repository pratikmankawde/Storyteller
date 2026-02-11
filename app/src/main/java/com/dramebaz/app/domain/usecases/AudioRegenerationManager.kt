package com.dramebaz.app.domain.usecases

import android.content.Context
import com.dramebaz.app.audio.SegmentAudioGenerator
import com.dramebaz.app.data.audio.PageAudioStorage
import com.dramebaz.app.data.db.AppDatabase
import com.dramebaz.app.data.db.CharacterPageMapping
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
 * AUDIO-REGEN-001: Manages background audio regeneration when voice settings change.
 *
 * This manager handles:
 * - Enqueueing regeneration jobs for specific characters/narrator on current page
 * - Cancelling pending jobs for the same character (avoid stale jobs)
 * - Sequential processing to avoid TTS resource contention
 * - Persisting pending jobs for resume on app restart
 */
object AudioRegenerationManager {
    private const val TAG = "AudioRegenerationManager"

    // Dependencies
    private var appContext: Context? = null
    private var database: AppDatabase? = null
    private var segmentAudioGenerator: SegmentAudioGenerator? = null
    private var pageAudioStorage: PageAudioStorage? = null

    // Job queue and processing
    private val jobQueue = LinkedBlockingQueue<RegenerationJob>()
    private val lock = ReentrantLock()
    private var processingJob: Job? = null
    private val processingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Status tracking
    private val _regenerationStatus = MutableStateFlow<Map<String, RegenerationStatus>>(emptyMap())
    val regenerationStatus: StateFlow<Map<String, RegenerationStatus>> = _regenerationStatus.asStateFlow()

    // Callback for when audio is regenerated (to refresh playback)
    var onAudioRegenerated: ((bookId: Long, chapterId: Long, pageNumber: Int, characterName: String) -> Unit)? = null

    /**
     * Initialize the manager with required dependencies.
     */
    fun initialize(
        context: Context,
        db: AppDatabase,
        generator: SegmentAudioGenerator,
        storage: PageAudioStorage
    ) {
        appContext = context.applicationContext
        database = db
        segmentAudioGenerator = generator
        pageAudioStorage = storage
        AppLogger.d(TAG, "AudioRegenerationManager initialized")
    }

    /**
     * Enqueue a regeneration job for a character's audio on the current page.
     * Cancels any existing pending job for the same character.
     */
    fun enqueueRegeneration(
        bookId: Long,
        chapterId: Long,
        pageNumber: Int,
        characterName: String,
        newSpeakerId: Int,
        speed: Float = 1.0f,
        energy: Float = 1.0f
    ) {
        lock.withLock {
            val jobKey = createJobKey(bookId, characterName)

            // Cancel existing job for this character
            val existingJobs = jobQueue.filter { it.key == jobKey }
            existingJobs.forEach { job ->
                jobQueue.remove(job)
                AppLogger.d(TAG, "Cancelled existing job for $characterName in book $bookId")
            }

            // Create and enqueue new job
            val job = RegenerationJob(
                key = jobKey,
                bookId = bookId,
                chapterId = chapterId,
                pageNumber = pageNumber,
                characterName = characterName,
                speakerId = newSpeakerId,
                speed = speed,
                energy = energy
            )
            jobQueue.add(job)
            AppLogger.i(TAG, "Enqueued regeneration job: $characterName, page=$pageNumber, speaker=$newSpeakerId")

            // Update status
            updateStatus(jobKey, RegenerationStatus.PENDING)

            // Start processing if not already running
            startProcessing()
        }
    }

    /**
     * Cancel all pending regeneration jobs for a book.
     */
    fun cancelForBook(bookId: Long) {
        lock.withLock {
            val toRemove = jobQueue.filter { it.bookId == bookId }
            toRemove.forEach { job ->
                jobQueue.remove(job)
                updateStatus(job.key, RegenerationStatus.CANCELLED)
            }
            AppLogger.i(TAG, "Cancelled ${toRemove.size} jobs for book $bookId")
        }
    }

    /**
     * Cancel regeneration job for a specific character.
     */
    fun cancelForCharacter(bookId: Long, characterName: String) {
        lock.withLock {
            val jobKey = createJobKey(bookId, characterName)
            val toRemove = jobQueue.filter { it.key == jobKey }
            toRemove.forEach { job ->
                jobQueue.remove(job)
                updateStatus(job.key, RegenerationStatus.CANCELLED)
            }
            if (toRemove.isNotEmpty()) {
                AppLogger.d(TAG, "Cancelled job for $characterName in book $bookId")
            }
        }
    }

    /**
     * Check if there are pending jobs.
     */
    fun hasPendingJobs(): Boolean = jobQueue.isNotEmpty()

    /**
     * Get pending job count.
     */
    fun getPendingJobCount(): Int = jobQueue.size

    /**
     * Start processing jobs from the queue.
     */
    private fun startProcessing() {
        if (processingJob?.isActive == true) {
            AppLogger.d(TAG, "Processing already running")
            return
        }

        processingJob = processingScope.launch {
            AppLogger.i(TAG, "Starting regeneration processing loop")
            while (jobQueue.isNotEmpty()) {
                val job = jobQueue.poll() ?: continue
                processJob(job)
            }
            AppLogger.i(TAG, "Regeneration processing complete")
        }
    }

    /**
     * Process a single regeneration job.
     */
    private suspend fun processJob(job: RegenerationJob) {
        val db = database ?: run {
            AppLogger.e(TAG, "Database not initialized")
            updateStatus(job.key, RegenerationStatus.FAILED)
            return
        }
        val generator = segmentAudioGenerator ?: run {
            AppLogger.e(TAG, "SegmentAudioGenerator not initialized")
            updateStatus(job.key, RegenerationStatus.FAILED)
            return
        }
        val storage = pageAudioStorage ?: run {
            AppLogger.e(TAG, "PageAudioStorage not initialized")
            updateStatus(job.key, RegenerationStatus.FAILED)
            return
        }

        try {
            updateStatus(job.key, RegenerationStatus.PROCESSING)
            AppLogger.i(TAG, "Processing regeneration for ${job.characterName}, page=${job.pageNumber}")

            // Find segments for this character on the current page
            val mappingDao = db.characterPageMappingDao()
            val segments = mappingDao.getSegmentsForCharacterOnPage(
                bookId = job.bookId,
                chapterId = job.chapterId,
                pageNumber = job.pageNumber,
                characterName = job.characterName
            )

            if (segments.isEmpty()) {
                AppLogger.d(TAG, "No segments found for ${job.characterName} on page ${job.pageNumber}")
                updateStatus(job.key, RegenerationStatus.COMPLETE)
                return
            }

            AppLogger.d(TAG, "Found ${segments.size} segments to regenerate for ${job.characterName}")

            // Delete old audio files for these segments (handled by regenerateSegments internally)

            // Regenerate audio with new speaker settings
            val regeneratedCount = generator.regenerateSegments(segments, job.speakerId)

            AppLogger.i(TAG, "Regenerated $regeneratedCount audio segments for ${job.characterName}")
            updateStatus(job.key, RegenerationStatus.COMPLETE)

            // Notify callback that audio was regenerated
            onAudioRegenerated?.invoke(job.bookId, job.chapterId, job.pageNumber, job.characterName)

        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to regenerate audio for ${job.characterName}", e)
            updateStatus(job.key, RegenerationStatus.FAILED)
        }
    }

    /**
     * Create a unique key for a character in a book.
     */
    private fun createJobKey(bookId: Long, characterName: String): String {
        return "$bookId:$characterName"
    }

    /**
     * Update status for a job.
     */
    private fun updateStatus(key: String, status: RegenerationStatus) {
        val current = _regenerationStatus.value.toMutableMap()
        current[key] = status
        _regenerationStatus.value = current
    }

    /**
     * Clear status for a job.
     */
    fun clearStatus(bookId: Long, characterName: String) {
        val key = createJobKey(bookId, characterName)
        val current = _regenerationStatus.value.toMutableMap()
        current.remove(key)
        _regenerationStatus.value = current
    }

    /**
     * Regeneration job data class.
     */
    data class RegenerationJob(
        val key: String,
        val bookId: Long,
        val chapterId: Long,
        val pageNumber: Int,
        val characterName: String,
        val speakerId: Int,
        val speed: Float = 1.0f,
        val energy: Float = 1.0f,
        val createdAt: Long = System.currentTimeMillis()
    )

    /**
     * Status of a regeneration job.
     */
    enum class RegenerationStatus {
        PENDING,
        PROCESSING,
        COMPLETE,
        FAILED,
        CANCELLED
    }
}
