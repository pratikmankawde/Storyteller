package com.dramebaz.app.ai.llm.models

import android.content.Context
import com.dramebaz.app.ai.llm.LiteRtLmEngine
import com.google.ai.edge.litertlm.Backend
import java.io.File

/**
 * LlmModel implementation that wraps LiteRtLmEngine.
 * Uses the Adapter Pattern to make LiteRtLmEngine conform to the LlmModel interface.
 *
 * This is a pure inference wrapper - all pass-specific logic (prompts, parsing) belongs
 * in the workflow/service layer.
 *
 * Works with LiteRT-LM format models (e.g., Gemma, Qwen .litertlm files).
 *
 * @param context Android context
 * @param preferredBackend Optional preferred backend (GPU/CPU). If null, uses config order.
 * @param modelPath Optional explicit model file path. If null, uses config-based discovery.
 */
class LiteRtLmEngineImpl(
    private val context: Context,
    preferredBackend: Backend? = null,
    private val modelPath: String? = null
) : LlmModel {

    private val liteRtLmEngine = LiteRtLmEngine(context, modelPath).apply {
        preferredBackend?.let { setPreferredBackend(it) }
    }
    private var defaultParams = GenerationParams.DEFAULT
    private var sessionParams = SessionParams.DEFAULT

    // ==================== Lifecycle ====================

    override suspend fun loadModel(): Boolean {
        return liteRtLmEngine.initialize()
    }

    override fun isModelLoaded(): Boolean {
        return liteRtLmEngine.isModelLoaded()
    }

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
     * Generate from image if the model supports it.
     * LiteRT-LM models may support image input depending on the model config.
     */
    override suspend fun generateFromImage(imagePath: String, userPrompt: String): String {
        // Check if this model supports image input
        val capabilities = liteRtLmEngine.getModelCapabilities()
        if (!capabilities.supportsImage) {
            return ""  // Not supported
        }
        return liteRtLmEngine.generateFromImage(imagePath, userPrompt)
    }

    // ==================== Metadata & Capabilities ====================

    override fun getExecutionProvider(): String {
        return liteRtLmEngine.getExecutionProvider()
    }

    override fun isUsingGpu(): Boolean {
        return liteRtLmEngine.isUsingGpu()
    }

    override fun getModelCapabilities() = liteRtLmEngine.getModelCapabilities()

    override fun getModelInfo(): ModelInfo {
        val capabilities = liteRtLmEngine.getModelCapabilities()
        return ModelInfo(
            name = capabilities.modelName,
            format = ModelFormat.LITERTLM,
            filePath = modelPath,
            sizeBytes = modelPath?.let { File(it).length() }
        )
    }
}

