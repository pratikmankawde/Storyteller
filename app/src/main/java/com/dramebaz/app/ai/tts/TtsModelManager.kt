package com.dramebaz.app.ai.tts

import android.content.Context
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manager for TTS model discovery, loading, and switching.
 * 
 * Handles:
 * - Discovering model type from folder contents (MODEL_CARD, file patterns)
 * - Stopping ongoing TTS processing before switching
 * - Unloading existing model and loading new model
 * - Registering external models with TtsModelRegistry
 */
class TtsModelManager(private val context: Context) {
    
    companion object {
        private const val TAG = "TtsModelManager"
        
        // Known model file patterns
        private const val MODEL_CARD_FILE = "MODEL_CARD"
        private const val MODEL_ONNX_SUFFIX = ".onnx"
        private const val TOKENS_FILE = "tokens.txt"
        private const val VOICES_BIN_FILE = "voices.bin"
        private const val ESPEAK_DIR = "espeak-ng-data"
        
        // MODEL_CARD content patterns for type detection
        private const val KOKORO_PATTERN = "kokoro"
        private const val VITS_PATTERN = "vits"
        private const val PIPER_PATTERN = "piper"
    }
    
    private val registry = TtsModelRegistry(context).also { it.init() }
    
    /**
     * Discovered model information from folder analysis.
     */
    data class DiscoveredModel(
        val folderPath: String,
        val modelType: ModelType,
        val modelName: String,
        val modelOnnxPath: String,
        val tokensPath: String?,
        val voicesPath: String?,
        val espeakDataPath: String?,
        val detectedFrom: String // How the model type was detected
    )
    
    enum class ModelType {
        KOKORO,
        VITS_PIPER,
        UNKNOWN
    }
    
    /**
     * Discover model type and files from a folder.
     * 
     * @param folderPath Path to folder containing TTS model files
     * @return DiscoveredModel if valid model found, null otherwise
     */
    suspend fun discoverModelFromFolder(folderPath: String): DiscoveredModel? = withContext(Dispatchers.IO) {
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) {
            AppLogger.w(TAG, "Invalid folder path: $folderPath")
            return@withContext null
        }
        
        val files = folder.listFiles() ?: return@withContext null
        
        // Find ONNX model file
        val onnxFile = files.find { it.name.endsWith(MODEL_ONNX_SUFFIX) }
        if (onnxFile == null) {
            AppLogger.w(TAG, "No .onnx model file found in: $folderPath")
            return@withContext null
        }
        
        // Find other required files
        val tokensFile = files.find { it.name == TOKENS_FILE }
        val voicesFile = files.find { it.name == VOICES_BIN_FILE }
        val espeakDir = files.find { it.name == ESPEAK_DIR && it.isDirectory }
        val modelCardFile = files.find { it.name == MODEL_CARD_FILE }
        
        // Detect model type
        val (modelType, detectedFrom) = detectModelType(modelCardFile, onnxFile, voicesFile)
        
        // Generate model name from folder name
        val modelName = folder.name.replace("-", " ").replace("_", " ")
            .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        
        DiscoveredModel(
            folderPath = folderPath,
            modelType = modelType,
            modelName = modelName,
            modelOnnxPath = onnxFile.absolutePath,
            tokensPath = tokensFile?.absolutePath,
            voicesPath = voicesFile?.absolutePath,
            espeakDataPath = espeakDir?.absolutePath,
            detectedFrom = detectedFrom
        ).also {
            AppLogger.i(TAG, "Discovered model: $modelName (${modelType.name}) from $detectedFrom")
        }
    }
    
    private fun detectModelType(
        modelCardFile: File?,
        onnxFile: File,
        voicesFile: File?
    ): Pair<ModelType, String> {
        // First, check MODEL_CARD file
        if (modelCardFile?.exists() == true) {
            val cardContent = try {
                modelCardFile.readText().lowercase()
            } catch (e: Exception) { "" }

            when {
                cardContent.contains(KOKORO_PATTERN) ->
                    return Pair(ModelType.KOKORO, "MODEL_CARD contains 'kokoro'")
                cardContent.contains(VITS_PATTERN) || cardContent.contains(PIPER_PATTERN) ->
                    return Pair(ModelType.VITS_PIPER, "MODEL_CARD contains 'vits/piper'")
            }
        }

        // Second, check ONNX filename
        val onnxName = onnxFile.name.lowercase()
        when {
            onnxName.contains(KOKORO_PATTERN) ->
                return Pair(ModelType.KOKORO, "ONNX filename contains 'kokoro'")
            onnxName.contains(VITS_PATTERN) || onnxName.contains(PIPER_PATTERN) || onnxName.contains("libritts") ->
                return Pair(ModelType.VITS_PIPER, "ONNX filename contains 'vits/piper/libritts'")
        }

        // Third, check for voices.bin (Kokoro-specific)
        if (voicesFile?.exists() == true) {
            return Pair(ModelType.KOKORO, "voices.bin file present (Kokoro indicator)")
        }

        // Default to VITS-Piper for unknown
        return Pair(ModelType.VITS_PIPER, "Default (no specific indicators found)")
    }

    /**
     * Create a TtsModelConfig from a discovered model.
     */
    fun createConfigFromDiscoveredModel(discovered: DiscoveredModel): TtsModelConfig {
        val id = "external-" + File(discovered.folderPath).name.lowercase().replace(" ", "-")

        return when (discovered.modelType) {
            ModelType.KOKORO -> TtsModelConfig.Kokoro(
                id = id,
                displayName = discovered.modelName,
                isExternal = true,
                sampleRate = 24000,  // Kokoro default
                modelPath = discovered.modelOnnxPath,
                tokensPath = discovered.tokensPath ?: "",
                voicesPath = discovered.voicesPath ?: "",
                espeakDataPath = discovered.espeakDataPath ?: "",
                voiceCount = 10,  // Default, will be updated when loaded
                defaultVoiceId = 0,
                defaultSpeed = 1.0f
            )
            ModelType.VITS_PIPER, ModelType.UNKNOWN -> TtsModelConfig.VitsPiper(
                id = id,
                displayName = discovered.modelName,
                isExternal = true,
                sampleRate = 22050,  // VITS-Piper default
                modelPath = discovered.modelOnnxPath,
                tokensPath = discovered.tokensPath ?: "",
                espeakDataPath = discovered.espeakDataPath ?: "",
                speakerCount = 1,  // Default for single-speaker
                defaultSpeakerId = 0
            )
        }
    }

    /**
     * Result of model loading operation.
     */
    sealed class LoadResult {
        data class Success(val modelInfo: TtsModelInfo) : LoadResult()
        data class Error(val message: String, val exception: Exception? = null) : LoadResult()
    }

    /**
     * Load and switch to a model from the discovered model info.
     *
     * Steps:
     * 1. Stop any ongoing TTS processing
     * 2. Unload the existing model
     * 3. Register the new model with the registry
     * 4. Load and initialize the new model
     *
     * @param ttsEngine The SherpaTtsEngine facade to use for switching
     * @param discovered The discovered model to load
     * @return LoadResult indicating success or failure
     */
    suspend fun loadAndSwitchModel(
        ttsEngine: SherpaTtsEngine,
        discovered: DiscoveredModel
    ): LoadResult = withContext(Dispatchers.IO) {
        try {
            AppLogger.i(TAG, "Starting model switch to: ${discovered.modelName}")

            // Step 1: Stop any ongoing TTS processing
            AppLogger.d(TAG, "Stopping ongoing TTS processing...")
            ttsEngine.stop()

            // Step 2: Create config from discovered model
            val newConfig = createConfigFromDiscoveredModel(discovered)
            AppLogger.d(TAG, "Created config: ${newConfig.id} (${newConfig.displayName})")

            // Step 3: Register the external model with registry
            val registered = registry.registerExternalModel(newConfig)
            if (!registered) {
                return@withContext LoadResult.Error("Failed to register model with registry")
            }

            // Step 4: Switch to the new model (this releases old engine and initializes new one)
            val switched = ttsEngine.switchModel(newConfig.id)
            if (!switched) {
                return@withContext LoadResult.Error("Failed to switch to new model")
            }

            // Get model info for result
            val modelInfo = ttsEngine.getModelInfo()
            if (modelInfo == null) {
                return@withContext LoadResult.Error("Model loaded but info unavailable")
            }

            AppLogger.i(TAG, "Model switch successful: ${modelInfo.displayName}")
            LoadResult.Success(modelInfo)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error during model switch", e)
            LoadResult.Error("Model switch failed: ${e.message}", e)
        }
    }

    /**
     * Get the list of available models (both built-in and external).
     */
    fun getAvailableModels(): List<TtsModelConfig> = registry.getAvailableModels()

    /**
     * Get the currently selected model.
     */
    fun getSelectedModel(): TtsModelConfig? = registry.getSelectedModel()
}

