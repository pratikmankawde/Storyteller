package com.dramebaz.app.ai.llm.pipeline

import android.content.Context
import com.dramebaz.app.ai.llm.ModelParamsManager
import com.dramebaz.app.ai.llm.prompts.TokenBudget

/**
 * Centralized configuration for the batched chapter analysis pipeline.
 *
 * Single source of truth for token budgets and pass configuration.
 * Used by:
 * - BatchedChapterAnalysisTask (for ParagraphBatcher)
 * - BatchedChapterAnalysisPass (for PassConfig)
 * - BatchedAnalysisPrompt (for TokenBudget)
 *
 * Token Budget Strategy:
 * - Analysis Pass: Prompt+Input = 4000 tokens, Output = 1000 tokens (Total = 5000)
 * - Summary Pass: Prompt+Input = 6000 tokens, Output = 500 tokens (Total = 6500)
 * - Input text is truncated at paragraph boundaries only
 *
 * Note: The user's maxTokens setting (from ModelParams) acts as an upper bound
 * on the total tokens per LLM call. If the configured budget exceeds the user's
 * setting, it will be scaled down proportionally at runtime.
 */
object BatchedPipelineConfig {

    /** Characters per token (conservative estimate for English) */
    const val CHARS_PER_TOKEN = 4

    // ==================== Analysis Pass (Characters, Dialogs, Traits, Voice) ====================

    /** Tokens reserved for analysis prompt template (system + user prompt) */
    const val ANALYSIS_PROMPT_TOKENS = 300

    /** Total tokens for prompt + input in analysis pass */
    const val ANALYSIS_PROMPT_INPUT_TOTAL = 4000

    /** Maximum input tokens for analysis pass (total - prompt) */
    const val ANALYSIS_INPUT_TOKENS = ANALYSIS_PROMPT_INPUT_TOTAL - ANALYSIS_PROMPT_TOKENS  // 3700

    /** Maximum output tokens for analysis pass */
    const val ANALYSIS_OUTPUT_TOKENS = 1000

    /** Total tokens for analysis pass (input + output) */
    const val ANALYSIS_TOTAL_TOKENS = ANALYSIS_PROMPT_INPUT_TOTAL + ANALYSIS_OUTPUT_TOKENS  // 5000

    /** Maximum input characters for analysis pass */
    const val ANALYSIS_MAX_INPUT_CHARS = ANALYSIS_INPUT_TOKENS * CHARS_PER_TOKEN  // 14,800

    // ==================== Summary Pass (Chapter Summary) ====================

    /** Tokens reserved for summary prompt template */
    const val SUMMARY_PROMPT_TOKENS = 200

    /** Total tokens for prompt + input in summary pass */
    const val SUMMARY_PROMPT_INPUT_TOTAL = 6000

    /** Maximum input tokens for summary pass */
    const val SUMMARY_INPUT_TOKENS = SUMMARY_PROMPT_INPUT_TOTAL - SUMMARY_PROMPT_TOKENS  // 5800

    /** Maximum output tokens for summary pass */
    const val SUMMARY_OUTPUT_TOKENS = 500

    /** Total tokens for summary pass (input + output) */
    const val SUMMARY_TOTAL_TOKENS = SUMMARY_PROMPT_INPUT_TOTAL + SUMMARY_OUTPUT_TOKENS  // 6500

    /** Maximum input characters for summary pass */
    const val SUMMARY_MAX_INPUT_CHARS = SUMMARY_INPUT_TOKENS * CHARS_PER_TOKEN  // 23,200

    // ==================== LLM Configuration ====================

    /** Temperature for batched analysis (near-zero for deterministic JSON output) */
    const val TEMPERATURE = 0.01f

    /** Maximum retry attempts on LLM failure */
    const val MAX_RETRIES = 2

    /** Tokens to reduce on each retry after token limit error */
    const val TOKEN_REDUCTION_ON_RETRY = 200

    /** LLM timeout in milliseconds (10 minutes) */
    const val LLM_TIMEOUT_MS = 10 * 60 * 1000L

    // ==================== Pre-built Configurations (Static) ====================

    // --- Analysis Pass Configs ---

    /**
     * Token budget for BatchedAnalysisPrompt (character/dialog extraction).
     */
    val TOKEN_BUDGET = TokenBudget(
        promptTokens = ANALYSIS_PROMPT_TOKENS,
        inputTokens = ANALYSIS_INPUT_TOKENS,
        outputTokens = ANALYSIS_OUTPUT_TOKENS
    )

    /** Alias for clarity - Analysis pass token budget */
    val ANALYSIS_TOKEN_BUDGET = TOKEN_BUDGET

    /** Input tokens for ParagraphBatcher (analysis pass) */
    const val INPUT_TOKENS = ANALYSIS_INPUT_TOKENS

    /**
     * PassConfig for BatchedChapterAnalysisPass.
     */
    val PASS_CONFIG = PassConfig(
        maxTokens = ANALYSIS_OUTPUT_TOKENS,
        temperature = TEMPERATURE,
        maxSegmentChars = ANALYSIS_MAX_INPUT_CHARS,
        maxRetries = MAX_RETRIES,
        tokenReductionOnRetry = TOKEN_REDUCTION_ON_RETRY
    )

    // --- Summary Pass Configs ---

    /**
     * Token budget for summary extraction pass.
     */
    val SUMMARY_TOKEN_BUDGET = TokenBudget(
        promptTokens = SUMMARY_PROMPT_TOKENS,
        inputTokens = SUMMARY_INPUT_TOKENS,
        outputTokens = SUMMARY_OUTPUT_TOKENS
    )

    /**
     * PassConfig for summary extraction pass.
     */
    val SUMMARY_PASS_CONFIG = PassConfig(
        maxTokens = SUMMARY_OUTPUT_TOKENS,
        temperature = TEMPERATURE,
        maxSegmentChars = SUMMARY_MAX_INPUT_CHARS,
        maxRetries = MAX_RETRIES,
        tokenReductionOnRetry = TOKEN_REDUCTION_ON_RETRY
    )

    // ==================== Runtime Configuration (Respects User Settings) ====================

    /**
     * Get the user's max tokens setting, which acts as the context budget cap.
     * @param context Android context for loading settings
     * @return User-configured max tokens (default 8192)
     */
    fun getUserMaxTokens(context: Context): Int {
        return ModelParamsManager.getParams(context).maxTokens
    }

    /**
     * Check if the analysis pass budget fits within the user's max tokens setting.
     * @param context Android context
     * @return true if budget fits, false if it exceeds user setting
     */
    fun analysisPassFitsInBudget(context: Context): Boolean {
        return ANALYSIS_TOTAL_TOKENS <= getUserMaxTokens(context)
    }

    /**
     * Check if the summary pass budget fits within the user's max tokens setting.
     * @param context Android context
     * @return true if budget fits, false if it exceeds user setting
     */
    fun summaryPassFitsInBudget(context: Context): Boolean {
        return SUMMARY_TOTAL_TOKENS <= getUserMaxTokens(context)
    }

    /**
     * Get analysis token budget, scaled down if needed to fit user's max tokens.
     * @param context Android context
     * @return TokenBudget that respects user's max tokens setting
     */
    fun getAnalysisTokenBudget(context: Context): TokenBudget {
        val userMax = getUserMaxTokens(context)
        return if (ANALYSIS_TOTAL_TOKENS <= userMax) {
            TOKEN_BUDGET
        } else {
            // Scale down: keep prompt tokens, reduce input proportionally, keep output
            val availableInput = userMax - ANALYSIS_PROMPT_TOKENS - ANALYSIS_OUTPUT_TOKENS
            val scaledInputTokens = availableInput.coerceAtLeast(500) // Minimum 500 input tokens
            TokenBudget(
                promptTokens = ANALYSIS_PROMPT_TOKENS,
                inputTokens = scaledInputTokens,
                outputTokens = ANALYSIS_OUTPUT_TOKENS
            )
        }
    }

    /**
     * Get analysis pass config, scaled down if needed to fit user's max tokens.
     * @param context Android context
     * @return PassConfig that respects user's max tokens setting
     */
    fun getAnalysisPassConfig(context: Context): PassConfig {
        val tokenBudget = getAnalysisTokenBudget(context)
        val maxInputChars = tokenBudget.inputTokens * CHARS_PER_TOKEN
        return PassConfig(
            maxTokens = tokenBudget.outputTokens,
            temperature = TEMPERATURE,
            maxSegmentChars = maxInputChars,
            maxRetries = MAX_RETRIES,
            tokenReductionOnRetry = TOKEN_REDUCTION_ON_RETRY
        )
    }

    /**
     * Get summary token budget, scaled down if needed to fit user's max tokens.
     * @param context Android context
     * @return TokenBudget that respects user's max tokens setting
     */
    fun getSummaryTokenBudget(context: Context): TokenBudget {
        val userMax = getUserMaxTokens(context)
        return if (SUMMARY_TOTAL_TOKENS <= userMax) {
            SUMMARY_TOKEN_BUDGET
        } else {
            // Scale down: keep prompt tokens, reduce input proportionally, keep output
            val availableInput = userMax - SUMMARY_PROMPT_TOKENS - SUMMARY_OUTPUT_TOKENS
            val scaledInputTokens = availableInput.coerceAtLeast(500) // Minimum 500 input tokens
            TokenBudget(
                promptTokens = SUMMARY_PROMPT_TOKENS,
                inputTokens = scaledInputTokens,
                outputTokens = SUMMARY_OUTPUT_TOKENS
            )
        }
    }

    /**
     * Get summary pass config, scaled down if needed to fit user's max tokens.
     * @param context Android context
     * @return PassConfig that respects user's max tokens setting
     */
    fun getSummaryPassConfig(context: Context): PassConfig {
        val tokenBudget = getSummaryTokenBudget(context)
        val maxInputChars = tokenBudget.inputTokens * CHARS_PER_TOKEN
        return PassConfig(
            maxTokens = tokenBudget.outputTokens,
            temperature = TEMPERATURE,
            maxSegmentChars = maxInputChars,
            maxRetries = MAX_RETRIES,
            tokenReductionOnRetry = TOKEN_REDUCTION_ON_RETRY
        )
    }

    /**
     * Get the effective max input tokens for ParagraphBatcher based on user settings.
     * @param context Android context
     * @return Max input tokens that respects user's budget
     */
    fun getInputTokens(context: Context): Int {
        return getAnalysisTokenBudget(context).inputTokens
    }
}

