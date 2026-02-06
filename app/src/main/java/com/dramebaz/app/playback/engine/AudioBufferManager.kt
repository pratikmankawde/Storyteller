package com.dramebaz.app.playback.engine

import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * READ-002: Audio Buffer Pre-loading
 * 
 * Manages audio buffer for seamless playback:
 * - Pre-loads audio for next 2 pages ahead of current playing page
 * - Triggers pre-generation when playback reaches 50% of current page
 * - Clears old buffers (already played pages) to manage memory
 * - Handles page jumps by clearing and re-preparing buffer
 */
class AudioBufferManager(
    private val scope: CoroutineScope,
    private val onPrepareAudio: suspend (Int, Int) -> Unit, // (startPage, count) -> generate audio
    private val onBufferCleared: (Int) -> Unit // (pageIndex) -> buffer cleared callback
) {
    private val tag = "AudioBufferManager"
    
    companion object {
        /** Number of pages to buffer ahead of current playing page */
        const val BUFFER_AHEAD_PAGES = 2
        
        /** Progress threshold (0.0-1.0) at which to trigger next page pre-generation */
        const val PRELOAD_TRIGGER_PROGRESS = 0.5f
        
        /** Maximum number of pages to keep in memory buffer */
        const val MAX_BUFFERED_PAGES = 5
    }
    
    /** Current page index that is playing */
    private var currentPlayingPage: Int = -1
    
    /** Set of pages for which pre-generation has been triggered */
    private val triggeredPages = mutableSetOf<Int>()
    
    /** Audio buffer: page index -> audio file */
    private val audioBuffer = mutableMapOf<Int, File>()
    
    /** Mutex for thread-safe buffer access */
    private val bufferMutex = Mutex()
    
    /** Active pre-generation job */
    private var preGenJob: Job? = null
    
    /** Flag to track if pre-generation was triggered at 50% for current page */
    private var midPageTriggerFired = false
    
    /**
     * Called when playback starts on a new page.
     * @param pageIndex The page index that started playing
     * @param totalPages Total number of pages in the chapter
     */
    fun onPlaybackStarted(pageIndex: Int, totalPages: Int) {
        AppLogger.d(tag, "Playback started on page $pageIndex (total: $totalPages)")
        currentPlayingPage = pageIndex
        midPageTriggerFired = false
        
        // Clear old buffers (pages before current)
        clearOldBuffers(pageIndex)
        
        // Trigger pre-generation for upcoming pages
        triggerPreGeneration(pageIndex, totalPages)
    }
    
    /**
     * Called with playback progress updates.
     * Triggers pre-generation when playback reaches 50% of current page.
     * 
     * @param progress Playback progress as a fraction (0.0 to 1.0)
     * @param totalPages Total number of pages in the chapter
     */
    fun onPlaybackProgress(progress: Float, totalPages: Int) {
        if (currentPlayingPage < 0 || midPageTriggerFired) return
        
        // Trigger at 50% progress
        if (progress >= PRELOAD_TRIGGER_PROGRESS) {
            midPageTriggerFired = true
            val nextPage = currentPlayingPage + 1
            if (nextPage < totalPages && !triggeredPages.contains(nextPage)) {
                AppLogger.i(tag, "Triggering pre-generation at ${(progress * 100).toInt()}% progress for pages $nextPage-${minOf(nextPage + BUFFER_AHEAD_PAGES, totalPages - 1)}")
                triggerPreGeneration(currentPlayingPage, totalPages)
            }
        }
    }
    
    /**
     * Called when user jumps to a different page (not sequential playback).
     * Clears buffer and re-prepares for new position.
     */
    fun onPageJump(newPageIndex: Int, totalPages: Int) {
        AppLogger.d(tag, "Page jump detected: $currentPlayingPage -> $newPageIndex")
        
        // Cancel any ongoing pre-generation
        preGenJob?.cancel()
        preGenJob = null
        
        // Clear all triggered pages that are no longer relevant
        triggeredPages.removeAll { it < newPageIndex - 1 || it > newPageIndex + BUFFER_AHEAD_PAGES + 1 }
        
        // Update current page and trigger new pre-generation
        currentPlayingPage = newPageIndex
        midPageTriggerFired = false
        clearOldBuffers(newPageIndex)
        triggerPreGeneration(newPageIndex, totalPages)
    }
    
    /**
     * Add a pre-generated audio file to the buffer.
     */
    suspend fun addToBuffer(pageIndex: Int, audioFile: File) = bufferMutex.withLock {
        audioBuffer[pageIndex] = audioFile
        AppLogger.d(tag, "Added page $pageIndex to buffer (buffer size: ${audioBuffer.size})")
    }
    
    /**
     * Get audio file from buffer if available.
     */
    suspend fun getFromBuffer(pageIndex: Int): File? = bufferMutex.withLock {
        audioBuffer[pageIndex]
    }
    
    /**
     * Check if page audio is in buffer.
     */
    suspend fun isInBuffer(pageIndex: Int): Boolean = bufferMutex.withLock {
        audioBuffer.containsKey(pageIndex)
    }
    
    /**
     * Trigger pre-generation for upcoming pages.
     */
    private fun triggerPreGeneration(currentPage: Int, totalPages: Int) {
        val startPage = currentPage + 1
        val endPage = minOf(startPage + BUFFER_AHEAD_PAGES, totalPages)

        if (startPage >= totalPages) {
            AppLogger.d(tag, "No more pages to pre-generate")
            return
        }

        // Filter out already triggered pages
        val pagesToGenerate = (startPage until endPage).filter { !triggeredPages.contains(it) }
        if (pagesToGenerate.isEmpty()) {
            AppLogger.d(tag, "All upcoming pages already triggered for pre-generation")
            return
        }

        // Mark pages as triggered
        triggeredPages.addAll(pagesToGenerate)

        // Launch pre-generation in background
        preGenJob = scope.launch(Dispatchers.IO) {
            val firstPage = pagesToGenerate.first()
            val count = pagesToGenerate.size
            AppLogger.i(tag, "Pre-generating audio for pages $firstPage to ${firstPage + count - 1}")
            onPrepareAudio(firstPage, count)
        }
    }

    /**
     * Clear old buffers to manage memory.
     * Removes pages that have already been played.
     */
    private fun clearOldBuffers(currentPage: Int) {
        scope.launch(Dispatchers.IO) {
            bufferMutex.withLock {
                val toRemove = audioBuffer.keys.filter { it < currentPage - 1 }
                toRemove.forEach { pageIndex ->
                    audioBuffer.remove(pageIndex)
                    onBufferCleared(pageIndex)
                    AppLogger.d(tag, "Cleared buffer for page $pageIndex")
                }

                // Also enforce max buffer size
                if (audioBuffer.size > MAX_BUFFERED_PAGES) {
                    val sortedKeys = audioBuffer.keys.sorted()
                    val excess = sortedKeys.take(audioBuffer.size - MAX_BUFFERED_PAGES)
                    excess.forEach { pageIndex ->
                        audioBuffer.remove(pageIndex)
                        onBufferCleared(pageIndex)
                        AppLogger.d(tag, "Cleared excess buffer for page $pageIndex")
                    }
                }
            }
        }
    }

    /**
     * Clear all buffers and reset state. Called when chapter changes.
     */
    fun reset() {
        scope.launch(Dispatchers.IO) {
            preGenJob?.cancel()
            preGenJob = null
            bufferMutex.withLock {
                audioBuffer.clear()
            }
            triggeredPages.clear()
            currentPlayingPage = -1
            midPageTriggerFired = false
            AppLogger.d(tag, "Buffer manager reset")
        }
    }

    /**
     * Get current buffer size for debugging.
     */
    suspend fun getBufferSize(): Int = bufferMutex.withLock { audioBuffer.size }
}

