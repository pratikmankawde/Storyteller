package com.dramebaz.app.ai.llm

import android.content.Context
import android.os.Environment
import com.dramebaz.app.data.models.ChapterSummary
import com.dramebaz.app.data.models.Dialog
import com.dramebaz.app.data.models.EmotionalSegment
import com.dramebaz.app.data.models.ProsodyHints
import com.dramebaz.app.data.models.SoundCueModel
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * GGUF Engine - Handles loading and inference for any GGUF format model.
 * Uses llama.cpp JNI (ggml-org/llama.cpp b7793) for inference.
 * Model files are discovered in Downloads/ folder on device.
 *
 * This engine is file-type specific (GGUF format) and can load any compatible .gguf model,
 * including Qwen, Llama, Mistral, Gemma GGUF variants, etc.
 *
 * Model-specific parameters (temperature, maxTokens, topK, topP, prompts) are loaded from
 * an external JSON configuration file via ModelParamsManager.
 *
 * @param context Android context
 * @param externalModelPath Optional explicit path to GGUF model file. If null, uses Downloads folder discovery.
 */
class GgufEngine(private val context: Context, private val externalModelPath: String? = null) {
    companion object {
        private const val TAG = "GgufEngine"
        private const val DEFAULT_MODEL_FILE_NAME = "model.gguf"
        // Match any .gguf file for flexibility
        private val GGUF_PATTERN = Regex(".*\\.gguf", RegexOption.IGNORE_CASE)
    }

    private val gson = Gson()
    private var nativeHandle: Long = 0L
    private var modelLoaded = false

    // Mutex to serialize LLM calls - only one inference at a time
    private val inferenceMutex = Mutex()

    private val maxInputChars = 10000
    private val jsonValidityReminder = "\nEnsure the JSON is valid and contains no trailing commas."

    private fun getPossibleDownloadPaths(): List<String> {
        val paths = mutableListOf<String>()
        paths.add("/sdcard/Download")
        paths.add("/storage/emulated/0/Download")
        paths.add("/storage/self/primary/Download")
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadsDir?.exists() == true) paths.add(downloadsDir.absolutePath)
        } catch (_: Exception) {}
        try {
            context.getExternalFilesDir(null)?.let { paths.add(it.absolutePath) }
        } catch (_: Exception) {}
        return paths.distinct()
    }

    /**
     * Look for model - first check external path if provided, then Downloads folder.
     * If a file needs to be copied to private storage, copy it there for faster loading.
     */
    private fun findGgufModel(): String? {
        val privateModelPath = File(context.filesDir, "model.gguf")

        // 1. Check if external path was provided (from LlmModelFactory discovery)
        if (externalModelPath != null) {
            val externalFile = File(externalModelPath)
            if (externalFile.exists() && externalFile.isFile) {
                AppLogger.i(TAG, "Using externally specified model: $externalModelPath")
                // Use directly without copying - the file was discovered by the factory
                return externalModelPath
            } else {
                AppLogger.w(TAG, "External model path doesn't exist: $externalModelPath")
            }
        }

        // 2. Check if we already have a model copied to private storage
        if (privateModelPath.exists() && privateModelPath.length() > 0) {
            AppLogger.i(TAG, "Found existing model in private storage: ${privateModelPath.absolutePath}")
            return privateModelPath.absolutePath
        }

        // 3. Check for model directly in Downloads folder (known names)
        val knownPaths = listOf(
            "/storage/emulated/0/Download/$DEFAULT_MODEL_FILE_NAME",
            "/sdcard/Download/$DEFAULT_MODEL_FILE_NAME",
            // Legacy paths for subfolder structure
            "/storage/emulated/0/Download/Qwen3-1.7B-Q4_K_M/$DEFAULT_MODEL_FILE_NAME",
            "/sdcard/Download/Qwen3-1.7B-Q4_K_M/$DEFAULT_MODEL_FILE_NAME"
        )
        for (path in knownPaths) {
            val file = File(path)
            if (file.exists() && file.isFile) {
                AppLogger.i(TAG, "Found GGUF model at known path: ${file.absolutePath}")
                return copyToPrivateStorage(file, privateModelPath)
            }
        }

        // 4. Scan download directories for any .gguf file
        for (dirPath in getPossibleDownloadPaths()) {
            val dir = File(dirPath)
            if (!dir.exists() || !dir.isDirectory) continue
            dir.listFiles()?.forEach { file ->
                if (file.isFile && GGUF_PATTERN.matches(file.name)) {
                    AppLogger.i(TAG, "Found GGUF model: ${file.absolutePath}")
                    return copyToPrivateStorage(file, privateModelPath)
                }
            }
            dir.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
                subDir.listFiles()?.forEach { file ->
                    if (file.isFile && GGUF_PATTERN.matches(file.name)) {
                        AppLogger.i(TAG, "Found GGUF in subdirectory: ${file.absolutePath}")
                        return copyToPrivateStorage(file, privateModelPath)
                    }
                }
            }
        }
        return null
    }

    private fun copyToPrivateStorage(sourceFile: File, destFile: File): String? {
        return try {
            val sourceSize = sourceFile.length()
            AppLogger.i(TAG, "Copying model to private storage (${sourceSize / 1024 / 1024} MB)...")
            val startTime = System.currentTimeMillis()
            sourceFile.inputStream().buffered(8 * 1024 * 1024).use { input ->
                destFile.outputStream().buffered(8 * 1024 * 1024).use { output ->
                    val buffer = ByteArray(8 * 1024 * 1024)
                    var bytesCopied = 0L
                    var bytesRead: Int
                    var lastLogTime = startTime
                    while (input.read(buffer).also { bytesRead = it } >= 0) {
                        output.write(buffer, 0, bytesRead)
                        bytesCopied += bytesRead
                        val now = System.currentTimeMillis()
                        if (now - lastLogTime > 5000) {
                            val percent = (bytesCopied * 100 / sourceSize).toInt()
                            AppLogger.i(TAG, "Copy progress: $percent% (${bytesCopied / 1024 / 1024} MB)")
                            lastLogTime = now
                        }
                    }
                }
            }
            AppLogger.i(TAG, "Model copied in ${(System.currentTimeMillis() - startTime) / 1000}s: ${destFile.absolutePath}")
            destFile.absolutePath
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error copying model", e)
            if (destFile.exists()) destFile.delete()
            null
        }
    }

    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        try {
            AppLogger.i(TAG, "Searching for GGUF model in Downloads folder...")
            val foundPath = findGgufModel() ?: run {
                AppLogger.e(TAG, "GGUF model not found in Downloads folder")
                return@withContext false
            }
            val modelFile = File(foundPath)
            AppLogger.i(TAG, "Using GGUF model: $foundPath (size=${modelFile.length() / (1024*1024)} MB)")
            val loadStartMs = System.currentTimeMillis()
            val handle = LlamaNative.loadModel(foundPath)
            if (handle == 0L) {
                AppLogger.e(TAG, "LlamaNative.loadModel failed")
                return@withContext false
            }
            nativeHandle = handle
            modelLoaded = true
            val provider = LlamaNative.getExecutionProvider(handle)
            AppLogger.i(TAG, "Execution provider: $provider")
            AppLogger.logPerformance(TAG, "Load GGUF model (llama.cpp JNI)", System.currentTimeMillis() - loadStartMs)
            AppLogger.i(TAG, "GGUF engine loaded successfully: ${modelFile.name}")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error loading GGUF model", e)
            modelLoaded = false
            false
        }
    }

    fun isModelLoaded(): Boolean = modelLoaded

    /**
     * Returns the execution provider: "GPU (Vulkan)" or "CPU" or "unknown"
     */
    fun getExecutionProvider(): String {
        return if (nativeHandle != 0L) {
            LlamaNative.getExecutionProvider(nativeHandle)
        } else {
            "unknown"
        }
    }

    /**
     * Returns true if using GPU (Vulkan) for inference
     */
    fun isUsingGpu(): Boolean {
        return getExecutionProvider().contains("GPU", ignoreCase = true)
    }

    // ==================== Public Inference API ====================

    /**
     * Generate a response from the model given system and user prompts.
     * This is the public API for workflow classes.
     *
     * Enhanced with comprehensive error handling to catch and log native crashes.
     *
     * @param systemPrompt System prompt that sets context/role for the model
     * @param userPrompt User prompt with the actual request
     * @param maxTokens Maximum tokens to generate
     * @param temperature Sampling temperature (0.0 = deterministic, 1.0+ = creative)
     * @return Generated text response, or empty string on error
     */
    suspend fun generateWithPrompts(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 1024,
        temperature: Float = 0.7f
    ): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!modelLoaded) {
            LlmErrorHandler.logError(
                category = LlmErrorHandler.ErrorCategory.MODEL_NOT_LOADED,
                operation = "GgufEngine.generateWithPrompts",
                message = "Model not loaded",
                inputText = userPrompt
            )
            return@withContext ""
        }

        return@withContext LlmErrorHandler.safeExecute(
            operation = "GgufEngine.generateWithPrompts",
            inputText = userPrompt,
            defaultValue = ""
        ) {
            val prompt = buildChatPrompt(systemPrompt, userPrompt)
            generateResponse(prompt, maxTokens, temperature, jsonMode = false)
        }
    }

    // ==================== Private Inference ====================

    /**
     * Core inference method with comprehensive error handling.
     * Wraps the native LlamaNative.generate() call with:
     * - Input sanitization to prevent native crashes
     * - Pre/post inference logging for debugging
     * - Detailed error categorization and logging
     * - Mutex to ensure only one LLM call is in progress at a time
     */
    private suspend fun generateResponse(prompt: String, maxTokens: Int, temperature: Float, jsonMode: Boolean = true): String = inferenceMutex.withLock {
        if (!modelLoaded || nativeHandle == 0L) {
            LlmErrorHandler.logError(
                category = LlmErrorHandler.ErrorCategory.MODEL_NOT_LOADED,
                operation = "GgufEngine.generateResponse",
                message = "Model not loaded or invalid handle (handle=$nativeHandle, loaded=$modelLoaded)",
                inputText = prompt
            )
            return@withLock ""
        }

        // Sanitize input to prevent native crashes from malformed text
        val sanitizedPrompt = LlmErrorHandler.sanitizeInput(prompt, maxLength = 50_000)
        if (sanitizedPrompt == null) {
            LlmErrorHandler.logError(
                category = LlmErrorHandler.ErrorCategory.INVALID_INPUT,
                operation = "GgufEngine.generateResponse",
                message = "Invalid or empty input after sanitization",
                inputText = prompt
            )
            return@withLock ""
        }

        val t = if (jsonMode) temperature.coerceIn(0f, 0.2f) else temperature

        // Log pre-inference for debugging native crashes
        LlmErrorHandler.logPreInference(
            operation = "GgufEngine.generateResponse",
            inputLength = sanitizedPrompt.length,
            maxTokens = maxTokens,
            temperature = t,
            additionalInfo = mapOf(
                "jsonMode" to jsonMode,
                "nativeHandle" to nativeHandle
            )
        )

        val startMs = System.currentTimeMillis()
        return@withLock try {
            val response = LlamaNative.generate(nativeHandle, sanitizedPrompt, maxTokens, t)
            val elapsedMs = System.currentTimeMillis() - startMs

            LlmErrorHandler.logPostInference(
                operation = "GgufEngine.generateResponse",
                outputLength = response?.length ?: 0,
                durationMs = elapsedMs
            )

            response ?: ""
        } catch (e: Throwable) {
            val elapsedMs = System.currentTimeMillis() - startMs
            val category = LlmErrorHandler.categorizeError(e)
            LlmErrorHandler.logError(
                category = category,
                operation = "GgufEngine.generateResponse",
                message = "Native inference error after ${elapsedMs}ms: ${e.message}",
                throwable = e,
                inputText = sanitizedPrompt
            )
            ""
        }
    }

    private fun buildChatPrompt(systemPrompt: String, userPrompt: String): String {
        // Add /no_think directive to disable Qwen3's chain-of-thought reasoning for faster inference
        // This reduces token generation by skipping the internal reasoning process
        return "<|im_start|>system\n$systemPrompt /no_think<|im_end|>\n<|im_start|>user\n$userPrompt<|im_end|>\n<|im_start|>assistant\n"
    }

    private fun extractJsonFromResponse(response: String): String {
        var json = response.trim()
        if (json.startsWith("```json")) json = json.removePrefix("```json").trim()
        if (json.startsWith("```")) json = json.removePrefix("```").trim()
        if (json.endsWith("```")) json = json.removeSuffix("```").trim()
        val start = json.indexOf('{')
        val end = json.lastIndexOf('}')
        if (start >= 0 && end > start) json = json.substring(start, end + 1)
        return json
    }

    /**
     * @deprecated Use ChapterAnalysisPass from the modular pipeline instead.
     * @see com.dramebaz.app.ai.llm.pipeline.ChapterAnalysisPass
     */
    @Deprecated("Use ChapterAnalysisPass from modular pipeline", ReplaceWith("ChapterAnalysisPass().execute(model, input, config)"))
    suspend fun analyzeChapter(chapterText: String): ChapterAnalysisResponse? = withContext(Dispatchers.IO) {
        if (!modelLoaded) return@withContext null
        try {
            val prompt = buildAnalysisPrompt(chapterText)
            // Reduced from 2048 to 768 - typical output is ~500 tokens
            val response = generateResponse(prompt, 768, 0.15f)
            parseAnalysisResponse(response)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error analyzing chapter", e)
            null
        }
    }

    /**
     * @deprecated Use FourPassCharacterAnalysisUseCase.analyzeChapter() for character analysis
     * and extendedAnalysisJson() separately for themes/symbols/vocabulary.
     * This method is no longer used in the main workflow.
     *
     * Single-pass full analysis: chapter summary, characters (with traits + voice_profile),
     * dialogs, sound_cues, and extended (themes, symbols, foreshadowing, vocabulary) in one LLM call.
     * More efficient than calling analyzeChapter + extendedAnalysisJson separately.
     */
    @Deprecated("Use FourPassCharacterAnalysisUseCase for character analysis")
    suspend fun analyzeChapterFull(chapterText: String): Pair<ChapterAnalysisResponse?, String?>? = withContext(Dispatchers.IO) {
        if (!modelLoaded) return@withContext null
        try {
            val prompt = buildFullAnalysisPrompt(chapterText)
            // Reduced from 3072 to 1024 - typical output is ~800 tokens
            val response = generateResponse(prompt, 1024, 0.15f)
            parseFullAnalysisResponse(response)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error in full chapter analysis", e)
            null
        }
    }

    /**
     * @deprecated Use ExtendedAnalysisPass from the modular pipeline instead.
     * @see com.dramebaz.app.ai.llm.pipeline.ExtendedAnalysisPass
     */
    @Deprecated("Use ExtendedAnalysisPass from modular pipeline", ReplaceWith("ExtendedAnalysisPass().execute(model, input, config)"))
    suspend fun extendedAnalysisJson(chapterText: String): String? = withContext(Dispatchers.IO) {
        if (!modelLoaded) return@withContext null
        try {
            val prompt = buildExtendedAnalysisPrompt(chapterText)
            val response = generateResponse(prompt, 1024, 0.15f)
            parseExtendedAnalysisResponse(response)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error in extended analysis", e)
            null
        }
    }

    /**
     * @deprecated Use CharacterExtractionPass + TraitsExtractionPass from the modular pipeline instead.
     * @see com.dramebaz.app.ai.llm.pipeline.CharacterExtractionPass
     * @see com.dramebaz.app.ai.llm.pipeline.TraitsExtractionPass
     */
    @Deprecated("Use CharacterExtractionPass + TraitsExtractionPass from modular pipeline")
    suspend fun extractCharactersAndTraitsInSegment(
        segmentText: String,
        skipNamesWithTraits: Collection<String>,
        namesNeedingTraits: Collection<String>
    ): List<Pair<String, List<String>>> = withContext(Dispatchers.IO) {
        if (!modelLoaded) return@withContext emptyList()
        try {
            val prompt = buildExtractCharactersAndTraitsPrompt(segmentText, skipNamesWithTraits, namesNeedingTraits)
            val response = generateResponse(prompt, 512, 0.15f)
            parseCharactersAndTraitsFromResponse(response)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error extracting characters and traits", e)
            emptyList()
        }
    }

    /**
     * @deprecated Use CharacterDetectionPass from the modular pipeline instead.
     * @see com.dramebaz.app.ai.llm.pipeline.CharacterDetectionPass
     */
    @Deprecated("Use CharacterDetectionPass from modular pipeline", ReplaceWith("CharacterDetectionPass().execute(model, input, config)"))
    suspend fun detectCharactersOnPage(pageText: String): List<String> = withContext(Dispatchers.IO) {
        if (!modelLoaded) return@withContext emptyList()
        try {
            val prompt = buildDetectCharactersOnPagePrompt(pageText)
            val response = generateResponse(prompt, 256, 0.1f)
            parseCharacterNamesFromResponse(response)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error detecting characters", e)
            emptyList()
        }
    }

    /**
     * @deprecated Use InferTraitsPass from the modular pipeline instead.
     * @see com.dramebaz.app.ai.llm.pipeline.InferTraitsPass
     */
    @Deprecated("Use InferTraitsPass from modular pipeline", ReplaceWith("InferTraitsPass().execute(model, input, config)"))
    suspend fun inferTraitsForCharacter(characterName: String, excerpt: String): List<String> = withContext(Dispatchers.IO) {
        if (!modelLoaded) return@withContext emptyList()
        try {
            val prompt = buildInferTraitsPrompt(characterName, excerpt)
            val response = generateResponse(prompt, 256, 0.15f)
            parseTraitsFromResponse(response)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error inferring traits", e)
            emptyList()
        }
    }

    /**
     * @deprecated Use VoiceProfileSuggestionPass from the modular pipeline instead.
     * @see com.dramebaz.app.ai.llm.pipeline.VoiceProfileSuggestionPass
     */
    @Deprecated("Use VoiceProfileSuggestionPass from modular pipeline", ReplaceWith("VoiceProfileSuggestionPass().execute(model, input, config)"))
    suspend fun suggestVoiceProfilesJson(charactersWithTraitsJson: String): String? = withContext(Dispatchers.IO) {
        if (!modelLoaded) return@withContext null
        try {
            val prompt = buildSuggestVoiceProfilesPrompt(charactersWithTraitsJson)
            val response = generateResponse(prompt, 512, 0.2f)
            val json = extractJsonFromResponse(response)
            if (json.isNotBlank()) json else null
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error suggesting voice profiles", e)
            null
        }
    }

    /**
     * @deprecated Use StoryGenerationPass from the modular pipeline instead.
     * @see com.dramebaz.app.ai.llm.pipeline.StoryGenerationPass
     */
    @Deprecated("Use StoryGenerationPass from modular pipeline", ReplaceWith("StoryGenerationPass().execute(model, input, config)"))
    suspend fun generateStory(userPrompt: String): String = withContext(Dispatchers.IO) {
        if (!modelLoaded) return@withContext ""
        val systemPrompt = """You are a creative story writer. Your task is to generate a complete, engaging story based on the user's prompt.
Rules:
1. Generate ONLY story content - no explanations, no meta-commentary, no JSON
2. Write a complete story with a beginning, middle, and end
3. Include dialogue, character development, and descriptive scenes
4. Make the story engaging and well-written
5. The story should be substantial (at least 1000 words)
6. Write in third person narrative style
7. Do not include any instructions or notes, only the story text itself.
Generate the story now:"""
        val fullPrompt = buildChatPrompt(systemPrompt, userPrompt)
        val response = generateResponse(fullPrompt, 1024, 0.3f, jsonMode = false)
        response.trim().replace(Regex("```[\\w]*\\n"), "").replace(Regex("```"), "").trim().ifEmpty { "" }
    }

    /**
     * STORY-003: Remix an existing story based on user instructions.
     * Uses a specialized remix prompt that includes the original story context.
     *
     * @deprecated Use StoryRemixPass from the modular pipeline instead.
     * @see com.dramebaz.app.ai.llm.pipeline.StoryRemixPass
     */
    @Deprecated("Use StoryRemixPass from modular pipeline", ReplaceWith("StoryRemixPass().execute(model, input, config)"))
    suspend fun remixStory(remixPrompt: String): String = withContext(Dispatchers.IO) {
        if (!modelLoaded) return@withContext ""
        val systemPrompt = """You are a creative story writer. Your task is to REMIX an existing story based on the user's instructions.
Rules:
1. Generate ONLY story content - no explanations, no meta-commentary, no JSON
2. Preserve the core narrative elements (characters, setting, major plot points) unless instructed otherwise
3. Apply the requested transformation creatively and consistently
4. Write a complete story with a beginning, middle, and end
5. Include dialogue, character development, and descriptive scenes
6. The remixed story should be substantial (at least 1000 words)
7. Write in third person narrative style
8. Do not include any instructions or notes, only the story text itself.
Generate the remixed story now:"""
        val fullPrompt = buildChatPrompt(systemPrompt, remixPrompt)
        val response = generateResponse(fullPrompt, 1024, 0.3f, jsonMode = false)
        response.trim().replace(Regex("```[\\w]*\\n"), "").replace(Regex("```"), "").trim().ifEmpty { "" }
    }

    // ==================== VIS-001: Scene Prompt Generation ====================

    /**
     * VIS-001: Generate an image generation prompt from scene text.
     * Extracts visual elements (characters, setting, mood, lighting) and creates
     * a structured prompt suitable for Stable Diffusion or Imagen.
     *
     * @deprecated Use ScenePromptGenerationPass from the modular pipeline instead.
     * @see com.dramebaz.app.ai.llm.pipeline.ScenePromptGenerationPass
     */
    @Deprecated("Use ScenePromptGenerationPass from modular pipeline", ReplaceWith("ScenePromptGenerationPass().execute(model, input, config)"))
    suspend fun generateScenePrompt(
        sceneText: String,
        mood: String? = null,
        characters: List<String> = emptyList()
    ): com.dramebaz.app.data.models.ScenePrompt? = withContext(Dispatchers.IO) {
        if (!modelLoaded) return@withContext null
        try {
            val prompt = buildScenePromptGenerationPrompt(sceneText, mood, characters)
            val response = generateResponse(prompt, 512, 0.3f)
            parseScenePromptResponse(response, sceneText)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error generating scene prompt", e)
            null
        }
    }

    private fun buildScenePromptGenerationPrompt(
        sceneText: String,
        mood: String?,
        characters: List<String>
    ): String {
        val text = sceneText.take(2000) // Limit scene text for prompt
        val moodHint = mood?.let { "Mood: $it\n" } ?: ""
        val charHint = if (characters.isNotEmpty()) "Characters present: ${characters.joinToString(", ")}\n" else ""

        val systemPrompt = """You are an image prompt generator. Extract visual elements from the scene and create a detailed image generation prompt. Output ONLY valid JSON."""

        val userPrompt = """Analyze this scene and create an image generation prompt for Stable Diffusion or similar models.

${moodHint}${charHint}
<SCENE>
$text
</SCENE>

Extract visual elements and return JSON:
{"prompt": "detailed description of the scene for image generation", "negative_prompt": "elements to avoid", "style": "art style suggestion", "mood": "detected mood", "setting": "location/environment", "time_of_day": "morning/afternoon/evening/night/unknown", "characters": ["list of characters visible"]}

Focus on: visual composition, lighting, colors, environment details, character descriptions if present.
$jsonValidityReminder"""

        return buildChatPrompt(systemPrompt, userPrompt)
    }

    private fun parseScenePromptResponse(
        response: String,
        originalText: String
    ): com.dramebaz.app.data.models.ScenePrompt? {
        return try {
            val json = extractJsonFromResponse(response)
            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return null

            val prompt = obj["prompt"] as? String ?: return null
            val negativePrompt = obj["negative_prompt"] as? String ?: com.dramebaz.app.data.models.ScenePrompt.DEFAULT_NEGATIVE
            val style = obj["style"] as? String ?: "detailed digital illustration"
            val mood = obj["mood"] as? String ?: "neutral"
            val setting = obj["setting"] as? String ?: ""
            val timeOfDay = obj["time_of_day"] as? String ?: ""
            val characters = (obj["characters"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

            com.dramebaz.app.data.models.ScenePrompt(
                prompt = prompt,
                negativePrompt = negativePrompt,
                style = style,
                mood = mood,
                setting = setting,
                timeOfDay = timeOfDay,
                characters = characters
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse scene prompt response, using fallback", e)
            // Fallback: create a basic prompt from the original text
            createFallbackScenePrompt(originalText)
        }
    }

    private fun createFallbackScenePrompt(sceneText: String): com.dramebaz.app.data.models.ScenePrompt {
        // Extract basic visual elements using simple heuristics
        val words = sceneText.lowercase()

        val mood = when {
            words.contains("dark") || words.contains("fear") || words.contains("shadow") -> "dark"
            words.contains("bright") || words.contains("happy") || words.contains("sun") -> "bright"
            words.contains("sad") || words.contains("tear") || words.contains("grief") -> "melancholy"
            else -> "neutral"
        }

        val timeOfDay = when {
            words.contains("morning") || words.contains("sunrise") || words.contains("dawn") -> "morning"
            words.contains("afternoon") || words.contains("midday") -> "afternoon"
            words.contains("evening") || words.contains("sunset") || words.contains("dusk") -> "evening"
            words.contains("night") || words.contains("midnight") || words.contains("moon") -> "night"
            else -> "unknown"
        }

        // Create a basic prompt from the first few sentences
        val sentences = sceneText.split(Regex("[.!?]")).filter { it.isNotBlank() }.take(3)
        val prompt = sentences.joinToString(". ") { it.trim() }

        return com.dramebaz.app.data.models.ScenePrompt(
            prompt = "Scene: $prompt",
            mood = mood,
            timeOfDay = timeOfDay,
            style = "detailed digital illustration"
        )
    }

    /**
     * @deprecated Use KeyMomentsExtractionPass from the modular pipeline instead.
     * @see com.dramebaz.app.ai.llm.pipeline.KeyMomentsExtractionPass
     */
    @Deprecated("Use KeyMomentsExtractionPass from modular pipeline", ReplaceWith("KeyMomentsExtractionPass().execute(model, input, config)"))
    suspend fun extractKeyMomentsForCharacter(characterName: String, chapterText: String, chapterTitle: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        if (!modelLoaded) return@withContext emptyList()
        try {
            val prompt = buildExtractKeyMomentsPrompt(characterName, chapterText, chapterTitle)
            val response = generateResponse(prompt, 512, 0.2f)
            parseKeyMomentsFromResponse(response)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error extracting key moments", e)
            emptyList()
        }
    }

    /**
     * @deprecated Use RelationshipsExtractionPass from the modular pipeline instead.
     * @see com.dramebaz.app.ai.llm.pipeline.RelationshipsExtractionPass
     */
    @Deprecated("Use RelationshipsExtractionPass from modular pipeline", ReplaceWith("RelationshipsExtractionPass().execute(model, input, config)"))
    suspend fun extractRelationshipsForCharacter(characterName: String, chapterText: String, allCharacterNames: List<String>): List<Map<String, String>> = withContext(Dispatchers.IO) {
        if (!modelLoaded) return@withContext emptyList()
        try {
            val prompt = buildExtractRelationshipsPrompt(characterName, chapterText, allCharacterNames)
            val response = generateResponse(prompt, 512, 0.2f)
            parseRelationshipsFromResponse(response)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error extracting relationships", e)
            emptyList()
        }
    }

    // ==================== Multi-Pass Character Analysis (Pass 1-4) ====================

    /**
     * Pass-1: Extract ONLY character names from the text.
     * Returns a list of character names exactly as they appear in the text.
     *
     * @deprecated Use CharacterExtractionPass from the modular pipeline instead.
     * @see com.dramebaz.app.ai.llm.pipeline.CharacterExtractionPass
     */
    @Deprecated("Use CharacterExtractionPass from modular pipeline", ReplaceWith("CharacterExtractionPass().execute(model, input, config)"))
    suspend fun extractCharacterNames(chapterText: String): List<String> = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "Pass-1: extractCharacterNames called (text=${chapterText.length} chars)")
        if (!modelLoaded) {
            AppLogger.w(TAG, "Pass-1: model not loaded, returning empty")
            return@withContext emptyList()
        }
        try {
            AppLogger.d(TAG, "Pass-1: building prompt...")
            val prompt = buildPass1ExtractNamesPrompt(chapterText)
            AppLogger.d(TAG, "Pass-1: calling generateResponse...")
            // Reduced from 512 to 256 - typical output is ~50-100 tokens (just character names)
            val response = generateResponse(prompt, 256, 0.1f)
            AppLogger.d(TAG, "Pass-1: parsing response (${response.length} chars)...")
            val names = parsePass1NamesResponse(response)
            AppLogger.d(TAG, "Pass-1: extracted ${names.size} names: $names")
            names
        } catch (e: Exception) {
            AppLogger.e(TAG, "Pass-1: Error extracting character names", e)
            emptyList()
        }
    }

    /**
     * Pass-2: Extract explicit traits for a specific character from the text.
     * Should be called in parallel for each character from Pass-1.
     *
     * @deprecated Use TraitsExtractionPass from the modular pipeline instead.
     * @see com.dramebaz.app.ai.llm.pipeline.TraitsExtractionPass
     */
    @Deprecated("Use TraitsExtractionPass from modular pipeline", ReplaceWith("TraitsExtractionPass().execute(model, input, config)"))
    suspend fun extractTraitsForCharacter(characterName: String, chapterText: String): List<String> = withContext(Dispatchers.IO) {
        if (!modelLoaded) return@withContext emptyList()
        try {
            val prompt = buildPass2ExtractTraitsPrompt(characterName, chapterText)
            val response = generateResponse(prompt, 512, 0.15f)
            parsePass2TraitsResponse(response)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Pass-2: Error extracting traits for $characterName", e)
            emptyList()
        }
    }

    /**
     * Pass-2.5: Extract dialogs and narrator text from a page.
     * Should be called after Pass-2 for each page.
     * @param pageText The text of the current page
     * @param characterNames List of character names found on this page (from Pass-1)
     * @return List of extracted dialogs with speaker attribution
     *
     * @deprecated Use DialogExtractionPass from the modular pipeline instead.
     * @see com.dramebaz.app.ai.llm.pipeline.DialogExtractionPass
     */
    @Deprecated("Use DialogExtractionPass from modular pipeline", ReplaceWith("DialogExtractionPass().execute(model, input, config)"))
    suspend fun extractDialogsFromPage(pageText: String, characterNames: List<String>): List<ExtractedDialogEntry> = withContext(Dispatchers.IO) {
        if (!modelLoaded) return@withContext emptyList()
        try {
            val prompt = buildPass2_5ExtractDialogsPrompt(pageText, characterNames)
            // Reduced from 1024 to 512 - typical output is ~200-400 tokens
            val response = generateResponse(prompt, 512, 0.15f)
            parsePass2_5DialogsResponse(response)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Pass-2.5: Error extracting dialogs", e)
            emptyList()
        }
    }

    /**
     * Data class for extracted dialog entries from Pass-2.5.
     */
    data class ExtractedDialogEntry(
        val speaker: String,
        val text: String,
        val emotion: String = "neutral",
        val intensity: Float = 0.5f
    )

    /**
     * Pass-3: Infer personality based on traits extracted in Pass-2.
     * Should be called in parallel for each character.
     *
     * @deprecated Use PersonalityInferencePass from the modular pipeline instead.
     * @see com.dramebaz.app.ai.llm.pipeline.PersonalityInferencePass
     */
    @Deprecated("Use PersonalityInferencePass from modular pipeline", ReplaceWith("PersonalityInferencePass().execute(model, input, config)"))
    suspend fun inferPersonalityFromTraits(characterName: String, traits: List<String>): List<String> = withContext(Dispatchers.IO) {
        if (!modelLoaded) return@withContext emptyList()
        try {
            val prompt = buildPass3InferPersonalityPrompt(characterName, traits)
            val response = generateResponse(prompt, 512, 0.2f)
            parsePass3PersonalityResponse(response)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Pass-3: Error inferring personality for $characterName", e)
            emptyList()
        }
    }

    /**
     * Pass-3: Extract traits AND suggest voice profile for 1-2 characters using aggregated context.
     * This is the final pass of the 3-pass workflow, processing characters with context from all pages.
     *
     * @param char1Name First character name
     * @param char1Context Aggregated text from all pages where char1 appears (up to 10,000 chars)
     * @param char2Name Optional second character name (null if processing single character)
     * @param char2Context Optional aggregated text for second character
     * @param char3Name Optional third character name
     * @param char3Context Optional aggregated text for third character
     * @param char4Name Optional fourth character name
     * @param char4Context Optional aggregated text for fourth character
     * @return List of Pair(characterName, Pair(traits, voiceProfile)) for each processed character
     *
     * @deprecated Use TraitsExtractionPass + VoiceProfileSuggestionPass from the modular pipeline instead.
     * @see com.dramebaz.app.ai.llm.pipeline.TraitsExtractionPass
     * @see com.dramebaz.app.ai.llm.pipeline.VoiceProfileSuggestionPass
     */
    @Deprecated("Use TraitsExtractionPass + VoiceProfileSuggestionPass from modular pipeline")
    suspend fun pass3ExtractTraitsAndVoiceProfile(
        char1Name: String,
        char1Context: String,
        char2Name: String? = null,
        char2Context: String? = null,
        char3Name: String? = null,
        char3Context: String? = null,
        char4Name: String? = null,
        char4Context: String? = null
    ): List<Pair<String, Pair<List<String>, Map<String, Any>>>> = withContext(Dispatchers.IO) {
        if (!modelLoaded) return@withContext emptyList()
        try {
            val prompt = buildPass3WithAggregatedContext(
                char1Name, char1Context,
                char2Name, char2Context,
                char3Name, char3Context,
                char4Name, char4Context
            )
            // Token allocation scales with character count:
            // 1 char: 384, 2 chars: 640, 3 chars: 960, 4 chars: 1280
            val charCount = listOfNotNull(char1Name, char2Name, char3Name, char4Name).size
            val maxTokens = when (charCount) {
                4 -> 1280
                3 -> 960
                2 -> 640
                else -> 384
            }
            val response = generateResponse(prompt, maxTokens, 0.1f)
            parsePass3Response(response, char1Name, char2Name, char3Name, char4Name)
        } catch (e: Exception) {
            val names = listOfNotNull(char1Name, char2Name, char3Name, char4Name).joinToString(", ")
            AppLogger.e(TAG, "Pass-3: Error for $names", e)
            emptyList()
        }
    }

    // ==================== End Multi-Pass Character Analysis ====================

    fun release() {
        try {
            if (nativeHandle != 0L) {
                LlamaNative.release(nativeHandle)
                nativeHandle = 0L
            }
        } catch (_: Exception) {}
        modelLoaded = false
    }

    private fun buildFullAnalysisPrompt(chapterText: String): String {
        val text = chapterText.take(maxInputChars) + if (chapterText.length > maxInputChars) "\n[...truncated]" else ""
        val systemPrompt = """You are a fiction story analyzer. Extract ALL requested information in ONE valid JSON. No commentary."""
        val userPrompt = """Analyze this FICTION STORY chapter and return ONE valid JSON with ALL of these fields:

1. chapter_summary: {"title": "Chapter Title", "short_summary": "Brief summary", "main_events": ["event1", "event2"], "emotional_arc": [{"segment": "start", "emotion": "curious", "intensity": 0.5}]}
2. characters: [{"name": "Character Full Name", "traits": ["male/female", "young/adult/old", "personality trait"], "voice_profile": {"pitch": 1.0, "speed": 1.0, "energy": 1.0}}]
   - Extract ONLY people/beings who are ACTORS (speak or act). NOT places, objects, narrator, or vague words.
3. dialogs: [{"speaker": "Character Name", "dialog": "Exact quoted speech", "emotion": "neutral", "intensity": 0.5, "confidence": 0.9}]
4. sound_cues: [{"event": "door slam", "sound_prompt": "loud wooden bang", "duration": 1.0, "category": "effect"}]
5. themes: ["string"]
6. symbols: ["string"]
7. foreshadowing: ["string"]
8. vocabulary: [{"word": "string", "definition": "string"}]

Return ONLY valid JSON in this exact shape (all keys required):
{"chapter_summary": {...}, "characters": [...], "dialogs": [...], "sound_cues": [...], "themes": [], "symbols": [], "foreshadowing": [], "vocabulary": []}

<STORY_CHAPTER>
$text
</STORY_CHAPTER>
$jsonValidityReminder"""
        return buildChatPrompt(systemPrompt, userPrompt)
    }

    private fun buildAnalysisPrompt(chapterText: String): String {
        val text = chapterText.take(maxInputChars) + if (chapterText.length > maxInputChars) "\n[...truncated]" else ""
        val systemPrompt = """You are a fiction story analyzer. The input is a NARRATIVE STORY EXCERPT from a novel or book.

STORY STRUCTURE:
- CHARACTERS/ACTORS: People or beings with names who speak dialog or perform actions in the story
- NARRATOR: The storytelling voice (do NOT extract as a character)
- DIALOGS: Quoted speech between characters
- SETTINGS: Places and locations (do NOT extract as characters)

Your task: Extract ONLY the CHARACTERS (people/beings who are actors in the story).

Output valid JSON only. No commentary."""
        val userPrompt = """Analyze this FICTION STORY chapter and extract characters, dialogs, sounds, and summary.

CHARACTERS - Extract ONLY people/beings who are ACTORS in the story:
✅ EXTRACT these (characters with names who speak or act):
   - Named people: "Harry Potter", "Hermione Granger", "Mr. Dursley", "Uncle Vernon"
   - Titled characters: "Professor Dumbledore", "Mrs. Weasley", "Dr. Watson"
   - Characters with dialog (anyone who speaks in quotes)
   - Characters performing actions or mentioned by other characters

❌ DO NOT EXTRACT these:
   - Places/locations: "Hogwarts", "London", "the house", "Privet Drive"
   - Objects: "wand", "letter", "car", "door"
   - Abstract concepts: "magic", "love", "fear", "darkness"
   - The narrator or narrative voice
   - Vague references: "someone", "everyone", "they", "people"
   - Common nouns: "boy", "girl", "man", "woman" (unless it's their actual name/title)

Return ONLY valid JSON:
{
  "chapter_summary": {"title": "Chapter Title", "short_summary": "Brief summary", "main_events": ["event1", "event2"], "emotional_arc": [{"segment": "start", "emotion": "curious", "intensity": 0.5}]},
  "characters": [{"name": "Character Full Name", "traits": ["male/female", "young/adult/old", "personality trait"], "voice_profile": {"pitch": 1.0, "speed": 1.0, "energy": 1.0}}],
  "dialogs": [{"speaker": "Character Name", "dialog": "Exact quoted speech", "emotion": "neutral", "intensity": 0.5, "confidence": 0.9}],
  "sound_cues": [{"event": "door slam", "sound_prompt": "loud wooden bang", "duration": 1.0, "category": "effect"}]
}

<STORY_CHAPTER>
$text
</STORY_CHAPTER>
$jsonValidityReminder"""
        return buildChatPrompt(systemPrompt, userPrompt)
    }

    private fun buildExtendedAnalysisPrompt(chapterText: String): String {
        val text = chapterText.take(maxInputChars) + if (chapterText.length > maxInputChars) "\n[...truncated]" else ""
        val systemPrompt = """You are an extraction engine. Your only job is to read the text and output valid JSON. Do not add commentary. Do not guess missing information."""
        val userPrompt = """Extract themes, symbols, foreshadowing, and vocabulary. Return ONLY valid JSON in this exact format:

{"themes": ["string"], "symbols": ["string"], "foreshadowing": ["string"], "vocabulary": [{"word": "string", "definition": "string"}]}

<CHAPTER_TEXT>
$text
</CHAPTER_TEXT>
$jsonValidityReminder"""
        return buildChatPrompt(systemPrompt, userPrompt)
    }

    private fun buildExtractCharactersAndTraitsPrompt(segmentText: String, skipNamesWithTraits: Collection<String>, namesNeedingTraits: Collection<String>): String {
        val text = segmentText.take(maxInputChars) + if (segmentText.length > maxInputChars) "\n[...truncated]" else ""
        val skipLine = if (skipNamesWithTraits.isEmpty()) "" else "\nAlready extracted (skip these): ${skipNamesWithTraits.joinToString(", ")}."
        val needTraitsLine = if (namesNeedingTraits.isEmpty()) "" else "\nNeed traits for: ${namesNeedingTraits.joinToString(", ")}."
        val systemPrompt = """You are a fiction story character extractor. The input is a NARRATIVE STORY EXCERPT from a novel.

CHARACTERS are people or beings who are ACTORS in the story - they speak dialog or perform actions.
DO NOT extract: places, objects, concepts, the narrator, or vague references like "someone".

Output valid JSON only."""
        val userPrompt = """Extract CHARACTERS (story actors) from this fiction text.

✅ EXTRACT: Named people who speak or act (e.g., "Harry Potter", "Mrs. Dursley", "Professor McGonagall")
❌ DO NOT EXTRACT: Places ("Hogwarts"), objects ("wand"), concepts ("magic"), narrator, or vague words ("someone", "boy")
$skipLine$needTraitsLine

For each character, include voice traits: gender (male/female), age (child/young/adult/elderly), personality.

Return ONLY valid JSON:
{"characters": [{"name": "Character Name", "traits": ["male", "adult", "stern"]}]}

<STORY_TEXT>
$text
</STORY_TEXT>
$jsonValidityReminder"""
        return buildChatPrompt(systemPrompt, userPrompt)
    }

    private fun buildDetectCharactersOnPagePrompt(pageText: String): String {
        val text = pageText.take(maxInputChars) + if (pageText.length > maxInputChars) "\n[...truncated]" else ""
        val systemPrompt = """You are a fiction story character detector. The input is a NARRATIVE STORY page.

Extract ONLY character names - people or beings who are ACTORS in the story.
DO NOT extract: places, objects, concepts, the narrator, or common nouns.

Output valid JSON only."""
        val userPrompt = """List all CHARACTER NAMES from this story page.

✅ EXTRACT: Named people/beings who speak or act (e.g., "Harry", "Dumbledore", "Mr. Dursley")
❌ DO NOT EXTRACT: Places, objects, concepts, narrator references, or vague words

Return ONLY valid JSON:
{"names": ["Character1", "Character2"]}
If no characters found: {"names": []}

<STORY_PAGE>
$text
</STORY_PAGE>
$jsonValidityReminder"""
        return buildChatPrompt(systemPrompt, userPrompt)
    }

    private fun buildInferTraitsPrompt(characterName: String, excerpt: String): String {
        val text = excerpt.take(5000) + if (excerpt.length > 5000) "\n[...truncated]" else ""
        val systemPrompt = """You are an extraction engine. Your only job is to output valid JSON. Do not add commentary."""
        val userPrompt = """Infer voice-relevant traits for "$characterName" from the excerpt (gender, age, accent when inferable). Return ONLY valid JSON:
{"traits": ["trait1", "trait2"]}

<EXCERPT>
$text
</EXCERPT>
$jsonValidityReminder"""
        return buildChatPrompt(systemPrompt, userPrompt)
    }

    private fun buildSuggestVoiceProfilesPrompt(charactersWithTraitsJson: String): String {
        val systemPrompt = """You are an extraction engine. Your only job is to output valid JSON. Do not add commentary."""
        val userPrompt = """For each character suggest TTS profile: pitch, speed, energy (0.5-1.5), emotion_bias. Return ONLY valid JSON in this exact format:
{"characters": [{"name": "string", "voice_profile": {"pitch": 1.0, "speed": 1.0, "energy": 1.0, "emotion_bias": {}}}]}

<CHARACTERS>
$charactersWithTraitsJson
</CHARACTERS>
$jsonValidityReminder"""
        return buildChatPrompt(systemPrompt, userPrompt)
    }

    private fun buildExtractKeyMomentsPrompt(characterName: String, chapterText: String, chapterTitle: String): String {
        val text = chapterText.take(maxInputChars) + if (chapterText.length > maxInputChars) "\n[...truncated]" else ""
        val systemPrompt = """You are an extraction engine. Your only job is to output valid JSON. Do not add commentary."""
        val userPrompt = """Extract 2-3 key moments for "$characterName" in this chapter. Key moments are significant events, decisions, revelations, or emotional scenes involving this character.
Return ONLY valid JSON:
{"moments": [{"chapter": "$chapterTitle", "moment": "brief description", "significance": "why it matters"}]}

<TEXT>
$text
</TEXT>
$jsonValidityReminder"""
        return buildChatPrompt(systemPrompt, userPrompt)
    }

    private fun buildExtractRelationshipsPrompt(characterName: String, chapterText: String, allCharacterNames: List<String>): String {
        val text = chapterText.take(maxInputChars) + if (chapterText.length > maxInputChars) "\n[...truncated]" else ""
        val otherNames = allCharacterNames.filter { !it.equals(characterName, ignoreCase = true) }.take(20).joinToString(", ")
        val systemPrompt = """You are an extraction engine. Your only job is to output valid JSON. Do not add commentary."""
        val userPrompt = """Extract relationships between "$characterName" and other characters: $otherNames
Relationship types: family, friend, enemy, romantic, professional, other.
Return ONLY valid JSON:
{"relationships": [{"character": "other character name", "relationship": "type", "nature": "brief description"}]}

<TEXT>
$text
</TEXT>
$jsonValidityReminder"""
        return buildChatPrompt(systemPrompt, userPrompt)
    }

    // ==================== Multi-Pass Prompt Builders (Pass 1-4) ====================

    /**
     * Pass-1 Prompt: Extract ONLY character names from the text.
     * Focus on explicit names only, no inference.
     */
    private fun buildPass1ExtractNamesPrompt(chapterText: String): String {
        val text = chapterText.take(maxInputChars) + if (chapterText.length > maxInputChars) "\n[...truncated]" else ""
        val systemPrompt = """You are a character name extraction engine. Extract ONLY character names that appear in the provided text."""
        val userPrompt = """STRICT RULES:
- Extract ONLY proper names explicitly written in the text (e.g., "Harry Potter", "Hermione", "Mr. Dursley")
- Do NOT include pronouns (he, she, they, etc.)
- Do NOT include generic descriptions (the boy, the woman, the teacher)
- Do NOT include group references (the family, the crowd, the students)
- Do NOT include titles alone (Professor, Sir, Madam) unless used as the character's actual name
- Do NOT infer or guess names not explicitly mentioned
- Do NOT split full names: if "Harry Potter" appears, do NOT list "Potter" separately
- Do NOT include names of characters who are only mentioned but not present/acting in the scene
- Include a name only if the character speaks, acts, or is directly described in this specific page

OUTPUT FORMAT (valid JSON only):
{"characters": ["Name1", "Name2", "Name3"]}

TEXT:
$text
$jsonValidityReminder"""
        return buildChatPrompt(systemPrompt, userPrompt)
    }

    /**
     * Pass-2 Prompt: Extract explicit traits for a specific character.
     * Only traits explicitly stated in text, no inference.
     */
    private fun buildPass2ExtractTraitsPrompt(characterName: String, chapterText: String): String {
        val text = chapterText.take(maxInputChars) + if (chapterText.length > maxInputChars) "\n[...truncated]" else ""
        val systemPrompt = """You are a trait extraction engine. Extract ONLY the explicitly stated traits for the character "$characterName" from the provided text."""
        val userPrompt = """STRICT RULES:
- Extract ONLY traits directly stated or shown in the text
- Include physical descriptions if explicitly mentioned (e.g., "tall", "red hair", "scarred")
- Include behavioral traits if explicitly shown (e.g., "spoke softly", "slammed the door", "laughed nervously")
- Include speech patterns if demonstrated (e.g., "stutters", "uses formal language", "speaks with accent")
- Include emotional states if explicitly described (e.g., "angry", "frightened", "cheerful")
- Do NOT infer personality from actions
- Do NOT add interpretations or assumptions
- Do NOT include traits of other characters
- If no traits are found for this character on this page, return an empty list

OUTPUT FORMAT (valid JSON only):
{"character": "$characterName", "traits": ["trait1", "trait2", "trait3"]}

TEXT:
$text
$jsonValidityReminder"""
        return buildChatPrompt(systemPrompt, userPrompt)
    }

    /**
     * Pass-2.5 Prompt: Extract dialogs and narrator text from a page.
     * Attributes quoted speech to the nearest character and extracts narrator prose.
     */
    private fun buildPass2_5ExtractDialogsPrompt(pageText: String, characterNames: List<String>): String {
        val text = pageText.take(maxInputChars) + if (pageText.length > maxInputChars) "\n[...truncated]" else ""
        // Add "Narrator" to the character list to ensure narrator text is properly extracted
        val charactersWithNarrator = characterNames + listOf("Narrator")
        val charactersJson = gson.toJson(charactersWithNarrator)
        val systemPrompt = """You are a dialog extraction engine. Extract quoted speech and attribute it to the correct speaker. Output valid JSON only."""
        val userPrompt = """CHARACTERS ON THIS PAGE: $charactersJson

EXTRACTION RULES:
1. DIALOGS - Extract text within quotation marks ("..." or '...'):
   - Attribute each dialog to the nearest character name appearing BEFORE or AFTER the quote (within ~200 chars)
   - Use attribution patterns: "said [Name]", "[Name] said", "[Name]:", "[Name] asked", "[Name] replied", "whispered", "shouted", "muttered", etc.
   - If a pronoun (he/she/they) refers to a recently mentioned character, attribute to that character
   - If speaker cannot be determined, use "Unknown"

2. NARRATOR TEXT - Extract descriptive prose between dialogs:
   - Scene descriptions, action descriptions, internal thoughts (if not in quotes)
   - Attribute narrator text to "Narrator"
   - Keep narrator segments reasonably sized (1-3 sentences each)

3. EMOTION DETECTION - For each segment:
   - Infer emotion: neutral, happy, sad, angry, surprised, fearful, excited, worried, curious, defiant
   - Estimate intensity: 0.0 (very mild) to 1.0 (very intense)
   - Use context clues: exclamation marks, word choice, described actions

4. ORDERING - Maintain the order of appearance in the text

OUTPUT FORMAT (valid JSON only):
{
  "dialogs": [
    {"speaker": "Character Name", "text": "Exact quoted speech or narrator text", "emotion": "neutral", "intensity": 0.5},
    {"speaker": "Narrator", "text": "Descriptive prose between dialogs", "emotion": "neutral", "intensity": 0.3}
  ]
}

TEXT:
$text
$jsonValidityReminder"""
        return buildChatPrompt(systemPrompt, userPrompt)
    }

    /**
     * Pass-3 Prompt: Infer personality from the traits extracted in Pass-2.
     * Now inference is allowed, but only based on the provided traits.
     */
    private fun buildPass3InferPersonalityPrompt(characterName: String, traits: List<String>): String {
        val traitsJson = gson.toJson(traits)
        val traitsText = if (traits.isEmpty()) "No explicit traits found." else traits.joinToString("\n- ", prefix = "- ")
        val systemPrompt = """You are a personality analysis engine. Infer the personality of "$characterName" based ONLY on the traits provided below."""
        val userPrompt = """TRAITS:
$traitsText

STRICT RULES:
- Base your inference ONLY on the provided traits
- Do NOT introduce new traits not in the list
- Do NOT contradict the provided traits
- Synthesize the traits into coherent personality descriptors
- Keep descriptions concise and grounded in the evidence
- Provide 3-5 personality points maximum
- If traits list is empty or insufficient, provide minimal inference (e.g., ["minor character", "limited information"])

OUTPUT FORMAT (valid JSON only):
{"character": "$characterName", "personality": ["personality_point1", "personality_point2", "personality_point3"]}

TRAITS:
$traitsJson
$jsonValidityReminder"""
        return buildChatPrompt(systemPrompt, userPrompt)
    }

    /**
     * Pass-3 Prompt: Extract traits AND suggest voice profile for 1-4 characters.
     * Uses aggregated context from all pages where each character appears (full context preserved).
     * Processes up to 4 characters at a time for efficiency.
     *
     * Traits are extracted as concise 1-2 word phrases only.
     */
    private fun buildPass3WithAggregatedContext(
        char1Name: String,
        char1Context: String,
        char2Name: String?,
        char2Context: String?,
        char3Name: String? = null,
        char3Context: String? = null,
        char4Name: String? = null,
        char4Context: String? = null
    ): String {
        val systemPrompt = "You are a character analyst for TTS voice casting. Extract observable traits and suggest voice profile. JSON only."

        // Build character sections dynamically based on how many characters we have
        val charNames = listOfNotNull(char1Name, char2Name, char3Name, char4Name)
        val charContexts = listOfNotNull(
            char1Context,
            char2Context,
            char3Context,
            char4Context
        )

        val characterSection = if (charNames.size > 1) {
            val namesStr = charNames.joinToString(", ") { "\"$it\"" }
            val sections = charNames.zip(charContexts).mapIndexed { idx, (name, context) ->
                """CHARACTER ${idx + 1} - "$name" TEXT:
$context"""
            }.joinToString("\n\n")
            """CHARACTERS TO ANALYZE: $namesStr

$sections"""
        } else {
            """CHARACTER: "$char1Name"

TEXT:
$char1Context"""
        }

        val outputFormat = if (charNames.size > 1) {
            val charEntries = charNames.joinToString(",\n    ") { name ->
                """{"character": "$name", "traits": ["trait1", "trait2"], "voice_profile": {"pitch": 1.0, "speed": 1.0, "energy": 0.7, "gender": "male|female", "age": "child|young|middle-aged|elderly", "tone": "brief description", "speaker_id": 45}}"""
            }
            """{
  "characters": [
    $charEntries
  ]
}"""
        } else {
            """{
  "character": "$char1Name",
  "traits": ["trait1", "trait2", "trait3"],
  "voice_profile": {"pitch": 1.0, "speed": 1.0, "energy": 0.7, "gender": "male|female", "age": "child|young|middle-aged|elderly", "tone": "brief description", "speaker_id": 45}
}"""
        }

        val userPrompt = """$characterSection

EXTRACT CONCISE TRAITS (1-2 words only):
- Examples: "gravelly voice", "nervous fidgeting", "dry humor", "rambling", "high-pitched", "slow pacing"
- DO NOT write verbose descriptions like "TTS Voice Traits: Pitch: Low..."

TRAIT → VOICE MAPPING:
- "gravelly/deep/commanding" → pitch: 0.8-0.9
- "bright/light/young" → pitch: 1.1-1.2
- "fast-paced/rambling/excited" → speed: 1.1-1.2
- "slow/deliberate/monotone" → speed: 0.8-0.9
- "energetic/dynamic/intense" → energy: 0.9-1.0
- "calm/stoic/reserved" → energy: 0.5-0.7

SPEAKER_ID GUIDE (VCTK 0-108): Male young 0-20, middle-aged 21-45, elderly 46-55; Female young 56-75, middle-aged 76-95, elderly 96-108

OUTPUT FORMAT (valid JSON only):
$outputFormat
$jsonValidityReminder"""

        return buildChatPrompt(systemPrompt, userPrompt)
    }

    // ==================== Multi-Pass Response Parsers (Pass 1-4) ====================

    private fun parsePass1NamesResponse(response: String): List<String> {
        return try {
            val json = extractJsonFromResponse(response)
            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            // Try "characters" first (new format), fall back to "names" (old format)
            val raw = ((obj["characters"] as? List<*>) ?: (obj["names"] as? List<*>))
                ?.mapNotNull { it as? String }?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            raw.distinctBy { it.lowercase() }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Pass-1: Failed to parse names response", e)
            emptyList()
        }
    }

    private fun parsePass2TraitsResponse(response: String): List<String> {
        return try {
            val json = extractJsonFromResponse(response)
            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            (obj["traits"] as? List<*>)?.mapNotNull { it as? String }?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Pass-2: Failed to parse traits response", e)
            emptyList()
        }
    }

    private fun parsePass2_5DialogsResponse(response: String): List<ExtractedDialogEntry> {
        return try {
            val json = extractJsonFromResponse(response)
            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val dialogsList = obj["dialogs"] as? List<*> ?: return emptyList()
            dialogsList.mapNotNull { item ->
                val dialogMap = item as? Map<*, *> ?: return@mapNotNull null
                val speaker = (dialogMap["speaker"] as? String)?.trim() ?: return@mapNotNull null
                val text = (dialogMap["text"] as? String)?.trim() ?: return@mapNotNull null
                if (text.isBlank()) return@mapNotNull null
                val emotion = (dialogMap["emotion"] as? String)?.trim()?.lowercase() ?: "neutral"
                val intensity = (dialogMap["intensity"] as? Number)?.toFloat() ?: 0.5f
                ExtractedDialogEntry(speaker, text, emotion, intensity.coerceIn(0f, 1f))
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Pass-2.5: Failed to parse dialogs response", e)
            emptyList()
        }
    }

    private fun parsePass3PersonalityResponse(response: String): List<String> {
        return try {
            val json = extractJsonFromResponse(response)
            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            (obj["personality"] as? List<*>)?.mapNotNull { it as? String }?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Pass-3: Failed to parse personality response", e)
            emptyList()
        }
    }

    /**
     * Pass-3 Response Parser: Extract traits and voice_profile for 1-4 characters.
     * Returns List of Pair(characterName, Pair(traits, voiceProfile))
     */
    private fun parsePass3Response(
        response: String,
        char1Name: String,
        char2Name: String? = null,
        char3Name: String? = null,
        char4Name: String? = null
    ): List<Pair<String, Pair<List<String>, Map<String, Any>>>> {
        return try {
            val json = extractJsonFromResponse(response)
            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *>
                ?: return emptyList()

            val results = mutableListOf<Pair<String, Pair<List<String>, Map<String, Any>>>>()

            // Check if it's a multi-character response (has "characters" array)
            val characters = obj["characters"] as? List<*>
            if (characters != null) {
                // Multi-character response
                for (charObj in characters) {
                    val charMap = charObj as? Map<*, *> ?: continue
                    val parsed = parseSingleCharacterFromMap(charMap)
                    if (parsed != null) {
                        results.add(parsed)
                    }
                }
            } else {
                // Single character response
                val parsed = parseSingleCharacterFromMap(obj)
                if (parsed != null) {
                    results.add(parsed)
                }
            }

            AppLogger.d(TAG, "Pass-3: Parsed ${results.size} character(s)")
            results
        } catch (e: Exception) {
            AppLogger.w(TAG, "Pass-3: Failed to parse response", e)
            emptyList()
        }
    }

    /**
     * Helper to parse a single character's traits and voice profile from a map.
     */
    private fun parseSingleCharacterFromMap(obj: Map<*, *>): Pair<String, Pair<List<String>, Map<String, Any>>>? {
        val characterName = obj["character"] as? String ?: return null

        // Parse traits array
        val traits = (obj["traits"] as? List<*>)
            ?.mapNotNull { it as? String }
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() && it.length <= 50 } // Filter out verbose traits
            ?: emptyList()

        // Parse voice_profile object
        val voiceProfile = obj["voice_profile"] as? Map<*, *>
        val result = mutableMapOf<String, Any>()

        if (voiceProfile != null) {
            voiceProfile["gender"]?.let { result["gender"] = it }
            voiceProfile["age"]?.let { result["age"] = it }
            voiceProfile["tone"]?.let { result["tone"] = it }
            voiceProfile["accent"]?.let { result["accent"] = it }
            voiceProfile["speaker_id"]?.let { result["speaker_id"] = it }
            result["pitch"] = (voiceProfile["pitch"] as? Number)?.toDouble() ?: 1.0
            result["speed"] = (voiceProfile["speed"] as? Number)?.toDouble() ?: 1.0
            result["energy"] = (voiceProfile["energy"] as? Number)?.toDouble() ?: 1.0
        }

        AppLogger.d(TAG, "Pass-3: '$characterName' -> ${traits.size} traits, voice_profile keys: ${result.keys}")
        return Pair(characterName, Pair(traits, result))
    }

    // ==================== End Multi-Pass Builders & Parsers ====================

    private fun parseFullAnalysisResponse(response: String): Pair<ChapterAnalysisResponse?, String?>? {
        return try {
            val json = extractJsonFromResponse(response)
            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return null
            val analysis = ChapterAnalysisResponse(
                chapterSummary = parseChapterSummary(obj["chapter_summary"]),
                characters = parseCharacters(obj["characters"]),
                dialogs = parseDialogs(obj["dialogs"]),
                soundCues = parseSoundCues(obj["sound_cues"])
            )
            @Suppress("UNCHECKED_CAST")
            val extended = mapOf<String, Any>(
                "themes" to (obj["themes"] as? List<*> ?: emptyList<Any>()),
                "symbols" to (obj["symbols"] as? List<*> ?: emptyList<Any>()),
                "foreshadowing" to (obj["foreshadowing"] as? List<*> ?: emptyList<Any>()),
                "vocabulary" to (obj["vocabulary"] as? List<*> ?: emptyList<Any>())
            )
            val extendedJson = gson.toJson(extended)
            Pair(analysis, extendedJson)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse full analysis response", e)
            null
        }
    }

    private fun parseAnalysisResponse(response: String): ChapterAnalysisResponse? {
        return try {
            val json = extractJsonFromResponse(response)
            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return null
            ChapterAnalysisResponse(
            chapterSummary = parseChapterSummary(obj["chapter_summary"]),
            characters = parseCharacters(obj["characters"]),
            dialogs = parseDialogs(obj["dialogs"]),
            soundCues = parseSoundCues(obj["sound_cues"])
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse analysis response", e)
            null
        }
    }

    private fun parseExtendedAnalysisResponse(response: String): String? {
        return try {
            val json = extractJsonFromResponse(response)
            gson.fromJson(json, Map::class.java)
            json
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse extended analysis", e)
            null
        }
    }

    private fun parseCharactersAndTraitsFromResponse(response: String): List<Pair<String, List<String>>> {
        return try {
            val json = extractJsonFromResponse(response)
            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val arr = obj["characters"] as? List<*> ?: return emptyList()
            val result = mutableListOf<Pair<String, List<String>>>()
            val seen = mutableSetOf<String>()
            for (item in arr) {
                val map = item as? Map<*, *> ?: continue
                val name = (map["name"] as? String)?.trim() ?: continue
                if (name.isBlank() || !seen.add(name.lowercase())) continue
                @Suppress("UNCHECKED_CAST")
                val traits = (map["traits"] as? List<*>)?.mapNotNull { it as? String }?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                result.add(name to traits)
            }
            result
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse characters and traits", e)
            emptyList()
        }
    }

    private fun parseCharacterNamesFromResponse(response: String): List<String> {
        return try {
            val json = extractJsonFromResponse(response)
            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val raw = (obj["names"] as? List<*>)?.mapNotNull { it as? String }?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            raw.distinctBy { it.lowercase() }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse character names", e)
            emptyList()
        }
    }

    private fun parseTraitsFromResponse(response: String): List<String> {
        return try {
            val json = extractJsonFromResponse(response)
            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            (obj["traits"] as? List<*>)?.mapNotNull { it as? String }?.filter { it.isNotBlank() } ?: emptyList()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse traits", e)
            emptyList()
        }
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

    private fun parseKeyMomentsFromResponse(response: String): List<Map<String, String>> {
        val jsonStart = response.indexOf("{")
        val jsonEnd = response.lastIndexOf("}") + 1
        if (jsonStart < 0 || jsonEnd <= jsonStart) return emptyList()
        return try {
            val json = response.substring(jsonStart, jsonEnd)
            val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
            val arr = obj.getAsJsonArray("moments") ?: return emptyList()
            arr.mapNotNull { el ->
                val o = el.asJsonObject ?: return@mapNotNull null
                mapOf(
                    "chapter" to (o.get("chapter")?.asString ?: ""),
                    "moment" to (o.get("moment")?.asString ?: ""),
                    "significance" to (o.get("significance")?.asString ?: "")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseRelationshipsFromResponse(response: String): List<Map<String, String>> {
        val jsonStart = response.indexOf("{")
        val jsonEnd = response.lastIndexOf("}") + 1
        if (jsonStart < 0 || jsonEnd <= jsonStart) return emptyList()
        return try {
            val json = response.substring(jsonStart, jsonEnd)
            val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
            val arr = obj.getAsJsonArray("relationships") ?: return emptyList()
            arr.mapNotNull { el ->
                val o = el.asJsonObject ?: return@mapNotNull null
                mapOf(
                    "character" to (o.get("character")?.asString ?: ""),
                    "relationship" to (o.get("relationship")?.asString ?: ""),
                    "nature" to (o.get("nature")?.asString ?: "")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ==================== Raw Text Generation ====================

    /**
     * INS-002: Generate raw text response from LLM for custom prompts.
     * Used for foreshadowing detection and other analysis tasks that need
     * custom prompt formatting.
     *
     * @param prompt The full prompt including system/user/assistant markers
     * @return Raw text response from the model
     */
    suspend fun generateRaw(prompt: String): String = withContext(Dispatchers.IO) {
        if (!modelLoaded) {
            AppLogger.w(TAG, "generateRaw: model not loaded")
            return@withContext ""
        }
        try {
            // Use JSON mode false for raw text generation
            generateResponse(prompt, 1024, 0.2f, jsonMode = false)
        } catch (e: Exception) {
            AppLogger.e(TAG, "generateRaw error", e)
            ""
        }
    }
}
