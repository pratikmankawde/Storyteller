package com.dramebaz.app.ai.llm.pipeline

import android.content.Context
import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Orchestrates multi-pass LLM analysis pipelines.
 * 
 * This class:
 * - Takes a list of pass definitions
 * - Executes them sequentially
 * - Manages checkpointing for resumability
 * - Provides progress callbacks
 * 
 * Design Pattern: Pipeline Pattern - each pass transforms data and feeds into the next.
 */
class MultipassPipeline(
    private val context: Context,
    private val llmModel: LlmModel,
    private val pipelineId: String = "default"
) {
    companion object {
        private const val TAG = "MultipassPipeline"
        private const val CHECKPOINT_DIR = "pipeline_checkpoints"
        private const val CHECKPOINT_EXPIRY_MS = 24 * 60 * 60 * 1000L  // 24 hours
    }
    
    private val gson = Gson()
    private val checkpointMutex = Mutex()
    
    /**
     * Execute a pipeline with the given steps.
     * 
     * @param steps List of pipeline steps to execute
     * @param initialContext Initial context data for the pipeline
     * @param bookId Book ID for checkpointing
     * @param chapterId Chapter ID for checkpointing
     * @param onProgress Progress callback
     * @return Final pipeline context after all steps complete
     */
    suspend fun <T : PipelineContext> execute(
        steps: List<PipelineStep<T>>,
        initialContext: T,
        bookId: Long,
        chapterId: Long,
        onProgress: ((PipelineProgress) -> Unit)? = null
    ): PipelineResult<T> {
        val startTime = System.currentTimeMillis()
        AppLogger.i(TAG, "Starting pipeline '$pipelineId' with ${steps.size} steps")
        
        // Try to load checkpoint
        val checkpoint = loadCheckpoint(bookId, chapterId, initialContext.contentHash)
        var currentContext = if (checkpoint != null && checkpoint.stepIndex < steps.size) {
            AppLogger.i(TAG, "Resuming from checkpoint: step ${checkpoint.stepIndex}")
            @Suppress("UNCHECKED_CAST")
            checkpoint.context as? T ?: initialContext
        } else {
            initialContext
        }
        
        val startStep = checkpoint?.stepIndex ?: 0
        var completedSteps = startStep
        
        for ((index, step) in steps.withIndex()) {
            if (index < startStep) continue

            val stepProgress = PipelineProgress(
                pipelineId = pipelineId,
                currentStep = index + 1,
                totalSteps = steps.size,
                stepName = step.name,
                message = "Executing ${step.name}..."
            )
            onProgress?.invoke(stepProgress)

            // Create segment progress callback that reports sub-progress
            val segmentProgressCallback: StepProgressCallback = { currentSegment, totalSegments ->
                val subProgress = if (totalSegments > 0) currentSegment.toFloat() / totalSegments else 0f
                val segmentProgress = PipelineProgress(
                    pipelineId = pipelineId,
                    currentStep = index + 1,
                    totalSteps = steps.size,
                    stepName = step.name,
                    message = "${step.name} (${currentSegment}/${totalSegments})",
                    subProgress = subProgress
                )
                onProgress?.invoke(segmentProgress)
            }

            try {
                AppLogger.d(TAG, "Executing step ${index + 1}/${steps.size}: ${step.name}")
                currentContext = step.execute(llmModel, currentContext, PassConfig(), segmentProgressCallback)
                completedSteps = index + 1
                
                // Save checkpoint after each step
                saveCheckpoint(PipelineCheckpoint(
                    bookId = bookId,
                    chapterId = chapterId,
                    pipelineId = pipelineId,
                    stepIndex = completedSteps,
                    contentHash = initialContext.contentHash,
                    context = currentContext,
                    timestamp = System.currentTimeMillis()
                ))
                
            } catch (e: Exception) {
                AppLogger.e(TAG, "Step ${step.name} failed", e)
                return PipelineResult(
                    success = false,
                    context = currentContext,
                    completedSteps = completedSteps,
                    totalSteps = steps.size,
                    error = e.message,
                    durationMs = System.currentTimeMillis() - startTime
                )
            }
        }
        
        // Delete checkpoint on successful completion
        deleteCheckpoint(bookId, chapterId)
        
        val duration = System.currentTimeMillis() - startTime
        AppLogger.i(TAG, "Pipeline '$pipelineId' completed in ${duration}ms")
        
        return PipelineResult(
            success = true,
            context = currentContext,
            completedSteps = completedSteps,
            totalSteps = steps.size,
            durationMs = duration
        )
    }
    
    // Checkpoint management methods
    private fun getCheckpointFile(bookId: Long, chapterId: Long): File {
        val checkpointDir = File(context.filesDir, CHECKPOINT_DIR)
        return File(checkpointDir, "${pipelineId}_${bookId}_${chapterId}.json")
    }
    
    private suspend fun loadCheckpoint(bookId: Long, chapterId: Long, contentHash: Int): PipelineCheckpoint? = 
        checkpointMutex.withLock {
            try {
                val file = getCheckpointFile(bookId, chapterId)
                if (!file.exists()) return@withLock null
                
                val checkpoint = gson.fromJson(file.readText(), PipelineCheckpoint::class.java)
                
                if (checkpoint.contentHash != contentHash) {
                    AppLogger.d(TAG, "Checkpoint invalid: content changed")
                    file.delete()
                    return@withLock null
                }
                
                if (System.currentTimeMillis() - checkpoint.timestamp > CHECKPOINT_EXPIRY_MS) {
                    AppLogger.d(TAG, "Checkpoint expired")
                    file.delete()
                    return@withLock null
                }
                
                checkpoint
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load checkpoint", e)
                null
            }
        }
    
    private suspend fun saveCheckpoint(checkpoint: PipelineCheckpoint) = checkpointMutex.withLock {
        try {
            val file = getCheckpointFile(checkpoint.bookId, checkpoint.chapterId)
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(checkpoint))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save checkpoint", e)
        }
    }
    
    private suspend fun deleteCheckpoint(bookId: Long, chapterId: Long) = checkpointMutex.withLock {
        try {
            getCheckpointFile(bookId, chapterId).delete()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to delete checkpoint", e)
        }
    }
}

