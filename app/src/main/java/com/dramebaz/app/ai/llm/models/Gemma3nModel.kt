package com.dramebaz.app.ai.llm.models

import android.content.Context
import com.dramebaz.app.ai.llm.LiteRtLmEngine
import com.dramebaz.app.utils.AppLogger

/**
 * Gemma 3n model implementation wrapping LiteRtLmEngine.
 *
 * This is a pure inference wrapper - all pass-specific logic (prompts, parsing) belongs
 * in the workflow classes (TwoPassWorkflow).
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

    override fun getExecutionProvider(): String = liteRtLmEngine.getExecutionProvider()

    override fun isUsingGpu(): Boolean = liteRtLmEngine.isUsingGpu()

    // ==================== Core Inference Method ====================

    override suspend fun generateResponse(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        temperature: Float
    ): String {
        return liteRtLmEngine.generate(systemPrompt, userPrompt, maxTokens, temperature)
    }

    // ==================== Gemma-Specific Methods ====================

    /**
     * STORY-002: Generate a story from an inspiration image.
     * Uses Gemma 3n multimodal capabilities to analyze the image and generate a story.
     *
     * @param imagePath Path to the image file on device
     * @param userPrompt Optional user prompt for story direction
     * @return Generated story text
     */
    suspend fun generateStoryFromImage(imagePath: String, userPrompt: String = ""): String {
        AppLogger.i(TAG, "generateStoryFromImage: image=$imagePath, prompt=${userPrompt.take(50)}...")
        return liteRtLmEngine.generateFromImage(imagePath, userPrompt)
    }

    // ==================== Legacy Access ====================
    // Get underlying engine for backward compatibility during migration

    /**
     * Get the underlying LiteRtLmEngine for access to additional methods.
     * @deprecated Use generateResponse() instead. This is kept for backward compatibility
     * during migration to workflow-based architecture.
     */
    fun getUnderlyingEngine(): LiteRtLmEngine = liteRtLmEngine
}

