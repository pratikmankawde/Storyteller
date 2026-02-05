package com.dramebaz.app.ai.llm.models

import android.content.Context
import com.dramebaz.app.utils.AppLogger
import java.io.File

/**
 * Factory for creating LLM model instances.
 * Uses the Factory Pattern to abstract model creation and selection based on device capabilities.
 * 
 * Currently supports:
 * - Qwen3Model (llama.cpp GGUF) - Primary model, requires downloaded GGUF file
 * - LiteRtLmEngine (LiteRT-LM) - Secondary model for Gemma 3n
 */
object LlmModelFactory {
    private const val TAG = "LlmModelFactory"
    
    /**
     * Model type enum for explicit model selection.
     */
    enum class ModelType {
        QWEN3_GGUF,     // Qwen3 via llama.cpp (GGUF format)
        GEMMA_LITERTLM  // Gemma 3n via LiteRT-LM
    }
    
    /**
     * Create the default/preferred model based on available model files.
     * Priority: Qwen3 GGUF > Gemma LiteRT-LM
     * 
     * @param context Android context for file access
     * @return LlmModel instance, or null if no model is available
     */
    fun createDefaultModel(context: Context): LlmModel? {
        AppLogger.d(TAG, "Creating default LLM model...")
        
        // Check for Qwen3 GGUF model first (preferred)
        if (isQwen3ModelAvailable(context)) {
            AppLogger.i(TAG, "Qwen3 GGUF model available, creating Qwen3Model")
            return Qwen3ModelImpl(context)
        }
        
        // Check for Gemma LiteRT-LM model
        if (isGemmaModelAvailable(context)) {
            AppLogger.i(TAG, "Gemma LiteRT-LM model available, creating LiteRtLmEngineImpl")
            return LiteRtLmEngineImpl(context)
        }
        
        AppLogger.w(TAG, "No LLM model files found on device")
        return null
    }
    
    /**
     * Create a specific model type.
     * 
     * @param context Android context
     * @param modelType The specific model type to create
     * @return LlmModel instance
     */
    fun createModel(context: Context, modelType: ModelType): LlmModel {
        return when (modelType) {
            ModelType.QWEN3_GGUF -> Qwen3ModelImpl(context)
            ModelType.GEMMA_LITERTLM -> LiteRtLmEngineImpl(context)
        }
    }
    
    /**
     * Check if Qwen3 GGUF model file exists on the device.
     */
    fun isQwen3ModelAvailable(context: Context): Boolean {
        val possiblePaths = listOf(
            "/storage/emulated/0/Download/Qwen3-1.7B-Q4_K_M.gguf",
            "/sdcard/Download/Qwen3-1.7B-Q4_K_M.gguf",
            "${context.filesDir.absolutePath}/model.gguf"
        )
        return possiblePaths.any { File(it).exists() }
    }
    
    /**
     * Check if Gemma LiteRT-LM model file exists on the device.
     */
    fun isGemmaModelAvailable(context: Context): Boolean {
        val possiblePaths = listOf(
            "/storage/emulated/0/Download/gemma-3n-E2B-it-int4.litertlm",
            "/sdcard/Download/gemma-3n-E2B-it-int4.litertlm",
            "${context.filesDir.absolutePath}/gemma-3n-E2B-it-int4.litertlm"
        )
        return possiblePaths.any { File(it).exists() }
    }
    
    /**
     * Get list of available model types on this device.
     */
    fun getAvailableModelTypes(context: Context): List<ModelType> {
        val available = mutableListOf<ModelType>()
        if (isQwen3ModelAvailable(context)) available.add(ModelType.QWEN3_GGUF)
        if (isGemmaModelAvailable(context)) available.add(ModelType.GEMMA_LITERTLM)
        return available
    }
}

