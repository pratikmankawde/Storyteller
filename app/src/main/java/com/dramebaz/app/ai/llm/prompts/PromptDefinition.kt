package com.dramebaz.app.ai.llm.prompts

/**
 * Defines a prompt template with its metadata, token budgets, and input/output specifications.
 * 
 * This interface separates prompt logic from pass execution logic, allowing:
 * - Prompts to be defined declaratively
 * - Passes to be reusable with different prompts
 * - Token budgets to be managed centrally
 * - Easy testing and modification of prompts
 */
interface PromptDefinition<I, O> {
    
    /** Unique identifier for this prompt (e.g., "character_extraction_v1") */
    val promptId: String
    
    /** Human-readable name for logging and UI */
    val displayName: String
    
    /** Description of what this prompt does */
    val purpose: String
    
    /** Token budget configuration for this prompt */
    val tokenBudget: TokenBudget
    
    /** System prompt for the LLM */
    val systemPrompt: String
    
    /** Temperature for LLM generation (0.0 = deterministic, 2.0 = creative) */
    val temperature: Float get() = 0.15f
    
    /**
     * Build the user prompt from the input data.
     * @param input The input data for this prompt
     * @return The formatted user prompt string
     */
    fun buildUserPrompt(input: I): String
    
    /**
     * Parse the LLM response into the output type.
     * @param response Raw LLM response string
     * @return Parsed output data
     */
    fun parseResponse(response: String): O
    
    /**
     * Prepare input by truncating to fit token budget.
     * Default implementation uses character-based truncation.
     */
    fun prepareInput(input: I): I = input
}

/**
 * Token budget configuration for a prompt.
 * Total budget is typically 4096 tokens shared between prompt, input, and output.
 */
data class TokenBudget(
    /** Maximum tokens for the prompt template (system + user prompt structure) */
    val promptTokens: Int,
    
    /** Maximum tokens for the input text */
    val inputTokens: Int,
    
    /** Maximum tokens for the LLM output */
    val outputTokens: Int
) {
    /** Total token budget */
    val totalTokens: Int get() = promptTokens + inputTokens + outputTokens
    
    /** Maximum input characters (assuming ~4 chars per token) */
    val maxInputChars: Int get() = inputTokens * CHARS_PER_TOKEN
    
    /** Maximum output characters */
    val maxOutputChars: Int get() = outputTokens * CHARS_PER_TOKEN
    
    companion object {
        const val CHARS_PER_TOKEN = 4
        const val DEFAULT_TOTAL_BUDGET = 4096
        
        /** Pass-1: Character Extraction - Prompt+Input 3500, Output 100 */
        val PASS1_CHARACTER_EXTRACTION = TokenBudget(
            promptTokens = 200,
            inputTokens = 3300,
            outputTokens = 100
        )
        
        /** Pass-2: Dialog Extraction - Prompt+Input 1800, Output 2200 */
        val PASS2_DIALOG_EXTRACTION = TokenBudget(
            promptTokens = 300,
            inputTokens = 1500,
            outputTokens = 2200
        )
        
        /** Pass-3: Voice Profile - Prompt+Input 2500, Output 1500 */
        val PASS3_VOICE_PROFILE = TokenBudget(
            promptTokens = 400,
            inputTokens = 2100,
            outputTokens = 1500
        )
        
        /** Traits Extraction */
        val TRAITS_EXTRACTION = TokenBudget(
            promptTokens = 200,
            inputTokens = 2500,
            outputTokens = 384
        )
        
        /** Key Moments Extraction */
        val KEY_MOMENTS = TokenBudget(
            promptTokens = 200,
            inputTokens = 2500,
            outputTokens = 512
        )
        
        /** Relationships Extraction */
        val RELATIONSHIPS = TokenBudget(
            promptTokens = 200,
            inputTokens = 2500,
            outputTokens = 512
        )
        
        /** Story Generation */
        val STORY_GENERATION = TokenBudget(
            promptTokens = 300,
            inputTokens = 500,
            outputTokens = 3200
        )
        
        /** Scene Prompt Generation */
        val SCENE_PROMPT = TokenBudget(
            promptTokens = 200,
            inputTokens = 800,
            outputTokens = 512
        )
    }
    
    init {
        require(totalTokens <= DEFAULT_TOTAL_BUDGET) {
            "Total tokens ($totalTokens) exceeds budget ($DEFAULT_TOTAL_BUDGET)"
        }
    }
}

