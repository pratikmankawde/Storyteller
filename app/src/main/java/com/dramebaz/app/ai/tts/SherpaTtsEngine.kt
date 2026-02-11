package com.dramebaz.app.ai.tts

import android.content.Context
import com.dramebaz.app.data.models.VoiceProfile
import com.dramebaz.app.utils.AppLogger
import com.dramebaz.app.utils.DegradedModeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * SherpaTTS facade that delegates to the modular TTS engine architecture.
 *
 * This class maintains backward compatibility with existing code while
 * internally using the new TtsEngine abstraction (VitsPiperTtsEngine, KokoroTtsEngine, etc.).
 *
 * The actual TTS model used is determined by the TtsModelRegistry configuration.
 *
 * @see TtsEngine
 * @see TtsModelRegistry
 * @see TtsEngineFactory
 */
class SherpaTtsEngine(private val context: Context) {

    private val tag = "SherpaTtsEngine"

    // Modular TTS architecture components
    private val modelRegistry: TtsModelRegistry by lazy {
        TtsModelRegistry(context).also { it.init() }
    }
    private var currentEngine: TtsEngine? = null
    private var initialized = false

    // Legacy fields for backward compatibility
    private val sampleRate: Int
        get() = currentEngine?.getSampleRate() ?: 22050
    private val defaultSpeakerId = 0

    /**
     * Get the underlying TTS engine (for advanced use cases).
     */
    fun getCurrentEngine(): TtsEngine? = currentEngine

    /**
     * Get the model registry (for model switching UI).
     */
    fun getRegistry(): TtsModelRegistry = modelRegistry

    /**
     * Get info about the current model.
     */
    fun getModelInfo(): TtsModelInfo? = currentEngine?.getModelInfo()

    /**
     * Switch to a different TTS model.
     *
     * @param modelId The ID of the model to switch to
     * @return true if switch was successful
     */
    suspend fun switchModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!modelRegistry.selectModel(modelId)) {
                AppLogger.e(tag, "Cannot switch to unknown model: $modelId")
                return@withContext false
            }

            // Release current engine
            currentEngine?.release()
            currentEngine = null
            initialized = false

            // Initialize new engine
            val success = init()
            if (success) {
                AppLogger.i(tag, "Switched to model: $modelId")
            } else {
                AppLogger.e(tag, "Failed to switch to model: $modelId")
            }
            success
        } catch (e: Exception) {
            AppLogger.e(tag, "Error switching model", e)
            false
        }
    }

    /**
     * AUG-034: Get cache statistics for debugging/settings display.
     */
    fun getCacheStats(): Pair<Int, Long> = currentEngine?.getCacheStats() ?: (0 to 0L)

    /**
     * AUG-034: Clear entire audio cache (user-triggered).
     */
    fun clearCache() {
        currentEngine?.clearCache()
        // Also clear legacy cache
        val legacyCacheDir = File(context.cacheDir, "tts_audio_cache")
        try {
            legacyCacheDir.listFiles()?.forEach { it.delete() }
            AppLogger.i(tag, "Legacy audio cache cleared")
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to clear legacy cache", e)
        }
    }

    fun init(): Boolean {
        return try {
            if (initialized && currentEngine?.isInitialized() == true) {
                AppLogger.d(tag, "SherpaTTS facade already initialized")
                return true
            }

            AppLogger.i(tag, "Initializing SherpaTTS facade with modular architecture...")
            val startTime = System.currentTimeMillis()

            // Get selected model from registry
            val selectedConfig = modelRegistry.getSelectedModel()
            if (selectedConfig == null) {
                AppLogger.e(tag, "No TTS model selected in registry")
                DegradedModeManager.setTtsMode(
                    DegradedModeManager.TtsMode.DISABLED,
                    "No TTS model configured"
                )
                return false
            }

            AppLogger.i(tag, "Selected model: ${selectedConfig.displayName} (${selectedConfig.id})")

            // Update SpeakerMatcher to use the correct speaker catalog for this model
            SpeakerMatcher.activeModelId = selectedConfig.id
            AppLogger.d(tag, "SpeakerMatcher configured for model: ${selectedConfig.id}")

            // Create and initialize engine via factory
            currentEngine = TtsEngineFactory.createAndInitialize(context, selectedConfig)

            if (currentEngine == null) {
                AppLogger.e(tag, "Failed to create/initialize TTS engine")
                DegradedModeManager.setTtsMode(
                    DegradedModeManager.TtsMode.DISABLED,
                    "Failed to initialize TTS engine"
                )
                return false
            }

            initialized = true
            AppLogger.logPerformance(tag, "SherpaTTS facade initialization", System.currentTimeMillis() - startTime)
            AppLogger.i(tag, "SherpaTTS facade initialized with: ${currentEngine?.getModelInfo()?.displayName}")
            DegradedModeManager.setTtsMode(DegradedModeManager.TtsMode.FULL)
            true
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to initialize SherpaTTS facade", e)
            DegradedModeManager.setTtsMode(
                DegradedModeManager.TtsMode.DISABLED,
                e.message ?: "Failed to initialize TTS engine"
            )
            false
        }
    }

    fun isInitialized(): Boolean = initialized && currentEngine?.isInitialized() == true

    /**
     * AUG-041: Retry initializing the TTS engine.
     * Call this if user wants to retry after a failure.
     * Returns true if initialization was successful.
     */
    suspend fun retryInit(): Boolean = withContext(Dispatchers.IO) {
        AppLogger.i(tag, "Retrying TTS engine initialization...")
        // Release any partial state
        release()
        // Try to initialize again
        val success = init()
        if (success) {
            AppLogger.i(tag, "TTS retry successful")
        } else {
            AppLogger.w(tag, "TTS retry failed")
        }
        success
    }

    /**
     * Release TTS resources and reset state.
     */
    fun release() {
        currentEngine?.release()
        currentEngine = null
        initialized = false
        DegradedModeManager.setTtsMode(DegradedModeManager.TtsMode.NOT_INITIALIZED)
        AppLogger.d(tag, "TTS facade released")
    }

    /**
     * Synthesize speech from text (delegates to current engine).
     */
    suspend fun speak(
        text: String,
        voiceProfile: VoiceProfile?,
        onComplete: (() -> Unit)? = null,
        speakerId: Int? = null
    ): Result<File?> = withContext(Dispatchers.IO) {
        if (text.isBlank()) {
            AppLogger.w(tag, "Empty text provided, returning failure")
            return@withContext Result.failure(IllegalArgumentException("Text cannot be empty"))
        }

        if (!initialized && !init()) {
            AppLogger.e(tag, "TTS engine not initialized")
            return@withContext Result.failure(Exception("TTS engine not initialized"))
        }

        val engine = currentEngine
        if (engine == null) {
            AppLogger.e(tag, "TTS engine is null")
            return@withContext Result.failure(Exception("TTS engine not available"))
        }

        val startTime = System.currentTimeMillis()
        try {
            val modelInfo = engine.getModelInfo()
            AppLogger.d(tag, "Synthesizing via ${modelInfo.displayName}: textLength=${text.length}")

            val result = engine.speak(text, voiceProfile, speakerId, onComplete)

            AppLogger.logPerformance(tag, "TTS synthesis (${modelInfo.modelType})", System.currentTimeMillis() - startTime)
            result
        } catch (e: Exception) {
            AppLogger.e(tag, "Error during speech synthesis", e)
            onComplete?.invoke()
            Result.failure(e)
        }
    }

    /**
     * Get the number of available speakers/voices in the current model.
     */
    fun getSpeakerCount(): Int = currentEngine?.getSpeakerCount() ?: 904

    fun stop() {
        // Stop any ongoing synthesis
        AppLogger.d(tag, "Stop requested")
    }

    fun cleanup() {
        try {
            release()
            TtsEngineFactory.releaseAllEngines()
            AppLogger.d(tag, "SherpaTTS facade cleaned up")
        } catch (e: Exception) {
            AppLogger.e(tag, "Error during cleanup", e)
        }
    }
}
