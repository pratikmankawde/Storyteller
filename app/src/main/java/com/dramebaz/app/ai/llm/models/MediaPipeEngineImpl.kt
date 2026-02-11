package com.dramebaz.app.ai.llm.models

import android.content.Context
import com.dramebaz.app.ai.llm.ModelCapabilities
import com.dramebaz.app.utils.AppLogger
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LlmModel implementation that wraps MediaPipe LLM Inference API.
 * Uses the Adapter Pattern to make MediaPipe conform to the LlmModel interface.
 *
 * This is a pure inference wrapper - all pass-specific logic (prompts, parsing) belongs
 * in the workflow/service layer.
 *
 * Works with MediaPipe .task format models (e.g., gemma-3n-E2B-it-int4.task).
 *
 * @param context Android context
 * @param modelPath Path to the .task model file
 * @param maxTokens Maximum tokens for generation (input + output)
 * @param topK Top-K sampling parameter
 * @param defaultTemperature Default temperature for generation
 */
class MediaPipeEngineImpl(
    private val context: Context,
    private val modelPath: String,
    private val maxTokens: Int = 1024,
    private val topK: Int = 40,
    private val defaultTemperature: Float = 0.8f
) : LlmModel {

    companion object {
        private const val TAG = "MediaPipeEngineImpl"
    }

    private var llmInference: LlmInference? = null
    private var isLoaded = false
    private var defaultParams = GenerationParams(
        maxTokens = maxTokens,
        temperature = defaultTemperature,
        topK = topK
    )
    private var sessionParams = SessionParams.DEFAULT

    // Mutex to serialize LLM calls - only one inference at a time
    private val inferenceMutex = Mutex()

    // ==================== Lifecycle ====================

    override suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        if (isLoaded && llmInference != null) {
            AppLogger.d(TAG, "Model already loaded")
            return@withContext true
        }

        try {
            AppLogger.i(TAG, "Loading MediaPipe model from: $modelPath")
            AppLogger.i(TAG, "MediaPipe options: maxTokens=$maxTokens, topK=$topK, temperature=$defaultTemperature")

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(maxTokens)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            isLoaded = true
            AppLogger.i(TAG, "MediaPipe model loaded successfully: ${getExecutionProvider()}")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load MediaPipe model: ${e.message}", e)
            isLoaded = false
            llmInference = null
            false
        }
    }

    override fun isModelLoaded(): Boolean = isLoaded && llmInference != null

    override fun release() {
        try {
            llmInference?.close()
            llmInference = null
            isLoaded = false
            AppLogger.i(TAG, "MediaPipe model released")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error releasing MediaPipe model", e)
        }
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
        // MediaPipe doesn't support session params, but store them anyway
        return true
    }

    override fun getSessionParamsSupport(): SessionParamsSupport = SessionParamsSupport.MEDIAPIPE

    // ==================== Core Inference ====================

    /**
     * Generate a response with mutex protection to ensure only one LLM call at a time.
     */
    override suspend fun generateResponse(
        systemPrompt: String,
        userPrompt: String,
        params: GenerationParams
    ): String = inferenceMutex.withLock {
        withContext(Dispatchers.IO) {
            val inference = llmInference
            if (inference == null || !isLoaded) {
                AppLogger.e(TAG, "Model not loaded, cannot generate response")
                return@withContext ""
            }

            try {
                val combinedPrompt = if (systemPrompt.isNotBlank()) {
                    "$systemPrompt\n\n$userPrompt"
                } else {
                    userPrompt
                }

                AppLogger.d(TAG, "Generating response (prompt length: ${combinedPrompt.length})")
                val startTime = System.currentTimeMillis()

                val result = inference.generateResponse(combinedPrompt)

                val elapsedMs = System.currentTimeMillis() - startTime
                AppLogger.d(TAG, "Generated response in ${elapsedMs}ms (${result.length} chars)")

                result ?: ""
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error generating response: ${e.message}", e)
                ""
            }
        }
    }

    // ==================== Metadata & Capabilities ====================

    override fun getExecutionProvider(): String {
        return if (isLoaded) "MediaPipe (GPU/CPU)" else "MediaPipe (not loaded)"
    }

    override fun isUsingGpu(): Boolean {
        return isLoaded
    }

    override fun getModelCapabilities(): ModelCapabilities {
        val displayName = ModelNameUtils.deriveDisplayName(modelPath)
        return ModelCapabilities(
            modelName = displayName,
            supportsImage = false,
            supportsAudio = false,
            supportsStreaming = false,  // MediaPipe API is synchronous
            maxContextLength = maxTokens,
            recommendedMaxTokens = maxTokens / 2
        )
    }

    override fun getModelInfo(): ModelInfo {
        val displayName = ModelNameUtils.deriveDisplayName(modelPath)
        return ModelInfo(
            name = displayName,
            format = ModelFormat.MEDIAPIPE,
            filePath = modelPath,
            sizeBytes = File(modelPath).length()
        )
    }
}

