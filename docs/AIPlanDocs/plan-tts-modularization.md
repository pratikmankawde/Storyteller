# TTS Pipeline Modularization Plan

## Overview

This plan describes the modularization of the TTS (Text-to-Speech) pipeline to support multiple TTS models with easy switching. The primary goal is to abstract the current monolithic `SherpaTtsEngine` into a flexible, plugin-like architecture that can load different model types (VITS-Piper, Kokoro, and future models) from both app assets and external storage.

### Goals and Success Criteria
- ✅ Create abstraction layer for TTS engines
- ✅ Support VITS-Piper (current) and Kokoro (new) model types
- ✅ Enable loading models from external device storage
- ✅ Configuration-based model selection
- ✅ Preserve existing features (caching, voice profiles, speaker matching)
- ✅ Minimal breaking changes to existing API
- ✅ Easy addition of future model types

### Scope Boundaries
- **Included:** TTS engine abstraction, model configs, factory pattern, Kokoro support
- **Excluded:** UI for model selection (separate task), automatic model download, model training

---

## Prerequisites

### Required Dependencies
- Sherpa-ONNX SDK (already included) - supports `OfflineTtsKokoroModelConfig`
- No new dependencies required

### Configuration Requirements
- JSON configuration file for model definitions
- External storage permission for loading models from device storage

---

## Implementation Steps

### Step 1: Create TTS Engine Interface and Base Types

**Files to create:**
- `app/src/main/java/com/dramebaz/app/ai/tts/TtsEngine.kt`

**Implementation details:**

```kotlin
// TtsEngine.kt
package com.dramebaz.app.ai.tts

import com.dramebaz.app.data.models.VoiceProfile
import java.io.File

/**
 * Abstraction layer for TTS engines.
 * Implementations handle specific model types (VITS-Piper, Kokoro, etc.)
 */
interface TtsEngine {
    /** Initialize the TTS engine. Returns true if successful. */
    fun init(): Boolean
    
    /** Check if engine is initialized and ready. */
    fun isInitialized(): Boolean
    
    /** Release engine resources. */
    fun release()
    
    /** Synthesize speech from text. */
    suspend fun speak(
        text: String,
        voiceProfile: VoiceProfile?,
        onComplete: (() -> Unit)? = null,
        speakerId: Int? = null
    ): Result<File?>
    
    /** Get the sample rate of the model output. */
    fun getSampleRate(): Int
    
    /** Get the number of speakers supported by the model. */
    fun getSpeakerCount(): Int
    
    /** Get model information. */
    fun getModelInfo(): TtsModelInfo
    
    /** Get cache statistics. */
    fun getCacheStats(): Pair<Int, Long>
    
    /** Clear audio cache. */
    fun clearCache()
    
    /** Stop any ongoing synthesis. */
    fun stop()
    
    /** Retry initialization after failure. */
    suspend fun retryInit(): Boolean
}

/**
 * Information about a loaded TTS model.
 */
data class TtsModelInfo(
    val modelId: String,
    val modelType: TtsModelType,
    val displayName: String,
    val speakerCount: Int,
    val sampleRate: Int,
    val isExternal: Boolean,
    val modelPath: String
)

/**
 * Supported TTS model types.
 */
enum class TtsModelType {
    VITS_PIPER,
    KOKORO,
    MATCHA,
    KITTEN
}
```

---

### Step 2: Create TTS Model Configuration Classes

**Files to create:**
- `app/src/main/java/com/dramebaz/app/ai/tts/TtsModelConfig.kt`

**Implementation details:**

```kotlin
// TtsModelConfig.kt
package com.dramebaz.app.ai.tts

/**
 * Sealed class hierarchy for TTS model configurations.
 * Each model type has its own configuration requirements.
 */
sealed class TtsModelConfig {
    abstract val modelId: String
    abstract val displayName: String
    abstract val modelType: TtsModelType
    abstract val isExternal: Boolean  // true = load from external storage
    
    /**
     * VITS-Piper model configuration.
     * Used for Piper-based models with espeak-ng phonemization.
     */
    data class VitsPiper(
        override val modelId: String,
        override val displayName: String,
        override val isExternal: Boolean = false,
        val modelPath: String,        // Path to .onnx model file
        val tokensPath: String,       // Path to tokens.txt
        val espeakDataPath: String,   // Path to espeak-ng-data directory
        val speakerCount: Int = 904,  // Default for LibriTTS
        val sampleRate: Int = 22050
    ) : TtsModelConfig() {
        override val modelType = TtsModelType.VITS_PIPER
    }
    
    /**
     * Kokoro model configuration.
     * Uses voice embeddings from voices.bin.
     */
    data class Kokoro(
        override val modelId: String,
        override val displayName: String,
        override val isExternal: Boolean = false,
        val modelPath: String,        // Path to model.int8.onnx
        val tokensPath: String,       // Path to tokens.txt
        val voicesPath: String,       // Path to voices.bin
        val espeakDataPath: String,   // Path to espeak-ng-data directory
        val speakerCount: Int = 1,    // Kokoro typically has voice embeddings instead
        val sampleRate: Int = 24000
    ) : TtsModelConfig() {
        override val modelType = TtsModelType.KOKORO
    }
}
```

---

### Step 3: Create TTS Model Registry

**Files to create:**
- `app/src/main/java/com/dramebaz/app/ai/tts/TtsModelRegistry.kt`

**Implementation details:**

```kotlin
// TtsModelRegistry.kt
package com.dramebaz.app.ai.tts

import android.content.Context
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File

/**
 * Registry for available TTS models.
 * Manages model discovery, validation, and configuration loading.
 */
class TtsModelRegistry(private val context: Context) {

    private val tag = "TtsModelRegistry"
    private val gson = Gson()

    // Built-in models (bundled in assets)
    private val builtInModels = mutableListOf<TtsModelConfig>()

    // External models (loaded from device storage)
    private val externalModels = mutableListOf<TtsModelConfig>()

    // Currently selected model ID
    private var selectedModelId: String = DEFAULT_MODEL_ID

    companion object {
        const val DEFAULT_MODEL_ID = "vits-piper-libritts-high"
        const val EXTERNAL_MODELS_DIR = "tts_models"
        const val MODEL_CONFIG_FILE = "tts_model_config.json"
    }

    init {
        registerBuiltInModels()
    }

    /**
     * Register built-in models bundled with the app.
     */
    private fun registerBuiltInModels() {
        // VITS-Piper LibriTTS High (current default)
        builtInModels.add(
            TtsModelConfig.VitsPiper(
                modelId = "vits-piper-libritts-high",
                displayName = "LibriTTS High (VITS-Piper)",
                isExternal = false,
                modelPath = "models/tts/sherpa/en_US-libritts-high.onnx",
                tokensPath = "models/tts/sherpa/tokens.txt",
                espeakDataPath = "models/tts/sherpa/espeak-ng-data",
                speakerCount = 904,
                sampleRate = 22050
            )
        )
    }

    /**
     * Scan for external models in the app's external files directory.
     */
    fun scanExternalModels(): List<TtsModelConfig> {
        externalModels.clear()
        val modelsDir = File(context.getExternalFilesDir(null), EXTERNAL_MODELS_DIR)

        if (!modelsDir.exists() || !modelsDir.isDirectory) {
            AppLogger.d(tag, "External models directory not found")
            return emptyList()
        }

        modelsDir.listFiles()?.forEach { modelDir ->
            if (modelDir.isDirectory) {
                val config = loadExternalModelConfig(modelDir)
                if (config != null) {
                    externalModels.add(config)
                    AppLogger.i(tag, "Found external model: ${config.modelId}")
                }
            }
        }

        return externalModels.toList()
    }

    /**
     * Load model configuration from an external model directory.
     */
    private fun loadExternalModelConfig(modelDir: File): TtsModelConfig? {
        val configFile = File(modelDir, MODEL_CONFIG_FILE)

        return try {
            if (configFile.exists()) {
                // Load from JSON config
                val json = configFile.readText()
                parseModelConfig(json, modelDir)
            } else {
                // Auto-detect model type from files
                detectModelType(modelDir)
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to load model config from ${modelDir.name}", e)
            null
        }
    }

    /**
     * Auto-detect model type from directory contents.
     */
    private fun detectModelType(modelDir: File): TtsModelConfig? {
        val files = modelDir.listFiles() ?: return null
        val fileNames = files.map { it.name }

        return when {
            // Kokoro model detection
            fileNames.any { it.contains("voices.bin") } -> {
                val modelFile = files.find { it.extension == "onnx" }
                val tokensFile = files.find { it.name == "tokens.txt" }
                val voicesFile = files.find { it.name == "voices.bin" }
                val espeakDir = files.find { it.name == "espeak-ng-data" && it.isDirectory }

                if (modelFile != null && tokensFile != null && voicesFile != null) {
                    TtsModelConfig.Kokoro(
                        modelId = "external-${modelDir.name}",
                        displayName = "Kokoro (${modelDir.name})",
                        isExternal = true,
                        modelPath = modelFile.absolutePath,
                        tokensPath = tokensFile.absolutePath,
                        voicesPath = voicesFile.absolutePath,
                        espeakDataPath = espeakDir?.absolutePath ?: "",
                        sampleRate = 24000
                    )
                } else null
            }
            // VITS-Piper model detection
            else -> {
                val modelFile = files.find { it.extension == "onnx" }
                val tokensFile = files.find { it.name == "tokens.txt" }
                val espeakDir = files.find { it.name == "espeak-ng-data" && it.isDirectory }

                if (modelFile != null && tokensFile != null) {
                    TtsModelConfig.VitsPiper(
                        modelId = "external-${modelDir.name}",
                        displayName = "VITS-Piper (${modelDir.name})",
                        isExternal = true,
                        modelPath = modelFile.absolutePath,
                        tokensPath = tokensFile.absolutePath,
                        espeakDataPath = espeakDir?.absolutePath ?: ""
                    )
                } else null
            }
        }
    }

    fun getAllModels(): List<TtsModelConfig> = builtInModels + externalModels
    fun getBuiltInModels(): List<TtsModelConfig> = builtInModels.toList()
    fun getExternalModels(): List<TtsModelConfig> = externalModels.toList()

    fun getModel(modelId: String): TtsModelConfig? =
        getAllModels().find { it.modelId == modelId }

    fun getSelectedModel(): TtsModelConfig? = getModel(selectedModelId)

    fun setSelectedModel(modelId: String): Boolean {
        return if (getModel(modelId) != null) {
            selectedModelId = modelId
            true
        } else false
    }
}
```

---

### Step 4: Create TTS Engine Factory

**Files to create:**
- `app/src/main/java/com/dramebaz/app/ai/tts/TtsEngineFactory.kt`

**Implementation details:**

```kotlin
// TtsEngineFactory.kt
package com.dramebaz.app.ai.tts

import android.content.Context
import com.dramebaz.app.utils.AppLogger

/**
 * Factory for creating TTS engine instances based on model configuration.
 */
object TtsEngineFactory {

    private const val TAG = "TtsEngineFactory"

    /**
     * Create a TTS engine for the given model configuration.
     */
    fun create(context: Context, config: TtsModelConfig): TtsEngine {
        AppLogger.i(TAG, "Creating TTS engine for model: ${config.modelId} (${config.modelType})")

        return when (config) {
            is TtsModelConfig.VitsPiper -> VitsPiperTtsEngine(context, config)
            is TtsModelConfig.Kokoro -> KokoroTtsEngine(context, config)
        }
    }

    /**
     * Create a TTS engine using the registry's selected model.
     */
    fun createFromRegistry(context: Context, registry: TtsModelRegistry): TtsEngine {
        val config = registry.getSelectedModel()
            ?: registry.getModel(TtsModelRegistry.DEFAULT_MODEL_ID)
            ?: throw IllegalStateException("No TTS model available")

        return create(context, config)
    }
}
```

---

### Step 5: Create Abstract Base TTS Engine

**Files to create:**
- `app/src/main/java/com/dramebaz/app/ai/tts/BaseTtsEngine.kt`

**Implementation details:**

This base class extracts common functionality from `SherpaTtsEngine`:
- Audio caching with LRU eviction
- Cache key computation
- WAV file generation
- Energy scaling post-processing
- Asset copying utilities

```kotlin
// BaseTtsEngine.kt - Key structure (abbreviated)
package com.dramebaz.app.ai.tts

import android.content.Context
import com.dramebaz.app.data.models.VoiceProfile
import com.dramebaz.app.utils.AppLogger
import com.k2fsa.sherpa.onnx.OfflineTts
import java.io.File
import java.io.FileOutputStream

/**
 * Abstract base class for Sherpa-ONNX based TTS engines.
 * Provides common caching, file handling, and audio processing logic.
 */
abstract class BaseTtsEngine(
    protected val context: Context
) : TtsEngine {

    protected var tts: OfflineTts? = null
    protected var initialized = false
    protected abstract val tag: String

    // Caching infrastructure
    protected val cacheDir by lazy { File(context.cacheDir, "tts_audio_cache") }
    protected val maxCacheSizeBytes = 100L * 1024 * 1024

    // Common methods: caching, WAV generation, energy scaling
    // (Extracted from current SherpaTtsEngine)

    override fun isInitialized(): Boolean = initialized
    override fun getCacheStats(): Pair<Int, Long> { /* ... */ }
    override fun clearCache() { /* ... */ }
    override fun stop() { /* ... */ }

    protected fun computeCacheKey(text: String, speakerId: Int, speed: Float, energy: Float): String
    protected fun getCachedAudio(text: String, speakerId: Int, speed: Float, energy: Float): File?
    protected fun cacheAudio(file: File, text: String, speakerId: Int, speed: Float, energy: Float): File
    protected fun evictCacheIfNeeded()
    protected fun saveAudioToFile(samples: FloatArray, sampleRate: Int, fileName: String): File
    protected fun applyEnergyScaling(samples: FloatArray, energy: Float): FloatArray
    protected fun copyAssetIfNeeded(assetPath: String, destFile: File): File?
    protected fun copyAssetDirectory(assetPath: String, destDir: File)
}
```

---

### Step 6: Create VITS-Piper TTS Engine

**Files to create:**
- `app/src/main/java/com/dramebaz/app/ai/tts/VitsPiperTtsEngine.kt`

**Implementation details:**

Refactored version of current `SherpaTtsEngine`, specific to VITS-Piper models:

```kotlin
// VitsPiperTtsEngine.kt
package com.dramebaz.app.ai.tts

import android.content.Context
import com.dramebaz.app.data.models.VoiceProfile
import com.dramebaz.app.utils.AppLogger
import com.dramebaz.app.utils.DegradedModeManager
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TTS Engine for VITS-Piper models.
 */
class VitsPiperTtsEngine(
    context: Context,
    private val config: TtsModelConfig.VitsPiper
) : BaseTtsEngine(context) {

    override val tag = "VitsPiperTtsEngine"

    override fun init(): Boolean {
        if (initialized) return true

        return try {
            val startTime = System.currentTimeMillis()

            // Prepare model files (copy from assets if needed for built-in models)
            val (modelFile, tokensFile, espeakDir) = prepareModelFiles()

            // Configure VITS model
            val vitsConfig = OfflineTtsVitsModelConfig(
                model = modelFile.absolutePath,
                lexicon = "",
                tokens = tokensFile.absolutePath,
                dataDir = espeakDir.absolutePath
            )

            // Try GPU first, fall back to CPU
            createTtsWithFallback(vitsConfig)

            initialized = true
            AppLogger.logPerformance(tag, "VitsPiper init", System.currentTimeMillis() - startTime)
            DegradedModeManager.setTtsMode(DegradedModeManager.TtsMode.FULL)
            true
        } catch (e: Exception) {
            AppLogger.e(tag, "Init failed", e)
            DegradedModeManager.setTtsMode(DegradedModeManager.TtsMode.DISABLED, e.message)
            false
        }
    }

    private fun createTtsWithFallback(vitsConfig: OfflineTtsVitsModelConfig) {
        val numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

        for (provider in listOf("gpu", "cpu")) {
            try {
                val modelConfig = OfflineTtsModelConfig(
                    vits = vitsConfig,
                    matcha = OfflineTtsMatchaModelConfig(),
                    kokoro = OfflineTtsKokoroModelConfig(),
                    kitten = OfflineTtsKittenModelConfig(),
                    numThreads = numThreads,
                    debug = false,
                    provider = provider
                )

                tts = OfflineTts(config = OfflineTtsConfig(model = modelConfig))
                AppLogger.i(tag, "Created with provider: $provider")
                return
            } catch (e: Exception) {
                AppLogger.w(tag, "Failed with provider $provider: ${e.message}")
                if (provider == "cpu") throw e
            }
        }
    }

    override fun getSampleRate(): Int = config.sampleRate
    override fun getSpeakerCount(): Int = config.speakerCount

    override fun getModelInfo(): TtsModelInfo = TtsModelInfo(
        modelId = config.modelId,
        modelType = TtsModelType.VITS_PIPER,
        displayName = config.displayName,
        speakerCount = config.speakerCount,
        sampleRate = config.sampleRate,
        isExternal = config.isExternal,
        modelPath = config.modelPath
    )

    // speak() implementation similar to current SherpaTtsEngine
}
```

---

### Step 7: Create Kokoro TTS Engine

**Files to create:**
- `app/src/main/java/com/dramebaz/app/ai/tts/KokoroTtsEngine.kt`

**Implementation details:**

```kotlin
// KokoroTtsEngine.kt
package com.dramebaz.app.ai.tts

import android.content.Context
import com.dramebaz.app.data.models.VoiceProfile
import com.dramebaz.app.utils.AppLogger
import com.dramebaz.app.utils.DegradedModeManager
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TTS Engine for Kokoro models.
 * Uses OfflineTtsKokoroModelConfig for Kokoro-specific configuration.
 */
class KokoroTtsEngine(
    context: Context,
    private val config: TtsModelConfig.Kokoro
) : BaseTtsEngine(context) {

    override val tag = "KokoroTtsEngine"

    override fun init(): Boolean {
        if (initialized) return true

        return try {
            val startTime = System.currentTimeMillis()

            // Validate model files exist (external models already have absolute paths)
            val modelFile = File(config.modelPath)
            val tokensFile = File(config.tokensPath)
            val voicesFile = File(config.voicesPath)
            val espeakDir = if (config.espeakDataPath.isNotEmpty())
                File(config.espeakDataPath) else null

            if (!modelFile.exists() || !tokensFile.exists() || !voicesFile.exists()) {
                throw IllegalStateException("Kokoro model files not found")
            }

            // Configure Kokoro model
            val kokoroConfig = OfflineTtsKokoroModelConfig(
                model = modelFile.absolutePath,
                voices = voicesFile.absolutePath,
                tokens = tokensFile.absolutePath,
                dataDir = espeakDir?.absolutePath ?: ""
            )

            // Create TTS with provider fallback
            createTtsWithFallback(kokoroConfig)

            initialized = true
            AppLogger.logPerformance(tag, "Kokoro init", System.currentTimeMillis() - startTime)
            DegradedModeManager.setTtsMode(DegradedModeManager.TtsMode.FULL)
            true
        } catch (e: Exception) {
            AppLogger.e(tag, "Kokoro init failed", e)
            DegradedModeManager.setTtsMode(DegradedModeManager.TtsMode.DISABLED, e.message)
            false
        }
    }

    private fun createTtsWithFallback(kokoroConfig: OfflineTtsKokoroModelConfig) {
        val numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

        for (provider in listOf("gpu", "cpu")) {
            try {
                val modelConfig = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(),
                    matcha = OfflineTtsMatchaModelConfig(),
                    kokoro = kokoroConfig,
                    kitten = OfflineTtsKittenModelConfig(),
                    numThreads = numThreads,
                    debug = false,
                    provider = provider
                )

                tts = OfflineTts(config = OfflineTtsConfig(model = modelConfig))
                AppLogger.i(tag, "Kokoro created with provider: $provider")
                return
            } catch (e: Exception) {
                AppLogger.w(tag, "Kokoro failed with provider $provider: ${e.message}")
                if (provider == "cpu") throw e
            }
        }
    }

    override suspend fun speak(
        text: String,
        voiceProfile: VoiceProfile?,
        onComplete: (() -> Unit)?,
        speakerId: Int?
    ): Result<File?> = withContext(Dispatchers.IO) {
        if (text.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Text cannot be empty"))
        }

        if (!initialized && !init()) {
            return@withContext Result.failure(Exception("Kokoro TTS not initialized"))
        }

        val ttsInstance = tts ?: return@withContext Result.failure(Exception("TTS instance null"))

        try {
            val params = VoiceProfileMapper.toTtsParams(voiceProfile)
            val sid = speakerId ?: 0  // Kokoro uses voice embeddings
            val speed = params.speed.coerceIn(0.5f, 2.0f)
            val energy = params.energy.coerceIn(0.5f, 1.5f)

            // Check cache
            val cached = getCachedAudio(text, sid, speed, energy)
            if (cached != null) {
                onComplete?.invoke()
                return@withContext Result.success(cached)
            }

            // Generate audio
            val audio = ttsInstance.generate(text = text, sid = sid, speed = speed)

            if (audio.samples.isEmpty()) {
                return@withContext Result.failure(Exception("No audio samples generated"))
            }

            // Apply energy scaling
            val scaled = if (energy != 1.0f) applyEnergyScaling(audio.samples, energy) else audio.samples

            // Save and cache
            val outputFile = saveAudioToFile(scaled, audio.sampleRate, text.hashCode().toString())
            val cachedFile = cacheAudio(outputFile, text, sid, speed, energy)

            onComplete?.invoke()
            Result.success(cachedFile)
        } catch (e: Exception) {
            AppLogger.e(tag, "Kokoro speak error", e)
            Result.failure(e)
        }
    }

    override fun getSampleRate(): Int = config.sampleRate
    override fun getSpeakerCount(): Int = config.speakerCount

    override fun getModelInfo(): TtsModelInfo = TtsModelInfo(
        modelId = config.modelId,
        modelType = TtsModelType.KOKORO,
        displayName = config.displayName,
        speakerCount = config.speakerCount,
        sampleRate = config.sampleRate,
        isExternal = config.isExternal,
        modelPath = config.modelPath
    )

    override fun release() {
        tts?.release()
        tts = null
        initialized = false
        DegradedModeManager.setTtsMode(DegradedModeManager.TtsMode.NOT_INITIALIZED)
    }

    override suspend fun retryInit(): Boolean = withContext(Dispatchers.IO) {
        release()
        init()
    }
}
```

---

### Step 8: Create Kokoro Speaker Catalog

**Files to create:**
- `app/src/main/java/com/dramebaz/app/ai/tts/KokoroSpeakerCatalog.kt`

**Implementation details:**

```kotlin
// KokoroSpeakerCatalog.kt
package com.dramebaz.app.ai.tts

/**
 * Speaker catalog for Kokoro TTS model.
 * Kokoro uses voice embeddings from voices.bin rather than speaker IDs.
 * This catalog maps voice names to metadata for the UI.
 */
object KokoroSpeakerCatalog {

    // Kokoro voice names (from voices.bin)
    // These are the embedded voices available in the model
    val voices = listOf(
        VoiceInfo("af_heart", "Heart", "F", "American"),
        VoiceInfo("af_sky", "Sky", "F", "American"),
        VoiceInfo("af_bella", "Bella", "F", "American"),
        VoiceInfo("af_sarah", "Sarah", "F", "American"),
        VoiceInfo("af_nicole", "Nicole", "F", "American"),
        VoiceInfo("am_adam", "Adam", "M", "American"),
        VoiceInfo("am_michael", "Michael", "M", "American"),
        VoiceInfo("bf_emma", "Emma", "F", "British"),
        VoiceInfo("bf_isabella", "Isabella", "F", "British"),
        VoiceInfo("bm_george", "George", "M", "British"),
        VoiceInfo("bm_lewis", "Lewis", "M", "British")
    )

    data class VoiceInfo(
        val id: String,
        val displayName: String,
        val gender: String,  // "M" or "F"
        val accent: String
    ) {
        val isFemale: Boolean get() = gender == "F"
        val isMale: Boolean get() = gender == "M"
    }

    fun getVoice(id: String): VoiceInfo? = voices.find { it.id == id }
    fun femaleVoices(): List<VoiceInfo> = voices.filter { it.isFemale }
    fun maleVoices(): List<VoiceInfo> = voices.filter { it.isMale }
}
```

---

### Step 9: Update DramebazApplication

**Files to modify:**
- `app/src/main/java/com/dramebaz/app/DramebazApplication.kt`

**Changes:**
- Replace direct `SherpaTtsEngine` instantiation with factory pattern
- Add `TtsModelRegistry` as application-level singleton
- Support model switching at runtime

```kotlin
// DramebazApplication.kt - Key changes
class DramebazApplication : Application() {

    // ...existing code...

    /** TTS model registry for managing available models */
    val ttsModelRegistry: TtsModelRegistry by lazy { TtsModelRegistry(this) }

    /** TTS engine - created via factory using selected model */
    private var _ttsEngine: TtsEngine? = null
    val ttsEngine: TtsEngine
        get() = _ttsEngine ?: createTtsEngine().also { _ttsEngine = it }

    private fun createTtsEngine(): TtsEngine {
        return TtsEngineFactory.createFromRegistry(this, ttsModelRegistry)
    }

    /**
     * Switch to a different TTS model.
     * Releases current engine and creates new one.
     */
    fun switchTtsModel(modelId: String): Boolean {
        if (!ttsModelRegistry.setSelectedModel(modelId)) {
            return false
        }

        // Release current engine
        _ttsEngine?.release()
        _ttsEngine = null

        // Create new engine (lazy - will be created on first access)
        return true
    }

    // ...rest of existing code...
}
```

---

### Step 10: Refactor SherpaTtsEngine to Delegate

**Files to modify:**
- `app/src/main/java/com/dramebaz/app/ai/tts/SherpaTtsEngine.kt`

**Changes:**
- Keep `SherpaTtsEngine` as a facade for backward compatibility
- Delegate to the new `TtsEngine` implementation via factory
- Mark as deprecated with migration guidance

```kotlin
// SherpaTtsEngine.kt - Refactored as facade
package com.dramebaz.app.ai.tts

import android.content.Context
import com.dramebaz.app.data.models.VoiceProfile
import java.io.File

/**
 * @deprecated Use TtsEngineFactory to create TtsEngine instances instead.
 * This class is kept for backward compatibility and delegates to the
 * underlying TtsEngine implementation.
 */
@Deprecated(
    "Use TtsEngineFactory.create() instead",
    ReplaceWith("TtsEngineFactory.createFromRegistry(context, TtsModelRegistry(context))")
)
class SherpaTtsEngine(private val context: Context) : TtsEngine {

    private val delegate: TtsEngine by lazy {
        val registry = TtsModelRegistry(context)
        TtsEngineFactory.createFromRegistry(context, registry)
    }

    override fun init(): Boolean = delegate.init()
    override fun isInitialized(): Boolean = delegate.isInitialized()
    override fun release() = delegate.release()

    override suspend fun speak(
        text: String,
        voiceProfile: VoiceProfile?,
        onComplete: (() -> Unit)?,
        speakerId: Int?
    ): Result<File?> = delegate.speak(text, voiceProfile, onComplete, speakerId)

    override fun getSampleRate(): Int = delegate.getSampleRate()
    override fun getSpeakerCount(): Int = delegate.getSpeakerCount()
    override fun getModelInfo(): TtsModelInfo = delegate.getModelInfo()
    override fun getCacheStats(): Pair<Int, Long> = delegate.getCacheStats()
    override fun clearCache() = delegate.clearCache()
    override fun stop() = delegate.stop()
    override suspend fun retryInit(): Boolean = delegate.retryInit()

    // Legacy method - kept for compatibility
    fun cleanup() = release()
}
```

---

### Step 11: Update SpeakerMatcher for Multi-Model Support

**Files to modify:**
- `app/src/main/java/com/dramebaz/app/ai/tts/SpeakerMatcher.kt`

**Changes:**
- Add model type awareness to speaker matching
- Support Kokoro voice embeddings alongside LibriTTS speaker IDs
- Make catalog selection dynamic based on active model

```kotlin
// SpeakerMatcher.kt - Key additions
object SpeakerMatcher {

    /**
     * Suggest speaker/voice for a character based on traits and model type.
     */
    fun suggestSpeaker(
        traits: String?,
        personalitySummary: String?,
        name: String?,
        modelType: TtsModelType = TtsModelType.VITS_PIPER
    ): SpeakerSuggestion {
        return when (modelType) {
            TtsModelType.VITS_PIPER -> {
                val speakerId = suggestSpeakerId(traits, personalitySummary, name)
                SpeakerSuggestion.LibriTTS(speakerId ?: 0)
            }
            TtsModelType.KOKORO -> {
                val voice = suggestKokoroVoice(traits, personalitySummary, name)
                SpeakerSuggestion.KokoroVoice(voice?.id ?: "af_heart")
            }
            else -> SpeakerSuggestion.LibriTTS(0) // Default
        }
    }

    private fun suggestKokoroVoice(
        traits: String?,
        personalitySummary: String?,
        name: String?
    ): KokoroSpeakerCatalog.VoiceInfo? {
        val traitTokens = parseTraits(traits)

        // Simple gender matching for Kokoro
        val isFemale = traitTokens.any { it in setOf("female", "woman", "lady", "girl", "she") }
        val isMale = traitTokens.any { it in setOf("male", "man", "gentleman", "boy", "he") }

        val candidates = when {
            isFemale -> KokoroSpeakerCatalog.femaleVoices()
            isMale -> KokoroSpeakerCatalog.maleVoices()
            else -> KokoroSpeakerCatalog.voices
        }

        // Use name hash for deterministic selection
        val nameHash = kotlin.math.abs(name?.hashCode() ?: 0)
        return candidates.getOrNull(nameHash % candidates.size)
    }

    sealed class SpeakerSuggestion {
        data class LibriTTS(val speakerId: Int) : SpeakerSuggestion()
        data class KokoroVoice(val voiceId: String) : SpeakerSuggestion()
    }
}
```

---

## File Changes Summary

### Files to Create (New)

| File | Purpose |
|------|---------|
| `app/src/main/java/com/dramebaz/app/ai/tts/TtsEngine.kt` | Interface and base types |
| `app/src/main/java/com/dramebaz/app/ai/tts/TtsModelConfig.kt` | Sealed class for model configs |
| `app/src/main/java/com/dramebaz/app/ai/tts/TtsModelRegistry.kt` | Model discovery and management |
| `app/src/main/java/com/dramebaz/app/ai/tts/TtsEngineFactory.kt` | Factory for creating engines |
| `app/src/main/java/com/dramebaz/app/ai/tts/BaseTtsEngine.kt` | Common base class |
| `app/src/main/java/com/dramebaz/app/ai/tts/VitsPiperTtsEngine.kt` | VITS-Piper implementation |
| `app/src/main/java/com/dramebaz/app/ai/tts/KokoroTtsEngine.kt` | Kokoro implementation |
| `app/src/main/java/com/dramebaz/app/ai/tts/KokoroSpeakerCatalog.kt` | Kokoro voice metadata |

### Files to Modify (Existing)

| File | Changes |
|------|---------|
| `SherpaTtsEngine.kt` | Convert to facade delegating to TtsEngine |
| `DramebazApplication.kt` | Add registry, use factory pattern |
| `SpeakerMatcher.kt` | Add multi-model support |
| `SegmentAudioGenerator.kt` | No changes needed (uses TtsEngine interface) |
| `AudioDirector.kt` | No changes needed (uses TtsEngine interface) |
| `PlaybackEngine.kt` | No changes needed (uses TtsEngine interface) |

### Files to Delete

None - backward compatibility is maintained.

---

## Testing Strategy

### Unit Tests

1. **TtsModelConfig Tests**
   - Verify sealed class instantiation
   - Test model type determination
   - Validate config field constraints

2. **TtsModelRegistry Tests**
   - Test built-in model registration
   - Test external model scanning (with mock file system)
   - Test model selection persistence

3. **TtsEngineFactory Tests**
   - Verify correct engine type creation
   - Test fallback behavior

4. **KokoroSpeakerCatalog Tests**
   - Test voice lookup
   - Test gender filtering

### Integration Tests

1. **VitsPiperTtsEngine Tests**
   - Test initialization from assets
   - Test speech synthesis
   - Test caching behavior

2. **KokoroTtsEngine Tests**
   - Test initialization from external storage
   - Test speech synthesis with voice embeddings
   - Test GPU/CPU fallback

### Manual Testing Steps

1. Verify existing VITS-Piper model works after refactor
2. Copy Kokoro model to external storage path:
   `Android/data/com.dramebaz.app/files/tts_models/kokoro-int8-en-v0_19/`
3. Verify Kokoro model is detected and selectable
4. Test synthesis with Kokoro model
5. Test model switching at runtime
6. Verify caching works across model switches

---

## Rollback Plan

1. **Code Rollback:**
   - Revert `SherpaTtsEngine.kt` to original implementation
   - Remove new files (TtsEngine, factories, etc.)
   - Revert `DramebazApplication.kt` changes

2. **Data Rollback:**
   - No database migrations involved
   - Clear TTS audio cache if needed

3. **Feature Flags (Optional):**
   - Add feature flag `enable_multi_model_tts`
   - Default to false initially
   - Gradual rollout to users

---

## Estimated Effort

| Task | Effort |
|------|--------|
| Step 1-2: Interface and Config classes | 2 hours |
| Step 3: Model Registry | 3 hours |
| Step 4-5: Factory and Base Engine | 4 hours |
| Step 6: VitsPiper Engine | 4 hours |
| Step 7-8: Kokoro Engine and Catalog | 4 hours |
| Step 9-10: Application and Legacy updates | 2 hours |
| Step 11: SpeakerMatcher updates | 2 hours |
| Testing | 4 hours |
| **Total** | **~25 hours** |

### Complexity Assessment

**Medium-High**

- Well-defined refactoring with clear abstractions
- Sherpa-ONNX SDK already supports multiple model types
- Main risk: external model file handling on Android
- Requires thorough testing of both model types

---

## Implementation Order

```
1. TtsEngine.kt (interface + TtsModelInfo + TtsModelType)
2. TtsModelConfig.kt (sealed class hierarchy)
3. BaseTtsEngine.kt (extract common logic from SherpaTtsEngine)
4. VitsPiperTtsEngine.kt (refactor current implementation)
5. TtsModelRegistry.kt (model discovery)
6. TtsEngineFactory.kt (factory pattern)
7. KokoroTtsEngine.kt (new Kokoro support)
8. KokoroSpeakerCatalog.kt (voice metadata)
9. SherpaTtsEngine.kt (convert to facade)
10. DramebazApplication.kt (update to use registry/factory)
11. SpeakerMatcher.kt (multi-model awareness)
```

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                      DramebazApplication                        │
│  ┌─────────────────────┐    ┌──────────────────────────────┐   │
│  │  TtsModelRegistry   │    │       TtsEngineFactory       │   │
│  │  - builtInModels    │───▶│  create(config) → TtsEngine  │   │
│  │  - externalModels   │    └──────────────────────────────┘   │
│  │  - selectedModelId  │                  │                     │
│  └─────────────────────┘                  ▼                     │
└───────────────────────────────────────────────────────────────┬─┘
                                                                │
                           ┌────────────────────────────────────┘
                           ▼
              ┌─────────────────────────┐
              │      <<interface>>      │
              │        TtsEngine        │
              │  - init()               │
              │  - speak()              │
              │  - getSampleRate()      │
              │  - getModelInfo()       │
              └───────────┬─────────────┘
                          │
          ┌───────────────┴───────────────┐
          │                               │
          ▼                               ▼
┌─────────────────────┐       ┌─────────────────────┐
│   BaseTtsEngine     │       │   BaseTtsEngine     │
│         │           │       │         │           │
│ VitsPiperTtsEngine  │       │  KokoroTtsEngine    │
│ - VITS model config │       │ - Kokoro model cfg  │
│ - 904 speakers      │       │ - Voice embeddings  │
│ - 22050 Hz          │       │ - 24000 Hz          │
└─────────────────────┘       └─────────────────────┘
          │                               │
          ▼                               ▼
┌─────────────────────┐       ┌─────────────────────┐
│ LibrittsSpeakerCat  │       │ KokoroSpeakerCat    │
│ - 904 speaker IDs   │       │ - Voice embeddings  │
│ - Gender metadata   │       │ - Voice names       │
└─────────────────────┘       └─────────────────────┘
```

---

## External Model Directory Structure

For users to add external Kokoro model:

```
Android/data/com.dramebaz.app/files/tts_models/
└── kokoro-int8-en-v0_19/
    ├── model.int8.onnx      (required)
    ├── tokens.txt           (required)
    ├── voices.bin           (required)
    ├── tts_model_config.json (optional - for custom settings)
    └── espeak-ng-data/      (optional - for phoneme generation)
        ├── en_dict
        ├── en_rules
        └── ...
```

### Optional Config File Format

```json
{
  "modelId": "kokoro-int8-en-v0_19",
  "displayName": "Kokoro English (Int8)",
  "modelType": "KOKORO",
  "sampleRate": 24000,
  "speakerCount": 11
}
```

