package com.dramebaz.app.ai.tts

import com.dramebaz.app.data.models.VoiceProfile
import java.io.File

/**
 * Interface defining the contract for all TTS (Text-to-Speech) engines.
 * 
 * This abstraction allows easy switching between different TTS models
 * (VITS-Piper, Kokoro, etc.) without changing the calling code.
 * 
 * Implementations should handle:
 * - Model loading and initialization
 * - Speech synthesis with voice profiles
 * - Audio caching for performance
 * - Resource cleanup
 */
interface TtsEngine {
    
    /**
     * Initialize the TTS engine and load the model.
     * @return true if initialization was successful, false otherwise
     */
    fun init(): Boolean
    
    /**
     * Check if the engine is initialized and ready for synthesis.
     */
    fun isInitialized(): Boolean
    
    /**
     * Release all resources held by the engine.
     * After calling this, init() must be called again before use.
     */
    fun release()
    
    /**
     * Retry initialization after a failure.
     * @return true if retry was successful
     */
    suspend fun retryInit(): Boolean
    
    /**
     * Synthesize speech from text.
     * 
     * @param text The text to synthesize
     * @param voiceProfile Optional voice profile for customization
     * @param speakerId Optional speaker ID (model-specific)
     * @param onComplete Optional callback when synthesis completes
     * @return Result containing the audio file, or failure with exception
     */
    suspend fun speak(
        text: String,
        voiceProfile: VoiceProfile? = null,
        speakerId: Int? = null,
        onComplete: (() -> Unit)? = null
    ): Result<File?>
    
    /**
     * Get the sample rate of the audio produced by this engine.
     */
    fun getSampleRate(): Int
    
    /**
     * Get the number of speakers/voices available in this model.
     */
    fun getSpeakerCount(): Int
    
    /**
     * Get information about the loaded model.
     */
    fun getModelInfo(): TtsModelInfo
    
    /**
     * Get cache statistics (file count, total size in bytes).
     */
    fun getCacheStats(): Pair<Int, Long>
    
    /**
     * Clear the audio cache.
     */
    fun clearCache()
}

/**
 * Information about a TTS model.
 */
data class TtsModelInfo(
    /** Unique identifier for the model */
    val id: String,
    
    /** Human-readable display name */
    val displayName: String,
    
    /** Model type (e.g., "vits-piper", "kokoro") */
    val modelType: String,
    
    /** Number of available speakers/voices */
    val speakerCount: Int,
    
    /** Audio sample rate in Hz */
    val sampleRate: Int,
    
    /** Whether the model is loaded from external storage */
    val isExternal: Boolean = false,
    
    /** Path to the model files */
    val modelPath: String = "",
    
    /** Additional model-specific metadata */
    val metadata: Map<String, String> = emptyMap()
)

