package com.dramebaz.app.ai.llm.models

import android.content.Context
import com.dramebaz.app.data.models.LlmBackend
import com.dramebaz.app.utils.AppLogger
import com.google.ai.edge.litertlm.Backend
import java.io.File

/**
 * Factory for creating LLM model instances.
 * Uses the Factory Pattern to abstract model creation and selection based on device capabilities.
 *
 * Supports dynamic model discovery by scanning for familiar model extensions:
 * - .litertlm - LiteRT-LM format (Gemma, Qwen, etc.) → Uses LiteRtLmEngineImpl
 * - .gguf - GGUF format for llama.cpp → Uses GgufEngineImpl
 *
 * The engines are file-type specific and can load any compatible model of that format.
 */
object LlmModelFactory {
    private const val TAG = "LlmModelFactory"

    // Supported model file extensions
    private val LITERTLM_EXTENSIONS = listOf(".litertlm")
    private val GGUF_EXTENSIONS = listOf(".gguf")
    private val MEDIAPIPE_EXTENSIONS = listOf(".task")

    // MediaPipe max tokens configuration
    private const val MEDIAPIPE_MAX_TOKENS = 8000

    // Download folder paths to scan for all model types
    private val DOWNLOAD_PATHS = listOf(
        "/storage/emulated/0/Download",
        "/sdcard/Download",
        "/storage/emulated/0/Download/LLM",
        "/sdcard/Download/LLM"
    )

    /**
     * Discovered model file info.
     */
    data class DiscoveredModel(
        val path: String,
        val fileName: String,
        val type: ModelType,
        val sizeBytes: Long
    ) {
        val sizeMB: Long get() = sizeBytes / (1024 * 1024)
        val displayName: String get() = fileName.substringBeforeLast(".")
    }

    /**
     * Model type enum for explicit model selection.
     * Based on file format/engine type rather than specific model names.
     */
    enum class ModelType {
        GGUF,           // GGUF format via llama.cpp (GgufEngine)
        LITERTLM,       // LiteRT-LM format (LiteRtLmEngine)
        MEDIAPIPE,      // MediaPipe .task format (MediaPipeEngineImpl)
        REMOTE_SERVER   // Remote AIServer via REST API
    }

    // Cache for discovered models
    private var cachedLiteRtLmModels: List<DiscoveredModel>? = null
    private var cachedGgufModels: List<DiscoveredModel>? = null
    private var cachedMediaPipeModels: List<DiscoveredModel>? = null

    /**
     * Scan download folders for LiteRT-LM model files (.litertlm).
     */
    fun discoverLiteRtLmModels(context: Context, forceRescan: Boolean = false): List<DiscoveredModel> {
        if (!forceRescan && cachedLiteRtLmModels != null) {
            return cachedLiteRtLmModels!!
        }

        val models = mutableListOf<DiscoveredModel>()

        // Scan download folders
        for (downloadPath in DOWNLOAD_PATHS) {
            val downloadDir = File(downloadPath)
            if (downloadDir.exists() && downloadDir.isDirectory) {
                downloadDir.listFiles()?.forEach { file ->
                    if (file.isFile && LITERTLM_EXTENSIONS.any { file.name.endsWith(it, ignoreCase = true) }) {
                        models.add(DiscoveredModel(
                            path = file.absolutePath,
                            fileName = file.name,
                            type = ModelType.LITERTLM,
                            sizeBytes = file.length()
                        ))
                        AppLogger.i(TAG, "Discovered LiteRT-LM model: ${file.name} (${file.length() / (1024*1024)} MB)")
                    }
                }
            }
        }

        // Also check app's files directory
        val appFilesDir = context.filesDir
        appFilesDir.listFiles()?.forEach { file ->
            if (file.isFile && LITERTLM_EXTENSIONS.any { file.name.endsWith(it, ignoreCase = true) }) {
                models.add(DiscoveredModel(
                    path = file.absolutePath,
                    fileName = file.name,
                    type = ModelType.LITERTLM,
                    sizeBytes = file.length()
                ))
                AppLogger.i(TAG, "Discovered LiteRT-LM model in app files: ${file.name}")
            }
        }

        cachedLiteRtLmModels = models
        AppLogger.i(TAG, "Total LiteRT-LM models discovered: ${models.size}")
        return models
    }

    /**
     * Scan download folders for GGUF model files (.gguf).
     */
    fun discoverGgufModels(context: Context, forceRescan: Boolean = false): List<DiscoveredModel> {
        if (!forceRescan && cachedGgufModels != null) {
            return cachedGgufModels!!
        }

        val models = mutableListOf<DiscoveredModel>()

        // Scan download folders
        for (downloadPath in DOWNLOAD_PATHS) {
            val downloadDir = File(downloadPath)
            if (downloadDir.exists() && downloadDir.isDirectory) {
                downloadDir.listFiles()?.forEach { file ->
                    if (file.isFile && GGUF_EXTENSIONS.any { file.name.endsWith(it, ignoreCase = true) }) {
                        models.add(DiscoveredModel(
                            path = file.absolutePath,
                            fileName = file.name,
                            type = ModelType.GGUF,
                            sizeBytes = file.length()
                        ))
                        AppLogger.i(TAG, "Discovered GGUF model: ${file.name} (${file.length() / (1024*1024)} MB)")
                    }
                }
            }
        }

        // Also check app's files directory
        val appFilesDir = context.filesDir
        appFilesDir.listFiles()?.forEach { file ->
            if (file.isFile && GGUF_EXTENSIONS.any { file.name.endsWith(it, ignoreCase = true) }) {
                models.add(DiscoveredModel(
                    path = file.absolutePath,
                    fileName = file.name,
                    type = ModelType.GGUF,
                    sizeBytes = file.length()
                ))
                AppLogger.i(TAG, "Discovered GGUF model in app files: ${file.name}")
            }
        }

        cachedGgufModels = models
        AppLogger.i(TAG, "Total GGUF models discovered: ${models.size}")
        return models
    }

    /**
     * Scan download folders for MediaPipe model files (.task).
     */
    fun discoverMediaPipeModels(context: Context, forceRescan: Boolean = false): List<DiscoveredModel> {
        if (!forceRescan && cachedMediaPipeModels != null) {
            return cachedMediaPipeModels!!
        }

        val models = mutableListOf<DiscoveredModel>()

        AppLogger.d(TAG, "Scanning for MediaPipe models in paths: $DOWNLOAD_PATHS")

        for (downloadPath in DOWNLOAD_PATHS) {
            val downloadDir = File(downloadPath)
            AppLogger.d(TAG, "Checking path: $downloadPath - exists=${downloadDir.exists()}, isDir=${downloadDir.isDirectory}, canRead=${downloadDir.canRead()}")

            if (downloadDir.exists() && downloadDir.isDirectory) {
                val files = downloadDir.listFiles()
                AppLogger.d(TAG, "  Files in $downloadPath: ${files?.size ?: "null (permission denied?)"}")
                files?.forEach { file ->
                    AppLogger.d(TAG, "    Found: ${file.name} (isFile=${file.isFile})")
                    if (file.isFile && MEDIAPIPE_EXTENSIONS.any { file.name.endsWith(it, ignoreCase = true) }) {
                        models.add(DiscoveredModel(
                            path = file.absolutePath,
                            fileName = file.name,
                            type = ModelType.MEDIAPIPE,
                            sizeBytes = file.length()
                        ))
                        AppLogger.i(TAG, "Discovered MediaPipe model: ${file.name} (${file.length() / (1024*1024)} MB)")
                    }
                }
            }
        }

        // Also check app's files directory
        val appFilesDir = context.filesDir
        appFilesDir.listFiles()?.forEach { file ->
            if (file.isFile && MEDIAPIPE_EXTENSIONS.any { file.name.endsWith(it, ignoreCase = true) }) {
                models.add(DiscoveredModel(
                    path = file.absolutePath,
                    fileName = file.name,
                    type = ModelType.MEDIAPIPE,
                    sizeBytes = file.length()
                ))
                AppLogger.i(TAG, "Discovered MediaPipe model in app files: ${file.name}")
            }
        }

        cachedMediaPipeModels = models
        AppLogger.i(TAG, "Total MediaPipe models discovered: ${models.size}")
        return models
    }

    /**
     * Discover all available models on the device.
     * Deduplicates by canonical path to avoid showing the same file twice
     * (e.g., /storage/emulated/0/Download and /sdcard/Download are often the same).
     */
    fun discoverAllModels(context: Context, forceRescan: Boolean = false): List<DiscoveredModel> {
        val liteRtLmModels = discoverLiteRtLmModels(context, forceRescan)
        val ggufModels = discoverGgufModels(context, forceRescan)
        val mediaPipeModels = discoverMediaPipeModels(context, forceRescan)

        // Deduplicate by canonical path to handle symlinks like /sdcard -> /storage/emulated/0
        val seen = mutableSetOf<String>()
        val deduplicated = mutableListOf<DiscoveredModel>()

        for (model in liteRtLmModels + ggufModels + mediaPipeModels) {
            try {
                val canonicalPath = File(model.path).canonicalPath
                if (canonicalPath !in seen) {
                    seen.add(canonicalPath)
                    deduplicated.add(model)
                } else {
                    AppLogger.d(TAG, "Skipping duplicate model: ${model.fileName} (same canonical path)")
                }
            } catch (e: Exception) {
                // If we can't get canonical path, use the original path
                if (model.path !in seen) {
                    seen.add(model.path)
                    deduplicated.add(model)
                }
            }
        }

        return deduplicated
    }

    /**
     * Get the first available LiteRT-LM model path.
     */
    fun getFirstLiteRtLmModelPath(context: Context): String? {
        val models = discoverLiteRtLmModels(context)
        return models.firstOrNull()?.path
    }

    /**
     * Get the first available GGUF model path.
     */
    fun getFirstGgufModelPath(context: Context): String? {
        val models = discoverGgufModels(context)
        return models.firstOrNull()?.path
    }

    /**
     * Get the first available MediaPipe model path.
     */
    fun getFirstMediaPipeModelPath(context: Context): String? {
        val models = discoverMediaPipeModels(context)
        return models.firstOrNull()?.path
    }

    /**
     * Clear the model cache to force re-discovery.
     */
    fun clearCache() {
        cachedLiteRtLmModels = null
        cachedGgufModels = null
        cachedMediaPipeModels = null
        AppLogger.d(TAG, "Model cache cleared")
    }

    /**
     * Create the default/preferred model based on available model files.
     * Priority: MediaPipe > LiteRT-LM > GGUF
     *
     * @param context Android context for file access
     * @return LlmModel instance, or null if no model is available
     */
    fun createDefaultModel(context: Context): LlmModel? {
        AppLogger.d(TAG, "Creating default LLM model...")

        // Check for MediaPipe models first (newest format, preferred)
        val mediaPipeModels = discoverMediaPipeModels(context)
        if (mediaPipeModels.isNotEmpty()) {
            val model = mediaPipeModels.first()
            AppLogger.i(TAG, "MediaPipe model available: ${model.fileName}, creating MediaPipeEngineImpl")
            return MediaPipeEngineImpl(context, modelPath = model.path, maxTokens = MEDIAPIPE_MAX_TOKENS)
        }

        // Check for LiteRT-LM models second
        val liteRtLmModels = discoverLiteRtLmModels(context)
        if (liteRtLmModels.isNotEmpty()) {
            val model = liteRtLmModels.first()
            AppLogger.i(TAG, "LiteRT-LM model available: ${model.fileName}, creating LiteRtLmEngineImpl")
            return LiteRtLmEngineImpl(context, modelPath = model.path)
        }

        // Check for GGUF models as fallback
        val ggufModels = discoverGgufModels(context)
        if (ggufModels.isNotEmpty()) {
            val model = ggufModels.first()
            AppLogger.i(TAG, "GGUF model available: ${model.fileName}, creating GgufEngineImpl")
            return GgufEngineImpl(context, modelPath = model.path)
        }

        AppLogger.w(TAG, "No LLM model files found on device")
        return null
    }

    /**
     * Create a specific model type.
     *
     * @param context Android context
     * @param modelType The specific model type to create
     * @return LlmModel instance, or null if no model of that type is available
     */
    fun createModel(context: Context, modelType: ModelType): LlmModel? {
        return when (modelType) {
            ModelType.GGUF -> {
                val path = getFirstGgufModelPath(context)
                if (path != null) GgufEngineImpl(context, modelPath = path) else null
            }
            ModelType.LITERTLM -> {
                val path = getFirstLiteRtLmModelPath(context)
                if (path != null) LiteRtLmEngineImpl(context, modelPath = path) else null
            }
            ModelType.MEDIAPIPE -> {
                val path = getFirstMediaPipeModelPath(context)
                if (path != null) MediaPipeEngineImpl(context, modelPath = path, maxTokens = MEDIAPIPE_MAX_TOKENS) else null
            }
            ModelType.REMOTE_SERVER -> {
                // Use default config; for custom config use createRemoteServerModel
                createRemoteServerModel()
            }
        }
    }

    /**
     * Create a specific model type with backend preference.
     *
     * @param context Android context
     * @param modelType The specific model type to create
     * @param backendPreference The preferred backend (GPU/CPU)
     * @return LlmModel instance, or null if no model of that type is available
     */
    fun createModelWithBackend(context: Context, modelType: ModelType, backendPreference: LlmBackend): LlmModel? {
        AppLogger.i(TAG, "Creating model $modelType with backend preference: $backendPreference")

        return when (modelType) {
            ModelType.GGUF -> {
                val path = getFirstGgufModelPath(context)
                if (path != null) {
                    AppLogger.d(TAG, "GGUF engine uses auto GPU/CPU selection (preference: $backendPreference)")
                    GgufEngineImpl(context, modelPath = path)
                } else null
            }
            ModelType.LITERTLM -> {
                val path = getFirstLiteRtLmModelPath(context)
                if (path != null) {
                    val liteRtBackend = when (backendPreference) {
                        LlmBackend.GPU -> Backend.GPU
                        LlmBackend.CPU -> Backend.CPU
                    }
                    LiteRtLmEngineImpl(context, liteRtBackend, modelPath = path)
                } else null
            }
            ModelType.MEDIAPIPE -> {
                val path = getFirstMediaPipeModelPath(context)
                if (path != null) {
                    // MediaPipe handles GPU/CPU selection automatically
                    AppLogger.d(TAG, "MediaPipe engine uses auto GPU/CPU selection (preference: $backendPreference)")
                    MediaPipeEngineImpl(context, modelPath = path, maxTokens = MEDIAPIPE_MAX_TOKENS)
                } else null
            }
            ModelType.REMOTE_SERVER -> {
                // Backend preference not applicable for remote server
                AppLogger.d(TAG, "Remote server model - backend preference not applicable")
                createRemoteServerModel()
            }
        }
    }

    /**
     * Create a remote server model with custom configuration.
     *
     * @param config Remote server configuration
     * @return RemoteServerModel instance
     */
    fun createRemoteServerModel(config: RemoteServerConfig = RemoteServerConfig.DEFAULT): LlmModel {
        AppLogger.i(TAG, "Creating remote server model: ${config.baseUrl}, model: ${config.modelId}")
        return RemoteServerModel(config)
    }

    /**
     * Create a model from a specific file path with backend preference.
     *
     * @param context Android context
     * @param modelPath The specific model file path to use
     * @param backendPreference The preferred backend (GPU/CPU)
     * @return LlmModel instance, or null if the path is invalid
     */
    fun createModelFromPath(context: Context, modelPath: String, backendPreference: LlmBackend): LlmModel? {
        AppLogger.i(TAG, "Creating model from specific path: $modelPath with backend: $backendPreference")

        // Determine model type from file extension
        return when {
            GGUF_EXTENSIONS.any { modelPath.endsWith(it, ignoreCase = true) } -> {
                val file = java.io.File(modelPath)
                if (file.exists()) {
                    AppLogger.d(TAG, "Creating GGUF engine from path: $modelPath")
                    GgufEngineImpl(context, modelPath = modelPath)
                } else {
                    AppLogger.w(TAG, "GGUF model file not found: $modelPath")
                    null
                }
            }
            LITERTLM_EXTENSIONS.any { modelPath.endsWith(it, ignoreCase = true) } -> {
                val file = java.io.File(modelPath)
                if (file.exists()) {
                    val liteRtBackend = when (backendPreference) {
                        LlmBackend.GPU -> Backend.GPU
                        LlmBackend.CPU -> Backend.CPU
                    }
                    AppLogger.d(TAG, "Creating LiteRT-LM engine from path: $modelPath")
                    LiteRtLmEngineImpl(context, liteRtBackend, modelPath = modelPath)
                } else {
                    AppLogger.w(TAG, "LiteRT-LM model file not found: $modelPath")
                    null
                }
            }
            MEDIAPIPE_EXTENSIONS.any { modelPath.endsWith(it, ignoreCase = true) } -> {
                val file = java.io.File(modelPath)
                if (file.exists()) {
                    AppLogger.d(TAG, "Creating MediaPipe engine from path: $modelPath")
                    MediaPipeEngineImpl(context, modelPath = modelPath, maxTokens = MEDIAPIPE_MAX_TOKENS)
                } else {
                    AppLogger.w(TAG, "MediaPipe model file not found: $modelPath")
                    null
                }
            }
            else -> {
                AppLogger.w(TAG, "Unknown model file extension: $modelPath")
                null
            }
        }
    }

    /**
     * Check if any GGUF model file exists on the device.
     */
    fun isGgufModelAvailable(context: Context): Boolean {
        return discoverGgufModels(context).isNotEmpty()
    }

    /**
     * Check if any LiteRT-LM model file exists on the device.
     */
    fun isLiteRtLmModelAvailable(context: Context): Boolean {
        return discoverLiteRtLmModels(context).isNotEmpty()
    }

    /**
     * Check if any MediaPipe model file exists on the device.
     */
    fun isMediaPipeModelAvailable(context: Context): Boolean {
        return discoverMediaPipeModels(context).isNotEmpty()
    }

    /**
     * Get list of available model types on this device.
     */
    fun getAvailableModelTypes(context: Context): List<ModelType> {
        val available = mutableListOf<ModelType>()
        if (isGgufModelAvailable(context)) available.add(ModelType.GGUF)
        if (isLiteRtLmModelAvailable(context)) available.add(ModelType.LITERTLM)
        if (isMediaPipeModelAvailable(context)) available.add(ModelType.MEDIAPIPE)
        return available
    }

    /**
     * Get human-readable list of discovered models for display.
     */
    fun getDiscoveredModelsDescription(context: Context): String {
        val allModels = discoverAllModels(context)
        if (allModels.isEmpty()) {
            return "No models found. Place .litertlm, .gguf, or .task files in Downloads folder (or Downloads/LLM for .task)."
        }

        return allModels.joinToString("\n") { model ->
            "✓ ${model.fileName} (${model.sizeMB} MB)"
        }
    }
}

