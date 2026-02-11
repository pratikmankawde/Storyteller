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

    /**
     * Maximum tokens (input + output) per LLM request.
     * This determines the context window budget for each generation call.
     *
     * Recommended values based on device RAM:
     * - 4GB RAM: 4096 tokens (conservative, fast)
     * - 6GB RAM: 8192 tokens (balanced, recommended)
     * - 8GB+ RAM: 16384-32768 tokens (full context, slower)
     *
     * Note: Gemma 3n E2B supports up to 32K tokens max.
     * Higher values allow longer context but use more memory and are slower.
     */
    val maxTokens: Int = 8192,

    /** Top-K sampling: consider only top K tokens */
    val topK: Int = 48,

    /** Top-P (nucleus) sampling: consider tokens with cumulative probability P */
    val topP: Double = 0.9,

    /** Repeat penalty to reduce repetition (1.0 = no penalty) */
    val repeatPenalty: Double = 1.1,

    /** Context window size in tokens (deprecated, use maxTokens instead) */
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

        /** Max tokens range - Gemma 3n E2B supports up to 32K context window */
        const val MAX_TOKENS_MIN = 1024
        const val MAX_TOKENS_MAX = 32768

        /** Default max tokens - 8192 is a good balance for 6GB+ RAM devices */
        const val MAX_TOKENS_DEFAULT = 8192

        /** Discrete steps for max tokens slider (power-of-2 for memory alignment) */
        val MAX_TOKENS_STEPS = listOf(1024, 2048, 4096, 8192, 16384, 32768)

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

        /**
         * Get recommended max tokens based on device RAM.
         * @param ramBytes Total device RAM in bytes
         * @return Recommended max tokens value
         */
        fun getRecommendedMaxTokens(ramBytes: Long): Int {
            val ramGb = ramBytes / (1024L * 1024L * 1024L)
            return when {
                ramGb >= 8 -> 16384  // High-end devices: larger context
                ramGb >= 6 -> 8192   // Mid-range: balanced
                ramGb >= 4 -> 4096   // Lower-end: conservative
                else -> 2048         // Very limited: minimal context
            }
        }
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

