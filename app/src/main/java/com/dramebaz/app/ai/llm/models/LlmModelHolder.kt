package com.dramebaz.app.ai.llm.models

import android.content.Context
import com.dramebaz.app.data.models.LlmBackend
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Singleton holder for the LLM model instance.
 *
 * This ensures only ONE model is loaded in GPU memory at a time,
 * preventing memory exhaustion crashes when multiple components
 * (AnalysisExecutor, LlmService, Settings) try to use the LLM.
 *
 * Usage:
 * ```
 * // Get or load the shared model
 * val model = LlmModelHolder.getOrLoadModel(context)
 *
 * // Release when app is backgrounded or model switch is needed
 * LlmModelHolder.release()
 * ```
 */
object LlmModelHolder {
    private const val TAG = "LlmModelHolder"

    private var sharedModel: LlmModel? = null
    private val modelMutex = Mutex()

    // Track if the model was explicitly released (for reload scenarios)
    private var wasReleased = false

    // Track if a model switch is in progress - components should stop using the model
    private val _isSwitching = MutableStateFlow(false)
    val isSwitching: StateFlow<Boolean> = _isSwitching.asStateFlow()

    // Callbacks for model switch notifications
    private val switchListeners = mutableListOf<() -> Unit>()
    
    /**
     * Get the shared LLM model, loading it if necessary.
     * 
     * @param context Android context for model loading
     * @return Loaded LlmModel instance, or null if loading failed
     */
    suspend fun getOrLoadModel(context: Context): LlmModel? = modelMutex.withLock {
        // Return existing model if loaded
        sharedModel?.let {
            if (it.isModelLoaded()) {
                AppLogger.d(TAG, "Returning existing shared model")
                return@withLock it
            }
        }
        
        // Load new model
        AppLogger.i(TAG, "Loading shared LLM model...")
        wasReleased = false
        
        return@withLock withContext(Dispatchers.IO) {
            try {
                val model = LlmModelFactory.createDefaultModel(context)
                if (model == null) {
                    AppLogger.e(TAG, "Failed to create LLM model - no model files found")
                    return@withContext null
                }
                
                model.loadModel()
                
                if (model.isModelLoaded()) {
                    sharedModel = model
                    AppLogger.i(TAG, "✅ Shared model loaded: ${model.getExecutionProvider()}")
                    return@withContext model
                } else {
                    AppLogger.e(TAG, "Model loading failed - isModelLoaded returned false")
                    return@withContext null
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load shared model", e)
                return@withContext null
            }
        }
    }
    
    /**
     * Get the shared LLM model with specific backend preference.
     * This will release any existing model and load with the new backend.
     *
     * @param context Android context
     * @param backend Preferred backend (GPU/CPU)
     * @param modelPath Optional specific model path. If null, auto-selects first available.
     * @return Loaded LlmModel instance, or null if loading failed
     */
    suspend fun getOrLoadModelWithBackend(
        context: Context,
        backend: LlmBackend,
        modelPath: String? = null
    ): LlmModel? = modelMutex.withLock {
        // Release existing model first
        sharedModel?.let {
            AppLogger.i(TAG, "Releasing existing model for backend switch to $backend")
            it.release()
            sharedModel = null
        }

        wasReleased = false

        return@withLock withContext(Dispatchers.IO) {
            try {
                val model: LlmModel? = if (!modelPath.isNullOrEmpty()) {
                    // Use specific model path if provided
                    AppLogger.i(TAG, "Loading specific model from path: $modelPath")
                    LlmModelFactory.createModelFromPath(context, modelPath, backend)
                } else {
                    // Auto-select: find the first available model type
                    // Priority: MediaPipe > LiteRT-LM > GGUF
                    val modelType = when {
                        LlmModelFactory.isMediaPipeModelAvailable(context) -> LlmModelFactory.ModelType.MEDIAPIPE
                        LlmModelFactory.isLiteRtLmModelAvailable(context) -> LlmModelFactory.ModelType.LITERTLM
                        LlmModelFactory.isGgufModelAvailable(context) -> LlmModelFactory.ModelType.GGUF
                        else -> null
                    }

                    if (modelType == null) {
                        AppLogger.e(TAG, "No model files available")
                        return@withContext null
                    }

                    LlmModelFactory.createModelWithBackend(context, modelType, backend)
                }

                if (model == null) {
                    AppLogger.e(TAG, "Failed to create model with backend $backend, path=$modelPath")
                    return@withContext null
                }

                model.loadModel()

                if (model.isModelLoaded()) {
                    sharedModel = model
                    AppLogger.i(TAG, "✅ Shared model loaded with $backend: ${model.getExecutionProvider()}")
                    return@withContext model
                } else {
                    AppLogger.e(TAG, "Model loading failed with backend $backend")
                    return@withContext null
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load model with backend $backend, path=$modelPath", e)
                return@withContext null
            }
        }
    }
    
    /**
     * Get the currently loaded model without loading a new one.
     * 
     * @return The loaded model if available, null otherwise
     */
    fun getLoadedModel(): LlmModel? = sharedModel?.takeIf { it.isModelLoaded() }
    
    /**
     * Check if a model is currently loaded.
     */
    fun isModelLoaded(): Boolean = sharedModel?.isModelLoaded() == true

    /**
     * Begin a model switch operation.
     * This notifies all listeners to stop using the current model.
     * Call this BEFORE releasing the model.
     */
    fun beginModelSwitch() {
        AppLogger.i(TAG, "🔄 Beginning model switch - notifying listeners")
        _isSwitching.value = true
        // Notify all listeners
        switchListeners.forEach { listener ->
            try {
                listener()
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error notifying switch listener", e)
            }
        }
    }

    /**
     * End a model switch operation.
     * Call this AFTER the new model is loaded.
     */
    fun endModelSwitch() {
        AppLogger.i(TAG, "✅ Model switch complete")
        _isSwitching.value = false
    }

    /**
     * Register a listener to be notified when a model switch begins.
     * The listener should clear any cached model references.
     */
    fun addSwitchListener(listener: () -> Unit) {
        switchListeners.add(listener)
    }

    /**
     * Remove a previously registered switch listener.
     */
    fun removeSwitchListener(listener: () -> Unit) {
        switchListeners.remove(listener)
    }

    /**
     * Release the shared model. Call when switching models or when app goes to background.
     */
    fun release() {
        sharedModel?.let {
            AppLogger.i(TAG, "Releasing shared model")
            it.release()
        }
        sharedModel = null
        wasReleased = true
    }

    /**
     * Check if the model was explicitly released (useful for reload scenarios).
     */
    fun wasExplicitlyReleased(): Boolean = wasReleased
}

