package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.prompts.PromptDefinition
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.withTimeout

/**
 * Base class for extraction passes that use PromptDefinition.
 * 
 * Handles common logic:
 * - Input preparation (truncation to fit token budget)
 * - LLM invocation with timeout
 * - Retry logic with token reduction
 * - Response parsing via PromptDefinition
 * 
 * @param I Input type for the prompt
 * @param O Output type from the prompt
 */
abstract class BaseExtractionPass<I, O>(
    protected val promptDefinition: PromptDefinition<I, O>
) : AnalysisPass<I, O> {
    
    companion object {
        private const val TAG = "BaseExtractionPass"
        const val LLM_TIMEOUT_MS = 10 * 60 * 1000L  // 10 minutes
        const val CHARS_PER_TOKEN = 4
    }
    
    override val passId: String get() = promptDefinition.promptId
    override val displayName: String get() = promptDefinition.displayName
    
    /**
     * Execute the pass using the prompt definition.
     * Handles input preparation, LLM invocation, and response parsing.
     */
    override suspend fun execute(
        model: LlmModel,
        input: I,
        config: PassConfig
    ): O {
        val preparedInput = promptDefinition.prepareInput(input)
        val userPrompt = promptDefinition.buildUserPrompt(preparedInput)
        
        AppLogger.d(TAG, "Executing $passId: ${userPrompt.length} chars prompt")
        
        var currentUserPrompt = userPrompt
        var attempt = 0
        
        while (attempt < config.maxRetries) {
            attempt++
            try {
                val response = withTimeout(LLM_TIMEOUT_MS) {
                    model.generateResponse(
                        systemPrompt = promptDefinition.systemPrompt,
                        userPrompt = currentUserPrompt,
                        maxTokens = promptDefinition.tokenBudget.outputTokens,
                        temperature = promptDefinition.temperature
                    )
                }
                
                // Check for token limit error
                if (response.contains("Max number of tokens reached", ignoreCase = true)) {
                    AppLogger.w(TAG, "Token limit hit, reducing input (attempt $attempt)")
                    currentUserPrompt = reducePrompt(currentUserPrompt, config.tokenReductionOnRetry)
                    continue
                }
                
                val output = promptDefinition.parseResponse(response)
                AppLogger.d(TAG, "Successfully parsed response for $passId")
                return output
                
            } catch (e: Exception) {
                if (e.message?.contains("Max number of tokens reached", ignoreCase = true) == true) {
                    currentUserPrompt = reducePrompt(currentUserPrompt, config.tokenReductionOnRetry)
                    continue
                }
                AppLogger.e(TAG, "Error on attempt $attempt for $passId", e)
                if (attempt >= config.maxRetries) {
                    throw e
                }
            }
        }
        
        AppLogger.w(TAG, "All attempts failed for $passId, returning default output")
        return getDefaultOutput()
    }
    
    /**
     * Reduce the prompt size by removing characters from the end.
     */
    protected open fun reducePrompt(prompt: String, tokenReduction: Int): String {
        val charsToRemove = tokenReduction * CHARS_PER_TOKEN
        return if (prompt.length > charsToRemove) {
            prompt.dropLast(charsToRemove)
        } else {
            prompt.take(prompt.length / 2)
        }
    }
    
    /**
     * Get the default output when all attempts fail.
     * Subclasses should override this to return an appropriate empty/default value.
     */
    protected abstract fun getDefaultOutput(): O
}

/**
 * Configuration for pass execution.
 * Extends the base PassConfig with additional options.
 */
data class PassExecutionConfig(
    val maxRetries: Int = 3,
    val tokenReductionOnRetry: Int = 500,
    val timeoutMs: Long = BaseExtractionPass.LLM_TIMEOUT_MS
)

