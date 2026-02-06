package com.dramebaz.app.ai.llm.models

/**
 * Interface defining the contract for all LLM model implementations.
 *
 * This is a pure inference wrapper interface - engines should only handle model loading,
 * lifecycle management, and raw text generation. All pass-specific logic (prompts, parsing)
 * belongs in the workflow classes (TwoPassWorkflow, ThreePassWorkflow).
 *
 * Design Pattern: Strategy Pattern - allows different LLM implementations to be used interchangeably.
 */
interface LlmModel {

    /**
     * Load the model. Returns true if successful.
     */
    suspend fun loadModel(): Boolean

    /**
     * Check if the model is loaded and ready for inference.
     */
    fun isModelLoaded(): Boolean

    /**
     * Release model resources.
     */
    fun release()

    /**
     * Get the execution provider (e.g., "GPU (Vulkan)", "CPU", "LiteRT-LM GPU")
     */
    fun getExecutionProvider(): String

    /**
     * Check if model is using GPU acceleration.
     */
    fun isUsingGpu(): Boolean

    // ==================== Core Inference Method ====================

    /**
     * Generate a response from the model given a system prompt and user prompt.
     * This is the core inference method - all pass-specific logic should use this.
     *
     * @param systemPrompt System prompt that sets context/role for the model
     * @param userPrompt User prompt with the actual request
     * @param maxTokens Maximum tokens to generate
     * @param temperature Sampling temperature (0.0 = deterministic, 1.0+ = creative)
     * @return Generated text response
     */
    suspend fun generateResponse(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 1024,
        temperature: Float = 0.7f
    ): String
}

/**
 * Data class for extracted dialog entries.
 * Used by workflow classes for structured dialog extraction results.
 */
data class ExtractedDialog(
    val speaker: String,
    val text: String,
    val emotion: String = "neutral",
    val intensity: Float = 0.5f
)

