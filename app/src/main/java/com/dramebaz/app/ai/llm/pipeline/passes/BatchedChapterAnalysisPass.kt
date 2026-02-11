package com.dramebaz.app.ai.llm.pipeline.passes

import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.pipeline.AnalysisPass
import com.dramebaz.app.ai.llm.pipeline.BatchedPipelineConfig
import com.dramebaz.app.ai.llm.pipeline.PassConfig
import com.dramebaz.app.ai.llm.prompts.BatchedAnalysisInput
import com.dramebaz.app.ai.llm.prompts.BatchedAnalysisOutput
import com.dramebaz.app.ai.llm.prompts.BatchedAnalysisPrompt
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.withTimeout

/**
 * Main extraction pass for batched chapter analysis.
 *
 * Extracts characters, traits, voice profiles, and dialogs in a single LLM call.
 * Uses BatchedAnalysisPrompt for unified extraction.
 */
class BatchedChapterAnalysisPass : AnalysisPass<BatchedAnalysisInput, BatchedAnalysisOutput> {

    companion object {
        private const val TAG = "BatchedChapterAnalysisPass"

        /** Pass config for batched analysis - uses centralized BatchedPipelineConfig */
        val CONFIG = BatchedPipelineConfig.PASS_CONFIG
    }
    
    private val prompt = BatchedAnalysisPrompt()
    
    override val passId: String = "batched_chapter_analysis"
    override val displayName: String = "Batched Chapter Analysis"
    
    override suspend fun execute(
        model: LlmModel,
        input: BatchedAnalysisInput,
        config: PassConfig
    ): BatchedAnalysisOutput {
        AppLogger.d(TAG, "Executing batch ${input.batchIndex}/${input.totalBatches} " +
                "(${input.text.length} chars)")
        
        // Prepare input (truncate if needed)
        val preparedInput = prompt.prepareInput(input)
        
        // Build prompts
        val systemPrompt = prompt.systemPrompt
        val userPrompt = prompt.buildUserPrompt(preparedInput)
        
        AppLogger.d(TAG, "System prompt: ${systemPrompt.length} chars")
        AppLogger.d(TAG, "User prompt: ${userPrompt.length} chars")
        
        var lastException: Exception? = null
        var currentMaxTokens = config.maxTokens
        
        for (attempt in 1..config.maxRetries) {
            try {
                val response = withTimeout(BatchedPipelineConfig.LLM_TIMEOUT_MS) {
                    model.generateResponse(
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        maxTokens = currentMaxTokens,
                        temperature = config.temperature
                    )
                }

                AppLogger.d(TAG, "LLM response received: ${response.length} chars")
                AppLogger.d(TAG, "LLM response preview: ${response.take(200)}...")

                val output = prompt.parseResponse(response)

                if (output.characters.isEmpty()) {
                    AppLogger.w(TAG, "Batch ${input.batchIndex}: No characters extracted! Full response:")
                    AppLogger.w(TAG, response)
                } else {
                    AppLogger.i(TAG, "Batch ${input.batchIndex}: Extracted ${output.characters.size} characters")
                    output.characters.forEach { char ->
                        AppLogger.d(TAG, "  - ${char.name}: ${char.dialogs.size} dialogs, ${char.traits.size} traits")
                    }
                }

                return output
                
            } catch (e: Exception) {
                lastException = e
                AppLogger.w(TAG, "Attempt $attempt failed: ${e.message}")
                
                // Reduce tokens for next retry
                if (attempt < config.maxRetries) {
                    currentMaxTokens -= config.tokenReductionOnRetry
                    AppLogger.d(TAG, "Reducing max tokens to $currentMaxTokens for retry")
                }
            }
        }
        
        AppLogger.e(TAG, "All retry attempts failed", lastException)
        return BatchedAnalysisOutput(emptyList())
    }
}

