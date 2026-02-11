package com.dramebaz.app.ai.audio

/**
 * Root configuration for Stable Audio models.
 * Read from external config file or set programmatically.
 */
data class StableAudioConfigRoot(
    /** Directory path containing the TFLite model files */
    val modelDirectory: String,
    /** Whether AI generation is enabled */
    val enabled: Boolean = true,
    /** Model-specific inference configuration */
    val inferenceConfig: StableAudioInferenceConfig = StableAudioInferenceConfig()
)

/**
 * Inference configuration for Stable Audio generation.
 */
data class StableAudioInferenceConfig(
    /** Number of diffusion inference steps (default: 100) */
    val numInferenceSteps: Int = 100,
    /** Maximum audio duration in seconds (max: 11.0) */
    val maxDurationSeconds: Float = 11.0f,
    /** Default audio duration if not specified (seconds) */
    val defaultDurationSeconds: Float = 5.0f,
    /** Number of CPU threads for inference */
    val numThreads: Int = 4,
    /** Whether to use GPU delegate for acceleration (default: false - CPU is default for faster init) */
    val useGpu: Boolean = false
) {
    companion object {
        /** Fast inference with fewer steps (lower quality but faster) */
        val FAST = StableAudioInferenceConfig(
            numInferenceSteps = 50,
            defaultDurationSeconds = 3.0f,
            useGpu = false
        )

        /** High quality inference (slower but better results) */
        val HIGH_QUALITY = StableAudioInferenceConfig(
            numInferenceSteps = 150,
            defaultDurationSeconds = 5.0f,
            useGpu = false
        )

        /** GPU-accelerated inference (requires longer first-time init for shader compilation) */
        val GPU_ACCELERATED = StableAudioInferenceConfig(
            numInferenceSteps = 100,
            defaultDurationSeconds = 5.0f,
            useGpu = true
        )
    }
}

/**
 * Information about required model files for Stable Audio.
 */
object StableAudioModelFiles {
    /** Conditioners model (T5 encoder + number conditioner) */
	    const val CONDITIONERS = "conditioners_int8.tflite"
    
    /** DiT (Diffusion Transformer) model */
    const val DIT = "dit_model_int8.tflite"
    
    /** AutoEncoder decoder model */
	    const val AUTOENCODER = "autoencoder_model_int8.tflite"
    
    /** SentencePiece tokenizer model */
    const val TOKENIZER = "spiece.model"

    /** All required model files */
    val ALL_FILES = listOf(CONDITIONERS, DIT, AUTOENCODER, TOKENIZER)

    /** Estimated total model size in bytes (~550MB) */
    const val ESTIMATED_TOTAL_SIZE_BYTES = 550_000_000L

    /** Check if all required model files exist in the directory */
    fun verifyModelFiles(directory: java.io.File): Boolean {
        return ALL_FILES.all { fileName ->
            java.io.File(directory, fileName).exists()
        }
    }

    /** Get list of missing model files in the directory */
    fun getMissingFiles(directory: java.io.File): List<String> {
        return ALL_FILES.filter { fileName ->
            !java.io.File(directory, fileName).exists()
        }
    }
}

