package com.dramebaz.app.ai.llm.models

import com.dramebaz.app.ai.llm.ModelCapabilities

/**
 * Interface defining the contract for all LLM model implementations.
 *
 * This is a pure inference wrapper interface - engines should only handle:
 * - **Lifecycle**: Model loading, unloading, and resource management
 * - **Configuration**: Generation parameter management
 * - **Core inference**: Raw text generation via generateResponse()
 * - **Metadata**: Model capabilities and execution info
 *
 * All domain-specific logic (prompt engineering, response parsing, analysis workflows)
 * belongs in the service/use case layer, NOT in model implementations.
 *
 * Design Patterns:
 * - **Strategy Pattern**: Implementations are interchangeable strategies for inference
 * - **Single Responsibility**: Models handle ONLY inference, not application logic
 *
 * Boundary Definition:
 * - LlmModel Layer (Infrastructure): Model loading, inference execution, hardware acceleration
 * - Service Layer (Application): Prompt engineering, response parsing, domain logic
 * - Workflow Layer (Orchestration): Multi-step analysis pipelines
 */
interface LlmModel {

    // ==================== Lifecycle ====================

    /**
     * Load the model into memory. Returns true if successful.
     * This may take significant time and memory depending on model size.
     */
    suspend fun loadModel(): Boolean

    /**
     * Check if the model is loaded and ready for inference.
     */
    fun isModelLoaded(): Boolean

    /**
     * Release model resources and free memory.
     * After calling this, the model must be reloaded before use.
     */
    fun release()

    // ==================== Configuration ====================

    /**
     * Get the default generation parameters for this model.
     * These are used when generateResponse is called without explicit params.
     */
    fun getDefaultParams(): GenerationParams

    /**
     * Update the default generation parameters.
     * Useful for changing model behavior without modifying each call site.
     *
     * @param params New default parameters
     */
    fun updateDefaultParams(params: GenerationParams)

    // ==================== Session Parameters ====================

    /**
     * Get current session parameters.
     * Session parameters persist across multiple inference calls and typically
     * affect model loading or context behavior.
     *
     * @return Current session parameters
     */
    fun getSessionParams(): SessionParams

    /**
     * Update session parameters.
     * Some engines may require model reload for changes to take effect.
     * Check getSessionParamsSupport().requiresReload to determine if reload is needed.
     *
     * @param params New session parameters
     * @return true if parameters were applied successfully
     */
    fun updateSessionParams(params: SessionParams): Boolean

    /**
     * Get information about which session parameters this engine supports.
     * Use this to enable/disable UI controls based on engine capabilities.
     *
     * @return Support information for session parameters
     */
    fun getSessionParamsSupport(): SessionParamsSupport

    // ==================== Core Inference ====================

    /**
     * Generate a response from the model.
     * This is the primary inference method that all higher-level operations should use.
     *
     * @param systemPrompt System prompt that sets context/role for the model
     * @param userPrompt User prompt with the actual request
     * @param params Generation parameters (temperature, maxTokens, etc.)
     * @return Generated text response, or empty string on error
     */
    suspend fun generateResponse(
        systemPrompt: String,
        userPrompt: String,
        params: GenerationParams = getDefaultParams()
    ): String

    /**
     * Generate a response with explicit parameter values.
     * Convenience overload for simple use cases.
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
        maxTokens: Int,
        temperature: Float
    ): String = generateResponse(
        systemPrompt,
        userPrompt,
        GenerationParams(maxTokens = maxTokens, temperature = temperature)
    )

    // ==================== Optional Multimodal Methods ====================

    /**
     * Generate a response from an image input.
     * Only supported by models with supportsImage=true in capabilities.
     *
     * Default implementation returns empty string (not supported).
     * Override in multimodal model implementations.
     *
     * @param imagePath Path to the image file
     * @param userPrompt Optional text prompt to guide generation
     * @return Generated text response, or empty string if not supported
     */
    suspend fun generateFromImage(imagePath: String, userPrompt: String = ""): String = ""

    // ==================== Metadata & Capabilities ====================

    /**
     * Get the execution provider description.
     * Examples: "GPU (Vulkan)", "CPU", "MediaPipe (GPU/CPU)", "LiteRT-LM GPU"
     */
    fun getExecutionProvider(): String

    /**
     * Check if model is using GPU acceleration.
     */
    fun isUsingGpu(): Boolean

    /**
     * Get the capabilities of this model.
     * Used by UI/service layer to enable/disable features based on model support.
     *
     * @return ModelCapabilities with model name and feature support flags
     */
    fun getModelCapabilities(): ModelCapabilities

    /**
     * Get detailed model information.
     * Includes format, file path, and other metadata.
     *
     * @return ModelInfo with detailed model metadata
     */
    fun getModelInfo(): ModelInfo
}

/**
 * Detailed model information for metadata and debugging.
 */
data class ModelInfo(
    /** Display name of the model (e.g., "Gemma 3n E2B (int4)") */
    val name: String,

    /** Model format identifier */
    val format: ModelFormat,

    /** Path to the model file, if applicable */
    val filePath: String? = null,

    /** Model version string, if available */
    val version: String? = null,

    /** Estimated model size in bytes */
    val sizeBytes: Long? = null
) {
    companion object {
        val UNKNOWN = ModelInfo(
            name = "Unknown",
            format = ModelFormat.UNKNOWN
        )
    }
}

/**
 * Supported model formats/engines.
 */
enum class ModelFormat {
    /** GGUF format via llama.cpp */
    GGUF,

    /** LiteRT-LM format (Google AI Edge) */
    LITERTLM,

    /** MediaPipe .task format */
    MEDIAPIPE,

    /** Unknown or unsupported format */
    UNKNOWN
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

