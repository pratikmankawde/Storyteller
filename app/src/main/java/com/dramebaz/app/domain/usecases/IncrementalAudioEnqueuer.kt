package com.dramebaz.app.domain.usecases

import com.dramebaz.app.ai.llm.pipeline.IncrementalMerger
import com.dramebaz.app.ai.llm.tasks.BatchedChapterAnalysisTask
import com.dramebaz.app.audio.SegmentAudioGenerator
import com.dramebaz.app.data.models.Dialog
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * INCREMENTAL-002: Helper class for enqueuing audio generation after each batch completes.
 * 
 * Handles the onBatchComplete callback from BatchedChapterAnalysisTask to:
 * 1. Extract dialogs for pages in the completed batch
 * 2. Enqueue audio generation for those pages
 * 3. Track pending jobs by character for cancellation
 */
class IncrementalAudioEnqueuer(
    private val segmentAudioGenerator: SegmentAudioGenerator,
    private val scope: CoroutineScope
) {
    private val tag = "IncrementalAudioEnqueuer"
    
    /** Mutex for thread-safe job tracking */
    private val jobMutex = Mutex()
    
    /** Active audio generation jobs by (bookId, chapterId, pageNumber) */
    private val activeJobs = mutableMapOf<Triple<Long, Long, Int>, Job>()
    
    /** Jobs by character name for cancellation on speaker change */
    private val jobsByCharacter = mutableMapOf<String, MutableSet<Triple<Long, Long, Int>>>()

    /**
     * Create a callback handler for BatchedChapterAnalysisTask.onBatchComplete.
     * 
     * @param bookId Book ID for audio file organization
     * @param chapterId Chapter ID for audio file organization
     * @return Suspend function to pass to BatchedChapterAnalysisTask
     */
    fun createBatchCompleteCallback(
        bookId: Long,
        chapterId: Long
    ): suspend (BatchedChapterAnalysisTask.BatchCompleteData) -> Unit = { batchData ->
        processBatchComplete(bookId, chapterId, batchData)
    }

    /**
     * Process batch completion and enqueue audio generation for pages in the batch.
     */
    private suspend fun processBatchComplete(
        bookId: Long,
        chapterId: Long,
        batchData: BatchedChapterAnalysisTask.BatchCompleteData
    ) {
        AppLogger.i(tag, "Batch ${batchData.batchIndex + 1}/${batchData.totalBatches} complete, " +
                "pages ${batchData.pageRange}, ${batchData.characters.size} characters")

        // Extract dialogs from accumulated characters
        val allDialogs = extractDialogsFromCharacters(batchData.characters)
        
        // Enqueue audio generation for each page in this batch
        for (pageIndex in batchData.pageRange) {
            val pageNumber = pageIndex + 1  // Convert 0-based to 1-based
            val pageText = batchData.pageTexts[pageIndex] ?: continue
            
            // Filter dialogs that appear on this page
            val pageDialogs = segmentAudioGenerator.getDialogsForPage(pageText, allDialogs)
            
            if (pageDialogs.isEmpty() && pageText.isBlank()) {
                AppLogger.d(tag, "Skipping empty page $pageNumber")
                continue
            }

            enqueuePageAudioGeneration(bookId, chapterId, pageNumber, pageText, pageDialogs)
        }
    }

    /**
     * Enqueue audio generation for a single page.
     */
    private suspend fun enqueuePageAudioGeneration(
        bookId: Long,
        chapterId: Long,
        pageNumber: Int,
        pageText: String,
        dialogs: List<Dialog>
    ) = jobMutex.withLock {
        val jobKey = Triple(bookId, chapterId, pageNumber)
        
        // Skip if already processing this page
        if (activeJobs[jobKey]?.isActive == true) {
            AppLogger.d(tag, "Page $pageNumber already being processed, skipping")
            return@withLock
        }

        val job = scope.launch(Dispatchers.IO) {
            try {
                AppLogger.d(tag, "Generating audio for page $pageNumber (${dialogs.size} dialogs)")
                segmentAudioGenerator.generatePageAudio(
                    bookId = bookId,
                    chapterId = chapterId,
                    pageNumber = pageNumber,
                    pageText = pageText,
                    dialogs = dialogs
                )
                AppLogger.i(tag, "✅ Audio generated for page $pageNumber")
            } catch (e: Exception) {
                AppLogger.e(tag, "Failed to generate audio for page $pageNumber", e)
            }
        }
        
        activeJobs[jobKey] = job

        // Track by character names for cancellation
        dialogs.forEach { dialog ->
            val characterJobs = jobsByCharacter.getOrPut(dialog.speaker) { mutableSetOf() }
            characterJobs.add(jobKey)
        }
    }

    /**
     * Extract Dialog objects from merged character data.
     * Note: MergedCharacterData.dialogs is a List<String> (dialog text only),
     * so we create Dialog objects with default emotion.
     */
    private fun extractDialogsFromCharacters(
        characters: List<IncrementalMerger.MergedCharacterData>
    ): List<Dialog> {
        return characters.flatMap { char ->
            char.dialogs.map { dialogText ->
                Dialog(
                    speaker = char.canonicalName,
                    dialog = dialogText,
                    emotion = "neutral"  // Default emotion since merged data doesn't preserve it
                )
            }
        }
    }

    /**
     * Cancel all pending audio jobs for a specific character.
     * Called when user changes speaker assignment.
     */
    suspend fun cancelJobsForCharacter(characterName: String) = jobMutex.withLock {
        val jobKeys = jobsByCharacter[characterName] ?: return@withLock
        
        AppLogger.i(tag, "Cancelling ${jobKeys.size} audio jobs for character: $characterName")
        
        for (jobKey in jobKeys.toList()) {
            activeJobs[jobKey]?.cancel()
            activeJobs.remove(jobKey)
        }
        jobsByCharacter.remove(characterName)
    }

    /** Cancel all pending audio jobs. */
    suspend fun cancelAll() = jobMutex.withLock {
        AppLogger.i(tag, "Cancelling all ${activeJobs.size} audio jobs")
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        jobsByCharacter.clear()
    }
}

