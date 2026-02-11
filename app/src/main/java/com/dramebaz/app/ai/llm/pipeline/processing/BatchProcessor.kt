package com.dramebaz.app.ai.llm.pipeline.processing

import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.pipeline.ParagraphBatcher
import com.dramebaz.app.ai.llm.tasks.TaskProgress
import com.dramebaz.app.utils.AppLogger

/**
 * Template Method Pattern for batch processing.
 * 
 * Defines the skeleton of the batch processing algorithm:
 * 1. Prepare paragraphs
 * 2. Load checkpoint (if any)
 * 3. Create batches
 * 4. Process each batch (abstract)
 * 5. Merge results (abstract)
 * 6. Save checkpoint after each batch
 * 7. Finalize results
 */
abstract class BatchProcessor<TAccumulator, TBatchResult> {
    
    companion object {
        private const val TAG = "BatchProcessor"
    }
    
    /**
     * Process all batches with the given model.
     * 
     * @param batches List of paragraph batches to process
     * @param model LLM model to use for processing
     * @param initialAccumulator Initial state of the accumulator
     * @param progressCallback Optional callback for progress updates
     * @return Final accumulated result
     */
    suspend fun processBatches(
        batches: List<ParagraphBatcher.ParagraphBatch>,
        model: LlmModel,
        initialAccumulator: TAccumulator,
        progressCallback: ((TaskProgress) -> Unit)? = null,
        taskId: String = "batch_processor"
    ): BatchProcessingResult<TAccumulator> {
        
        if (batches.isEmpty()) {
            AppLogger.d(TAG, "No batches to process")
            return BatchProcessingResult(
                accumulator = initialAccumulator,
                batchesProcessed = 0,
                totalBatches = 0,
                success = true
            )
        }
        
        var accumulator = initialAccumulator
        val totalBatches = batches.size
        
        for ((index, batch) in batches.withIndex()) {
            val progressPercent = ((index.toFloat() / totalBatches) * 100).toInt()
            
            progressCallback?.invoke(TaskProgress(
                taskId = taskId,
                message = "Processing batch ${index + 1}/$totalBatches",
                percent = progressPercent,
                currentStep = index + 1,
                totalSteps = totalBatches,
                stepName = "Batch ${index + 1}"
            ))
            
            AppLogger.d(TAG, "Processing batch ${batch.batchIndex}: " +
                    "paras ${batch.startParagraphIndex}-${batch.endParagraphIndex}")
            
            try {
                // Process this batch (template method)
                val batchResult = processBatch(batch, model)
                
                // Merge into accumulator (template method)
                accumulator = mergeBatchResult(accumulator, batchResult)
                
                // Notify after batch complete (hook)
                onBatchComplete(batch, index, totalBatches, accumulator)
                
                AppLogger.d(TAG, "Batch ${batch.batchIndex} complete")
                
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to process batch ${batch.batchIndex}", e)
                return BatchProcessingResult(
                    accumulator = accumulator,
                    batchesProcessed = index,
                    totalBatches = totalBatches,
                    success = false,
                    error = e.message
                )
            }
        }
        
        progressCallback?.invoke(TaskProgress(taskId, "Processing complete", 100))
        
        return BatchProcessingResult(
            accumulator = accumulator,
            batchesProcessed = totalBatches,
            totalBatches = totalBatches,
            success = true
        )
    }
    
    /**
     * Process a single batch. Must be implemented by subclasses.
     */
    protected abstract suspend fun processBatch(
        batch: ParagraphBatcher.ParagraphBatch,
        model: LlmModel
    ): TBatchResult
    
    /**
     * Merge batch result into accumulator. Must be implemented by subclasses.
     */
    protected abstract fun mergeBatchResult(
        accumulator: TAccumulator,
        batchResult: TBatchResult
    ): TAccumulator
    
    /**
     * Hook called after each batch completes. Override for checkpoint saving, etc.
     */
    protected open fun onBatchComplete(
        batch: ParagraphBatcher.ParagraphBatch,
        batchIndex: Int,
        totalBatches: Int,
        accumulator: TAccumulator
    ) {
        // Default: no-op
    }
}

/**
 * Result of batch processing.
 */
data class BatchProcessingResult<T>(
    val accumulator: T,
    val batchesProcessed: Int,
    val totalBatches: Int,
    val success: Boolean,
    val error: String? = null
)

