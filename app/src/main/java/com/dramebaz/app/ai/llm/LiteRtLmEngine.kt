package com.dramebaz.app.ai.llm

import android.app.ActivityManager
import android.content.Context
import com.dramebaz.app.ai.llm.models.ModelNameUtils
import com.dramebaz.app.utils.AppLogger
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.dramebaz.app.ai.tts.LibrittsSpeakerCatalog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * LiteRT-LM engine wrapper for Gemma 3n E2B Lite model inference.
 * Config-driven: backend order, sampling (topK/topP/temperature), model path, and memory check
 * from assets/models/llm_model_config.json. Uses async streaming (sendMessageAsync) when available.
 *
 * Two-Pass Character Analysis Workflow:
 * - Pass 1: Extract character names; output {"characters": ["Name1", "Name2", ...]}
 * - Pass 2: Extract dialogs as array of {character: dialog} mappings in order of appearance
 *
 * Model: gemma-3n-E2B-it-int4.litertlm (path from config).
 */
/**
 * @param context Android context
 * @param externalModelPath Optional explicit path to .litertlm model file. If null, uses config-based discovery.
 */
class LiteRtLmEngine(private val context: Context, private val externalModelPath: String? = null) {
    companion object {
        private const val TAG = "LiteRtLmEngine"
        private const val CONFIG_ASSET_PATH = "models/llm_model_config.json"
        private const val MAX_TOKENS_DEFAULT = 2048

        // Memory check: require only 50% of estimated peak since LiteRT-LM uses memory mapping
        // and int4 quantized models have much lower runtime memory footprint than peak estimate
        private const val MEMORY_SAFETY_FRACTION = 0.5

        // Max total prompt length (system + user including segment text) in characters
        private const val PASS1_MAX_PROMPT_CHARS = 15_000
        private const val PASS2_MAX_PROMPT_CHARS = 7_000
        private const val PASS1_TEMPLATE_CHARS = 200
        private const val PASS2_TEMPLATE_CHARS = 600
        private const val PASS1_MAX_SEGMENT_CHARS = PASS1_MAX_PROMPT_CHARS - PASS1_TEMPLATE_CHARS
        private const val PASS2_MAX_SEGMENT_CHARS = PASS2_MAX_PROMPT_CHARS - PASS2_TEMPLATE_CHARS
        private const val TOKEN_REDUCTION_ON_ERROR = 500
        private const val CHARS_PER_TOKEN_ESTIMATE = 4

        private const val TOKEN_LIMIT_ERROR = "Max number of tokens reached"

        // STORY-002: Image-to-Story prompts
        private const val IMAGE_STORY_SYSTEM_PROMPT = """You are a creative storytelling AI.
Analyze the given image and generate a complete, engaging short story inspired by it.
The story should:
- Be 500-800 words long
- Have a clear beginning, middle, and end
- Include vivid descriptions and dialogue
- Capture the mood and elements visible in the image
- Be suitable for all ages"""

        private const val IMAGE_STORY_USER_PROMPT_PREFIX = """Look at this image and write a creative short story inspired by what you see.
Consider the setting, characters (if any), mood, colors, and atmosphere in the image.

User's additional direction: """
    }

    /**
     * Data class for extracted character with voice profile from Pass 1.
     */
    data class CharacterWithProfile(
        val name: String,
        val traits: List<String>,
        val voiceProfile: Map<String, Any>
    )

    /**
     * Data class for extracted dialog from Pass 2.
     */
    data class ExtractedDialog(
        val speaker: String,
        val text: String,
        val emotion: String = "neutral",
        val intensity: Float = 0.5f
    )

    private val gson = Gson()
    private var engine: Engine? = null
    private var isInitialized = false
    private var chosenBackend: Backend? = null
    private var modelConfig: LlmModelEntry? = null

    // Mutex to serialize LLM calls - LiteRT-LM only supports one session at a time
    private val sessionMutex = Mutex()

    // Preferred backend set by user settings (null = use config order)
    private var preferredBackend: Backend? = null

    /**
     * Set the preferred backend for initialization.
     * Must be called before initialize().
     */
    fun setPreferredBackend(backend: Backend?) {
        preferredBackend = backend
    }

    private fun loadModelConfig(): LlmModelEntry? {
        if (modelConfig != null) return modelConfig
        return try {
            val json = context.assets.open(CONFIG_ASSET_PATH).bufferedReader().use { it.readText() }
            val root = gson.fromJson(json, LlmModelConfigRoot::class.java)
            val entry = root.models.getOrNull(root.selectedModelId) ?: root.models.firstOrNull()
            modelConfig = entry
            AppLogger.d(TAG, "Loaded model config: ${entry?.displayName}, skipMemoryCheck=${entry?.skipMemoryCheck}")
            entry
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not load LLM config from assets, using fallback: ${e.message}")
            modelConfig = LlmModelEntry(
                modelFileName = "gemma-3n-E2B-it-int4.litertlm",
                displayName = "Gemma 3n E2B (int4)",
                estimatedPeakMemoryInBytes = 4294967296L, // 4GB - more realistic for int4 model
                defaultConfig = LlmDefaultConfig(topK = 48, topP = 0.9, temperature = 0.6, maxTokens = 2048, accelerators = "gpu,cpu"),
                pass1 = LlmPassOverride(temperature = 0.1, maxTokens = 100),
                pass2 = LlmPassOverride(temperature = 0.35, topP = 0.85, maxTokens = 2100),
                skipMemoryCheck = true, // Enable by default in fallback
                llmSupportImage = true, // Gemma 3n E2B is multimodal
                llmSupportAudio = true
            )
            modelConfig
        }
    }

    private fun getSelectedModelEntry(): LlmModelEntry? = loadModelConfig()

    private fun parseBackendOrder(accelerators: String): List<Backend> {
        return accelerators.split(",").map { it.trim().lowercase() }.mapNotNull { name ->
            Backend.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }.ifEmpty { listOf(Backend.GPU, Backend.CPU) }
    }

    /**
     * Check if device has enough memory for model loading.
     * Returns a Pair of (hasEnough, availableMB) for logging purposes.
     */
    private fun checkMemory(estimatedPeakBytes: Long): Pair<Boolean, Long> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return Pair(true, -1L)
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val availableMB = memInfo.availMem / (1024 * 1024)
        val requiredMB = ((estimatedPeakBytes * MEMORY_SAFETY_FRACTION) / (1024 * 1024)).toLong()
        return Pair(availableMB >= requiredMB, availableMB)
    }

    /**
     * @deprecated Use checkMemory() instead for better logging
     */
    private fun hasEnoughMemory(estimatedPeakBytes: Long): Boolean {
        return checkMemory(estimatedPeakBytes).first
    }

    private fun buildSamplerConfig(defaultConfig: LlmDefaultConfig, passOverride: LlmPassOverride?): SamplerConfig {
        val topK = passOverride?.topK ?: defaultConfig.topK
        val topP = passOverride?.topP ?: defaultConfig.topP
        val temp = passOverride?.temperature ?: defaultConfig.temperature
        return SamplerConfig(
            topK = topK,
            topP = topP,
            temperature = temp.coerceIn(0.01, 2.0)
        )
    }

    /**
     * Initialize the LiteRT-LM engine. Loads config, checks memory, finds model file,
     * then tries backends in config order (e.g. gpu,cpu or gpu,npu,cpu).
     *
     * @param skipMemoryCheckOverride If true, bypass memory check regardless of config
     */
    suspend fun initialize(skipMemoryCheckOverride: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext true

        val entry = getSelectedModelEntry() ?: run {
            AppLogger.e(TAG, "No model config available")
            return@withContext false
        }

        // Use external path if provided, otherwise use config-based discovery
        val modelPath = if (externalModelPath != null) {
            val externalFile = java.io.File(externalModelPath)
            if (externalFile.exists() && externalFile.isFile) {
                AppLogger.i(TAG, "Using externally specified model: $externalModelPath")
                externalModelPath
            } else {
                AppLogger.w(TAG, "External model path doesn't exist: $externalModelPath, falling back to config")
                findModelFile(entry.modelFileName)
            }
        } else {
            findModelFile(entry.modelFileName)
        }

        if (modelPath == null) {
            AppLogger.e(TAG, "Model file not found. Expected: ${entry.modelFileName}")
            AppLogger.e(TAG, "Please download the model to /sdcard/Download/${entry.modelFileName}")
            return@withContext false
        }

        // Log model file size
        val modelFile = java.io.File(modelPath)
        val modelSizeMB = modelFile.length() / (1024 * 1024)
        AppLogger.i(TAG, "Found model at: $modelPath (${modelSizeMB} MB)")

        // Use skipMemoryCheck from config, or override if explicitly passed
        val shouldSkipMemoryCheck = skipMemoryCheckOverride || entry.skipMemoryCheck

        // Memory check with detailed logging
        entry.estimatedPeakMemoryInBytes?.let { peak ->
            val (hasEnough, availableMB) = checkMemory(peak)
            val requiredMB = ((peak * MEMORY_SAFETY_FRACTION) / (1024 * 1024)).toLong()

            AppLogger.i(TAG, "Memory check: available=${availableMB}MB, required=${requiredMB}MB (${(MEMORY_SAFETY_FRACTION * 100).toInt()}% of ${peak / (1024 * 1024)}MB peak)")

            if (!hasEnough) {
                if (shouldSkipMemoryCheck) {
                    AppLogger.w(TAG, "⚠️ Memory check failed but skipMemoryCheck=true, attempting load anyway...")
                } else {
                    AppLogger.e(TAG, "❌ Insufficient memory: need ${requiredMB}MB but only ${availableMB}MB available")
                    AppLogger.e(TAG, "💡 Try closing other apps or set skipMemoryCheck=true in config to bypass")
                    return@withContext false
                }
            }
        }

        // Use preferred backend if set, otherwise use config order
        val backends = if (preferredBackend != null) {
            AppLogger.i(TAG, "Using preferred backend: ${preferredBackend!!.name}")
            listOf(preferredBackend!!)
        } else {
            parseBackendOrder(entry.defaultConfig.accelerators)
        }

        // Check model capabilities from config to determine vision backend strategy
        val modelSupportsVision = entry.llmSupportImage
        AppLogger.i(TAG, "Model capabilities: llmSupportImage=${entry.llmSupportImage}, llmSupportAudio=${entry.llmSupportAudio}")

        for (backend in backends) {
            // For models that don't support vision, skip vision backend attempt entirely
            // For multimodal models, try vision first, then fall back to text-only if needed
            val visionModes = if (modelSupportsVision) listOf(true, false) else listOf(false)

            for (useVision in visionModes) {
                try {
                    val visionBackendConfig = if (useVision) Backend.GPU else null
                    val visionLabel = if (useVision) "visionBackend=GPU" else "visionBackend=null (text-only)"
                    AppLogger.i(TAG, "Initializing LiteRT-LM with ${backend.name} backend ($visionLabel)...")

                    val config = EngineConfig(
                        modelPath = modelPath,
                        backend = backend,
                        visionBackend = visionBackendConfig,  // null for text-only models
                        cacheDir = context.cacheDir.absolutePath
                    )
                    engine = Engine(config)
                    engine?.initialize()
                    chosenBackend = backend
                    isInitialized = true
                    AppLogger.i(TAG, "✅ LiteRT-LM initialized successfully with ${backend.name} backend ($visionLabel)")
                    return@withContext true
                } catch (e: Exception) {
                    val errorMsg = e.message ?: ""
                    AppLogger.w(TAG, "${backend.name} backend failed (vision=$useVision): $errorMsg")

                    // If vision encoder fails, try without vision backend (fallback for misconfigured models or GPU memory issues)
                    // Check for common vision-related error patterns
                    val isVisionError = useVision && (
                        errorMsg.contains("TF_LITE_VISION_ENCODER", ignoreCase = true) ||
                        errorMsg.contains("vision_litert", ignoreCase = true) ||
                        errorMsg.contains("vision", ignoreCase = true) ||
                        errorMsg.contains("litert_tensor_buffer", ignoreCase = true)
                    )

                    if (isVisionError) {
                        AppLogger.i(TAG, "Vision backend failed, retrying ${backend.name} as text-only...")
                        continue  // Try again with useVision=false on same backend
                    }

                    // Log full stack trace for debugging
                    AppLogger.d(TAG, "Stack trace: ${e.stackTraceToString()}")
                    break  // Move to next backend
                }
            }
        }

        AppLogger.e(TAG, "❌ All backends failed to initialize")
        isInitialized = false
        false
    }

    fun isModelLoaded(): Boolean = isInitialized

    fun isUsingGpu(): Boolean = chosenBackend == Backend.GPU

    fun getExecutionProvider(): String = chosenBackend?.let { "${it.name} (LiteRT-LM)" } ?: "unknown"

    /**
     * Get the capabilities of the currently loaded model.
     * Uses ModelNameUtils for consistent display name derivation across all engine types.
     *
     * @return ModelCapabilities with model name and feature support flags
     */
    fun getModelCapabilities(): ModelCapabilities {
        val entry = getSelectedModelEntry() ?: return ModelCapabilities.UNKNOWN

        // If using an external model path, derive the model name using shared utility
        // This ensures consistent naming across all engine types
        val modelName = if (externalModelPath != null) {
            ModelNameUtils.deriveDisplayName(externalModelPath)
        } else {
            entry.displayName ?: entry.modelFileName
        }

        return ModelCapabilities(
            modelName = modelName,
            supportsImage = entry.llmSupportImage,
            supportsAudio = entry.llmSupportAudio
        )
    }

    /**
     * Generate a response from the model using async streaming (sendMessageAsync).
     * Uses config for topK/topP; temperature can be overridden per call.
     * Logs total generation time for metrics.
     *
     * Enhanced with comprehensive error handling to catch and log native crashes.
     */
    suspend fun generate(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = MAX_TOKENS_DEFAULT,
        temperature: Float = (getSelectedModelEntry()?.defaultConfig?.temperature?.toFloat() ?: 0.6f)
    ): String = withContext(Dispatchers.IO) {
        if (!isInitialized || engine == null) {
            LlmErrorHandler.logError(
                category = LlmErrorHandler.ErrorCategory.MODEL_NOT_LOADED,
                operation = "LiteRtLmEngine.generate",
                message = "Engine not initialized",
                inputText = userPrompt
            )
            return@withContext ""
        }

        // Sanitize input to prevent native crashes from malformed text
        val sanitizedPrompt = LlmErrorHandler.sanitizeInput(userPrompt, maxLength = 50_000)
        if (sanitizedPrompt == null) {
            LlmErrorHandler.logError(
                category = LlmErrorHandler.ErrorCategory.INVALID_INPUT,
                operation = "LiteRtLmEngine.generate",
                message = "Invalid or empty input after sanitization",
                inputText = userPrompt
            )
            return@withContext ""
        }

        val entry = getSelectedModelEntry() ?: return@withContext ""
        val samplerConfig = buildSamplerConfig(entry.defaultConfig, null).let { base ->
            SamplerConfig(
                topK = base.topK,
                topP = base.topP,
                temperature = temperature.coerceIn(0.01f, 2f).toDouble()
            )
        }

        val conversationConfig = ConversationConfig(
            systemMessage = Message.of(systemPrompt),
            samplerConfig = samplerConfig
        )

        // Log pre-inference for debugging native crashes
        LlmErrorHandler.logPreInference(
            operation = "LiteRtLmEngine.generate",
            inputLength = sanitizedPrompt.length,
            maxTokens = maxTokens,
            temperature = temperature,
            additionalInfo = mapOf(
                "backend" to (chosenBackend?.name ?: "unknown"),
                "systemPromptLen" to systemPrompt.length
            )
        )

        val t0 = System.nanoTime()
        // Use mutex to serialize LLM calls - LiteRT-LM only supports one session at a time
        sessionMutex.withLock {
            try {
                engine!!.createConversation(conversationConfig).use { conversation ->
                    sendMessageAsyncAndCollect(conversation, sanitizedPrompt, maxTokens)
                }.also { response ->
                    val elapsedMs = (System.nanoTime() - t0) / 1_000_000
                    LlmErrorHandler.logPostInference(
                        operation = "LiteRtLmEngine.generate",
                        outputLength = response.length,
                        durationMs = elapsedMs
                    )
                    response
                }
            } catch (e: Throwable) {
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000
                val category = LlmErrorHandler.categorizeError(e)
                LlmErrorHandler.logError(
                    category = category,
                    operation = "LiteRtLmEngine.generate",
                    message = "Error after ${elapsedMs}ms: ${e.message}",
                    throwable = e,
                    inputText = sanitizedPrompt
                )
                ""
            }
        }
    }

    /**
     * Uses sendMessageAsync with MessageCallback and collects full response via suspendCancellableCoroutine.
     * Note: LiteRT-LM API requires Message.of() wrapper for the user prompt string.
     *
     * Enhanced with error logging in the callback to capture native errors.
     * Implements soft token limiting - stops collecting tokens when maxTokens is reached.
     *
     * @param conversation The LiteRT-LM conversation
     * @param userPrompt The prompt to send
     * @param maxTokens Maximum tokens to collect (soft limit - SDK doesn't support native cancellation)
     */
    private suspend fun sendMessageAsyncAndCollect(
        conversation: Conversation,
        userPrompt: String,
        maxTokens: Int = MAX_TOKENS_DEFAULT
    ): String =
        suspendCancellableCoroutine { cont ->
            val sb = StringBuilder()
            var tokenCount = 0
            var limitReached = false
            try {
                conversation.sendMessageAsync(Message.of(userPrompt), object : MessageCallback {
                    override fun onMessage(message: Message) {
                        // Skip if we already hit the limit
                        if (limitReached) return

                        try {
                            tokenCount++

                            // Check token limit before appending
                            if (tokenCount > maxTokens) {
                                if (!limitReached) {
                                    limitReached = true
                                    AppLogger.w(TAG, "Token limit reached ($maxTokens), stopping collection. Total collected: ${tokenCount - 1}")
                                    // Resume with what we have so far
                                    if (cont.isActive) cont.resume(sb.toString())
                                }
                                return
                            }

                            sb.append(message.toString())
                        } catch (e: Throwable) {
                            // Log but don't crash - native callback errors
                            AppLogger.e(TAG, "Error in onMessage callback (token $tokenCount)", e)
                        }
                    }
                    override fun onDone() {
                        // Only resume if we haven't already (due to limit)
                        if (!limitReached) {
                            AppLogger.d(TAG, "sendMessageAsync completed: $tokenCount tokens received")
                            if (cont.isActive) cont.resume(sb.toString())
                        } else {
                            AppLogger.d(TAG, "sendMessageAsync completed (after limit): $tokenCount total tokens generated")
                        }
                    }
                    override fun onError(throwable: Throwable) {
                        // Only resume with error if we haven't already resumed
                        if (!limitReached) {
                            // Log the error with full context before resuming with exception
                            LlmErrorHandler.logError(
                                category = LlmErrorHandler.categorizeError(throwable),
                                operation = "LiteRtLmEngine.sendMessageAsync.onError",
                                message = "Callback error after $tokenCount tokens: ${throwable.message}",
                                throwable = throwable,
                                inputText = userPrompt.take(500)
                            )
                            if (cont.isActive) cont.resumeWithException(throwable)
                        }
                    }
                })
            } catch (e: Throwable) {
                // Catch any error during sendMessageAsync setup (before callbacks)
                LlmErrorHandler.logError(
                    category = LlmErrorHandler.categorizeError(e),
                    operation = "LiteRtLmEngine.sendMessageAsync.setup",
                    message = "Error setting up async message: ${e.message}",
                    throwable = e,
                    inputText = userPrompt.take(500)
                )
                if (cont.isActive) cont.resumeWithException(e)
            }
        }

    // ==================== Two-Pass Character Analysis API ====================

    /**
     * Pass 1: Extract character names AND complete voice profiles in a single pass.
     * Uses ~3000 tokens per segment. Handles token limit errors with retry logic.
     *
     * @param segmentText Text segment to analyze (should be ~12,000 chars max)
     * @return List of characters with their names, traits, and voice profiles
     */
    suspend fun pass1ExtractCharactersAndVoiceProfiles(
        segmentText: String
    ): List<CharacterWithProfile> = withContext(Dispatchers.IO) {
        if (!isInitialized || engine == null) {
            AppLogger.w(TAG, "Pass-1: Engine not initialized")
            return@withContext emptyList()
        }

        var inputText = segmentText
        var attempt = 0
        val maxAttempts = 3

        while (attempt < maxAttempts) {
            attempt++
            try {
                val prompt = buildPass1Prompt(inputText)
                AppLogger.d(TAG, "Pass-1: Attempt $attempt with ${inputText.length} chars")

                val entry = getSelectedModelEntry()
                val pass1MaxTokens = entry?.pass1?.maxTokens ?: 1024
                val pass1Temp = (entry?.pass1?.temperature ?: 0.1).toFloat()

                val response = generate(
                    systemPrompt = PASS1_SYSTEM_PROMPT,
                    userPrompt = prompt,
                    maxTokens = pass1MaxTokens,
                    temperature = pass1Temp
                )

                if (response.contains(TOKEN_LIMIT_ERROR, ignoreCase = true)) {
                    AppLogger.w(TAG, "Pass-1: Token limit hit, reducing input")
                    inputText = reduceTextByTokens(inputText, TOKEN_REDUCTION_ON_ERROR)
                    continue
                }

                val result = parsePass1Response(response)
                AppLogger.d(TAG, "Pass-1: Extracted ${result.size} characters")
                return@withContext result

            } catch (e: Exception) {
                if (e.message?.contains(TOKEN_LIMIT_ERROR, ignoreCase = true) == true) {
                    AppLogger.w(TAG, "Pass-1: Token limit exception, reducing input")
                    inputText = reduceTextByTokens(inputText, TOKEN_REDUCTION_ON_ERROR)
                    continue
                }
                AppLogger.e(TAG, "Pass-1: Error on attempt $attempt", e)
            }
        }

        AppLogger.w(TAG, "Pass-1: All attempts failed")
        emptyList()
    }

    /**
     * Pass 2: Extract dialogs with speaker attribution.
     * Uses ~1500 tokens per segment. Handles token limit errors with retry logic.
     *
     * @param segmentText Text segment to analyze (should be ~6,000 chars max)
     * @param characterNames List of known character names from Pass 1
     * @return List of extracted dialogs with speaker, text, emotion, and intensity
     */
    suspend fun pass2ExtractDialogs(
        segmentText: String,
        characterNames: List<String>
    ): List<ExtractedDialog> = withContext(Dispatchers.IO) {
        if (!isInitialized || engine == null) {
            AppLogger.w(TAG, "Pass-2: Engine not initialized")
            return@withContext emptyList()
        }

        var inputText = segmentText
        var attempt = 0
        val maxAttempts = 3

        while (attempt < maxAttempts) {
            attempt++
            try {
                val prompt = buildPass2Prompt(inputText, characterNames)
                AppLogger.d(TAG, "Pass-2: Attempt $attempt with ${inputText.length} chars")

                val entry = getSelectedModelEntry()
                val pass2MaxTokens = entry?.pass2?.maxTokens ?: 1024
                val pass2Temp = (entry?.pass2?.temperature ?: 0.15).toFloat()

                val response = generate(
                    systemPrompt = PASS2_SYSTEM_PROMPT,
                    userPrompt = prompt,
                    maxTokens = pass2MaxTokens,
                    temperature = pass2Temp
                )

                if (response.contains(TOKEN_LIMIT_ERROR, ignoreCase = true)) {
                    AppLogger.w(TAG, "Pass-2: Token limit hit, reducing input")
                    inputText = reduceTextByTokens(inputText, TOKEN_REDUCTION_ON_ERROR)
                    continue
                }

                val result = parsePass2Response(response)
                AppLogger.d(TAG, "Pass-2: Extracted ${result.size} dialogs")
                return@withContext result

            } catch (e: Exception) {
                if (e.message?.contains(TOKEN_LIMIT_ERROR, ignoreCase = true) == true) {
                    AppLogger.w(TAG, "Pass-2: Token limit exception, reducing input")
                    inputText = reduceTextByTokens(inputText, TOKEN_REDUCTION_ON_ERROR)
                    continue
                }
                AppLogger.e(TAG, "Pass-2: Error on attempt $attempt", e)
            }
        }

        AppLogger.w(TAG, "Pass-2: All attempts failed")
        emptyList()
    }

    // ==================== Text Segmentation ====================

    /**
     * Segment text for Pass 1 so total prompt (system + user + text) stays under 15,000 chars.
     * Splits at paragraph/sentence boundaries.
     */
    fun segmentTextForPass1(fullText: String): List<String> {
        return segmentText(fullText, PASS1_MAX_SEGMENT_CHARS)
    }

    /**
     * Segment text for Pass 2 so total prompt (system + user + text) stays under 7,000 chars.
     * Splits at paragraph/sentence boundaries.
     */
    fun segmentTextForPass2(fullText: String): List<String> {
        return segmentText(fullText, PASS2_MAX_SEGMENT_CHARS)
    }

    private fun segmentText(text: String, maxCharsPerSegment: Int): List<String> {
        if (text.length <= maxCharsPerSegment) {
            return listOf(text)
        }

        val segments = mutableListOf<String>()
        var remaining = text

        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxCharsPerSegment) {
                segments.add(remaining)
                break
            }

            var breakPoint = maxCharsPerSegment
            val searchStart = (maxCharsPerSegment * 0.8).toInt()

            // Try paragraph break first
            val paragraphBreak = remaining.lastIndexOf("\n\n", maxCharsPerSegment)
            if (paragraphBreak > searchStart) {
                breakPoint = paragraphBreak + 2
            } else {
                // Try sentence break
                val sentenceEnds = listOf(". ", "! ", "? ", ".\n", "!\n", "?\n")
                for (end in sentenceEnds) {
                    val idx = remaining.lastIndexOf(end, maxCharsPerSegment)
                    if (idx > searchStart) {
                        breakPoint = idx + end.length
                        break
                    }
                }
            }

            segments.add(remaining.substring(0, breakPoint))
            remaining = remaining.substring(breakPoint)
        }

        return segments
    }

    /**
     * Reduce text by approximately the given number of tokens.
     */
    private fun reduceTextByTokens(text: String, tokens: Int): String {
        val charsToRemove = tokens * CHARS_PER_TOKEN_ESTIMATE
        if (text.length <= charsToRemove) {
            return text.take(text.length / 2)
        }
        return text.dropLast(charsToRemove)
    }

    // ==================== End Two-Pass Character Analysis API ====================

    /**
     * Find the model file on the device. Uses modelFileName from config (e.g. gemma-3n-E2B-it-int4.litertlm).
     */
    private fun findModelFile(modelFileName: String): String? {
        val paths = listOf(
            "/sdcard/Download/$modelFileName",
            "/storage/emulated/0/Download/$modelFileName",
            "${context.filesDir.absolutePath}/$modelFileName",
            "${context.getExternalFilesDir(null)?.absolutePath}/$modelFileName"
        )

        for (path in paths) {
            val file = File(path)
            if (file.exists() && file.isFile && file.length() > 0) {
                AppLogger.i(TAG, "Found model at: $path")
                return path
            }
        }

        AppLogger.w(TAG, "Model not found in any expected location: $modelFileName")
        return null
    }

    fun release() {
        try {
            engine?.close()
            engine = null
            isInitialized = false
            AppLogger.i(TAG, "LiteRT-LM engine released")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error releasing engine", e)
        }
    }

    // ==================== Prompt Builders ====================

    private val PASS1_SYSTEM_PROMPT = """Extract characters from the input story snippet.
Output format: {"characters": ["Name1", "Name2", "Name3"]}
Output valid JSON only."""

    private fun buildPass1Prompt(text: String): String = """Extract characters from the input story snippet.
Output format: {"characters": ["Name1", "Name2", "Name3"]}

<TEXT>
$text
</TEXT>"""

    private val PASS2_SYSTEM_PROMPT = """Extract dialogs for the given characters from the passage.
Output an array of {character: dialog} mappings, in order of appearance. Each element is a single key-value object, e.g. {"Character Name": "exact dialog or prose"}.
Use "Narrator" for non-dialog prose. Output valid JSON only."""

    private fun buildPass2Prompt(text: String, characterNames: List<String>): String {
        val namesList = characterNames + listOf("Narrator")
        val namesBlock = namesList.joinToString(", ")
        return """Extract dialogs of these characters: $namesBlock

Output: an array of {character: dialog} mappings, in order of their appearance. Each item is one object with a single key (speaker name) and value (dialog text). Use "Narrator" for prose/descriptions.

Example: [{"Alice": "Hello!"}, {"Narrator": "She left."}, {"Bob": "Bye."}]

<TEXT>
$text
</TEXT>"""
    }

    // ==================== Response Parsers ====================

    private fun parsePass1Response(response: String): List<CharacterWithProfile> {
        return try {
            val json = extractJsonFromResponse(response)
            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return emptyList()

            @Suppress("UNCHECKED_CAST")
            val characters = obj["characters"] as? List<*> ?: return emptyList()

            characters.mapNotNull { entry ->
                val name = when (entry) {
                    is String -> entry.trim()
                    else -> (entry as? Map<*, *>)?.get("name")?.toString()?.trim()
                }
                if (name.isNullOrBlank()) return@mapNotNull null
                val voiceProfile = createRandomVoiceProfile()
                CharacterWithProfile(name, emptyList(), voiceProfile)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Pass-1: Failed to parse response", e)
            emptyList()
        }
    }

    private fun parsePass2Response(response: String): List<ExtractedDialog> {
        return try {
            val json = extractJsonFromResponse(response)
            @Suppress("UNCHECKED_CAST")
            val list = gson.fromJson<List<Map<String, Any>>>(json, object : TypeToken<List<Map<String, Any>>>() {}.type)
                ?: return emptyList()

            val result = mutableListOf<ExtractedDialog>()
            for (map in list) {
                if (map.size != 1) continue
                val (speaker, text) = map.entries.firstOrNull() ?: continue
                val speakerStr = speaker.trim()
                val textStr = (text as? String)?.trim() ?: text?.toString()?.trim() ?: continue
                if (textStr.isBlank()) continue
                result.add(ExtractedDialog(speakerStr, textStr, "neutral", 0.5f))
            }
            result
        } catch (e: Exception) {
            // Fallback: try legacy "dialogs" array format
            try {
                val obj = gson.fromJson(extractJsonFromResponse(response), Map::class.java) as? Map<*, *> ?: return emptyList()
                @Suppress("UNCHECKED_CAST")
                val dialogs = obj["dialogs"] as? List<*> ?: return emptyList()
                dialogs.mapNotNull { dialogObj ->
                    val dialogMap = dialogObj as? Map<*, *> ?: return@mapNotNull null
                    val speaker = (dialogMap["speaker"] as? String)?.trim() ?: return@mapNotNull null
                    val text = (dialogMap["text"] as? String)?.trim() ?: return@mapNotNull null
                    if (text.isBlank()) return@mapNotNull null
                    ExtractedDialog(speaker, text, "neutral", 0.5f)
                }
            } catch (e2: Exception) {
                AppLogger.w(TAG, "Pass-2: Failed to parse response", e2)
                emptyList()
            }
        }
    }

    private fun extractJsonFromResponse(response: String): String {
        var json = response.trim()

        // Remove markdown code fences
        if (json.startsWith("```json")) {
            json = json.removePrefix("```json").trim()
        }
        if (json.startsWith("```")) {
            json = json.removePrefix("```").trim()
        }
        if (json.endsWith("```")) {
            json = json.removeSuffix("```").trim()
        }

        // Extract JSON object or array
        val objStart = json.indexOf('{')
        val objEnd = json.lastIndexOf('}')
        val arrStart = json.indexOf('[')
        val arrEnd = json.lastIndexOf(']')

        return when {
            objStart >= 0 && objEnd > objStart -> json.substring(objStart, objEnd + 1)
            arrStart >= 0 && arrEnd > arrStart -> json.substring(arrStart, arrEnd + 1)
            else -> json
        }
    }

    /** Creates a minimal voice profile with a random LibriTTS speaker ID (0–903). */
    private fun createRandomVoiceProfile(): Map<String, Any> {
        val speakerId = (LibrittsSpeakerCatalog.MIN_SPEAKER_ID..LibrittsSpeakerCatalog.MAX_SPEAKER_ID).random()
        return mapOf(
            "pitch" to 1.0,
            "speed" to 1.0,
            "energy" to 0.7,
            "gender" to "neutral",
            "age" to "middle-aged",
            "tone" to "neutral",
            "accent" to "neutral",
            "emotion_bias" to mapOf("calm" to 0.5),
            "speaker_id" to speakerId
        )
    }

    // ==================== STORY-002: Image-to-Story Generation ====================

    /**
     * STORY-002: Generate a story from an inspiration image.
     * Uses Gemma 3n multimodal capabilities to analyze the image and generate a story.
     *
     * @param imagePath Path to the image file on device
     * @param userPrompt Optional user prompt for story direction
     * @return Generated story text
     */
    suspend fun generateFromImage(
        imagePath: String,
        userPrompt: String = "Write an engaging story inspired by this image."
    ): String = withContext(Dispatchers.IO) {
        if (!isInitialized || engine == null) {
            AppLogger.w(TAG, "generateFromImage: Engine not initialized")
            return@withContext ""
        }

        // Verify image file exists
        val imageFile = java.io.File(imagePath)
        if (!imageFile.exists()) {
            AppLogger.e(TAG, "generateFromImage: Image file not found: $imagePath")
            return@withContext ""
        }

        val entry = getSelectedModelEntry() ?: return@withContext ""
        val samplerConfig = buildSamplerConfig(entry.defaultConfig, null).let { base ->
            SamplerConfig(
                topK = base.topK,
                topP = base.topP,
                temperature = 0.7  // Creative temperature for story generation
            )
        }

        val conversationConfig = ConversationConfig(
            systemInstruction = Contents.of(Content.Text(IMAGE_STORY_SYSTEM_PROMPT)),
            samplerConfig = samplerConfig
        )

        val t0 = System.nanoTime()
        // Use mutex to serialize LLM calls - LiteRT-LM only supports one session at a time
        sessionMutex.withLock {
            try {
                engine!!.createConversation(conversationConfig).use { conversation ->
                    // Build multimodal message with image and text
                    val fullPrompt = "$IMAGE_STORY_USER_PROMPT_PREFIX$userPrompt"

                    // Use Contents.of() with Content.ImageFile() and Content.Text() for multimodal input
                    // LiteRT-LM Kotlin API supports multimodal via Contents.of(Content.ImageFile, Content.Text)
                    val multimodalContents = Contents.of(
                        Content.ImageFile(imagePath),
                        Content.Text(fullPrompt)
                    )

                    sendMessageAsyncAndCollectMultimodal(conversation, multimodalContents)
                }.also { response ->
                    val elapsedMs = (System.nanoTime() - t0) / 1_000_000
                    AppLogger.d(TAG, "generateFromImage() took ${elapsedMs}ms, response length=${response.length}")
                    response
                }
            } catch (e: Exception) {
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000
                AppLogger.e(TAG, "Error generating story from image after ${elapsedMs}ms", e)
                ""
            }
        }
    }

    /**
     * Send a multimodal message (using Contents) and collect the response.
     * Enhanced with error logging for native crash debugging.
     */
    private suspend fun sendMessageAsyncAndCollectMultimodal(conversation: Conversation, contents: Contents): String =
        suspendCancellableCoroutine { cont ->
            val sb = StringBuilder()
            var tokenCount = 0
            try {
                conversation.sendMessageAsync(contents, object : MessageCallback {
                    override fun onMessage(msg: Message) {
                        try {
                            sb.append(msg.toString())
                            tokenCount++
                        } catch (e: Throwable) {
                            AppLogger.e(TAG, "Error in multimodal onMessage callback (token $tokenCount)", e)
                        }
                    }
                    override fun onDone() {
                        AppLogger.d(TAG, "Multimodal sendMessageAsync completed: $tokenCount tokens received")
                        if (cont.isActive) cont.resume(sb.toString())
                    }
                    override fun onError(throwable: Throwable) {
                        LlmErrorHandler.logError(
                            category = LlmErrorHandler.categorizeError(throwable),
                            operation = "LiteRtLmEngine.sendMessageAsyncMultimodal.onError",
                            message = "Multimodal callback error after $tokenCount tokens: ${throwable.message}",
                            throwable = throwable,
                            inputText = "[multimodal content]"
                        )
                        if (cont.isActive) cont.resumeWithException(throwable)
                    }
                })
            } catch (e: Throwable) {
                LlmErrorHandler.logError(
                    category = LlmErrorHandler.categorizeError(e),
                    operation = "LiteRtLmEngine.sendMessageAsyncMultimodal.setup",
                    message = "Error setting up multimodal async message: ${e.message}",
                    throwable = e,
                    inputText = "[multimodal content]"
                )
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
}
