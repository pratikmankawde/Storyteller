package com.dramebaz.app.domain.usecases

import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.PriorityQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A thread-safe priority queue for analysis jobs.
 * 
 * Jobs are ordered by priority (higher priority first), then by creation time (older first).
 * Provides operations for enqueue, dequeue, pause, resume, and cancel.
 */
class AnalysisJobQueue(
    /**
     * Name of this queue for logging purposes.
     */
    val name: String
) {
    private val tag = "AnalysisJobQueue($name)"
    
    /**
     * Internal priority queue with custom comparator.
     * Higher priority comes first, then older jobs come first.
     */
    private val queue = PriorityQueue<AnalysisJob>(
        compareByDescending<AnalysisJob> { it.priority }
            .thenBy { it.createdAt }
    )
    
    private val lock = ReentrantLock()
    
    @Volatile
    private var isPaused = false
    
    /**
     * Jobs that were removed due to pause. They'll be re-added on resume.
     */
    private val pausedJobs = mutableListOf<AnalysisJob>()
    
    /**
     * Book IDs that have been cancelled. Jobs for these books will be rejected.
     */
    private val cancelledBookIds = mutableSetOf<Long>()
    
    /**
     * Current queue size.
     */
    private val _size = MutableStateFlow(0)
    val size: StateFlow<Int> = _size.asStateFlow()
    
    /**
     * Queue paused state.
     */
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()
    
    /**
     * Enqueue a job. Returns true if added, false if rejected (e.g., book was cancelled).
     */
    fun enqueue(job: AnalysisJob): Boolean = lock.withLock {
        if (cancelledBookIds.contains(job.bookId)) {
            AppLogger.d(tag, "Rejecting job ${job.id} - book ${job.bookId} was cancelled")
            return false
        }
        
        // Check for duplicate job for same book and type
        val existing = queue.find { 
            it.bookId == job.bookId && it.type == job.type && it.chapterId == job.chapterId 
        }
        if (existing != null) {
            AppLogger.d(tag, "Job for book ${job.bookId} type ${job.type} already queued")
            return false
        }
        
        queue.add(job)
        _size.value = queue.size
        AppLogger.d(tag, "Enqueued ${job.getDisplayName()}, queue size: ${queue.size}")
        return true
    }
    
    /**
     * Dequeue the next job. Returns null if queue is empty or paused.
     */
    fun dequeue(): AnalysisJob? = lock.withLock {
        if (isPaused) {
            AppLogger.d(tag, "Queue is paused, cannot dequeue")
            return null
        }
        
        val job = queue.poll()
        if (job != null) {
            _size.value = queue.size
            AppLogger.d(tag, "Dequeued ${job.getDisplayName()}, queue size: ${queue.size}")
        }
        return job
    }
    
    /**
     * Peek at the next job without removing it.
     */
    fun peek(): AnalysisJob? = lock.withLock {
        queue.peek()
    }
    
    /**
     * Check if the queue is empty.
     */
    fun isEmpty(): Boolean = lock.withLock {
        queue.isEmpty()
    }
    
    /**
     * Check if the queue has jobs.
     */
    fun isNotEmpty(): Boolean = !isEmpty()
    
    /**
     * Get the current queue size.
     */
    fun getSize(): Int = lock.withLock {
        queue.size
    }
    
    /**
     * Check if the queue is paused.
     */
    fun isPaused(): Boolean = isPaused
    
    /**
     * Pause the queue. Dequeue operations will return null until resumed.
     */
    fun pause() = lock.withLock {
        if (isPaused) return@withLock
        isPaused = true
        _paused.value = true
        AppLogger.i(tag, "⏸️ Queue paused")
    }
    
    /**
     * Resume the queue. Re-adds any jobs that were paused.
     */
    fun resume() = lock.withLock {
        if (!isPaused) return@withLock
        isPaused = false
        _paused.value = false

        // Re-add paused jobs
        for (job in pausedJobs) {
            queue.add(job)
        }
        pausedJobs.clear()
        _size.value = queue.size
        AppLogger.i(tag, "▶️ Queue resumed, size: ${queue.size}")
    }

    /**
     * Cancel all jobs for a specific book.
     * Future jobs for this book will be rejected until clearCancellation is called.
     */
    fun cancelForBook(bookId: Long): Int = lock.withLock {
        cancelledBookIds.add(bookId)
        val removed = queue.filter { it.bookId == bookId }
        queue.removeAll(removed.toSet())
        _size.value = queue.size
        AppLogger.i(tag, "Cancelled ${removed.size} jobs for book $bookId")
        return removed.size
    }

    /**
     * Clear the cancellation flag for a book, allowing new jobs to be queued.
     */
    fun clearCancellation(bookId: Long) = lock.withLock {
        cancelledBookIds.remove(bookId)
        AppLogger.d(tag, "Cleared cancellation for book $bookId")
    }

    /**
     * Check if a book has been cancelled.
     */
    fun isBookCancelled(bookId: Long): Boolean = lock.withLock {
        cancelledBookIds.contains(bookId)
    }

    /**
     * Remove a specific job by ID.
     */
    fun remove(jobId: String): Boolean = lock.withLock {
        val job = queue.find { it.id == jobId }
        val removed = if (job != null) queue.remove(job) else false
        if (removed) {
            _size.value = queue.size
            AppLogger.d(tag, "Removed job $jobId")
        }
        return removed
    }

    /**
     * Get all jobs for a specific book.
     */
    fun getJobsForBook(bookId: Long): List<AnalysisJob> = lock.withLock {
        queue.filter { it.bookId == bookId }
    }

    /**
     * Check if there are any jobs for a specific book.
     */
    fun hasJobsForBook(bookId: Long): Boolean = lock.withLock {
        queue.any { it.bookId == bookId }
    }

    /**
     * Clear all jobs from the queue.
     */
    fun clear() = lock.withLock {
        queue.clear()
        _size.value = 0
        AppLogger.i(tag, "Queue cleared")
    }

    /**
     * Get all queued jobs as a list (snapshot).
     */
    fun toList(): List<AnalysisJob> = lock.withLock {
        queue.toList().sortedWith(
            compareByDescending<AnalysisJob> { it.priority }
                .thenBy { it.createdAt }
        )
    }
}

