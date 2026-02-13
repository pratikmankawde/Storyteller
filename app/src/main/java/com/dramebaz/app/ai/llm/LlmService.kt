package com.dramebaz.app.ai.llm

import android.content.Context
import com.dramebaz.app.ai.llm.models.ExtractedDialog
import com.dramebaz.app.ai.llm.models.GenerationParams
import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.models.LlmModelFactory
import com.dramebaz.app.ai.llm.models.LlmModelHolder
import com.dramebaz.app.ai.llm.models.ModelFormat
import com.dramebaz.app.ai.llm.models.SessionParams
import com.dramebaz.app.ai.llm.models.SessionParamsSupport
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
 * Architecture:
 * - Uses LlmModelFactory to create the appropriate model (GGUF, LiteRT-LM, or MediaPipe)
 * - All model implementations conform to the LlmModel interface (Strategy Pattern)
 * - Uses only polymorphic interface methods - no type-checking for specific implementations
 * - Uses StubFallbacks for fallback when LLM is unavailable
 *
 * Usage:
 * ```
 * // Initialize once at app startup
 * LlmService.setApplicationContext(context)
 *
 * // Use for analysis
 * val result = LlmService.analyzeChapter(text)
 * ```
 */
object LlmService {
    private const val TAG = "LlmService"
    private const val LLM_TIMEOUT_MS = 600_000L  // 10 minutes timeout for slow models
    private const val MAX_INPUT_CHARS = 10_000

    private var appContext: Context? = null
    private var llmModel: LlmModel? = null
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
     * Uses the singleton LlmModelHolder to prevent multiple model instances in GPU memory.
     */
    suspend fun ensureInitialized(): Boolean = initMutex.withLock {
        if (initialized) return@withLock llmModel?.isModelLoaded() == true

        val ctx = appContext ?: return@withLock false
        initialized = true

        return@withLock withContext(Dispatchers.IO) {
            try {
                // Use the singleton model holder to get/load the shared model
                llmModel = LlmModelHolder.getOrLoadModel(ctx)
                val loaded = llmModel?.isModelLoaded() == true
                if (loaded) {
                    AppLogger.i(TAG, "✅ Using shared LLM model: ${llmModel?.getExecutionProvider()}")
                } else {
                    AppLogger.w(TAG, "⚠️ Shared model not loaded")
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
     * Uses the singleton model holder to prevent multiple model instances.
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
                // Use the singleton model holder with backend preference and saved model path
                llmModel = LlmModelHolder.getOrLoadModelWithBackend(
                    context,
                    settings.preferredBackend,
                    settings.selectedModelPath
                )
                val loaded = llmModel?.isModelLoaded() == true

                if (loaded) {
                    AppLogger.i(TAG, "✅ Using shared model: ${llmModel?.getExecutionProvider()}")
                } else {
                    AppLogger.w(TAG, "⚠️ Shared model not loaded")
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
     * The shared model is managed by LlmModelHolder.
     */
    fun release() {
        // Don't release llmModel directly - it's managed by LlmModelHolder
        llmModel = null
        initialized = false
    }

    /**
     * Fully release all resources including the shared model.
     * Call this when the app is closing or switching models.
     */
    fun releaseAll() {
        llmModel = null
        LlmModelHolder.release()
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
    fun isReady(): Boolean = llmModel?.isModelLoaded() == true

    /**
     * Check if running in full LLM mode (not stub fallback).
     */
    fun isUsingLlm(): Boolean = isReady()

    /**
     * Returns the execution provider: "GPU (Vulkan)" or "CPU" or "unknown"
     */
    fun getExecutionProvider(): String = llmModel?.getExecutionProvider() ?: "unknown"

    /**
     * Returns true if using GPU for inference.
     */
    fun isUsingGpu(): Boolean = llmModel?.isUsingGpu() == true

    /**
     * Get the format/type of the currently loaded model using polymorphic interface.
     * No type-checking required - uses ModelInfo from the model itself.
     *
     * @return ModelType based on ModelInfo.format, or null if no model is loaded
     */
    fun getLoadedModelType(): LlmModelFactory.ModelType? {
        val modelInfo = llmModel?.getModelInfo() ?: return null
        return when (modelInfo.format) {
            ModelFormat.LITERTLM -> LlmModelFactory.ModelType.LITERTLM
            ModelFormat.GGUF -> LlmModelFactory.ModelType.GGUF
            ModelFormat.MEDIAPIPE -> LlmModelFactory.ModelType.MEDIAPIPE
            ModelFormat.UNKNOWN -> null
        }
    }

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
     * Uses polymorphism via LlmModel.getModelCapabilities() - no type-checking required.
     * This follows the Open/Closed Principle: adding new engine types doesn't require
     * modifying this method.
     *
     * @return ModelCapabilities with model name and feature support flags, or UNKNOWN if no model loaded
     */
    fun getModelCapabilities(): ModelCapabilities {
        // Polymorphic call - each LlmModel implementation provides its own capabilities
        return llmModel?.getModelCapabilities() ?: ModelCapabilities.UNKNOWN
    }

    // ==================== Session Parameters ====================

    /**
     * Get current session parameters for the loaded model.
     * Session parameters persist across multiple inference calls.
     *
     * @return Current session parameters, or defaults if no model is loaded
     */
    fun getSessionParams(): SessionParams {
        return llmModel?.getSessionParams() ?: SessionParams.DEFAULT
    }

    /**
     * Update session parameters for the loaded model.
     * Some engines may require model reload for changes to take effect.
     *
     * @param params New session parameters
     * @return true if parameters were applied successfully, false if no model loaded
     */
    fun updateSessionParams(params: SessionParams): Boolean {
        return llmModel?.updateSessionParams(params) ?: false
    }

    /**
     * Get information about which session parameters the current engine supports.
     * Use this to enable/disable UI controls based on engine capabilities.
     *
     * @return Support information for session parameters, or NONE if no model loaded
     */
    fun getSessionParamsSupport(): SessionParamsSupport {
        return llmModel?.getSessionParamsSupport() ?: SessionParamsSupport.NONE
    }

    /**
     * Retry loading the model. Call this if user wants to retry after failure.
     * Uses singleton model holder.
     */
    suspend fun retryLoadModel(context: Context): Boolean = initMutex.withLock {
        releaseAll()  // Use releaseAll to release the shared model
        initialized = false
        return@withLock withContext(Dispatchers.IO) {
            llmModel = LlmModelHolder.getOrLoadModel(context)
            llmModel?.isModelLoaded() == true
        }
    }

    /**
     * Reload the model with specific settings (model type and backend).
     * Called when user changes LLM settings.
     * Uses the singleton model holder to prevent multiple model instances.
     *
     * @param context Android context
     * @param settings LLM settings with model type and backend preference
     * @return true if model loaded successfully
     */
    suspend fun reloadWithSettings(context: Context, settings: LlmSettings): Boolean = initMutex.withLock {
        AppLogger.i(TAG, "Reloading LLM with settings: model=${settings.selectedModelType}, backend=${settings.preferredBackend}, path=${settings.selectedModelPath}")

        // Notify all components that a model switch is starting
        LlmModelHolder.beginModelSwitch()

        try {
            // Release all models including shared model (user is explicitly switching)
            releaseAll()
            initialized = true  // Prevent re-init during reload

            return@withLock withContext(Dispatchers.IO) {
                try {
                    // Use the singleton model holder with backend preference and specific model path
                    llmModel = LlmModelHolder.getOrLoadModelWithBackend(
                        context,
                        settings.preferredBackend,
                        settings.selectedModelPath
                    )
                    val loaded = llmModel?.isModelLoaded() == true

                    if (loaded) {
                        AppLogger.i(TAG, "✅ Model loaded successfully: ${llmModel?.getExecutionProvider()}")
                    } else {
                        AppLogger.w(TAG, "⚠️ Model failed to load")
                    }

                    loaded
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to reload model with settings", e)
                    false
                }
            }
        } finally {
            // Always notify that model switch is complete
            LlmModelHolder.endModelSwitch()
        }
    }

    // ==================== Analysis Methods ====================

    /**
     * Analyze chapter for summary, characters, dialogs, and sound cues.
     * Uses the polymorphic LlmModel interface - no type-checking required.
     */
    suspend fun analyzeChapter(chapterText: String): ChapterAnalysisResponse = withContext(Dispatchers.IO) {
        ensureInitialized()
        beginInference()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "analyzeChapter: Using llmModel ($MAX_INPUT_CHARS chars)")
                    val response = model.generateResponse(
                        systemPrompt = AnalysisPrompts.ANALYSIS_SYSTEM_PROMPT,
                        userPrompt = AnalysisPrompts.buildAnalysisPrompt(chapterText, MAX_INPUT_CHARS),
                        params = GenerationParams.JSON_EXTRACTION.copy(maxTokens = 2048)
                    )
                    AppLogger.d(TAG, "analyzeChapter: LLM response length=${response.length}")
                    AppLogger.d(TAG, "analyzeChapter: LLM response preview=${response.take(500)}")
                    val parsed = LlmResponseParser.parseAnalysisResponse(response)
                    if (parsed != null) {
                        AppLogger.d(TAG, "analyzeChapter: Parsed successfully - characters=${parsed.characters?.size}, dialogs=${parsed.dialogs?.size}")
                        return@withTimeout parsed
                    } else {
                        AppLogger.w(TAG, "analyzeChapter: parseAnalysisResponse returned null for response: ${response.take(300)}")
                    }
                }

                // Fallback to stubs
                AppLogger.d(TAG, "analyzeChapter: Using StubFallbacks")
                null
            } ?: StubFallbacks.analyzeChapter(chapterText)
        } catch (e: TimeoutCancellationException) {
            AppLogger.w(TAG, "analyzeChapter timed out, using fallback")
            StubFallbacks.analyzeChapter(chapterText)
        } catch (e: kotlinx.coroutines.CancellationException) {
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
     * Uses the polymorphic LlmModel interface - no type-checking required.
     */
    suspend fun extendedAnalysisJson(chapterText: String): String = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "extendedAnalysisJson: Using llmModel")
                    val response = model.generateResponse(
                        systemPrompt = AnalysisPrompts.EXTENDED_ANALYSIS_SYSTEM_PROMPT,
                        userPrompt = AnalysisPrompts.buildExtendedAnalysisPrompt(chapterText, MAX_INPUT_CHARS),
                        params = GenerationParams.JSON_EXTRACTION
                    )
                    val parsed = LlmResponseParser.parseExtendedAnalysisResponse(response)
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
     * Uses the polymorphic LlmModel interface - no type-checking required.
     */
    suspend fun pass1ExtractCharacterNames(chapterText: String): List<String> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "pass1ExtractCharacterNames: Using llmModel")
                    val response = model.generateResponse(
                        systemPrompt = ExtractionPrompts.PASS1_SYSTEM_PROMPT,
                        userPrompt = ExtractionPrompts.buildPass1ExtractNamesPrompt(chapterText, MAX_INPUT_CHARS),
                        params = GenerationParams.SHORT_EXTRACTION
                    )
                    val parsed = LlmResponseParser.parseCharacterNamesFromResponse(response)
                    if (parsed.isNotEmpty()) {
                        return@withTimeout parsed
                    }
                }

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
     * Uses the polymorphic LlmModel interface - no type-checking required.
     */
    suspend fun pass2ExtractDialogs(pageText: String, characterNames: List<String>): List<ExtractedDialog> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "pass2ExtractDialogs: Using llmModel")
                    val response = model.generateResponse(
                        systemPrompt = ExtractionPrompts.PASS2_SYSTEM_PROMPT,
                        userPrompt = ExtractionPrompts.buildPass2ExtractDialogsPrompt(pageText, characterNames, MAX_INPUT_CHARS),
                        params = GenerationParams.JSON_EXTRACTION.copy(maxTokens = 512)
                    )
                    val parsed = LlmResponseParser.parseDialogsFromResponse(response)
                    if (parsed.isNotEmpty()) {
                        return@withTimeout parsed
                    }
                }

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
     * Uses the polymorphic LlmModel interface - no type-checking required.
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
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "pass3ExtractTraitsAndVoiceProfile: Using llmModel")
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
                            params = GenerationParams.JSON_EXTRACTION.copy(maxTokens = 256)
                        )
                        val parsed = LlmResponseParser.parsePass3Response(response, name)
                        if (parsed != null) {
                            results.add(Pair(name, parsed))
                        } else {
                            results.add(Pair(name, StubFallbacks.singleCharacterTraitsAndProfile(name)))
                        }
                    }

                    if (results.isNotEmpty()) {
                        return@withTimeout results
                    }
                }

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
     * Uses the polymorphic LlmModel interface - no type-checking required.
     */
    suspend fun detectCharactersOnPage(pageText: String): List<String> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "detectCharactersOnPage: Using llmModel")
                    val response = model.generateResponse(
                        systemPrompt = StoryPrompts.DETECT_CHARACTERS_SYSTEM_PROMPT,
                        userPrompt = StoryPrompts.buildDetectCharactersPrompt(pageText, MAX_INPUT_CHARS),
                        params = GenerationParams.SHORT_EXTRACTION
                    )
                    val parsed = LlmResponseParser.parseCharacterNamesFromResponse(response)
                    if (parsed.isNotEmpty()) {
                        return@withTimeout parsed
                    }
                }

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
     * Uses the polymorphic LlmModel interface - no type-checking required.
     */
    suspend fun inferTraitsForCharacter(characterName: String, excerpt: String): List<String> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "inferTraitsForCharacter: Using llmModel")
                    val response = model.generateResponse(
                        systemPrompt = StoryPrompts.INFER_TRAITS_SYSTEM_PROMPT,
                        userPrompt = StoryPrompts.buildInferTraitsPrompt(characterName, excerpt),
                        params = GenerationParams.JSON_EXTRACTION.copy(maxTokens = 256)
                    )
                    val parsed = LlmResponseParser.parseTraitsFromResponse(response)
                    if (parsed.isNotEmpty()) {
                        return@withTimeout parsed
                    }
                }

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
     * Uses the polymorphic LlmModel interface - no type-checking required.
     */
    suspend fun generateStory(userPrompt: String): String = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "generateStory: Using llmModel")
                    val response = model.generateResponse(
                        systemPrompt = StoryPrompts.STORY_GENERATION_SYSTEM_PROMPT,
                        userPrompt = StoryPrompts.buildStoryPrompt(userPrompt),
                        params = GenerationParams.CREATIVE.copy(maxTokens = 2048)
                    )
                    if (response.isNotBlank()) {
                        return@withTimeout response
                    }
                }

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
     * Remix an existing story based on user instructions.
     * Uses the polymorphic LlmModel interface - no type-checking required.
     * @param remixInstruction User's instruction for how to remix (e.g., "make it scary", "from the villain's perspective")
     * @param sourceStory The original story text to remix
     * @return The remixed story content
     */
    suspend fun remixStory(remixInstruction: String, sourceStory: String): String = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            val remixPrompt = StoryPrompts.buildRemixPrompt(remixInstruction, sourceStory)
            withTimeout(LLM_TIMEOUT_MS) {
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "remixStory: Using llmModel")
                    val response = model.generateResponse(
                        systemPrompt = StoryPrompts.STORY_REMIX_SYSTEM_PROMPT,
                        userPrompt = remixPrompt,
                        params = GenerationParams.CREATIVE.copy(maxTokens = 2048)
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
     * Generate a story from an inspiration image.
     * Uses the polymorphic LlmModel.generateFromImage() - no type-checking required.
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
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "generateStoryFromImage: Using llmModel.generateFromImage()")
                    val response = model.generateFromImage(imagePath, userPrompt)
                    if (response.isNotBlank()) {
                        return@withTimeout response
                    }
                }
                AppLogger.w(TAG, "generateStoryFromImage: Model returned empty")
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
     * Uses the polymorphic LlmModel interface - no type-checking required.
     */
    suspend fun extractKeyMomentsForCharacter(
        characterName: String,
        chapterText: String,
        chapterTitle: String
    ): List<Map<String, String>> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "extractKeyMomentsForCharacter: Using llmModel")
                    val response = model.generateResponse(
                        systemPrompt = StoryPrompts.KEY_MOMENTS_SYSTEM_PROMPT,
                        userPrompt = StoryPrompts.buildKeyMomentsPrompt(characterName, chapterText, chapterTitle, MAX_INPUT_CHARS),
                        params = GenerationParams.JSON_EXTRACTION.copy(maxTokens = 512)
                    )
                    val parsed = LlmResponseParser.parseKeyMomentsFromResponse(response)
                    if (parsed.isNotEmpty()) {
                        return@withTimeout parsed
                    }
                }

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
     * Uses the polymorphic LlmModel interface - no type-checking required.
     */
    suspend fun extractRelationshipsForCharacter(
        characterName: String,
        chapterText: String,
        allCharacterNames: List<String>
    ): List<Map<String, String>> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "extractRelationshipsForCharacter: Using llmModel")
                    val response = model.generateResponse(
                        systemPrompt = StoryPrompts.RELATIONSHIPS_SYSTEM_PROMPT,
                        userPrompt = StoryPrompts.buildRelationshipsPrompt(characterName, chapterText, allCharacterNames, MAX_INPUT_CHARS),
                        params = GenerationParams.JSON_EXTRACTION.copy(maxTokens = 512)
                    )
                    val parsed = LlmResponseParser.parseRelationshipsFromResponse(response)
                    if (parsed.isNotEmpty()) {
                        return@withTimeout parsed
                    }
                }

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
     * Uses the polymorphic LlmModel interface - no type-checking required.
     */
    suspend fun suggestVoiceProfilesJson(charactersWithTraitsJson: String): String? = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "suggestVoiceProfilesJson: Using llmModel")
                    val response = model.generateResponse(
                        systemPrompt = StoryPrompts.VOICE_PROFILE_SYSTEM_PROMPT,
                        userPrompt = StoryPrompts.buildVoiceProfilesPrompt(charactersWithTraitsJson),
                        params = GenerationParams.JSON_EXTRACTION.copy(maxTokens = 512)
                    )
                    if (response.isNotBlank()) {
                        return@withTimeout LlmResponseParser.extractJsonFromResponse(response)
                    }
                }

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
     * Uses the polymorphic LlmModel interface - no type-checking required.
     */
    suspend fun extractCharactersAndTraitsInSegment(
        segmentText: String,
        skipNamesWithTraits: Collection<String>,
        namesNeedingTraits: Collection<String>
    ): List<Pair<String, List<String>>> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "extractCharactersAndTraitsInSegment: Using llmModel")
                    // First get character names
                    val namesResponse = model.generateResponse(
                        systemPrompt = StoryPrompts.DETECT_CHARACTERS_SYSTEM_PROMPT,
                        userPrompt = StoryPrompts.buildDetectCharactersPrompt(segmentText, MAX_INPUT_CHARS),
                        params = GenerationParams.SHORT_EXTRACTION
                    )
                    val detectedNames = LlmResponseParser.parseCharacterNamesFromResponse(namesResponse)
                        .filter { it !in skipNamesWithTraits }

                    // Then get traits for each character that needs them
                    val results = mutableListOf<Pair<String, List<String>>>()
                    for (name in detectedNames.union(namesNeedingTraits)) {
                        if (name in skipNamesWithTraits) continue
                        val traitsResponse = model.generateResponse(
                            systemPrompt = StoryPrompts.INFER_TRAITS_SYSTEM_PROMPT,
                            userPrompt = StoryPrompts.buildInferTraitsPrompt(name, segmentText),
                            params = GenerationParams.SHORT_EXTRACTION.copy(maxTokens = 128)
                        )
                        val traits = LlmResponseParser.parseTraitsFromResponse(traitsResponse)
                        results.add(Pair(name, traits))
                    }
                    if (results.isNotEmpty()) {
                        return@withTimeout results
                    }
                }

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
     * Generate an image generation prompt from scene text.
     * Creates a structured prompt suitable for Stable Diffusion or Imagen.
     * Uses the polymorphic LlmModel interface - no type-checking required.
     */
    suspend fun generateScenePrompt(
        sceneText: String,
        mood: String? = null,
        characters: List<String> = emptyList()
    ): com.dramebaz.app.data.models.ScenePrompt = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "generateScenePrompt: Using llmModel")
                    val userPrompt = buildScenePromptRequest(sceneText, mood, characters)
                    val response = model.generateResponse(
                        systemPrompt = "You are an image prompt generator. Create a vivid, descriptive prompt for image generation from the scene. Output valid JSON only.",
                        userPrompt = userPrompt,
                        params = GenerationParams(maxTokens = 256, temperature = 0.4f)
                    )
                    val parsed = LlmResponseParser.parseScenePromptFromResponse(response, mood)
                    if (parsed != null) {
                        return@withTimeout parsed
                    }
                }

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
     * Generate raw text response from LLM for custom prompts.
     * Used for foreshadowing detection and other analysis tasks.
     * Uses the polymorphic LlmModel interface - no type-checking required.
     */
    suspend fun generateRawText(prompt: String): String = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                val model = llmModel
                if (model != null && model.isModelLoaded()) {
                    AppLogger.d(TAG, "generateRawText: Using llmModel")
                    val response = model.generateResponse(
                        systemPrompt = "You are a helpful assistant. Answer the following query.",
                        userPrompt = prompt,
                        params = GenerationParams.DEFAULT
                    )
                    if (response.isNotBlank()) {
                        return@withTimeout response
                    }
                }

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
}

