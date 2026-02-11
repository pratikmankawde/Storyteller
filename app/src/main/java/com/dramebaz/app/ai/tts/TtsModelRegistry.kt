package com.dramebaz.app.ai.tts

import android.content.Context
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Registry for discovering and managing available TTS models.
 * 
 * Supports:
 * - Built-in models bundled in app assets
 * - External models from device storage
 * - Model selection persistence via SharedPreferences
 */
class TtsModelRegistry(private val context: Context) {
    
    private val tag = "TtsModelRegistry"
    private val gson = Gson()
    private val prefs = context.getSharedPreferences("tts_model_prefs", Context.MODE_PRIVATE)
    
    private val models = mutableMapOf<String, TtsModelConfig>()
    private var selectedModelId: String? = null
    
    companion object {
        private const val PREF_SELECTED_MODEL = "selected_model_id"
        private const val CONFIG_ASSET_PATH = "tts/tts_model_config.json"
        
        // Default built-in model IDs
        const val MODEL_LIBRITTS = "libritts-en-us-high"
        const val MODEL_KOKORO_EXTERNAL = "kokoro-int8-en"
    }
    
    /**
     * Initialize the registry by loading available models.
     */
    fun init(): Boolean {
        return try {
            AppLogger.i(tag, "Initializing TTS model registry...")
            
            // Load models from config file
            loadModelsFromConfig()
            
            // Load saved model selection
            selectedModelId = prefs.getString(PREF_SELECTED_MODEL, null)
            if (selectedModelId != null && !models.containsKey(selectedModelId)) {
                AppLogger.w(tag, "Saved model $selectedModelId not found, resetting selection")
                selectedModelId = null
            }
            
            // Default to first available model if none selected
            if (selectedModelId == null && models.isNotEmpty()) {
                selectedModelId = models.keys.first()
                AppLogger.i(tag, "No model selected, defaulting to: $selectedModelId")
            }
            
            AppLogger.i(tag, "Registry initialized with ${models.size} models, selected: $selectedModelId")
            true
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to initialize registry", e)
            false
        }
    }
    
    /**
     * Load models from the JSON configuration file.
     */
    private fun loadModelsFromConfig() {
        try {
            // Try to load from assets
            val configJson = try {
                context.assets.open(CONFIG_ASSET_PATH).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                AppLogger.w(tag, "Config file not found in assets, using defaults")
                getDefaultConfigJson()
            }
            
            val configType = object : TypeToken<TtsConfigFile>() {}.type
            val configFile: TtsConfigFile = gson.fromJson(configJson, configType)
            
            for (modelJson in configFile.models) {
                try {
                    // Validate external models exist
                    if (modelJson.isExternal && !validateExternalModel(modelJson)) {
                        AppLogger.w(tag, "External model not found, skipping: ${modelJson.id}")
                        continue
                    }
                    
                    val config = modelJson.toModelConfig()
                    models[config.id] = config
                    AppLogger.d(tag, "Registered model: ${config.id} (${config.displayName})")
                } catch (e: Exception) {
                    AppLogger.e(tag, "Failed to load model config: ${modelJson.id}", e)
                }
            }
            
            // Set selected model from config if not already set
            if (selectedModelId == null && configFile.selectedModelId != null) {
                selectedModelId = configFile.selectedModelId
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to load models from config", e)
            // Register default built-in model
            registerDefaultModel()
        }
    }
    
    private fun validateExternalModel(modelJson: TtsModelConfigJson): Boolean {
        return File(modelJson.modelPath).exists()
    }
    
    private fun registerDefaultModel() {
        val defaultConfig = TtsModelConfig.VitsPiper(
            id = MODEL_LIBRITTS,
            displayName = "LibriTTS English (High)",
            isExternal = false,
            sampleRate = 22050,
            modelPath = "tts/en_US-libritts-high.onnx",
            tokensPath = "tts/tokens.txt",
            espeakDataPath = "tts/espeak-ng-data",
            speakerCount = 904,
            defaultSpeakerId = 0
        )
        models[defaultConfig.id] = defaultConfig
        selectedModelId = defaultConfig.id
    }
    
    private fun getDefaultConfigJson(): String {
        return """
        {
            "models": [
                {
                    "id": "libritts-en-us-high",
                    "displayName": "LibriTTS English (High)",
                    "modelType": "vits-piper",
                    "isExternal": false,
                    "sampleRate": 22050,
                    "modelPath": "tts/en_US-libritts-high.onnx",
                    "tokensPath": "tts/tokens.txt",
                    "espeakDataPath": "tts/espeak-ng-data",
                    "speakerCount": 904,
                    "defaultSpeakerId": 0
                }
            ],
            "selectedModelId": "libritts-en-us-high"
        }
        """.trimIndent()
    }
    
    fun getAvailableModels(): List<TtsModelConfig> = models.values.toList()

    fun getModel(id: String): TtsModelConfig? = models[id]

    fun getSelectedModel(): TtsModelConfig? = selectedModelId?.let { models[it] }

    fun getSelectedModelId(): String? = selectedModelId

    /**
     * Select a model by ID and persist the selection.
     */
    fun selectModel(modelId: String): Boolean {
        if (!models.containsKey(modelId)) {
            AppLogger.w(tag, "Cannot select unknown model: $modelId")
            return false
        }
        selectedModelId = modelId
        prefs.edit().putString(PREF_SELECTED_MODEL, modelId).apply()
        AppLogger.i(tag, "Selected model: $modelId")
        return true
    }

    /**
     * Register an external model at runtime.
     */
    fun registerExternalModel(config: TtsModelConfig): Boolean {
        return try {
            if (config.isExternal) {
                val modelPath = when (config) {
                    is TtsModelConfig.VitsPiper -> config.modelPath
                    is TtsModelConfig.Kokoro -> config.modelPath
                }
                if (!File(modelPath).exists()) {
                    AppLogger.e(tag, "External model file not found: $modelPath")
                    return false
                }
            }
            models[config.id] = config
            AppLogger.i(tag, "Registered external model: ${config.id}")
            true
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to register external model", e)
            false
        }
    }

    /**
     * Unregister a model.
     */
    fun unregisterModel(modelId: String) {
        if (models.remove(modelId) != null) {
            AppLogger.i(tag, "Unregistered model: $modelId")
            if (selectedModelId == modelId) {
                selectedModelId = models.keys.firstOrNull()
                prefs.edit().putString(PREF_SELECTED_MODEL, selectedModelId).apply()
            }
        }
    }
}

/**
 * JSON structure for the TTS config file.
 */
data class TtsConfigFile(
    val models: List<TtsModelConfigJson>,
    val selectedModelId: String? = null
)

