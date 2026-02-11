package com.dramebaz.app.ai.llm.pipeline.processing

import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.pipeline.IncrementalMerger
import com.dramebaz.app.ai.llm.pipeline.ParagraphBatcher
import com.dramebaz.app.ai.llm.pipeline.passes.BatchedChapterAnalysisPass
import com.dramebaz.app.ai.llm.prompts.BatchedAnalysisInput
import com.dramebaz.app.ai.llm.prompts.BatchedAnalysisOutput
import com.dramebaz.app.utils.AppLogger

/**
 * Concrete implementation of BatchProcessor for character analysis.
 * 
 * Processes paragraph batches through the BatchedChapterAnalysisPass and
 * merges results using IncrementalMerger.
 */
class CharacterAnalysisBatchProcessor(
    private val totalBatches: Int,
    private val onBatchCompleteCallback: ((Int, Int, List<IncrementalMerger.MergedCharacterData>) -> Unit)? = null
) : BatchProcessor<MutableMap<String, IncrementalMerger.MergedCharacterData>, BatchedAnalysisOutput>() {
    
    companion object {
        private const val TAG = "CharacterAnalysisBatchProcessor"
    }
    
    private val pass = BatchedChapterAnalysisPass()
    
    override suspend fun processBatch(
        batch: ParagraphBatcher.ParagraphBatch,
        model: LlmModel
    ): BatchedAnalysisOutput {
        val input = BatchedAnalysisInput(
            text = batch.text,
            batchIndex = batch.batchIndex,
            totalBatches = totalBatches
        )
        
        AppLogger.d(TAG, "Executing LLM call for batch ${batch.batchIndex}")
        return pass.execute(model, input, BatchedChapterAnalysisPass.CONFIG)
    }
    
    override fun mergeBatchResult(
        accumulator: MutableMap<String, IncrementalMerger.MergedCharacterData>,
        batchResult: BatchedAnalysisOutput
    ): MutableMap<String, IncrementalMerger.MergedCharacterData> {
        return IncrementalMerger.merge(accumulator, batchResult)
    }
    
    override fun onBatchComplete(
        batch: ParagraphBatcher.ParagraphBatch,
        batchIndex: Int,
        totalBatches: Int,
        accumulator: MutableMap<String, IncrementalMerger.MergedCharacterData>
    ) {
        val characters = IncrementalMerger.toList(accumulator)
        AppLogger.i(TAG, "Batch $batchIndex complete: ${accumulator.size} characters total")
        onBatchCompleteCallback?.invoke(batchIndex, totalBatches, characters)
    }
}

/**
 * Factory for creating CharacterAnalysisBatchProcessor instances.
 */
object CharacterAnalysisBatchProcessorFactory {
    
    /**
     * Create a new processor with optional batch completion callback.
     */
    fun create(
        totalBatches: Int,
        onBatchComplete: ((batchIndex: Int, totalBatches: Int, characters: List<IncrementalMerger.MergedCharacterData>) -> Unit)? = null
    ): CharacterAnalysisBatchProcessor {
        return CharacterAnalysisBatchProcessor(totalBatches, onBatchComplete)
    }
}

