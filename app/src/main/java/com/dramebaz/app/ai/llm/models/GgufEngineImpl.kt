package com.dramebaz.app.ai.llm.models

import android.content.Context
import com.dramebaz.app.ai.llm.GgufEngine

/**
 * LlmModel implementation that wraps GgufEngine.
 * Uses the Adapter Pattern to make GgufEngine conform to the LlmModel interface.
 *
 * This is a pure inference wrapper - all pass-specific logic (prompts, parsing) belongs
 * in the workflow classes (ThreePassWorkflow).
 *
 * This adapter works with any GGUF format model via llama.cpp JNI, including:
 * - Qwen models (.gguf)
 * - Llama models (.gguf)
 * - Mistral models (.gguf)
 * - Other compatible GGUF models
 *
 * @param context Android context
 * @param modelPath Optional explicit model file path. If null, uses Downloads folder discovery.
 */
class GgufEngineImpl(context: Context, modelPath: String? = null) : LlmModel {

    private val ggufEngine = GgufEngine(context, modelPath)

    override suspend fun loadModel(): Boolean {
        return ggufEngine.loadModel()
    }

    override fun isModelLoaded(): Boolean {
        return ggufEngine.isModelLoaded()
    }

    override fun release() {
        ggufEngine.release()
    }

    override fun getExecutionProvider(): String {
        return ggufEngine.getExecutionProvider()
    }

    override fun isUsingGpu(): Boolean {
        return ggufEngine.isUsingGpu()
    }

    // ==================== Core Inference Method ====================

    override suspend fun generateResponse(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        temperature: Float
    ): String {
        return ggufEngine.generateWithPrompts(systemPrompt, userPrompt, maxTokens, temperature)
    }

    // ==================== Legacy Access ====================
    // Get underlying engine for backward compatibility during migration

    /**
     * Get the underlying GgufEngine for access to additional methods.
     * @deprecated Use generateResponse() instead. This is kept for backward compatibility
     * during migration to workflow-based architecture.
     */
    fun getUnderlyingEngine(): GgufEngine = ggufEngine
}

