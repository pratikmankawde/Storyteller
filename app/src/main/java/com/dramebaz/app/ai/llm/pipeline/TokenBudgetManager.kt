package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.utils.AppLogger

/**
 * Manages token budgets for LLM passes.
 * 
 * Total budget: 4096 tokens shared between prompt + input_text + output.
 * Each pass has specific allocations defined in PassTokenBudget.
 * 
 * Token estimation: ~4 characters per token (conservative estimate for English text)
 */
object TokenBudgetManager {
    private const val TAG = "TokenBudgetManager"
    
    /** Approximate characters per token (conservative for English) */
    const val CHARS_PER_TOKEN = 4
    
    /** Total token budget for all passes */
    const val TOTAL_TOKEN_BUDGET = 4096
    
    /**
     * Token budget configuration for each pass.
     * 
     * @param promptTokens Fixed tokens for the prompt template
     * @param inputTokens Maximum tokens for input text
     * @param outputTokens Maximum tokens for LLM output
     */
    data class PassTokenBudget(
        val promptTokens: Int,
        val inputTokens: Int,
        val outputTokens: Int
    ) {
        val totalTokens: Int get() = promptTokens + inputTokens + outputTokens
        val inputChars: Int get() = inputTokens * CHARS_PER_TOKEN
        val outputChars: Int get() = outputTokens * CHARS_PER_TOKEN
        
        init {
            require(totalTokens <= TOTAL_TOKEN_BUDGET) {
                "Total tokens ($totalTokens) exceeds budget ($TOTAL_TOKEN_BUDGET)"
            }
        }
    }
    
    /**
     * Pass-1: Character Name Extraction (Per Page)
     * Prompt+Input: 3500 tokens, Output: 100 tokens
     * Prompt is ~200 tokens, so input gets ~3300 tokens
     */
    val PASS1_CHARACTER_EXTRACTION = PassTokenBudget(
        promptTokens = 200,
        inputTokens = 3300,
        outputTokens = 100
    )
    
    /**
     * Pass-2: Dialog Extraction
     * Prompt+Input: 1800 tokens, Output: 2200 tokens
     * Prompt is ~300 tokens (includes character list), so input gets ~1500 tokens
     */
    val PASS2_DIALOG_EXTRACTION = PassTokenBudget(
        promptTokens = 300,
        inputTokens = 1500,
        outputTokens = 2200
    )
    
    /**
     * Pass-3: Voice Profile Suggestion
     * Prompt+Input: 2500 tokens, Output: 1500 tokens
     * Prompt is ~400 tokens (detailed instructions), so input gets ~2100 tokens
     */
    val PASS3_VOICE_PROFILE = PassTokenBudget(
        promptTokens = 400,
        inputTokens = 2100,
        outputTokens = 1500
    )
    
    /**
     * Prepare input text for a pass by truncating to fit the token budget.
     * Tries to fill at least 90% of the input budget.
     * Truncates at paragraph boundary, falling back to sentence boundary.
     * 
     * @param text The input text to prepare
     * @param budget The token budget for this pass
     * @return Truncated text that fits within the input token budget
     */
    fun prepareInputText(text: String, budget: PassTokenBudget): String {
        val maxChars = budget.inputChars
        val targetMinChars = (maxChars * 0.9).toInt()
        
        if (text.length <= maxChars) {
            AppLogger.d(TAG, "Text fits within budget: ${text.length}/$maxChars chars")
            return text
        }
        
        // Try to truncate at paragraph boundary
        val truncated = TextCleaner.truncateAtParagraphBoundary(text, maxChars)
        
        if (truncated.length >= targetMinChars) {
            AppLogger.d(TAG, "Truncated at paragraph: ${truncated.length}/$maxChars chars (${truncated.length * 100 / maxChars}%)")
            return truncated
        }
        
        // If paragraph truncation is too short, try sentence boundary
        val sentenceTruncated = TextCleaner.truncateAtSentenceBoundary(text, maxChars)
        AppLogger.d(TAG, "Truncated at sentence: ${sentenceTruncated.length}/$maxChars chars (${sentenceTruncated.length * 100 / maxChars}%)")
        return sentenceTruncated
    }
    
    /**
     * Estimate token count for a text string.
     */
    fun estimateTokens(text: String): Int {
        return (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
    }
    
    /**
     * Check if text fits within a token limit.
     */
    fun fitsWithinTokens(text: String, maxTokens: Int): Boolean {
        return estimateTokens(text) <= maxTokens
    }
    
    /**
     * Get the maximum input characters for a pass.
     */
    fun getMaxInputChars(budget: PassTokenBudget): Int = budget.inputChars
    
    /**
     * Get the maximum output tokens for a pass.
     */
    fun getMaxOutputTokens(budget: PassTokenBudget): Int = budget.outputTokens
}

