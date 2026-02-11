package com.dramebaz.app.ai.audio

import com.dramebaz.app.utils.AppLogger

/**
 * JNI bridge for native Stable Audio Open Small inference.
 * Uses LiteRT (TensorFlow Lite) with XNNPack for optimized CPU inference on Arm.
 * GPU acceleration available via TFLite GPU delegate.
 *
 * Required model files in the model directory (int8 quantized):
 * - conditioners_int8.tflite - T5 encoder + number conditioner (quantized)
 * - dit_model_int8.tflite - Diffusion Transformer (quantized)
 * - autoencoder_model_int8.tflite - AutoEncoder decoder (quantized)
 * - spiece.model - SentencePiece tokenizer for T5
 *
 * @see StableAudioModelFiles for canonical model file names
 */
object StableAudioNative {
    private const val TAG = "StableAudioNative"

    private var isLibraryLoaded = false

    init {
        try {
            System.loadLibrary("stable_audio_jni")
            isLibraryLoaded = true
            AppLogger.i(TAG, "stable_audio_jni library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            AppLogger.e(TAG, "Failed to load stable_audio_jni library", e)
            isLibraryLoaded = false
        }
    }

    /**
     * Check if the native library is available.
     */
    fun isAvailable(): Boolean = isLibraryLoaded

    /**
     * Load the Stable Audio models from the specified directory.
     *
     * @param modelDirectory Path to directory containing the TFLite model files
     * @param numThreads Number of CPU threads to use for inference (default: 4)
     * @param useGpu Whether to use GPU acceleration if available (default: false - CPU is faster for init)
     * @return Native handle for the loaded models, or 0 if loading failed
     */
    @JvmStatic
    external fun loadModels(modelDirectory: String, numThreads: Int = 4, useGpu: Boolean = false): Long

    /**
     * Generate audio from a text prompt.
     *
     * @param handle Native handle from loadModels()
     * @param prompt Text description of the desired sound
     * @param durationSeconds Duration of audio to generate (0.5 to 11 seconds)
     * @param numSteps Number of diffusion steps (default: 8, higher = better quality but slower)
     * @param seed Random seed for reproducibility (different seeds = different outputs)
     * @param outputPath Path where the generated WAV file should be saved
     * @return true if generation succeeded, false otherwise
     */
    @JvmStatic
    external fun generate(
        handle: Long,
        prompt: String,
        durationSeconds: Float,
        numSteps: Int = 8,
        seed: Long = 99,
        outputPath: String
    ): Boolean

    /**
     * Get the last error message from native code.
     * @return Error message string, or empty if no error
     */
    @JvmStatic
    external fun getLastError(): String

    /**
     * Get generation progress (0.0 to 1.0).
     * Can be called from another thread during generation.
     * @param handle Native handle
     * @return Progress value between 0.0 and 1.0
     */
    @JvmStatic
    external fun getProgress(handle: Long): Float

    /**
     * Cancel an ongoing generation.
     * @param handle Native handle
     */
    @JvmStatic
    external fun cancel(handle: Long)

    /**
     * Release all resources associated with the handle.
     * @param handle Native handle from loadModels()
     */
    @JvmStatic
    external fun release(handle: Long)

    // ==================== GPU Shader Management ====================

    /**
     * Check if GPU delegate is available in this build.
     * @return true if the native library was built with GPU delegate support
     */
    @JvmStatic
    external fun isGpuDelegateAvailable(): Boolean

    /**
     * Check if GPU shaders are ready (cached from previous compilation).
     * @param modelDirectory Path to model directory containing gpu_cache folder
     * @return true if shaders are cached and ready to use
     */
    @JvmStatic
    external fun isGpuShadersReady(modelDirectory: String): Boolean

    /**
     * Compile GPU shaders in background with reduced resources.
     * This function loads models, compiles GPU shaders to cache, then releases everything.
     * Should be called from a background thread with low priority.
     *
     * @param modelDirectory Path to model directory
     * @param numThreads Number of threads to use (1-2 recommended for background)
     * @return true if shader compilation succeeded
     */
    @JvmStatic
    external fun prepareGpuShaders(modelDirectory: String, numThreads: Int = 1): Boolean

    /**
     * Inspect all models and return a detailed report of their input/output tensors.
     * Useful for debugging tensor mismatches and understanding the pipeline.
     *
     * @param modelDirectory Path to model directory
     * @return String containing detailed model inspection report
     */
    @JvmStatic
    external fun inspectModels(modelDirectory: String): String
}

