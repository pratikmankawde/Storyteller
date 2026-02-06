package com.dramebaz.app.ai.llm

import android.content.Context
import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.models.LlmModelFactory
import com.dramebaz.app.ai.llm.prompts.AnalysisPrompts
import com.dramebaz.app.ai.llm.prompts.ExtractionPrompts
import com.dramebaz.app.ai.llm.prompts.StoryPrompts
import com.dramebaz.app.data.models.ChapterSummary
import com.dramebaz.app.data.models.Dialog
import com.dramebaz.app.data.models.EmotionalSegment
import com.dramebaz.app.data.models.LlmBackend
import com.dramebaz.app.data.models.LlmModelType
import com.dramebaz.app.data.models.LlmSettings
import com.dramebaz.app.data.models.ProsodyHints
import com.dramebaz.app.data.models.SoundCueModel
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * LLM Service - Primary entry point for LLM operations.
 *
 * This is a facade that provides access to LLM models for character analysis,
 * dialog extraction, and story generation. Uses StubFallbacks when LLM is unavailable.
 *
 * Design Pattern: Facade Pattern - provides a simplified interface to the LLM subsystem.
 *
 * Usage:
 * ```
 * // Initialize once at app startup
 * LlmService.setApplicationContext(context)
 *
 * // Use for analysis
 * val result = LlmService.analyzeChapter(text)
 * ```
 *
 * Architecture:
 * - Uses LlmModelFactory to create the appropriate model (GGUF or LiteRT-LM)
 * - Uses StubFallbacks for fallback when LLM is unavailable
 * - Uses GgufEngine directly for 3-pass character analysis workflows
 */
object LlmService {
    private const val TAG = "LlmService"
    private const val LLM_TIMEOUT_MS = 600_000L  // 10 minutes timeout for slow LiteRT-LM models
    private const val MAX_INPUT_CHARS = 10_000

    private var appContext: Context? = null
    private var llmModel: LlmModel? = null
    private var ggufEngine: GgufEngine? = null  // Direct reference for 3-pass workflow
    private val initMutex = Mutex()
    private var initialized = false
    private val gson = Gson()

    // Track active inference count to prevent releasing engine during inference
    @Volatile
    private var activeInferenceCount = 0
    private val inferenceCountLock = Any()

    /** Set once from Application.onCreate so LLM can initialize lazily on first use. */
    fun setApplicationContext(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Ensure LLM is initialized (lazy init on first use).
     * Priority: LiteRT-LM > GGUF (determined by LlmModelFactory)
     */
    suspend fun ensureInitialized(): Boolean = initMutex.withLock {
        if (initialized) return@withLock llmModel?.isModelLoaded() == true

        val ctx = appContext ?: return@withLock false
        initialized = true

        return@withLock withContext(Dispatchers.IO) {
            try {
                // Use factory to create the default model (LiteRT-LM preferred, then GGUF)
                llmModel = LlmModelFactory.createDefaultModel(ctx)
                val loaded = llmModel?.loadModel() ?: false
                if (loaded) {
                    AppLogger.i(TAG, "✅ LLM model loaded successfully: ${llmModel?.getExecutionProvider()}")
                    // If GGUF is available, also load it for 3-pass workflow compatibility
                    if (LlmModelFactory.isGgufModelAvailable(ctx)) {
                        ggufEngine = GgufEngine(ctx)
                        ggufEngine?.loadModel()
                        AppLogger.i(TAG, "GgufEngine also loaded for 3-pass workflow")
                    }
                } else {
                    AppLogger.w(TAG, "⚠️ Default model failed to load")
                }
                loaded
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to initialize LLM", e)
                false
            }
        }
    }

    /**
     * Initialize LLM.
     */
    suspend fun initialize(context: Context): Boolean {
        setApplicationContext(context)
        return ensureInitialized()
    }

    /**
     * Initialize LLM with saved settings.
     * This should be called on app startup to load the user's preferred model.
     *
     * @param context Android context
     * @param settings Saved LLM settings (model type, backend, and model path)
     * @return true if model loaded successfully
     */
    suspend fun initializeWithSettings(context: Context, settings: LlmSettings): Boolean = initMutex.withLock {
        if (initialized) {
            AppLogger.d(TAG, "LLM already initialized, skipping")
            return@withLock llmModel?.isModelLoaded() == true
        }

        setApplicationContext(context)
        initialized = true

        AppLogger.i(TAG, "Initializing LLM with saved settings: model=${settings.selectedModelType}, backend=${settings.preferredBackend}, path=${settings.selectedModelPath}")

        return@withLock withContext(Dispatchers.IO) {
            try {
                // If a specific model path is saved, use it directly
                if (!settings.selectedModelPath.isNullOrEmpty()) {
                    val file = java.io.File(settings.selectedModelPath)
                    if (file.exists()) {
                        AppLogger.i(TAG, "Loading saved model path: ${settings.selectedModelPath}")
                        llmModel = LlmModelFactory.createModelFromPath(
                            context,
                            settings.selectedModelPath,
                            settings.preferredBackend
                        )
                        val loaded = llmModel?.loadModel() ?: false
                        if (loaded) {
                            AppLogger.i(TAG, "✅ Saved model loaded successfully: ${llmModel?.getExecutionProvider()}")
                            // Also load GGUF for 3-pass workflow if available
                            if (!settings.selectedModelPath.endsWith(".gguf", ignoreCase = true) &&
                                LlmModelFactory.isGgufModelAvailable(context)) {
                                ggufEngine = GgufEngine(context)
                                ggufEngine?.loadModel()
                                AppLogger.i(TAG, "GgufEngine also loaded for 3-pass workflow")
                            }
                            return@withContext true
                        } else {
                            AppLogger.w(TAG, "Saved model failed to load, falling back to default")
                        }
                    } else {
                        AppLogger.w(TAG, "Saved model file not found: ${settings.selectedModelPath}, falling back to default")
                    }
                }

                // Fall back to default model selection based on model type preference
                val modelType = when (settings.selectedModelType) {
                    LlmModelType.AUTO -> {
                        if (LlmModelFactory.isLiteRtLmModelAvailable(context)) {
                            LlmModelFactory.ModelType.LITERTLM
                        } else if (LlmModelFactory.isGgufModelAvailable(context)) {
                            LlmModelFactory.ModelType.GGUF
                        } else {
                            null
                        }
                    }
                    LlmModelType.LITERTLM -> {
                        if (LlmModelFactory.isLiteRtLmModelAvailable(context)) {
                            LlmModelFactory.ModelType.LITERTLM
                        } else null
                    }
                    LlmModelType.GGUF -> {
                        if (LlmModelFactory.isGgufModelAvailable(context)) {
                            LlmModelFactory.ModelType.GGUF
                        } else null
                    }
                }

                if (modelType == null) {
                    AppLogger.w(TAG, "No model available for settings, trying default")
                    llmModel = LlmModelFactory.createDefaultModel(context)
                } else {
                    llmModel = LlmModelFactory.createModelWithBackend(context, modelType, settings.preferredBackend)
                }

                val loaded = llmModel?.loadModel() ?: false
                if (loaded) {
                    AppLogger.i(TAG, "✅ LLM model loaded successfully: ${llmModel?.getExecutionProvider()}")
                    // Also load GGUF for 3-pass workflow if available
                    if (LlmModelFactory.isGgufModelAvailable(context) &&
                        modelType != LlmModelFactory.ModelType.GGUF) {
                        ggufEngine = GgufEngine(context)
                        ggufEngine?.loadModel()
                        AppLogger.i(TAG, "GgufEngine also loaded for 3-pass workflow")
                    }
                } else {
                    AppLogger.w(TAG, "⚠️ Model failed to load")
                }
                loaded
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to initialize LLM with settings", e)
                false
            }
        }
    }

    /**
     * Release LLM resources.
     */
    fun release() {
        ggufEngine?.release()
        ggufEngine = null
        llmModel?.release()
        llmModel = null
        initialized = false
    }

    /**
     * Increment active inference count. Call before starting LLM inference.
     */
    private fun beginInference() {
        synchronized(inferenceCountLock) {
            activeInferenceCount++
            AppLogger.d(TAG, "Begin inference, active count: $activeInferenceCount")
        }
    }

    /**
     * Decrement active inference count. Call after LLM inference completes.
     */
    private fun endInference() {
        synchronized(inferenceCountLock) {
            activeInferenceCount--
            AppLogger.d(TAG, "End inference, active count: $activeInferenceCount")
        }
    }

    /**
     * Wait for all active inferences to complete.
     * Used before releasing the engine to prevent crashes.
     * @param timeoutMs Maximum time to wait in milliseconds
     */
    suspend fun waitForInferenceCompletion(timeoutMs: Long = 5000L) {
        val startTime = System.currentTimeMillis()
        while (activeInferenceCount > 0) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                AppLogger.w(TAG, "Timeout waiting for inference completion, active count: $activeInferenceCount")
                break
            }
            kotlinx.coroutines.delay(100)
        }
        AppLogger.d(TAG, "Inference wait complete, active count: $activeInferenceCount")
    }

    /**
     * Check if any inference is currently active.
     */
    fun isInferenceActive(): Boolean = activeInferenceCount > 0

    /**
     * Check if the model is ready for analysis.
     */
    fun isReady(): Boolean = ggufEngine?.isModelLoaded() == true || llmModel?.isModelLoaded() == true

    /**
     * Check if running in full LLM mode (not stub fallback).
     */
    fun isUsingLlm(): Boolean = isReady()

    /** Alias for backward compatibility with GGUF/llama.cpp */
    fun isUsingLlama(): Boolean = ggufEngine?.isModelLoaded() == true

    /**
     * Returns the execution provider: "GPU (Vulkan)" or "CPU" or "unknown"
     */
    fun getExecutionProvider(): String = ggufEngine?.getExecutionProvider()
        ?: llmModel?.getExecutionProvider()
        ?: "unknown"

    /**
     * Returns true if using GPU for inference.
     */
    fun isUsingGpu(): Boolean = ggufEngine?.isUsingGpu() == true || llmModel?.isUsingGpu() == true

    /**
     * Get the type of the currently loaded model.
     * Used to select the appropriate analysis pipeline.
     *
     * @return ModelType.LITERTLM if a LiteRT-LM model is loaded,
     *         ModelType.GGUF if a GGUF model is loaded,
     *         null if no model is loaded
     */
    fun getLoadedModelType(): LlmModelFactory.ModelType? {
        // Check LiteRT-LM first (preferred)
        if (llmModel is com.dramebaz.app.ai.llm.models.LiteRtLmEngineImpl) {
            return LlmModelFactory.ModelType.LITERTLM
        }
        // Check GGUF
        if (llmModel is com.dramebaz.app.ai.llm.models.GgufEngineImpl || ggufEngine?.isModelLoaded() == true) {
            return LlmModelFactory.ModelType.GGUF
        }
        return null
    }

    /**
     * Check if the loaded model is a LiteRT-LM model.
     * Used to determine which analysis pipeline to use.
     */
    fun isUsingLiteRtLm(): Boolean = llmModel is com.dramebaz.app.ai.llm.models.LiteRtLmEngineImpl

    /**
     * Check if the loaded model is a GGUF model.
     * Used to determine which analysis pipeline to use.
     */
    fun isUsingGguf(): Boolean = llmModel is com.dramebaz.app.ai.llm.models.GgufEngineImpl || ggufEngine?.isModelLoaded() == true

    /**
     * Get the underlying LlmModel instance for use with modular passes.
     * Prefer using the llmModel. Note: ggufEngine (GgufEngine) is not an LlmModel,
     * so this returns null if only ggufEngine is available without llmModel.
     *
     * @return LlmModel instance if available, null otherwise
     */
    fun getModel(): LlmModel? = llmModel

    /**
     * Get the capabilities of the currently loaded LLM model.
     * Used by UI to enable/disable features like image-to-story generation.
     *
     * @return ModelCapabilities with image/audio support flags, or UNKNOWN if no model loaded
     */
    fun getModelCapabilities(): ModelCapabilities {
        // Check if using LiteRT-LM model with capabilities
        val liteRtLmImpl = llmModel as? com.dramebaz.app.ai.llm.models.LiteRtLmEngineImpl
        if (liteRtLmImpl != null) {
            return liteRtLmImpl.getModelCapabilities()
        }

        // GGUF models don't support vision/audio by default
        if (ggufEngine?.isModelLoaded() == true) {
            return ModelCapabilities(
                modelName = "GGUF Model",
                supportsImage = false,
                supportsAudio = false
            )
        }

        return ModelCapabilities.UNKNOWN
    }

    /**
     * Retry loading the model. Call this if user wants to retry after failure.
     * Uses factory default priority: LiteRT-LM > GGUF
     */
    suspend fun retryLoadModel(context: Context): Boolean = initMutex.withLock {
        release()
        initialized = false
        return@withLock withContext(Dispatchers.IO) {
            llmModel = LlmModelFactory.createDefaultModel(context)
            val loaded = llmModel?.loadModel() ?: false
            if (loaded && LlmModelFactory.isGgufModelAvailable(context)) {
                ggufEngine = GgufEngine(context)
                ggufEngine?.loadModel()
            }
            loaded
        }
    }

    /**
     * Reload the model with specific settings (model type and backend).
     * Called when user changes LLM settings.
     *
     * @param context Android context
     * @param settings LLM settings with model type and backend preference
     * @return true if model loaded successfully
     */
    suspend fun reloadWithSettings(context: Context, settings: LlmSettings): Boolean = initMutex.withLock {
        AppLogger.i(TAG, "Reloading LLM with settings: model=${settings.selectedModelType}, backend=${settings.preferredBackend}, path=${settings.selectedModelPath}")

        // Release existing models
        release()
        initialized = true  // Prevent re-init during reload

        return@withLock withContext(Dispatchers.IO) {
            try {
                // If a specific model path is provided, use it directly
                if (!settings.selectedModelPath.isNullOrEmpty()) {
                    AppLogger.i(TAG, "Using specific model path: ${settings.selectedModelPath}")
                    llmModel = LlmModelFactory.createModelFromPath(
                        context,
                        settings.selectedModelPath,
                        settings.preferredBackend
                    )
                    if (llmModel == null) {
                        AppLogger.w(TAG, "Failed to create model from path: ${settings.selectedModelPath}")
                        // Fall through to auto-selection
                    }
                }

                // Track if we loaded a GGUF model (for 3-pass workflow setup)
                var loadedGguf = false

                // If no model loaded yet (no path or path failed), use model type selection
                if (llmModel == null) {
                    // Determine which model to load based on settings
                    val modelType = when (settings.selectedModelType) {
                        LlmModelType.AUTO -> {
                            // Auto-select: prefer LiteRT-LM, fallback to GGUF
                            if (LlmModelFactory.isLiteRtLmModelAvailable(context)) {
                                LlmModelFactory.ModelType.LITERTLM
                            } else if (LlmModelFactory.isGgufModelAvailable(context)) {
                                LlmModelFactory.ModelType.GGUF
                            } else {
                                null
                            }
                        }
                        LlmModelType.LITERTLM -> {
                            if (LlmModelFactory.isLiteRtLmModelAvailable(context)) {
                                LlmModelFactory.ModelType.LITERTLM
                            } else null
                        }
                        LlmModelType.GGUF -> {
                            if (LlmModelFactory.isGgufModelAvailable(context)) {
                                LlmModelFactory.ModelType.GGUF
                            } else null
                        }
                    }

                    if (modelType == null) {
                        AppLogger.w(TAG, "No model available for settings")
                        return@withContext false
                    }

                    loadedGguf = (modelType == LlmModelFactory.ModelType.GGUF)

                    // Create and load the model with backend preference
                    llmModel = LlmModelFactory.createModelWithBackend(context, modelType, settings.preferredBackend)
                } else {
                    // Model was created from specific path - check if it's GGUF
                    loadedGguf = llmModel is com.dramebaz.app.ai.llm.models.GgufEngineImpl
                }

                val loaded = llmModel?.loadModel() ?: false

                if (loaded) {
                    AppLogger.i(TAG, "✅ Model loaded successfully: ${llmModel?.getExecutionProvider()}")

                    // Also load GGUF for 3-pass workflow if available and not already loaded
                    if (!loadedGguf && LlmModelFactory.isGgufModelAvailable(context)) {
                        ggufEngine = GgufEngine(context)
                        ggufEngine?.loadModel()
                        AppLogger.i(TAG, "GgufEngine also loaded for 3-pass workflow")
                    } else if (loadedGguf) {
                        // If GGUF is the primary model, use it directly
                        ggufEngine = (llmModel as? com.dramebaz.app.ai.llm.models.GgufEngineImpl)?.let {
                            GgufEngine(context).apply { loadModel() }
                        }
                    }
                } else {
                    AppLogger.w(TAG, "⚠️ Model failed to load")
                }

                loaded
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to reload model with settings", e)
                false
            }
        }
    }

    // ==================== Analysis Methods ====================

    /**
     * Analyze chapter for summary, characters, dialogs, and sound cues.
     * Uses GgufEngine if available, falls back to llmModel with prompts, then to StubFallbacks.
     */
    suspend fun analyzeChapter(chapterText: String): ChapterAnalysisResponse = withContext(Dispatchers.IO) {
        ensureInitialized()
        beginInference()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                // Try GgufEngine first (has built-in analyzeChapter)
                val ggufResult = ggufEngine?.analyzeChapter(chapterText)
                if (ggufResult != null) {
                    AppLogger.d(TAG, "analyzeChapter: Using GgufEngine result")
                    return@withTimeout ggufResult
                }

                // Fall back to llmModel with standard prompts
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "analyzeChapter: Using llmModel with standard prompts ($MAX_INPUT_CHARS chars)")
                    val response = model.generateResponse(
                        systemPrompt = AnalysisPrompts.ANALYSIS_SYSTEM_PROMPT,
                        userPrompt = AnalysisPrompts.buildAnalysisPrompt(chapterText, MAX_INPUT_CHARS),
                        maxTokens = 2048,
                        temperature = 0.15f
                    )
                    val parsed = parseAnalysisResponse(response)
                    if (parsed != null) {
                        return@withTimeout parsed
                    }
                }

                // Final fallback to stubs
                AppLogger.d(TAG, "analyzeChapter: Using StubFallbacks")
                null
            } ?: StubFallbacks.analyzeChapter(chapterText)
        } catch (e: TimeoutCancellationException) {
            AppLogger.w(TAG, "analyzeChapter timed out, using fallback")
            StubFallbacks.analyzeChapter(chapterText)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Re-throw CancellationException to allow coroutine cancellation to work properly
            AppLogger.d(TAG, "analyzeChapter cancelled")
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "analyzeChapter error, using fallback", e)
            StubFallbacks.analyzeChapter(chapterText)
        } finally {
            endInference()
        }
    }

    /**
     * Extended analysis for themes, symbols, vocabulary, foreshadowing.
     * Uses GgufEngine if available, falls back to llmModel with prompts, then to StubFallbacks.
     */
    suspend fun extendedAnalysisJson(chapterText: String): String = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                // Try GgufEngine first
                val ggufResult = ggufEngine?.extendedAnalysisJson(chapterText)
                if (ggufResult != null) {
                    AppLogger.d(TAG, "extendedAnalysisJson: Using GgufEngine result")
                    return@withTimeout ggufResult
                }

                // Fall back to llmModel with prompts
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "extendedAnalysisJson: Using llmModel with prompts")
                    val response = model.generateResponse(
                        systemPrompt = AnalysisPrompts.EXTENDED_ANALYSIS_SYSTEM_PROMPT,
                        userPrompt = AnalysisPrompts.buildExtendedAnalysisPrompt(chapterText, MAX_INPUT_CHARS),
                        maxTokens = 1024,
                        temperature = 0.15f
                    )
                    val parsed = parseExtendedAnalysisResponse(response)
                    if (parsed != null) {
                        return@withTimeout parsed
                    }
                }

                // Final fallback to stubs
                AppLogger.d(TAG, "extendedAnalysisJson: Using StubFallbacks")
                null
            } ?: StubFallbacks.extendedAnalysisJson(chapterText)
        } catch (e: TimeoutCancellationException) {
            AppLogger.w(TAG, "extendedAnalysisJson timed out, using fallback")
            StubFallbacks.extendedAnalysisJson(chapterText)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Re-throw CancellationException to allow coroutine cancellation to work properly
            AppLogger.d(TAG, "extendedAnalysisJson cancelled")
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "extendedAnalysisJson error, using fallback", e)
            StubFallbacks.extendedAnalysisJson(chapterText)
        }
    }

    // ==================== Character Extraction Methods ====================

    /**
     * Pass 1: Extract character names from text.
     * Uses GgufEngine if available, falls back to llmModel with prompts, then to StubFallbacks.
     */
    suspend fun pass1ExtractCharacterNames(chapterText: String): List<String> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                // Try GgufEngine first
                val ggufResult = ggufEngine?.extractCharacterNames(chapterText)
                if (!ggufResult.isNullOrEmpty()) {
                    AppLogger.d(TAG, "pass1ExtractCharacterNames: Using GgufEngine result")
                    return@withTimeout ggufResult
                }

                // Fall back to llmModel with prompts
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "pass1ExtractCharacterNames: Using llmModel with prompts")
                    val response = model.generateResponse(
                        systemPrompt = ExtractionPrompts.PASS1_SYSTEM_PROMPT,
                        userPrompt = ExtractionPrompts.buildPass1ExtractNamesPrompt(chapterText, MAX_INPUT_CHARS),
                        maxTokens = 256,
                        temperature = 0.1f
                    )
                    val parsed = parseCharacterNamesFromResponse(response)
                    if (parsed.isNotEmpty()) {
                        return@withTimeout parsed
                    }
                }

                // Final fallback to stubs
                AppLogger.d(TAG, "pass1ExtractCharacterNames: Using StubFallbacks")
                null
            } ?: StubFallbacks.detectCharactersOnPage(chapterText)
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppLogger.d(TAG, "pass1ExtractCharacterNames cancelled")
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "pass1ExtractCharacterNames failed, using fallback", e)
            StubFallbacks.detectCharactersOnPage(chapterText)
        }
    }

    /**
     * Pass 2: Extract dialogs from a page with speaker attribution.
     * Uses GgufEngine if available, falls back to llmModel with prompts, then to StubFallbacks.
     */
    suspend fun pass2ExtractDialogs(pageText: String, characterNames: List<String>): List<GgufEngine.ExtractedDialogEntry> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                // Try GgufEngine first
                val ggufResult = ggufEngine?.extractDialogsFromPage(pageText, characterNames)
                if (!ggufResult.isNullOrEmpty()) {
                    AppLogger.d(TAG, "pass2ExtractDialogs: Using GgufEngine result")
                    return@withTimeout ggufResult
                }

                // Fall back to llmModel with prompts
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "pass2ExtractDialogs: Using llmModel with prompts")
                    val response = model.generateResponse(
                        systemPrompt = ExtractionPrompts.PASS2_SYSTEM_PROMPT,
                        userPrompt = ExtractionPrompts.buildPass2ExtractDialogsPrompt(pageText, characterNames, MAX_INPUT_CHARS),
                        maxTokens = 512,
                        temperature = 0.15f
                    )
                    val parsed = parseDialogsFromResponse(response)
                    if (parsed.isNotEmpty()) {
                        return@withTimeout parsed
                    }
                }

                // Final fallback to stubs
                AppLogger.d(TAG, "pass2ExtractDialogs: Using StubFallbacks")
                null
            } ?: StubFallbacks.extractDialogsFromText(pageText, characterNames)
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppLogger.d(TAG, "pass2ExtractDialogs cancelled")
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "pass2ExtractDialogs failed, using fallback", e)
            StubFallbacks.extractDialogsFromText(pageText, characterNames)
        }
    }

    /**
     * Extract traits and voice profile for characters.
     * Uses GgufEngine if available, falls back to llmModel with prompts, then to StubFallbacks.
     */
    suspend fun pass3ExtractTraitsAndVoiceProfile(
        char1Name: String, char1Context: String,
        char2Name: String? = null, char2Context: String? = null,
        char3Name: String? = null, char3Context: String? = null,
        char4Name: String? = null, char4Context: String? = null
    ): List<Pair<String, Pair<List<String>, Map<String, Any>>>> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                // Try GgufEngine first
                val ggufResult = ggufEngine?.pass3ExtractTraitsAndVoiceProfile(
                    char1Name, char1Context,
                    char2Name, char2Context,
                    char3Name, char3Context,
                    char4Name, char4Context
                )
                if (!ggufResult.isNullOrEmpty()) {
                    AppLogger.d(TAG, "pass3ExtractTraitsAndVoiceProfile: Using GgufEngine result")
                    return@withTimeout ggufResult
                }

                // Fall back to llmModel with prompts - process each character
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "pass3ExtractTraitsAndVoiceProfile: Using llmModel with prompts")
                    val results = mutableListOf<Pair<String, Pair<List<String>, Map<String, Any>>>>()

                    // Process each character
                    val characters = listOfNotNull(
                        Pair(char1Name, char1Context),
                        char2Name?.let { Pair(it, char2Context ?: "") },
                        char3Name?.let { Pair(it, char3Context ?: "") },
                        char4Name?.let { Pair(it, char4Context ?: "") }
                    )

                    for ((name, context) in characters) {
                        val response = model.generateResponse(
                            systemPrompt = ExtractionPrompts.PASS3_SYSTEM_PROMPT,
                            userPrompt = ExtractionPrompts.buildPass3TraitsPrompt(name, context),
                            maxTokens = 256,
                            temperature = 0.15f
                        )
                        val parsed = parsePass3Response(response, name)
                        if (parsed != null) {
                            results.add(Pair(name, parsed))
                        } else {
                            // Use stub fallback for this character
                            results.add(Pair(name, StubFallbacks.singleCharacterTraitsAndProfile(name)))
                        }
                    }

                    if (results.isNotEmpty()) {
                        return@withTimeout results
                    }
                }

                // Final fallback to stubs
                AppLogger.d(TAG, "pass3ExtractTraitsAndVoiceProfile: Using StubFallbacks")
                null
            } ?: buildStubPass3Result(char1Name, char2Name, char3Name, char4Name)
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppLogger.d(TAG, "pass3ExtractTraitsAndVoiceProfile cancelled")
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "pass3ExtractTraitsAndVoiceProfile failed, using fallback", e)
            buildStubPass3Result(char1Name, char2Name, char3Name, char4Name)
        }
    }

    private fun buildStubPass3Result(
        char1Name: String,
        char2Name: String?,
        char3Name: String?,
        char4Name: String?
    ): List<Pair<String, Pair<List<String>, Map<String, Any>>>> {
        val results = mutableListOf<Pair<String, Pair<List<String>, Map<String, Any>>>>()
        results.add(Pair(char1Name, StubFallbacks.singleCharacterTraitsAndProfile(char1Name)))
        char2Name?.let { results.add(Pair(it, StubFallbacks.singleCharacterTraitsAndProfile(it))) }
        char3Name?.let { results.add(Pair(it, StubFallbacks.singleCharacterTraitsAndProfile(it))) }
        char4Name?.let { results.add(Pair(it, StubFallbacks.singleCharacterTraitsAndProfile(it))) }
        return results
    }

    /**
     * Detect characters on a single page.
     * Uses GgufEngine if available, falls back to llmModel with prompts, then to StubFallbacks.
     */
    suspend fun detectCharactersOnPage(pageText: String): List<String> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                // Try GgufEngine first
                val ggufResult = ggufEngine?.detectCharactersOnPage(pageText)
                if (!ggufResult.isNullOrEmpty()) {
                    AppLogger.d(TAG, "detectCharactersOnPage: Using GgufEngine result")
                    return@withTimeout ggufResult
                }

                // Fall back to llmModel with prompts
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "detectCharactersOnPage: Using llmModel with prompts")
                    val response = model.generateResponse(
                        systemPrompt = StoryPrompts.DETECT_CHARACTERS_SYSTEM_PROMPT,
                        userPrompt = StoryPrompts.buildDetectCharactersPrompt(pageText, MAX_INPUT_CHARS),
                        maxTokens = 256,
                        temperature = 0.1f
                    )
                    val parsed = parseCharacterNamesFromResponse(response)
                    if (parsed.isNotEmpty()) {
                        return@withTimeout parsed
                    }
                }

                // Final fallback to stubs
                AppLogger.d(TAG, "detectCharactersOnPage: Using StubFallbacks")
                null
            } ?: StubFallbacks.detectCharactersOnPage(pageText)
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppLogger.d(TAG, "detectCharactersOnPage cancelled")
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "detectCharactersOnPage failed, using fallback", e)
            StubFallbacks.detectCharactersOnPage(pageText)
        }
    }

    /**
     * Infer traits for a character from an excerpt.
     * Uses GgufEngine if available, falls back to llmModel with prompts, then to StubFallbacks.
     */
    suspend fun inferTraitsForCharacter(characterName: String, excerpt: String): List<String> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                // Try GgufEngine first
                val ggufResult = ggufEngine?.inferTraitsForCharacter(characterName, excerpt)
                if (!ggufResult.isNullOrEmpty()) {
                    AppLogger.d(TAG, "inferTraitsForCharacter: Using GgufEngine result")
                    return@withTimeout ggufResult
                }

                // Fall back to llmModel with prompts
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "inferTraitsForCharacter: Using llmModel with prompts")
                    val response = model.generateResponse(
                        systemPrompt = StoryPrompts.INFER_TRAITS_SYSTEM_PROMPT,
                        userPrompt = StoryPrompts.buildInferTraitsPrompt(characterName, excerpt),
                        maxTokens = 256,
                        temperature = 0.15f
                    )
                    val parsed = parseTraitsFromResponse(response)
                    if (parsed.isNotEmpty()) {
                        return@withTimeout parsed
                    }
                }

                // Final fallback to stubs
                AppLogger.d(TAG, "inferTraitsForCharacter: Using StubFallbacks")
                null
            } ?: StubFallbacks.inferTraitsFromName(characterName)
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppLogger.d(TAG, "inferTraitsForCharacter cancelled")
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "inferTraitsForCharacter failed, using fallback", e)
            StubFallbacks.inferTraitsFromName(characterName)
        }
    }

    // ==================== Story Generation ====================

    /**
     * Generate a story based on user prompt.
     * Uses GgufEngine if available, falls back to llmModel with prompts, then to StubFallbacks.
     */
    suspend fun generateStory(userPrompt: String): String = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                // Try GgufEngine first
                val ggufResult = ggufEngine?.generateStory(userPrompt)
                if (!ggufResult.isNullOrEmpty()) {
                    AppLogger.d(TAG, "generateStory: Using GgufEngine result")
                    return@withTimeout ggufResult
                }

                // Fall back to llmModel with prompts
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "generateStory: Using llmModel with prompts")
                    val response = model.generateResponse(
                        systemPrompt = StoryPrompts.STORY_GENERATION_SYSTEM_PROMPT,
                        userPrompt = StoryPrompts.buildStoryPrompt(userPrompt),
                        maxTokens = 2048,
                        temperature = 0.7f
                    )
                    if (response.isNotBlank()) {
                        return@withTimeout response
                    }
                }

                // Final fallback to stubs
                AppLogger.d(TAG, "generateStory: Using StubFallbacks")
                null
            } ?: StubFallbacks.generateStory(userPrompt)
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppLogger.d(TAG, "generateStory cancelled")
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "generateStory failed, using fallback", e)
            StubFallbacks.generateStory(userPrompt)
        }
    }

    /**
     * STORY-003: Remix an existing story based on user instructions.
     * Uses GgufEngine if available, falls back to llmModel with prompts, then to StubFallbacks.
     * @param remixInstruction User's instruction for how to remix (e.g., "make it scary", "from the villain's perspective")
     * @param sourceStory The original story text to remix
     * @return The remixed story content
     */
    suspend fun remixStory(remixInstruction: String, sourceStory: String): String = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            val remixPrompt = StoryPrompts.buildRemixPrompt(remixInstruction, sourceStory)
            withTimeout(LLM_TIMEOUT_MS) {
                // Try GgufEngine first
                val ggufResult = ggufEngine?.remixStory(remixPrompt)
                if (!ggufResult.isNullOrEmpty()) {
                    AppLogger.d(TAG, "remixStory: Using GgufEngine result")
                    return@withTimeout ggufResult
                }

                // Fall back to llmModel with prompts
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "remixStory: Using llmModel with prompts")
                    val response = model.generateResponse(
                        systemPrompt = StoryPrompts.STORY_REMIX_SYSTEM_PROMPT,
                        userPrompt = remixPrompt,
                        maxTokens = 2048,
                        temperature = 0.7f
                    )
                    if (response.isNotBlank()) {
                        return@withTimeout response
                    }
                }

                // Final fallback to stubs
                AppLogger.d(TAG, "remixStory: Using StubFallbacks")
                null
            } ?: StubFallbacks.remixStory(remixInstruction, sourceStory)
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppLogger.d(TAG, "remixStory cancelled")
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "remixStory failed, using fallback", e)
            StubFallbacks.remixStory(remixInstruction, sourceStory)
        }
    }

    /**
     * STORY-002: Generate a story from an inspiration image.
     * Uses multimodal capabilities (if supported) to analyze the image and generate a story.
     * Supports both Gemma3nModel and LiteRtLmEngineImpl with image capabilities.
     *
     * @param imagePath Path to the image file on device
     * @param userPrompt Optional user prompt for story direction
     * @return Generated story text
     */
    suspend fun generateStoryFromImage(imagePath: String, userPrompt: String = ""): String = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            // Check if the current model supports image input
            val capabilities = getModelCapabilities()
            if (!capabilities.supportsImage) {
                AppLogger.w(TAG, "generateStoryFromImage: Current model doesn't support images, using fallback")
                return@withContext StubFallbacks.generateStoryFromImage(imagePath, userPrompt)
            }

            val result = withTimeout(LLM_TIMEOUT_MS) {
                // Try Gemma3nModel first (has dedicated generateStoryFromImage method)
                val gemmaModel = llmModel as? com.dramebaz.app.ai.llm.models.Gemma3nModel
                if (gemmaModel != null) {
                    AppLogger.d(TAG, "generateStoryFromImage: Using Gemma3nModel")
                    return@withTimeout gemmaModel.generateStoryFromImage(imagePath, userPrompt)
                }

                // Try LiteRtLmEngineImpl (use underlying engine's generateFromImage)
                val liteRtModel = llmModel as? com.dramebaz.app.ai.llm.models.LiteRtLmEngineImpl
                if (liteRtModel != null) {
                    AppLogger.d(TAG, "generateStoryFromImage: Using LiteRtLmEngineImpl")
                    return@withTimeout liteRtModel.getUnderlyingEngine().generateFromImage(imagePath, userPrompt)
                }

                // Model doesn't support image generation
                AppLogger.w(TAG, "generateStoryFromImage: No compatible model for image generation")
                null
            }

            // Use fallback if result is null or empty
            if (result.isNullOrEmpty()) {
                AppLogger.w(TAG, "generateStoryFromImage LLM returned empty, using fallback")
                StubFallbacks.generateStoryFromImage(imagePath, userPrompt)
            } else {
                result
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppLogger.d(TAG, "generateStoryFromImage cancelled")
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "generateStoryFromImage failed, using fallback", e)
            StubFallbacks.generateStoryFromImage(imagePath, userPrompt)
        }
    }

    // ==================== Key Moments & Relationships ====================

    /**
     * Extract key moments for a character in a chapter.
     * Uses GgufEngine if available, falls back to llmModel with prompts, then to StubFallbacks.
     */
    suspend fun extractKeyMomentsForCharacter(
        characterName: String,
        chapterText: String,
        chapterTitle: String
    ): List<Map<String, String>> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                // Try GgufEngine first
                val ggufResult = ggufEngine?.extractKeyMomentsForCharacter(characterName, chapterText, chapterTitle)
                if (!ggufResult.isNullOrEmpty()) {
                    AppLogger.d(TAG, "extractKeyMomentsForCharacter: Using GgufEngine result")
                    return@withTimeout ggufResult
                }

                // Fall back to llmModel with prompts
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "extractKeyMomentsForCharacter: Using llmModel with prompts")
                    val response = model.generateResponse(
                        systemPrompt = StoryPrompts.KEY_MOMENTS_SYSTEM_PROMPT,
                        userPrompt = StoryPrompts.buildKeyMomentsPrompt(characterName, chapterText, chapterTitle, MAX_INPUT_CHARS),
                        maxTokens = 512,
                        temperature = 0.15f
                    )
                    val parsed = parseKeyMomentsFromResponse(response)
                    if (parsed.isNotEmpty()) {
                        return@withTimeout parsed
                    }
                }

                // Final fallback to stubs
                AppLogger.d(TAG, "extractKeyMomentsForCharacter: Using StubFallbacks")
                null
            } ?: StubFallbacks.extractKeyMoments(characterName, chapterTitle)
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppLogger.d(TAG, "extractKeyMomentsForCharacter cancelled")
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "extractKeyMomentsForCharacter failed, using fallback", e)
            StubFallbacks.extractKeyMoments(characterName, chapterTitle)
        }
    }

    /**
     * Extract relationships for a character.
     * Uses GgufEngine if available, falls back to llmModel with prompts, then to StubFallbacks.
     */
    suspend fun extractRelationshipsForCharacter(
        characterName: String,
        chapterText: String,
        allCharacterNames: List<String>
    ): List<Map<String, String>> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                // Try GgufEngine first
                val ggufResult = ggufEngine?.extractRelationshipsForCharacter(characterName, chapterText, allCharacterNames)
                if (!ggufResult.isNullOrEmpty()) {
                    AppLogger.d(TAG, "extractRelationshipsForCharacter: Using GgufEngine result")
                    return@withTimeout ggufResult
                }

                // Fall back to llmModel with prompts
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "extractRelationshipsForCharacter: Using llmModel with prompts")
                    val response = model.generateResponse(
                        systemPrompt = StoryPrompts.RELATIONSHIPS_SYSTEM_PROMPT,
                        userPrompt = StoryPrompts.buildRelationshipsPrompt(characterName, chapterText, allCharacterNames, MAX_INPUT_CHARS),
                        maxTokens = 512,
                        temperature = 0.15f
                    )
                    val parsed = parseRelationshipsFromResponse(response)
                    if (parsed.isNotEmpty()) {
                        return@withTimeout parsed
                    }
                }

                // Final fallback to stubs
                AppLogger.d(TAG, "extractRelationshipsForCharacter: Using StubFallbacks")
                null
            } ?: StubFallbacks.extractRelationships(characterName, allCharacterNames)
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppLogger.d(TAG, "extractRelationshipsForCharacter cancelled")
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "extractRelationshipsForCharacter failed, using fallback", e)
            StubFallbacks.extractRelationships(characterName, allCharacterNames)
        }
    }

    /**
     * Suggest voice profiles from JSON.
     * Uses GgufEngine if available, falls back to llmModel with prompts, then to StubFallbacks.
     */
    suspend fun suggestVoiceProfilesJson(charactersWithTraitsJson: String): String? = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                // Try GgufEngine first
                val ggufResult = ggufEngine?.suggestVoiceProfilesJson(charactersWithTraitsJson)
                if (!ggufResult.isNullOrEmpty()) {
                    AppLogger.d(TAG, "suggestVoiceProfilesJson: Using GgufEngine result")
                    return@withTimeout ggufResult
                }

                // Fall back to llmModel with prompts
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "suggestVoiceProfilesJson: Using llmModel with prompts")
                    val response = model.generateResponse(
                        systemPrompt = StoryPrompts.VOICE_PROFILE_SYSTEM_PROMPT,
                        userPrompt = StoryPrompts.buildVoiceProfilesPrompt(charactersWithTraitsJson),
                        maxTokens = 512,
                        temperature = 0.15f
                    )
                    if (response.isNotBlank()) {
                        return@withTimeout extractJsonFromResponse(response)
                    }
                }

                // Final fallback to stubs
                AppLogger.d(TAG, "suggestVoiceProfilesJson: Using StubFallbacks")
                null
            } ?: StubFallbacks.suggestVoiceProfiles(charactersWithTraitsJson)
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppLogger.d(TAG, "suggestVoiceProfilesJson cancelled")
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "suggestVoiceProfilesJson failed, using fallback", e)
            StubFallbacks.suggestVoiceProfiles(charactersWithTraitsJson)
        }
    }

    // ==================== Stub-Only Methods ====================

    /** Stub-only analysis without loading LLM (safe when LLM native lib would crash). */
    fun analyzeChapterStubOnly(chapterText: String): ChapterAnalysisResponse =
        StubFallbacks.analyzeChapter(chapterText)

    /** Stub-only extended analysis without loading LLM. */
    fun extendedAnalysisJsonStubOnly(chapterText: String): String =
        StubFallbacks.extendedAnalysisJson(chapterText)

    // ==================== Additional Extraction Methods ====================

    /**
     * Extract characters and traits in a text segment.
     * Uses GgufEngine if available, falls back to llmModel with prompts, then to StubFallbacks.
     */
    suspend fun extractCharactersAndTraitsInSegment(
        segmentText: String,
        skipNamesWithTraits: Collection<String>,
        namesNeedingTraits: Collection<String>
    ): List<Pair<String, List<String>>> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                // Try GgufEngine first
                val ggufResult = ggufEngine?.extractCharactersAndTraitsInSegment(segmentText, skipNamesWithTraits, namesNeedingTraits)
                if (!ggufResult.isNullOrEmpty()) {
                    AppLogger.d(TAG, "extractCharactersAndTraitsInSegment: Using GgufEngine result")
                    return@withTimeout ggufResult
                }

                // Fall back to llmModel - extract characters first, then traits for each
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "extractCharactersAndTraitsInSegment: Using llmModel with prompts")
                    // First get character names
                    val namesResponse = model.generateResponse(
                        systemPrompt = StoryPrompts.DETECT_CHARACTERS_SYSTEM_PROMPT,
                        userPrompt = StoryPrompts.buildDetectCharactersPrompt(segmentText, MAX_INPUT_CHARS),
                        maxTokens = 256,
                        temperature = 0.1f
                    )
                    val detectedNames = parseCharacterNamesFromResponse(namesResponse)
                        .filter { it !in skipNamesWithTraits }

                    // Then get traits for each character that needs them
                    val results = mutableListOf<Pair<String, List<String>>>()
                    for (name in detectedNames.union(namesNeedingTraits)) {
                        if (name in skipNamesWithTraits) continue
                        val traitsResponse = model.generateResponse(
                            systemPrompt = StoryPrompts.INFER_TRAITS_SYSTEM_PROMPT,
                            userPrompt = StoryPrompts.buildInferTraitsPrompt(name, segmentText),
                            maxTokens = 128,
                            temperature = 0.15f
                        )
                        val traits = parseTraitsFromResponse(traitsResponse)
                        results.add(Pair(name, traits))
                    }
                    if (results.isNotEmpty()) {
                        return@withTimeout results
                    }
                }

                // Final fallback to stubs
                AppLogger.d(TAG, "extractCharactersAndTraitsInSegment: Using StubFallbacks")
                null
            } ?: StubFallbacks.extractCharactersAndTraitsInSegment(segmentText, skipNamesWithTraits, namesNeedingTraits)
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppLogger.d(TAG, "extractCharactersAndTraitsInSegment cancelled")
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "extractCharactersAndTraitsInSegment failed, using fallback", e)
            StubFallbacks.extractCharactersAndTraitsInSegment(segmentText, skipNamesWithTraits, namesNeedingTraits)
        }
    }

    /**
     * Infer traits from a character's name using heuristics.
     * Used as fallback when LLM extraction returns no traits.
     */
    fun inferTraitsFromName(characterName: String): List<String> =
        StubFallbacks.inferTraitsFromName(characterName)

    // ==================== VIS-001: Scene Prompt Generation ====================

    /**
     * VIS-001: Generate an image generation prompt from scene text.
     * Creates a structured prompt suitable for Stable Diffusion or Imagen.
     * Uses GgufEngine if available, falls back to llmModel with prompts, then to StubFallbacks.
     */
    suspend fun generateScenePrompt(
        sceneText: String,
        mood: String? = null,
        characters: List<String> = emptyList()
    ): com.dramebaz.app.data.models.ScenePrompt = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                // Try GgufEngine first
                val ggufResult = ggufEngine?.generateScenePrompt(sceneText, mood, characters)
                if (ggufResult != null) {
                    AppLogger.d(TAG, "generateScenePrompt: Using GgufEngine result")
                    return@withTimeout ggufResult
                }

                // Fall back to llmModel - generate scene prompt text
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "generateScenePrompt: Using llmModel with prompts")
                    val userPrompt = buildScenePromptRequest(sceneText, mood, characters)
                    val response = model.generateResponse(
                        systemPrompt = "You are an image prompt generator. Create a vivid, descriptive prompt for image generation from the scene. Output valid JSON only.",
                        userPrompt = userPrompt,
                        maxTokens = 256,
                        temperature = 0.4f
                    )
                    val parsed = parseScenePromptFromResponse(response, sceneText, mood)
                    if (parsed != null) {
                        return@withTimeout parsed
                    }
                }

                // Final fallback to stubs
                AppLogger.d(TAG, "generateScenePrompt: Using StubFallbacks")
                null
            } ?: StubFallbacks.generateScenePrompt(sceneText, mood, characters)
        } catch (e: TimeoutCancellationException) {
            AppLogger.w(TAG, "generateScenePrompt timed out, using fallback")
            StubFallbacks.generateScenePrompt(sceneText, mood, characters)
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppLogger.d(TAG, "generateScenePrompt cancelled")
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "generateScenePrompt error, using fallback", e)
            StubFallbacks.generateScenePrompt(sceneText, mood, characters)
        }
    }

    // ==================== Raw Text Generation ====================

    /**
     * INS-002: Generate raw text response from LLM for custom prompts.
     * Used for foreshadowing detection and other analysis tasks.
     * Uses GgufEngine if available, falls back to llmModel, then to StubFallbacks.
     */
    suspend fun generateRawText(prompt: String): String = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                // Try GgufEngine first
                val ggufResult = ggufEngine?.generateRaw(prompt)
                if (!ggufResult.isNullOrEmpty()) {
                    AppLogger.d(TAG, "generateRawText: Using GgufEngine result")
                    return@withTimeout ggufResult
                }

                // Fall back to llmModel
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "generateRawText: Using llmModel")
                    val response = model.generateResponse(
                        systemPrompt = "You are a helpful assistant. Answer the following query.",
                        userPrompt = prompt,
                        maxTokens = 1024,
                        temperature = 0.5f
                    )
                    if (response.isNotBlank()) {
                        return@withTimeout response
                    }
                }

                // Final fallback to stubs
                AppLogger.d(TAG, "generateRawText: Using StubFallbacks")
                null
            } ?: StubFallbacks.generateRawText(prompt)
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppLogger.d(TAG, "generateRawText cancelled")
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "generateRawText error, using fallback", e)
            StubFallbacks.generateRawText(prompt)
        }
    }

    // ==================== Serialization ====================

    /**
     * Convert ChapterAnalysisResponse to JSON string.
     */
    fun toJson(response: ChapterAnalysisResponse): String =
        com.google.gson.Gson().toJson(response)

    // ==================== Parsing Helper Methods ====================

    /**
     * Parse LLM response for chapter analysis into ChapterAnalysisResponse.
     */
    private fun parseAnalysisResponse(response: String): ChapterAnalysisResponse? {
        return try {
            val json = extractJsonFromResponse(response)
            if (json.isEmpty()) return null
            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return null
            ChapterAnalysisResponse(
                chapterSummary = parseChapterSummary(obj["chapter_summary"]),
                characters = parseCharacters(obj["characters"]),
                dialogs = parseDialogs(obj["dialogs"]),
                soundCues = parseSoundCues(obj["sound_cues"])
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse analysis response: ${e.message}")
            null
        }
    }

    /**
     * Parse LLM response for extended analysis, returning JSON string.
     */
    private fun parseExtendedAnalysisResponse(response: String): String? {
        return try {
            val json = extractJsonFromResponse(response)
            if (json.isEmpty()) return null
            // Validate JSON by parsing
            gson.fromJson(json, Map::class.java) ?: return null
            json
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse extended analysis response: ${e.message}")
            null
        }
    }

    /**
     * Extract JSON from LLM response (handles markdown code blocks and extra text).
     */
    private fun extractJsonFromResponse(response: String): String {
        var text = response.trim()
        // Remove markdown code block wrapper if present
        if (text.startsWith("```json")) {
            text = text.removePrefix("```json").trim()
        } else if (text.startsWith("```")) {
            text = text.removePrefix("```").trim()
        }
        if (text.endsWith("```")) {
            text = text.removeSuffix("```").trim()
        }
        // Find JSON object bounds
        val jsonStart = text.indexOf("{")
        val jsonEnd = text.lastIndexOf("}") + 1
        if (jsonStart < 0 || jsonEnd <= jsonStart) return ""
        return text.substring(jsonStart, jsonEnd)
    }

    private fun parseChapterSummary(obj: Any?): ChapterSummary? {
        if (obj !is Map<*, *>) return null
        return ChapterSummary(
            title = obj["title"] as? String ?: "Chapter",
            shortSummary = obj["short_summary"] as? String ?: "",
            mainEvents = (obj["main_events"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            emotionalArc = (obj["emotional_arc"] as? List<*>)?.mapNotNull {
                if (it is Map<*, *>) EmotionalSegment(
                    segment = it["segment"] as? String ?: "",
                    emotion = it["emotion"] as? String ?: "neutral",
                    intensity = (it["intensity"] as? Number)?.toFloat() ?: 0.5f
                ) else null
            } ?: emptyList()
        )
    }

    private fun parseCharacters(obj: Any?): List<CharacterStub>? {
        if (obj !is List<*>) return null
        return obj.mapNotNull {
            if (it is Map<*, *>) {
                val vp = (it["voice_profile"] as? Map<*, *>)?.mapValues { (_, v) ->
                    when (v) {
                        is Number -> v.toDouble() as Any
                        is String -> (v.toString().toDoubleOrNull() ?: 1.0) as Any
                        else -> 1.0 as Any
                    }
                } as? Map<String, Any>
                CharacterStub(
                    name = it["name"] as? String ?: "Unknown",
                    traits = (it["traits"] as? List<*>)?.mapNotNull { t -> t as? String },
                    voiceProfile = vp
                )
            } else null
        }
    }

    private fun parseDialogs(obj: Any?): List<Dialog>? {
        if (obj !is List<*>) return null
        return obj.mapNotNull {
            if (it is Map<*, *>) {
                val p = it["prosody"] as? Map<*, *>
                val prosody = p?.let { pr ->
                    ProsodyHints(
                        pitchVariation = pr["pitch_variation"] as? String ?: "normal",
                        speed = pr["speed"] as? String ?: "normal",
                        stressPattern = pr["stress_pattern"] as? String ?: ""
                    )
                }
                val confidence = (it["confidence"] as? Number)?.toFloat() ?: 1.0f
                Dialog(
                    speaker = it["speaker"] as? String ?: it["character"] as? String ?: "unknown",
                    dialog = it["dialog"] as? String ?: it["text"] as? String ?: "",
                    emotion = it["emotion"] as? String ?: "neutral",
                    intensity = (it["intensity"] as? Number)?.toFloat() ?: 0.5f,
                    prosody = prosody,
                    confidence = confidence
                )
            } else null
        }
    }

    private fun parseSoundCues(obj: Any?): List<SoundCueModel>? {
        if (obj !is List<*>) return null
        return obj.mapNotNull {
            if (it is Map<*, *>)
                SoundCueModel(
                    event = it["event"] as? String ?: "effect",
                    soundPrompt = it["sound_prompt"] as? String ?: "",
                    duration = (it["duration"] as? Number)?.toFloat() ?: 2f,
                    category = it["category"] as? String ?: "effect"
                )
            else null
        }
    }

    // ==================== LLM Response Parsing Helpers ====================

    /**
     * Parse character names from LLM response.
     * Expected format: {"characters": ["Name1", "Name2"]} or {"names": ["Name1", "Name2"]}
     */
    private fun parseCharacterNamesFromResponse(response: String): List<String> {
        return try {
            val cleanedResponse = extractJsonFromResponse(response)
            val map = gson.fromJson(cleanedResponse, Map::class.java)
            val names = (map["characters"] as? List<*>) ?: (map["names"] as? List<*>)
            names?.filterIsInstance<String>()?.filter { it.isNotBlank() } ?: emptyList()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse character names from response: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parse dialogs from LLM response.
     * Expected format: {"dialogs": [{"speaker": "...", "text": "...", "emotion": "...", "intensity": 0.5}]}
     */
    private fun parseDialogsFromResponse(response: String): List<GgufEngine.ExtractedDialogEntry> {
        return try {
            val cleanedResponse = extractJsonFromResponse(response)
            val map = gson.fromJson(cleanedResponse, Map::class.java)
            val dialogs = map["dialogs"] as? List<*> ?: return emptyList()
            dialogs.mapNotNull { item ->
                if (item is Map<*, *>) {
                    GgufEngine.ExtractedDialogEntry(
                        speaker = item["speaker"] as? String ?: "Unknown",
                        text = item["text"] as? String ?: "",
                        emotion = item["emotion"] as? String ?: "neutral",
                        intensity = (item["intensity"] as? Number)?.toFloat() ?: 0.5f
                    )
                } else null
            }.filter { it.text.isNotBlank() }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse dialogs from response: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parse traits from LLM response.
     * Expected format: {"traits": ["trait1", "trait2"]} or full pass3 format
     */
    private fun parseTraitsFromResponse(response: String): List<String> {
        return try {
            val cleanedResponse = extractJsonFromResponse(response)
            val map = gson.fromJson(cleanedResponse, Map::class.java)
            val traits = map["traits"] as? List<*>
            traits?.filterIsInstance<String>()?.filter { it.isNotBlank() } ?: emptyList()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse traits from response: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parse voice profile from LLM response for pass3.
     * Expected format: {"character": "...", "traits": [...], "voice_profile": {...}}
     */
    private fun parsePass3Response(response: String, characterName: String): Pair<List<String>, Map<String, Any>>? {
        return try {
            val cleanedResponse = extractJsonFromResponse(response)
            val map = gson.fromJson(cleanedResponse, Map::class.java)
            val traits = (map["traits"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            val voiceProfile = (map["voice_profile"] as? Map<*, *>)?.mapKeys { it.key.toString() }
                ?.mapValues { it.value as Any } ?: emptyMap()
            if (traits.isNotEmpty() || voiceProfile.isNotEmpty()) {
                Pair(traits, voiceProfile)
            } else null
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse pass3 response for $characterName: ${e.message}")
            null
        }
    }

    /**
     * Parse key moments from LLM response.
     * Expected format: {"moments": [{"chapter": "...", "moment": "...", "significance": "..."}]}
     */
    private fun parseKeyMomentsFromResponse(response: String): List<Map<String, String>> {
        return try {
            val cleanedResponse = extractJsonFromResponse(response)
            val map = gson.fromJson(cleanedResponse, Map::class.java)
            val moments = map["moments"] as? List<*> ?: return emptyList()
            moments.mapNotNull { item ->
                if (item is Map<*, *>) {
                    mapOf(
                        "chapter" to (item["chapter"] as? String ?: ""),
                        "moment" to (item["moment"] as? String ?: ""),
                        "significance" to (item["significance"] as? String ?: "")
                    )
                } else null
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse key moments from response: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parse relationships from LLM response.
     * Expected format: {"relationships": [{"character": "...", "relationship": "...", "nature": "..."}]}
     */
    private fun parseRelationshipsFromResponse(response: String): List<Map<String, String>> {
        return try {
            val cleanedResponse = extractJsonFromResponse(response)
            val map = gson.fromJson(cleanedResponse, Map::class.java)
            val relationships = map["relationships"] as? List<*> ?: return emptyList()
            relationships.mapNotNull { item ->
                if (item is Map<*, *>) {
                    mapOf(
                        "character" to (item["character"] as? String ?: ""),
                        "relationship" to (item["relationship"] as? String ?: ""),
                        "nature" to (item["nature"] as? String ?: "")
                    )
                } else null
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse relationships from response: ${e.message}")
            emptyList()
        }
    }

    /**
     * Build a scene prompt request for the LLM.
     */
    private fun buildScenePromptRequest(sceneText: String, mood: String?, characters: List<String>): String {
        val moodPart = mood?.let { "Mood: $it\n" } ?: ""
        val charsPart = if (characters.isNotEmpty()) "Characters: ${characters.joinToString(", ")}\n" else ""
        return """Create an image generation prompt for this scene.
$moodPart$charsPart
Scene: ${sceneText.take(1000)}

Return ONLY valid JSON:
{"prompt": "detailed image generation prompt", "style": "art style", "mood": "detected mood"}"""
    }

    /**
     * Parse scene prompt from LLM response.
     */
    private fun parseScenePromptFromResponse(response: String, sceneText: String, mood: String?): com.dramebaz.app.data.models.ScenePrompt? {
        return try {
            val cleanedResponse = extractJsonFromResponse(response)
            val map = gson.fromJson(cleanedResponse, Map::class.java)
            val prompt = map["prompt"] as? String ?: return null
            val style = map["style"] as? String ?: "realistic"
            val detectedMood = map["mood"] as? String ?: mood ?: "neutral"
            com.dramebaz.app.data.models.ScenePrompt(
                prompt = prompt,
                style = style,
                mood = detectedMood
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse scene prompt from response: ${e.message}")
            null
        }
    }
}

