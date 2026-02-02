package com.dramebaz.app.ai.llm

/**
 * JNI bridge to llama.cpp (Qwen3 GGUF). Native library built from app/src/main/cpp
 * using ggml-org/llama.cpp b7793.
 */
object LlamaNative {
    init {
        try {
            System.loadLibrary("llama_jni")
        } catch (t: Throwable) {
            // Log in caller if needed
        }
    }

    /**
     * Load model from path. Returns opaque handle (0 on failure).
     */
    external fun loadModel(modelPath: String): Long

    /**
     * Generate text. Returns empty string on error or if handle is invalid.
     */
    external fun generate(handle: Long, prompt: String, maxTokens: Int, temperature: Float): String

    /**
     * Release model and context. Safe to call with 0.
     */
    external fun release(handle: Long)

    /**
     * Returns which execution provider is in use: "GPU (Vulkan) [X/Y layers]" or "CPU".
     * Returns "unknown" if handle is invalid.
     */
    external fun getExecutionProvider(handle: Long): String

    /**
     * Returns the number of layers offloaded to GPU (Vulkan).
     * Returns 0 if using CPU or handle is invalid.
     */
    external fun getGpuLayerCount(handle: Long): Int

    /**
     * Returns the total number of layers in the model.
     * Returns 0 if handle is invalid.
     */
    external fun getTotalLayerCount(handle: Long): Int
}
