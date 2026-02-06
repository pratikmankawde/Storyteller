package com.dramebaz.app.ai.llm.models

import android.content.Context
import com.dramebaz.app.ai.llm.LiteRtLmEngine
import com.google.ai.edge.litertlm.Backend

/**
 * LlmModel implementation that wraps LiteRtLmEngine.
 * Uses the Adapter Pattern to make LiteRtLmEngine conform to the LlmModel interface.
 *
 * This is a pure inference wrapper - all pass-specific logic (prompts, parsing) belongs
 * in the workflow classes (TwoPassWorkflow).
 *
 * Works with LiteRT-LM format models (e.g., Gemma, Qwen .litertlm files).
 *
 * @param context Android context
 * @param preferredBackend Optional preferred backend (GPU/CPU). If null, uses config order.
 * @param modelPath Optional explicit model file path. If null, uses config-based discovery.
 */
class LiteRtLmEngineImpl(
    context: Context,
    preferredBackend: Backend? = null,
    modelPath: String? = null
) : LlmModel {

    private val liteRtLmEngine = LiteRtLmEngine(context, modelPath).apply {
        preferredBackend?.let { setPreferredBackend(it) }
    }

    override suspend fun loadModel(): Boolean {
        return liteRtLmEngine.initialize()
    }

    override fun isModelLoaded(): Boolean {
        return liteRtLmEngine.isModelLoaded()
    }

    override fun release() {
        liteRtLmEngine.release()
    }

    override fun getExecutionProvider(): String {
        return liteRtLmEngine.getExecutionProvider()
    }

    override fun isUsingGpu(): Boolean {
        return liteRtLmEngine.isUsingGpu()
    }

    // ==================== Core Inference Method ====================

    override suspend fun generateResponse(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        temperature: Float
    ): String {
        return liteRtLmEngine.generate(systemPrompt, userPrompt, maxTokens, temperature)
    }

    // ==================== Model Capabilities ====================

    /**
     * Get the capabilities of the currently loaded model.
     * @return ModelCapabilities with image/audio support flags
     */
    fun getModelCapabilities() = liteRtLmEngine.getModelCapabilities()

    // ==================== Legacy Access ====================
    // Get underlying engine for backward compatibility during migration

    /**
     * Get the underlying LiteRtLmEngine for access to additional methods.
     * @deprecated Use generateResponse() instead. This is kept for backward compatibility
     * during migration to workflow-based architecture.
     */
    fun getUnderlyingEngine(): LiteRtLmEngine = liteRtLmEngine
}

