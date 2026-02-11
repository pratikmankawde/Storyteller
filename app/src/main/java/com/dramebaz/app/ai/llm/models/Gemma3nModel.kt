package com.dramebaz.app.ai.llm.models

import android.content.Context
import com.dramebaz.app.ai.llm.LiteRtLmEngine
import com.dramebaz.app.ai.llm.ModelCapabilities
import com.dramebaz.app.utils.AppLogger

/**
 * Gemma 3n model implementation wrapping LiteRtLmEngine.
 *
 * This is a pure inference wrapper - all pass-specific logic (prompts, parsing) belongs
 * in the workflow/service layer.
 *
 * Design Pattern: Adapter Pattern - wraps LiteRtLmEngine to implement LlmModel interface.
 *
 * Model: gemma-3n-E2B-it-int4.litertlm (or any .litertlm file)
 */
class Gemma3nModel(private val context: Context) : LlmModel {
    companion object {
        private const val TAG = "Gemma3nModel"
    }

    private val liteRtLmEngine = LiteRtLmEngine(context)
    private var defaultParams = GenerationParams.DEFAULT
    private var sessionParams = SessionParams.DEFAULT

    // ==================== Lifecycle ====================

    override suspend fun loadModel(): Boolean {
        AppLogger.i(TAG, "Loading Gemma 3n model via LiteRT-LM...")
        val success = liteRtLmEngine.initialize()
        if (success) {
            AppLogger.i(TAG, "Gemma 3n model loaded successfully: ${getExecutionProvider()}")
        } else {
            AppLogger.e(TAG, "Failed to load Gemma 3n model")
        }
        return success
    }

    override fun isModelLoaded(): Boolean = liteRtLmEngine.isModelLoaded()

    override fun release() {
        liteRtLmEngine.release()
    }

    // ==================== Configuration ====================

    override fun getDefaultParams(): GenerationParams = defaultParams

    override fun updateDefaultParams(params: GenerationParams) {
        defaultParams = params.validated()
    }

    // ==================== Session Parameters ====================

    override fun getSessionParams(): SessionParams = sessionParams

    override fun updateSessionParams(params: SessionParams): Boolean {
        sessionParams = params.validated()
        // LiteRT-LM only supports accelerator selection, applied on next init
        return true
    }

    override fun getSessionParamsSupport(): SessionParamsSupport = SessionParamsSupport.LITERTLM

    // ==================== Core Inference ====================

    override suspend fun generateResponse(
        systemPrompt: String,
        userPrompt: String,
        params: GenerationParams
    ): String {
        return liteRtLmEngine.generate(
            systemPrompt,
            userPrompt,
            params.maxTokens,
            params.temperature
        )
    }

    // ==================== Multimodal Methods ====================

    /**
     * Generate a story from an inspiration image.
     * Uses Gemma 3n multimodal capabilities to analyze the image and generate a story.
     *
     * @param imagePath Path to the image file on device
     * @param userPrompt Optional user prompt for story direction
     * @return Generated story text
     */
    override suspend fun generateFromImage(imagePath: String, userPrompt: String): String {
        AppLogger.i(TAG, "generateFromImage: image=$imagePath, prompt=${userPrompt.take(50)}...")
        return liteRtLmEngine.generateFromImage(imagePath, userPrompt)
    }

    // ==================== Metadata & Capabilities ====================

    override fun getExecutionProvider(): String = liteRtLmEngine.getExecutionProvider()

    override fun isUsingGpu(): Boolean = liteRtLmEngine.isUsingGpu()

    override fun getModelCapabilities(): ModelCapabilities {
        return liteRtLmEngine.getModelCapabilities()
    }

    override fun getModelInfo(): ModelInfo {
        val capabilities = liteRtLmEngine.getModelCapabilities()
        return ModelInfo(
            name = capabilities.modelName,
            format = ModelFormat.LITERTLM,
            filePath = null  // Gemma3nModel uses config-based discovery
        )
    }
}

