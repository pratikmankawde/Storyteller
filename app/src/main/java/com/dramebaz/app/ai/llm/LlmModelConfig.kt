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
    val pass2: LlmPassOverride? = null
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
