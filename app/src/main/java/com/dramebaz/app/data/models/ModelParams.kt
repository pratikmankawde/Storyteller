package com.dramebaz.app.data.models

/**
 * Data class for external model parameters.
 * These parameters can be tuned by the user via a dialog and are stored in an external JSON file.
 * 
 * This allows users to customize LLM behavior without modifying app assets.
 */
data class ModelParams(
    /** Sampling temperature (0.0 = deterministic, 1.0 = creative) */
    val temperature: Double = 0.6,
    
    /** Maximum tokens to generate per response */
    val maxTokens: Int = 2048,
    
    /** Top-K sampling: consider only top K tokens */
    val topK: Int = 48,
    
    /** Top-P (nucleus) sampling: consider tokens with cumulative probability P */
    val topP: Double = 0.9,
    
    /** Repeat penalty to reduce repetition (1.0 = no penalty) */
    val repeatPenalty: Double = 1.1,
    
    /** Context window size in tokens */
    val contextLength: Int = 8192,
    
    /** Preferred accelerator order (comma-separated: "gpu,cpu" or "cpu") */
    val accelerators: String = "gpu,cpu",
    
    /** Number of GPU layers to offload (-1 = all layers) */
    val gpuLayers: Int = -1
) {
    companion object {
        /** Default parameters */
        val DEFAULT = ModelParams()
        
        /** Validation ranges */
        const val TEMPERATURE_MIN = 0.0
        const val TEMPERATURE_MAX = 2.0
        const val MAX_TOKENS_MIN = 64
        const val MAX_TOKENS_MAX = 8192
        const val TOP_K_MIN = 1
        const val TOP_K_MAX = 100
        const val TOP_P_MIN = 0.0
        const val TOP_P_MAX = 1.0
        const val REPEAT_PENALTY_MIN = 1.0
        const val REPEAT_PENALTY_MAX = 2.0
        const val CONTEXT_LENGTH_MIN = 512
        const val CONTEXT_LENGTH_MAX = 32768
        const val GPU_LAYERS_MIN = -1
        const val GPU_LAYERS_MAX = 100
    }
    
    /**
     * Validate and clamp parameters to valid ranges.
     */
    fun validated(): ModelParams = copy(
        temperature = temperature.coerceIn(TEMPERATURE_MIN, TEMPERATURE_MAX),
        maxTokens = maxTokens.coerceIn(MAX_TOKENS_MIN, MAX_TOKENS_MAX),
        topK = topK.coerceIn(TOP_K_MIN, TOP_K_MAX),
        topP = topP.coerceIn(TOP_P_MIN, TOP_P_MAX),
        repeatPenalty = repeatPenalty.coerceIn(REPEAT_PENALTY_MIN, REPEAT_PENALTY_MAX),
        contextLength = contextLength.coerceIn(CONTEXT_LENGTH_MIN, CONTEXT_LENGTH_MAX),
        gpuLayers = gpuLayers.coerceIn(GPU_LAYERS_MIN, GPU_LAYERS_MAX)
    )
}

