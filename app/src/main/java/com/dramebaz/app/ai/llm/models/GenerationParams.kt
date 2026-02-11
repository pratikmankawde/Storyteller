package com.dramebaz.app.ai.llm.models

/**
 * Parameters for a single LLM generation call.
 *
 * This is a lightweight parameter object for per-call inference settings.
 * For session-level configuration, see SessionParams.
 *
 * Design: Immutable data class following the Parameter Object pattern.
 * Each inference call can use different parameters for fine-grained control.
 */
data class GenerationParams(
    /** Maximum tokens to generate in the response */
    val maxTokens: Int = 1024,

    /** Sampling temperature (0.0 = deterministic, 1.0+ = creative) */
    val temperature: Float = 0.7f,

    /** Top-K sampling: consider only top K most likely tokens */
    val topK: Int = 40,

    /** Top-P (nucleus) sampling: consider tokens with cumulative probability P */
    val topP: Float = 0.95f,

    /** Stop sequences that terminate generation early */
    val stopSequences: List<String> = emptyList()
) {
    companion object {
        /** Default parameters for general text generation */
        val DEFAULT = GenerationParams()

        /** Parameters optimized for JSON/structured output (low temperature) */
        val JSON_EXTRACTION = GenerationParams(
            maxTokens = 2048,
            temperature = 0.15f,
            topK = 40,
            topP = 0.9f
        )

        /** Parameters optimized for creative writing (higher temperature) */
        val CREATIVE = GenerationParams(
            maxTokens = 2048,
            temperature = 0.8f,
            topK = 50,
            topP = 0.95f
        )

        /** Parameters for short extraction tasks (character names, etc.) */
        val SHORT_EXTRACTION = GenerationParams(
            maxTokens = 256,
            temperature = 0.1f,
            topK = 40,
            topP = 0.9f
        )
    }

    /**
     * Create a copy with modified max tokens.
     */
    fun withMaxTokens(maxTokens: Int) = copy(maxTokens = maxTokens)

    /**
     * Create a copy with modified temperature.
     */
    fun withTemperature(temperature: Float) = copy(temperature = temperature)

    /**
     * Validate and clamp parameters to valid ranges.
     */
    fun validated(): GenerationParams = copy(
        maxTokens = maxTokens.coerceIn(1, 32768),
        temperature = temperature.coerceIn(0f, 2f),
        topK = topK.coerceIn(1, 100),
        topP = topP.coerceIn(0f, 1f)
    )
}

/**
 * Session-level parameters that persist across multiple inference calls.
 *
 * These parameters affect the model's behavior at a session/context level,
 * not individual generation calls. They typically require model reload or
 * session reset to take effect.
 *
 * Not all engines support all parameters - use LlmModel.getSupportedSessionParams()
 * to check which parameters are supported by the current engine.
 */
data class SessionParams(
    /** Repeat penalty to reduce repetition (1.0 = no penalty, higher = more penalty) */
    val repeatPenalty: Float = 1.1f,

    /** Context window size in tokens (affects memory usage) */
    val contextLength: Int = 8192,

    /** Number of GPU layers to offload (-1 = all layers, 0 = CPU only) */
    val gpuLayers: Int = -1,

    /** Preferred accelerator order (e.g., "gpu,cpu" or "cpu") */
    val accelerators: String = "gpu,cpu"
) {
    companion object {
        /** Default session parameters */
        val DEFAULT = SessionParams()

        /** CPU-only configuration */
        val CPU_ONLY = SessionParams(
            gpuLayers = 0,
            accelerators = "cpu"
        )

        /** Maximum GPU offload configuration */
        val MAX_GPU = SessionParams(
            gpuLayers = -1,
            accelerators = "gpu,cpu"
        )

        /** Validation ranges */
        const val REPEAT_PENALTY_MIN = 1.0f
        const val REPEAT_PENALTY_MAX = 2.0f
        const val CONTEXT_LENGTH_MIN = 512
        const val CONTEXT_LENGTH_MAX = 32768
        const val GPU_LAYERS_MIN = -1
        const val GPU_LAYERS_MAX = 100

        /**
         * Create from ModelParams (for backward compatibility).
         */
        fun fromModelParams(params: com.dramebaz.app.data.models.ModelParams): SessionParams {
            return SessionParams(
                repeatPenalty = params.repeatPenalty.toFloat(),
                contextLength = params.contextLength,
                gpuLayers = params.gpuLayers,
                accelerators = params.accelerators
            )
        }
    }

    /**
     * Validate and clamp parameters to valid ranges.
     */
    fun validated(): SessionParams = copy(
        repeatPenalty = repeatPenalty.coerceIn(REPEAT_PENALTY_MIN, REPEAT_PENALTY_MAX),
        contextLength = contextLength.coerceIn(CONTEXT_LENGTH_MIN, CONTEXT_LENGTH_MAX),
        gpuLayers = gpuLayers.coerceIn(GPU_LAYERS_MIN, GPU_LAYERS_MAX)
    )

    /**
     * Convert to ModelParams (for backward compatibility).
     */
    fun toModelParams(): com.dramebaz.app.data.models.ModelParams {
        return com.dramebaz.app.data.models.ModelParams(
            repeatPenalty = repeatPenalty.toDouble(),
            contextLength = contextLength,
            gpuLayers = gpuLayers,
            accelerators = accelerators
        )
    }
}

/**
 * Describes which session parameters are supported by an engine.
 */
data class SessionParamsSupport(
    /** Whether repeat penalty is supported */
    val supportsRepeatPenalty: Boolean = false,

    /** Whether context length can be configured */
    val supportsContextLength: Boolean = false,

    /** Whether GPU layer offloading is supported */
    val supportsGpuLayers: Boolean = false,

    /** Whether accelerator selection is supported */
    val supportsAccelerators: Boolean = false,

    /** Whether session params require model reload to take effect */
    val requiresReload: Boolean = true
) {
    companion object {
        /** No session params supported */
        val NONE = SessionParamsSupport()

        /** Full GGUF support (llama.cpp) */
        val GGUF_FULL = SessionParamsSupport(
            supportsRepeatPenalty = true,
            supportsContextLength = true,
            supportsGpuLayers = true,
            supportsAccelerators = true,
            requiresReload = true
        )

        /** LiteRT-LM support */
        val LITERTLM = SessionParamsSupport(
            supportsRepeatPenalty = false,
            supportsContextLength = false,
            supportsGpuLayers = false,
            supportsAccelerators = true,
            requiresReload = true
        )

        /** MediaPipe support */
        val MEDIAPIPE = SessionParamsSupport(
            supportsRepeatPenalty = false,
            supportsContextLength = false,
            supportsGpuLayers = false,
            supportsAccelerators = false,
            requiresReload = false
        )
    }

    /** Check if any session params are supported */
    fun hasAnySupport(): Boolean = supportsRepeatPenalty || supportsContextLength ||
                                    supportsGpuLayers || supportsAccelerators
}

