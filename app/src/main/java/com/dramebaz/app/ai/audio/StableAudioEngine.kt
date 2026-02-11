package com.dramebaz.app.ai.audio

import android.content.Context
import android.os.Debug
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Stable Audio Open Small model engine for AI-powered SFX generation.
 * Uses native C++ implementation with LiteRT (TensorFlow Lite) and XNNPack
 * for optimized inference on Arm CPUs. GPU acceleration available via delegate.
 *
 * Architecture:
 * 1. Conditioners (T5 + Number) - Text encoding + duration conditioning
 * 2. DiT (Diffusion Transformer) - Diffusion model for audio generation
 * 3. AutoEncoder Decoder - Decode latents to audio waveform
 *
 * Required model files (int8 quantized TFLite format):
 * - conditioners_int8.tflite - T5 encoder + number conditioner (quantized)
 * - dit_model_int8.tflite - Diffusion Transformer (quantized)
 * - autoencoder_model_int8.tflite - AutoEncoder decoder (quantized)
 * - spiece.model - SentencePiece tokenizer for T5
 *
 * @see StableAudioModelFiles for canonical model file names
 */
class StableAudioEngine(private val context: Context) {

    companion object {
        private const val TAG = "StableAudioEngine"

        // Model file names - use StableAudioModelFiles for canonical references
        @Deprecated("Use StableAudioModelFiles.CONDITIONERS instead", ReplaceWith("StableAudioModelFiles.CONDITIONERS"))
        const val CONDITIONERS_MODEL = "conditioners_int8.tflite"
        @Deprecated("Use StableAudioModelFiles.DIT instead", ReplaceWith("StableAudioModelFiles.DIT"))
        const val DIT_MODEL = "dit_model_int8.tflite"
        @Deprecated("Use StableAudioModelFiles.AUTOENCODER instead", ReplaceWith("StableAudioModelFiles.AUTOENCODER"))
        const val AUTOENCODER_MODEL = "autoencoder_model_int8.tflite"
        @Deprecated("Use StableAudioModelFiles.TOKENIZER instead", ReplaceWith("StableAudioModelFiles.TOKENIZER"))
        const val TOKENIZER_MODEL = "spiece.model"

        // Model parameters (from Stable Audio Open Small config)
        const val SAMPLE_RATE = 44100
        const val MAX_DURATION_SECONDS = 11.0f
        const val DEFAULT_NUM_STEPS = 8
        const val DEFAULT_SEED = 99L
    }

    private var isInitialized = false
    private var modelPath: String? = null
    private var numThreads = 4
    private var gpuEnabled = false

    // Native implementation handle
    private var nativeHandle: Long = 0L

    @Volatile
    private var progress: Float = 0f

    @Volatile
    private var cancelled: Boolean = false

    @Volatile
    private var lastError: String? = null

    /**
     * Callback interface for progress and status updates
     */
    interface ProgressCallback {
        fun onProgress(progress: Float, stage: String)
        fun onError(error: String)
    }

    private var progressCallback: ProgressCallback? = null

    fun setProgressCallback(callback: ProgressCallback?) {
        progressCallback = callback
    }

    /**
     * Smart initialization with automatic GPU shader management.
     *
     * Strategy:
     * 1. If GPU shaders are ready (cached), use GPU for faster inference
     * 2. Otherwise, use CPU for fast init and schedule background shader compilation
     * 3. Next run will automatically use GPU once shaders are ready
     *
     * @param modelDirectory Path to directory containing the TFLite model files
     * @param threads Number of CPU threads to use for inference (default: 4)
     * @param forceGpu Force GPU usage even if shaders aren't ready (will block for compilation)
     * @param forceCpu Force CPU usage even if GPU shaders are ready (for debugging)
     */
    suspend fun initializeSmart(
        modelDirectory: String,
        threads: Int = 4,
        forceGpu: Boolean = false,
        forceCpu: Boolean = false
    ): Boolean {
        // Check if GPU shaders are ready
        val shadersReady = GpuShaderManager.areShadersReady(context, modelDirectory)

        // Determine whether to use GPU
        val useGpu = when {
            forceCpu -> {
                AppLogger.i(TAG, "Forcing CPU mode (forceCpu=true)")
                false
            }
            forceGpu -> true
            else -> shadersReady
        }

        AppLogger.i(TAG, "Smart init: shadersReady=$shadersReady, useGpu=$useGpu, forceCpu=$forceCpu")

        val success = initialize(modelDirectory, threads, useGpu)

        // If we used CPU and shaders aren't ready, schedule background compilation
        // Don't schedule if forceCpu is set (debugging mode)
        if (success && !useGpu && !shadersReady && !forceCpu) {
            AppLogger.i(TAG, "Scheduling background GPU shader compilation...")
            GpuShaderManager.scheduleBackgroundCompilation(context, modelDirectory) { compiled ->
                if (compiled) {
                    AppLogger.i(TAG, "GPU shaders ready for next session")
                }
            }
        }

        return success
    }

    /**
     * Initialize the engine with native C++ implementation.
     * Requires the native library to be built with STABLE_AUDIO_ENABLED=ON.
     *
     * @param modelDirectory Path to directory containing the TFLite model files
     * @param threads Number of CPU threads to use for inference (default: 4)
     * @param useGpu Whether to use GPU acceleration if available (default: false - CPU is faster for init)
     */
    suspend fun initialize(
        modelDirectory: String,
        threads: Int = 4,
        useGpu: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext true

        try {
            logMemoryUsage("Before initialization")
            modelPath = modelDirectory
            numThreads = threads
            gpuEnabled = useGpu
            val modelDir = File(modelDirectory)

            if (!modelDir.exists() || !modelDir.isDirectory) {
                val error = "Model directory does not exist: $modelDirectory"
                AppLogger.e(TAG, error)
                lastError = error
                return@withContext false
            }

            // Check for required model files
            val missingFiles = StableAudioModelFiles.getMissingFiles(modelDir)
            if (missingFiles.isNotEmpty()) {
                val error = "Missing model files: ${missingFiles.joinToString(", ")}"
                AppLogger.e(TAG, error)
                lastError = error
                return@withContext false
            }

            // Check if native library is available
            if (!StableAudioNative.isAvailable()) {
                val error = "Native library not available. Build with STABLE_AUDIO_ENABLED=ON"
                AppLogger.e(TAG, error)
                lastError = error
                return@withContext false
            }

            // Load models using native implementation
            AppLogger.i(TAG, "Loading models from: $modelDirectory (GPU: $useGpu, threads: $threads)")
            val handle = StableAudioNative.loadModels(modelDirectory, threads, useGpu)

            if (handle == 0L) {
                val nativeError = StableAudioNative.getLastError()
                val error = if (nativeError.isNotEmpty()) nativeError else "Failed to load models"
                AppLogger.e(TAG, error)
                lastError = error
                return@withContext false
            }

            nativeHandle = handle
            isInitialized = true
            gpuEnabled = useGpu // Track actual GPU usage
            logMemoryUsage("After native initialization")
            val accelerator = if (useGpu) "GPU delegate" else "CPU only"
            AppLogger.i(TAG, "✅ StableAudioEngine ready (native C++ implementation, $accelerator)")
            true
        } catch (e: Exception) {
            val error = "Failed to initialize StableAudioEngine: ${e.message}"
            AppLogger.e(TAG, error, e)
            lastError = error
            release()
            false
        }
    }

    fun isReady(): Boolean = isInitialized

    /**
     * Check if native implementation is available and initialized.
     */
    fun isNativeAvailable(): Boolean = isInitialized && nativeHandle != 0L

    /**
     * Check if GPU acceleration is enabled.
     */
    fun isGpuEnabled(): Boolean = gpuEnabled

    /**
     * Get implementation type for UI display.
     */
    fun getImplementationType(): String = when {
        isInitialized && nativeHandle != 0L -> "Native C++ (LiteRT + XNNPack)"
        else -> "Not initialized"
    }

    fun getProgress(): Float {
        if (nativeHandle != 0L) {
            return StableAudioNative.getProgress(nativeHandle)
        }
        return progress
    }

    fun getLastError(): String? {
        if (nativeHandle != 0L) {
            val nativeError = StableAudioNative.getLastError()
            if (nativeError.isNotEmpty()) return nativeError
        }
        return lastError
    }

    fun cancel() {
        cancelled = true
        if (nativeHandle != 0L) {
            StableAudioNative.cancel(nativeHandle)
        }
    }

    private fun logMemoryUsage(label: String) {
        val runtime = Runtime.getRuntime()
        val usedMemMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMemMB = runtime.maxMemory() / (1024 * 1024)
        val nativeHeapMB = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        AppLogger.d(TAG, "[$label] Memory: Java ${usedMemMB}MB/${maxMemMB}MB, Native ${nativeHeapMB}MB")
    }

    private fun updateProgress(progress: Float, stage: String) {
        this.progress = progress
        progressCallback?.onProgress(progress, stage)
        AppLogger.d(TAG, "Progress: ${(progress * 100).toInt()}% - $stage")
    }

    /**
     * Generate audio from a text prompt using native C++ implementation.
     */
    suspend fun generateAudio(
        prompt: String,
        durationSeconds: Float = 5.0f,
        numSteps: Int = DEFAULT_NUM_STEPS,
        seed: Long = DEFAULT_SEED
    ): File? = withContext(Dispatchers.IO) {
        if (!isInitialized || nativeHandle == 0L) {
            val error = "Engine not initialized"
            AppLogger.e(TAG, error)
            lastError = error
            return@withContext null
        }

        progress = 0f
        cancelled = false
        lastError = null

        val clampedDuration = durationSeconds.coerceIn(0.5f, MAX_DURATION_SECONDS)
        AppLogger.i(TAG, "═══════════════════════════════════════════════════════")
        AppLogger.i(TAG, "Starting generation: prompt='$prompt'")
        AppLogger.i(TAG, "Duration: ${clampedDuration}s, Steps: $numSteps, Seed: $seed")
        AppLogger.i(TAG, "Implementation: ${getImplementationType()}")

        try {
            logMemoryUsage("Native generation start")
            val startTime = System.currentTimeMillis()

            // Create output file
            val outputDir = File(context.cacheDir, "stable_audio")
            outputDir.mkdirs()
            val timestamp = System.currentTimeMillis()
            val outputFile = File(outputDir, "sfx_${timestamp}.wav")

            updateProgress(0.05f, "Starting native generation")

            // Start progress polling in background
            val progressJob = GlobalScope.launch {
                while (!cancelled && progress < 0.99f) {
                    val nativeProgress = StableAudioNative.getProgress(nativeHandle)
                    if (nativeProgress > progress) {
                        val stage = when {
                            nativeProgress < 0.10f -> "Running conditioners"
                            nativeProgress < 0.85f -> "Running diffusion (step ${((nativeProgress - 0.10f) / 0.75f * numSteps).toInt()}/$numSteps)"
                            nativeProgress < 0.95f -> "Decoding audio"
                            else -> "Saving file"
                        }
                        updateProgress(nativeProgress, stage)
                    }
                    delay(100)
                }
            }

            // Call native generate
            val success = StableAudioNative.generate(
                handle = nativeHandle,
                prompt = prompt,
                durationSeconds = clampedDuration,
                numSteps = numSteps,
                seed = seed,
                outputPath = outputFile.absolutePath
            )

            progressJob.cancel()

            val elapsedMs = System.currentTimeMillis() - startTime
            val elapsedSec = elapsedMs / 1000.0
            logMemoryUsage("Native generation complete")

            if (success && outputFile.exists()) {
                updateProgress(1.0f, "Complete")
                AppLogger.i(TAG, "✅ Generated in ${String.format("%.1f", elapsedSec)}s: ${outputFile.absolutePath}")
                AppLogger.i(TAG, "═══════════════════════════════════════════════════════")
                outputFile
            } else {
                val error = StableAudioNative.getLastError().ifEmpty { "Native generation failed" }
                lastError = error
                AppLogger.e(TAG, "Native generation failed: $error")
                progressCallback?.onError(error)
                null
            }
        } catch (e: Exception) {
            val error = "Native generation failed: ${e.message}"
            AppLogger.e(TAG, error, e)
            lastError = error
            progressCallback?.onError(error)
            null
        }
    }

    /**
     * Release all resources.
     */
    fun release() {
        if (nativeHandle != 0L) {
            try {
                StableAudioNative.release(nativeHandle)
                AppLogger.d(TAG, "Native handle released")
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error releasing native handle: ${e.message}")
            }
            nativeHandle = 0L
        }

        isInitialized = false
        modelPath = null
        progressCallback = null
        lastError = null
        AppLogger.i(TAG, "StableAudioEngine released")
    }
}
