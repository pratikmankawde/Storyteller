package com.dramebaz.app.ai.tts

import android.content.Context
import com.dramebaz.app.utils.AppLogger

/**
 * Factory for creating TTS engine instances based on model configuration.
 * 
 * This factory abstracts the creation of different TTS engine types,
 * allowing easy switching between models without changing calling code.
 */
object TtsEngineFactory {
    
    private const val TAG = "TtsEngineFactory"
    
    // Cache of created engines to avoid recreating for the same model
    private val engineCache = mutableMapOf<String, TtsEngine>()
    
    /**
     * Create or retrieve a TTS engine for the given model configuration.
     * 
     * @param context Application context
     * @param config Model configuration
     * @param forceRecreate If true, creates a new engine even if cached
     * @return TTS engine instance, or null if creation failed
     */
    fun createEngine(
        context: Context,
        config: TtsModelConfig,
        forceRecreate: Boolean = false
    ): TtsEngine? {
        // Check cache first
        if (!forceRecreate && engineCache.containsKey(config.id)) {
            val cached = engineCache[config.id]
            if (cached != null && cached.isInitialized()) {
                AppLogger.d(TAG, "Returning cached engine for ${config.id}")
                return cached
            }
        }
        
        // Release any existing cached engine for this config
        engineCache[config.id]?.release()
        
        // Create new engine based on config type
        val engine = when (config) {
            is TtsModelConfig.VitsPiper -> {
                AppLogger.i(TAG, "Creating VITS-Piper engine: ${config.displayName}")
                VitsPiperTtsEngine(context, config)
            }
            is TtsModelConfig.Kokoro -> {
                AppLogger.i(TAG, "Creating Kokoro engine: ${config.displayName}")
                KokoroTtsEngine(context, config)
            }
        }
        
        // Cache the engine
        engineCache[config.id] = engine
        
        return engine
    }
    
    /**
     * Create and initialize a TTS engine for the given model.
     * 
     * @param context Application context
     * @param config Model configuration
     * @return Initialized TTS engine, or null if initialization failed
     */
    fun createAndInitialize(
        context: Context,
        config: TtsModelConfig
    ): TtsEngine? {
        val engine = createEngine(context, config) ?: return null
        
        return if (engine.init()) {
            AppLogger.i(TAG, "Engine initialized successfully: ${config.id}")
            engine
        } else {
            AppLogger.e(TAG, "Failed to initialize engine: ${config.id}")
            engineCache.remove(config.id)
            null
        }
    }
    
    /**
     * Create engine from registry's selected model.
     * 
     * @param context Application context
     * @param registry Model registry
     * @return TTS engine for the selected model, or null if none available
     */
    fun createFromRegistry(
        context: Context,
        registry: TtsModelRegistry
    ): TtsEngine? {
        val selectedConfig = registry.getSelectedModel()
        if (selectedConfig == null) {
            AppLogger.w(TAG, "No model selected in registry")
            return null
        }
        return createEngine(context, selectedConfig)
    }
    
    /**
     * Get a cached engine by model ID.
     */
    fun getCachedEngine(modelId: String): TtsEngine? = engineCache[modelId]
    
    /**
     * Release a specific engine by model ID.
     */
    fun releaseEngine(modelId: String) {
        engineCache[modelId]?.let { engine ->
            engine.release()
            engineCache.remove(modelId)
            AppLogger.d(TAG, "Released engine: $modelId")
        }
    }
    
    /**
     * Release all cached engines.
     */
    fun releaseAllEngines() {
        AppLogger.i(TAG, "Releasing all engines (${engineCache.size} cached)")
        engineCache.values.forEach { it.release() }
        engineCache.clear()
    }
    
    /**
     * Get statistics about cached engines.
     */
    fun getCacheStats(): Map<String, Pair<Boolean, TtsModelInfo>> {
        return engineCache.mapValues { (_, engine) ->
            engine.isInitialized() to engine.getModelInfo()
        }
    }
}

