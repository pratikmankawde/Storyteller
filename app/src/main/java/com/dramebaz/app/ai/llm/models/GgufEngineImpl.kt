package com.dramebaz.app.ai.llm.models

import android.content.Context
import com.dramebaz.app.ai.llm.GgufEngine
import com.dramebaz.app.ai.llm.ModelCapabilities
import java.io.File

/**
 * LlmModel implementation that wraps GgufEngine.
 * Uses the Adapter Pattern to make GgufEngine conform to the LlmModel interface.
 *
 * This is a pure inference wrapper - all pass-specific logic (prompts, parsing) belongs
 * in the workflow/service layer.
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
class GgufEngineImpl(context: Context, private val modelPath: String? = null) : LlmModel {

    private val ggufEngine = GgufEngine(context, modelPath)
    private var defaultParams = GenerationParams.DEFAULT
    private var sessionParams = SessionParams.DEFAULT

    // ==================== Lifecycle ====================

    override suspend fun loadModel(): Boolean {
        return ggufEngine.loadModel()
    }

    override fun isModelLoaded(): Boolean {
        return ggufEngine.isModelLoaded()
    }

    override fun release() {
        ggufEngine.release()
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
        // GGUF/llama.cpp session params require model reload to take effect
        // Return true to indicate params were stored; caller should reload model
        return true
    }

    override fun getSessionParamsSupport(): SessionParamsSupport = SessionParamsSupport.GGUF_FULL

    // ==================== Core Inference ====================

    override suspend fun generateResponse(
        systemPrompt: String,
        userPrompt: String,
        params: GenerationParams
    ): String {
        return ggufEngine.generateWithPrompts(
            systemPrompt,
            userPrompt,
            params.maxTokens,
            params.temperature
        )
    }

    // ==================== Metadata & Capabilities ====================

    override fun getExecutionProvider(): String {
        return ggufEngine.getExecutionProvider()
    }

    override fun isUsingGpu(): Boolean {
        return ggufEngine.isUsingGpu()
    }

    override fun getModelCapabilities(): ModelCapabilities {
        val displayName = if (modelPath != null) {
            ModelNameUtils.deriveDisplayName(modelPath)
        } else {
            ModelNameUtils.getDefaultName("gguf")
        }
        return ModelCapabilities(
            modelName = displayName,
            supportsImage = false,
            supportsAudio = false,
            supportsStreaming = true,  // llama.cpp supports streaming
            maxContextLength = 8192,    // Typical GGUF model context
            recommendedMaxTokens = 2048
        )
    }

    override fun getModelInfo(): ModelInfo {
        val displayName = if (modelPath != null) {
            ModelNameUtils.deriveDisplayName(modelPath)
        } else {
            ModelNameUtils.getDefaultName("gguf")
        }
        return ModelInfo(
            name = displayName,
            format = ModelFormat.GGUF,
            filePath = modelPath,
            sizeBytes = modelPath?.let { File(it).length() }
        )
    }
}

