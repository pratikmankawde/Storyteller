package com.dramebaz.app.data.models

import com.dramebaz.app.ai.llm.models.LlmModelFactory
import com.dramebaz.app.ai.llm.models.RemoteServerConfig

/**
 * LLM Settings for model and backend selection.
 * Allows users to choose which LLM model to use and CPU/GPU preference.
 */
data class LlmSettings(
    val selectedModelType: LlmModelType = LlmModelType.AUTO,
    val preferredBackend: LlmBackend = LlmBackend.GPU,
    /** Specific model file path when user selects a discovered model (null = auto-select first available) */
    val selectedModelPath: String? = null,
    /** Remote server configuration for REMOTE_SERVER model type */
    val remoteServerConfig: RemoteServerConfig = RemoteServerConfig.DEFAULT
) {
    companion object {
        /** Default settings - auto-select model, prefer GPU */
        val DEFAULT = LlmSettings()
    }
}

/**
 * LLM model type enum for user selection.
 * Maps to LlmModelFactory.ModelType based on file format/engine type.
 */
enum class LlmModelType(val displayName: String, val description: String) {
    AUTO("Auto", "Automatically select best available model"),
    LITERTLM("LiteRT-LM (.litertlm)", "LiteRT-LM format models (Gemma, Qwen, etc.)"),
    GGUF("GGUF (.gguf)", "GGUF format models via llama.cpp"),
    MEDIAPIPE("MediaPipe (.task)", "MediaPipe format models (Gemma 3n, etc.)"),
    REMOTE_SERVER("Remote Server", "Remote AIServer via network");

    /**
     * Convert to factory model type (returns null for AUTO).
     */
    fun toFactoryType(): LlmModelFactory.ModelType? = when (this) {
        AUTO -> null
        LITERTLM -> LlmModelFactory.ModelType.LITERTLM
        GGUF -> LlmModelFactory.ModelType.GGUF
        MEDIAPIPE -> LlmModelFactory.ModelType.MEDIAPIPE
        REMOTE_SERVER -> LlmModelFactory.ModelType.REMOTE_SERVER
    }

    companion object {
        /**
         * Create from factory model type.
         */
        fun fromFactoryType(type: LlmModelFactory.ModelType): LlmModelType = when (type) {
            LlmModelFactory.ModelType.LITERTLM -> LITERTLM
            LlmModelFactory.ModelType.GGUF -> GGUF
            LlmModelFactory.ModelType.MEDIAPIPE -> MEDIAPIPE
            LlmModelFactory.ModelType.REMOTE_SERVER -> REMOTE_SERVER
        }
    }
}

/**
 * LLM backend preference enum.
 */
enum class LlmBackend(val displayName: String, val description: String) {
    GPU("GPU", "Faster but uses more battery"),
    CPU("CPU", "Slower but more battery efficient");
    
    /**
     * Get accelerator string for config (e.g., "gpu,cpu" or "cpu").
     */
    fun toAcceleratorString(): String = when (this) {
        GPU -> "gpu,cpu"  // GPU first, fallback to CPU
        CPU -> "cpu"       // CPU only
    }
}

