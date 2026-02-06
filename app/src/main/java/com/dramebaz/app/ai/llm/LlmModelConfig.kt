package com.dramebaz.app.ai.llm

/**
 * Root config for LLM models (read from assets/models/llm_model_config.json).
 * Used for centralized sampling, accelerator order, and model path.
 */
data class LlmModelConfigRoot(
    val models: List<LlmModelEntry>,
    val selectedModelId: Int = 0
)

/**
 * Single model entry with file name, memory estimate, and default/pass-specific config.
 */
data class LlmModelEntry(
    val modelFileName: String,
    val displayName: String? = null,
    val estimatedPeakMemoryInBytes: Long? = null,
    val defaultConfig: LlmDefaultConfig,
    val pass1: LlmPassOverride? = null,
    val pass2: LlmPassOverride? = null,
    /** If true, skip the memory availability check and attempt to load anyway */
    val skipMemoryCheck: Boolean = false,
    /** If true, model supports image input (vision/multimodal) */
    val llmSupportImage: Boolean = false,
    /** If true, model supports audio input */
    val llmSupportAudio: Boolean = false
)

/**
 * Default sampling and accelerator config for the model.
 */
data class LlmDefaultConfig(
    val topK: Int = 48,
    val topP: Double = 0.9,
    val temperature: Double = 0.6,
    val maxTokens: Int = 2048,
    val accelerators: String = "gpu,cpu"
)

/**
 * Per-pass override (e.g. Pass1/Pass2 extraction). Missing fields fall back to defaultConfig.
 */
data class LlmPassOverride(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topK: Int? = null,
    val topP: Double? = null
)

/**
 * Model capabilities information for runtime feature checks.
 * Used to enable/disable UI features based on current model support.
 */
data class ModelCapabilities(
    /** Model display name (e.g., "Gemma 3n E2B (int4)") */
    val modelName: String,
    /** If true, model supports image input (vision/multimodal) */
    val supportsImage: Boolean,
    /** If true, model supports audio input */
    val supportsAudio: Boolean
) {
    companion object {
        /** Default capabilities for when no model is loaded */
        val UNKNOWN = ModelCapabilities(
            modelName = "Unknown",
            supportsImage = false,
            supportsAudio = false
        )
    }
}
