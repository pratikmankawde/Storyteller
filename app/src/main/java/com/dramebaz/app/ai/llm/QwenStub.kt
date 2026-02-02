package com.dramebaz.app.ai.llm

import android.content.Context
import android.os.Build
import com.dramebaz.app.data.models.ChapterSummary
import com.dramebaz.app.data.models.Dialog
import com.dramebaz.app.data.models.SoundCueModel
import com.dramebaz.app.utils.AppLogger
import com.dramebaz.app.utils.DegradedModeManager
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * LLM entry point. Tries llama.cpp (Qwen3-1.7B-Q4_K_M GGUF in Downloads folder) on ARM;
 * otherwise uses stub.
 *
 * Timeout notes:
 * - Q4_K_M model: ~17s per pass on PC benchmark (87s for 5 passes) - 90s timeout is sufficient
 * - Q8_0 model: ~32s per pass on PC benchmark (163s for 5 passes) - would need 180s timeout
 * - Android may be slower; 180s timeout provides safety margin for either model
 * - If Android hangs despite timeout, the issue is likely in native code (Vulkan/GPU or memory)
 */
object QwenStub {
    // 180 second timeout for LLM calls (3 minutes)
    // Increased from 60s to accommodate:
    // 1. Slower Android inference compared to PC
    // 2. Q8_0 model if user has it (slower but still works)
    // 3. Safety margin for complex prompts with large context
    private const val LLM_TIMEOUT_MS = 180000L
    private var llamaModel: Qwen3Model? = null
    @Volatile private var modelInitialized = false
    @Volatile private var initializationInProgress = false
    private val initLock = Any()
    @Volatile private var appContext: Context? = null

    private const val USE_NATIVE_LLM = true

    /** Set once from Application.onCreate so LLM can initialize lazily on first use. */
    fun setApplicationContext(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Ensure LLM is initialized (lazy init on first use). Call before using analyzeChapter etc.
     * On some devices llama.cpp native lib fails to load; init still completes using stub.
     */
    suspend fun ensureInitialized(): Boolean {
        val ctx = appContext
        if (ctx == null) {
            AppLogger.w("QwenStub", "No app context; LLM will use stub")
            return true
        }
        return initialize(ctx)
    }

    /**
     * Initialize LLM. Tries llama.cpp GGUF model (Downloads/Qwen3-1.7B-Q4_K_M.gguf) on ARM only; else stub.
     * Thread-safe: Uses synchronization to prevent concurrent initialization.
     */
    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        // Fast path: already initialized
        if (modelInitialized) {
            AppLogger.d("QwenStub", "Model already initialized")
            return@withContext true
        }

        // Thread-safe initialization with double-checked locking
        synchronized(initLock) {
            // Double-check after acquiring lock
            if (modelInitialized) {
                AppLogger.d("QwenStub", "Model initialized by another thread")
                return@withContext true
            }

            // Prevent re-entrant initialization
            if (initializationInProgress) {
                AppLogger.w("QwenStub", "Initialization already in progress, waiting...")
                // Wait for initialization to complete (max 30 seconds)
                var waitMs = 0
                while (initializationInProgress && waitMs < 30000) {
                    try { (initLock as Object).wait(100) } catch (_: Exception) {}
                    waitMs += 100
                }
                return@withContext modelInitialized
            }

            initializationInProgress = true
            AppLogger.i("QwenStub", "Starting LLM initialization...")
        }

        try {
            if (!USE_NATIVE_LLM) {
                AppLogger.i("QwenStub", "Native LLM disabled, using stub")
                synchronized(initLock) {
                    modelInitialized = true
                    initializationInProgress = false
                    (initLock as Object).notifyAll()
                }
                return@withContext true
            }

            // llama.cpp builds for ARM only. Only try on ARM devices.
            val primaryAbi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Build.SUPPORTED_ABIS.firstOrNull() ?: ""
            } else {
                @Suppress("DEPRECATION")
                Build.CPU_ABI
            }
            val tryLlama = primaryAbi == "arm64-v8a" || primaryAbi == "armeabi-v7a"
            if (!tryLlama) {
                AppLogger.i("QwenStub", "Skipping llama.cpp on ABI $primaryAbi (emulator/x86); using stub")
            }
            AppLogger.i("QwenStub", "Initializing LLM (Qwen3 GGUF from Downloads/Qwen3-1.7B-Q4_K_M.gguf on ARM, else stub)...")
            val llamaLoaded = if (tryLlama) {
                llamaModel = Qwen3Model(context)
                llamaModel?.loadModel() ?: false
            } else {
                llamaModel = null
                false
            }
            if (llamaLoaded) {
                AppLogger.i("QwenStub", "Qwen3-1.7B-Q4_K_M GGUF loaded successfully via llama.cpp")
                // Report full mode to DegradedModeManager (using ONNX_FULL for compatibility)
                DegradedModeManager.setLlmMode(DegradedModeManager.LlmMode.ONNX_FULL)
            } else {
                llamaModel = null
                AppLogger.w("QwenStub", "GGUF model not found or skipped, using stub")
                // Report degraded mode to DegradedModeManager
                DegradedModeManager.setLlmMode(
                    DegradedModeManager.LlmMode.STUB_FALLBACK,
                    "GGUF model not found or not supported on this device"
                )
            }
            synchronized(initLock) {
                modelInitialized = true
                initializationInProgress = false
                (initLock as Object).notifyAll()
            }
            return@withContext true
        } catch (e: Exception) {
            AppLogger.e("QwenStub", "Error initializing LLM", e)
            llamaModel = null
            // Report degraded mode with error reason
            DegradedModeManager.setLlmMode(
                DegradedModeManager.LlmMode.STUB_FALLBACK,
                e.message ?: "Unknown error loading LLM"
            )
            synchronized(initLock) {
                modelInitialized = true
                initializationInProgress = false
                (initLock as Object).notifyAll()
            }
            return@withContext true
        }
    }

    fun release() {
        llamaModel?.release()
        llamaModel = null
        modelInitialized = false
        DegradedModeManager.setLlmMode(DegradedModeManager.LlmMode.NOT_INITIALIZED)
    }

    private fun hasModel(): Boolean = llamaModel?.isModelLoaded() == true

    /**
     * Check if the model is ready for analysis.
     * Returns true if initialization was attempted (even if using stub fallback).
     */
    fun isReady(): Boolean = modelInitialized

    /**
     * Check if running in full LLM mode (not stub fallback).
     */
    fun isUsingLlama(): Boolean = hasModel()

    /** Alias for backward compatibility */
    fun isUsingOnnx(): Boolean = hasModel()

    /**
     * Returns the execution provider: "GPU (Vulkan)" or "CPU" or "unknown"
     */
    fun getExecutionProvider(): String {
        return llamaModel?.getExecutionProvider() ?: "unknown"
    }

    /**
     * Returns true if using GPU (Vulkan) for inference
     */
    fun isUsingGpu(): Boolean {
        return llamaModel?.isUsingGpu() ?: false
    }

    /**
     * Retry loading the llama.cpp model. Call this if user wants to retry after failure.
     * Returns true if GGUF model loaded successfully.
     */
    suspend fun retryLoadModel(context: Context): Boolean = withContext(Dispatchers.IO) {
        AppLogger.i("QwenStub", "Retrying llama.cpp model load...")
        release() // Clear any previous state
        val success = initialize(context)
        if (hasModel()) {
            AppLogger.i("QwenStub", "llama.cpp model retry successful")
            true
        } else {
            AppLogger.w("QwenStub", "llama.cpp model retry failed, still using stub")
            false
        }
    }

    private val gson = Gson()

    suspend fun analyzeChapter(chapterText: String): ChapterAnalysisResponse = withContext(Dispatchers.IO) {
        ensureInitialized()
        val result = try {
            withTimeout(LLM_TIMEOUT_MS) {
                llamaModel?.analyzeChapter(chapterText)
            }
        } catch (e: TimeoutCancellationException) {
            AppLogger.w("QwenStub", "LLM analysis timed out after ${LLM_TIMEOUT_MS}ms, using fallback")
            null
        } catch (e: Exception) {
            AppLogger.e("QwenStub", "Error in model analysis, using fallback", e)
            null
        }
        result ?: stubAnalyzeChapter(chapterText)
    }

    /**
     * @deprecated Use FourPassCharacterAnalysisUseCase.analyzeChapter() for character analysis
     * and extendedAnalysisJson() separately for themes/symbols/vocabulary.
     * This method is no longer used in the main workflow.
     *
     * Single-pass full analysis: chapter summary, characters (with traits + voice_profile),
     * dialogs, sound_cues, and extended (themes, symbols, vocabulary) in one LLM call.
     * Returns (ChapterAnalysisResponse, extendedJson). Falls back to two calls if full fails.
     */
    @Deprecated("Use FourPassCharacterAnalysisUseCase for character analysis", ReplaceWith("FourPassCharacterAnalysisUseCase.analyzeChapter()"))
    suspend fun analyzeChapterFull(chapterText: String): Pair<ChapterAnalysisResponse, String> = withContext(Dispatchers.IO) {
        ensureInitialized()
        val full = try {
            withTimeout(LLM_TIMEOUT_MS) {
                llamaModel?.analyzeChapterFull(chapterText)
            }
        } catch (e: TimeoutCancellationException) {
            AppLogger.w("QwenStub", "LLM full analysis timed out, using two-pass fallback")
            null
        } catch (e: Exception) {
            AppLogger.e("QwenStub", "Full analysis failed, using two-pass fallback", e)
            null
        }
        if (full != null && full.first != null) {
            Pair(full.first!!, full.second ?: "{}")
        } else {
            val resp = analyzeChapter(chapterText)
            val extended = extendedAnalysisJson(chapterText)
            Pair(resp, extended.ifEmpty { "{}" })
        }
    }

    /** Stub-only analysis without loading ONNX (safe when LLM native lib would crash). */
    fun analyzeChapterStubOnly(chapterText: String): ChapterAnalysisResponse = stubAnalyzeChapter(chapterText)

    /** Stub-only extended analysis without loading ONNX. */
    fun extendedAnalysisJsonStubOnly(chapterText: String): String = stubExtendedAnalysisJson(chapterText)

    suspend fun extendedAnalysisJson(chapterText: String): String = withContext(Dispatchers.IO) {
        ensureInitialized()
        val result = try {
            withTimeout(LLM_TIMEOUT_MS) {
                llamaModel?.extendedAnalysisJson(chapterText)
            }
        } catch (e: TimeoutCancellationException) {
            AppLogger.w("QwenStub", "Extended analysis timed out after ${LLM_TIMEOUT_MS}ms, using fallback")
            null
        } catch (e: Exception) {
            AppLogger.e("QwenStub", "Error in extended analysis, using fallback", e)
            null
        }
        result ?: stubExtendedAnalysisJson(chapterText)
    }

    suspend fun extractCharactersAndTraitsInSegment(
        segmentText: String,
        skipNamesWithTraits: Collection<String>,
        namesNeedingTraits: Collection<String>
    ): List<Pair<String, List<String>>> = withContext(Dispatchers.IO) {
        AppLogger.d("QwenStub", "extractCharactersAndTraitsInSegment: starting (${segmentText.length} chars)")
        ensureInitialized()
        AppLogger.d("QwenStub", "extractCharactersAndTraitsInSegment: LLM initialized, hasModel=${hasModel()}")
        try {
            val startMs = System.currentTimeMillis()
            val result = withTimeout(LLM_TIMEOUT_MS) {
                AppLogger.d("QwenStub", "extractCharactersAndTraitsInSegment: calling LLM...")
                llamaModel?.extractCharactersAndTraitsInSegment(segmentText, skipNamesWithTraits, namesNeedingTraits)
            }
            val elapsed = System.currentTimeMillis() - startMs
            if (result != null) {
                AppLogger.d("QwenStub", "extractCharactersAndTraitsInSegment: LLM returned ${result.size} chars in ${elapsed}ms")
                result
            } else {
                AppLogger.d("QwenStub", "extractCharactersAndTraitsInSegment: LLM returned null, using stub")
                stubExtractCharactersAndTraitsInSegment(segmentText, skipNamesWithTraits, namesNeedingTraits)
            }
        } catch (e: TimeoutCancellationException) {
            AppLogger.w("QwenStub", "extractCharactersAndTraitsInSegment timed out after ${LLM_TIMEOUT_MS}ms, using fallback")
            stubExtractCharactersAndTraitsInSegment(segmentText, skipNamesWithTraits, namesNeedingTraits)
        } catch (e: Exception) {
            AppLogger.e("QwenStub", "extractCharactersAndTraitsInSegment failed: ${e.message}", e)
            stubExtractCharactersAndTraitsInSegment(segmentText, skipNamesWithTraits, namesNeedingTraits)
        }
    }

    suspend fun detectCharactersOnPage(pageText: String): List<String> = withContext(Dispatchers.IO) {
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                llamaModel?.detectCharactersOnPage(pageText)
            } ?: stubDetectCharactersOnPage(pageText)
        } catch (e: TimeoutCancellationException) {
            AppLogger.w("QwenStub", "detectCharactersOnPage timed out, using fallback")
            stubDetectCharactersOnPage(pageText)
        }
    }

    suspend fun inferTraitsForCharacter(characterName: String, excerpt: String): List<String> = withContext(Dispatchers.IO) {
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                llamaModel?.inferTraitsForCharacter(characterName, excerpt)
            } ?: stubInferTraits(characterName)
        } catch (e: TimeoutCancellationException) {
            AppLogger.w("QwenStub", "inferTraitsForCharacter timed out, using fallback")
            stubInferTraits(characterName)
        }
    }

    /** Suggest voice_profile JSON for each character (TTS-ready). Returns JSON string. */
    suspend fun suggestVoiceProfilesJson(charactersWithTraitsJson: String): String? = withContext(Dispatchers.IO) {
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                llamaModel?.suggestVoiceProfilesJson(charactersWithTraitsJson)
            } ?: stubSuggestVoiceProfiles(charactersWithTraitsJson)
        } catch (e: TimeoutCancellationException) {
            AppLogger.w("QwenStub", "suggestVoiceProfilesJson timed out, using fallback")
            stubSuggestVoiceProfiles(charactersWithTraitsJson)
        }
    }

    // ==================== Multi-Pass Character Analysis API (Pass 1-4) ====================

    /**
     * Pass-1: Extract ONLY character names from the chapter text.
     * Returns a list of names exactly as they appear in the text.
     */
    suspend fun pass1ExtractCharacterNames(chapterText: String): List<String> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                llamaModel?.extractCharacterNames(chapterText)
            } ?: stubDetectCharactersOnPage(chapterText)
        } catch (e: TimeoutCancellationException) {
            AppLogger.w("QwenStub", "Pass-1: extractCharacterNames timed out, using fallback")
            stubDetectCharactersOnPage(chapterText)
        } catch (e: Exception) {
            AppLogger.e("QwenStub", "Pass-1: extractCharacterNames failed", e)
            stubDetectCharactersOnPage(chapterText)
        }
    }

    /**
     * Pass-2: Extract explicit traits for a specific character.
     * Call this in parallel for each character from Pass-1.
     */
    suspend fun pass2ExtractTraitsForCharacter(characterName: String, chapterText: String): List<String> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                llamaModel?.extractTraitsForCharacter(characterName, chapterText)
            } ?: stubPass2ExtractTraits(characterName)
        } catch (e: TimeoutCancellationException) {
            AppLogger.w("QwenStub", "Pass-2: extractTraitsForCharacter($characterName) timed out, using fallback")
            stubPass2ExtractTraits(characterName)
        } catch (e: Exception) {
            AppLogger.e("QwenStub", "Pass-2: extractTraitsForCharacter($characterName) failed", e)
            stubPass2ExtractTraits(characterName)
        }
    }

    /**
     * Pass-3: Infer personality from traits extracted in Pass-2.
     * Call this in parallel for each character.
     */
    suspend fun pass3InferPersonalityFromTraits(characterName: String, traits: List<String>): List<String> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                llamaModel?.inferPersonalityFromTraits(characterName, traits)
            } ?: stubPass3InferPersonality(traits)
        } catch (e: TimeoutCancellationException) {
            AppLogger.w("QwenStub", "Pass-3: inferPersonalityFromTraits($characterName) timed out, using fallback")
            stubPass3InferPersonality(traits)
        } catch (e: Exception) {
            AppLogger.e("QwenStub", "Pass-3: inferPersonalityFromTraits($characterName) failed", e)
            stubPass3InferPersonality(traits)
        }
    }

    /**
     * Pass-3: Extract traits AND suggest voice profile for 1-4 characters.
     * Uses aggregated context from all pages where each character appears.
     * Batches up to 4 characters per LLM call for efficiency.
     *
     * @param char1Name First character name
     * @param char1Context Aggregated text from all pages where char1 appears
     * @param char2Name Optional second character name
     * @param char2Context Optional aggregated text for second character
     * @param char3Name Optional third character name
     * @param char3Context Optional aggregated text for third character
     * @param char4Name Optional fourth character name
     * @param char4Context Optional aggregated text for fourth character
     * @return List of Pair(characterName, Pair(traits, voiceProfile)) for each processed character
     */
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
        ensureInitialized()
        val charNames = listOfNotNull(char1Name, char2Name, char3Name, char4Name).joinToString(", ")
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                llamaModel?.pass3ExtractTraitsAndVoiceProfile(
                    char1Name, char1Context,
                    char2Name, char2Context,
                    char3Name, char3Context,
                    char4Name, char4Context
                )
            } ?: stubPass3(char1Name, char2Name, char3Name, char4Name)
        } catch (e: TimeoutCancellationException) {
            AppLogger.w("QwenStub", "Pass-3: timed out for $charNames, using fallback")
            stubPass3(char1Name, char2Name, char3Name, char4Name)
        } catch (e: Exception) {
            AppLogger.e("QwenStub", "Pass-3: failed for $charNames", e)
            stubPass3(char1Name, char2Name, char3Name, char4Name)
        }
    }

    /**
     * Pass-2: Extract dialogs and narrator text from a page.
     * Extracts quoted speech and attributes to the correct speaker.
     * @param pageText The text of the current page
     * @param characterNames List of character names found on this page (1-2 characters per call)
     * @return List of extracted dialogs with speaker, text, emotion, and intensity
     */
    suspend fun pass2ExtractDialogs(pageText: String, characterNames: List<String>): List<Qwen3Model.ExtractedDialogEntry> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                llamaModel?.extractDialogsFromPage(pageText, characterNames)
            } ?: stubPass2ExtractDialogs(pageText, characterNames)
        } catch (e: TimeoutCancellationException) {
            AppLogger.w("QwenStub", "Pass-2: extractDialogs timed out, using fallback")
            stubPass2ExtractDialogs(pageText, characterNames)
        } catch (e: Exception) {
            AppLogger.e("QwenStub", "Pass-2: extractDialogs failed", e)
            stubPass2ExtractDialogs(pageText, characterNames)
        }
    }

    // Stub fallbacks for multi-pass analysis
    // Note: Stubs include gender hints so SpeakerMatcher can assign speakers even when LLM is unavailable
    /**
     * Infer traits from a character's name using heuristics.
     * Used as fallback when LLM extraction returns no traits.
     * Public so it can be called from FourPassCharacterAnalysisUseCase.
     */
    fun inferTraitsFromName(characterName: String): List<String> {
        val lowerName = characterName.lowercase()
        val traits = mutableListOf<String>()

        // Gender hints from common names/titles
        val femaleIndicators = listOf("mrs", "ms", "miss", "lady", "queen", "princess", "duchess",
            "mary", "jane", "elizabeth", "anna", "sarah", "emma", "sophia", "olivia", "isabella",
            "alice", "luna", "stella", "nova", "aurora", "rose", "lily", "grace", "ella", "mia",
            "emily", "chloe", "madison", "hannah", "abigail", "natalie", "victoria", "jessica",
            "ashley", "samantha", "katherine", "catherine", "hermione", "ginny", "molly", "petunia")
        val maleIndicators = listOf("mr", "sir", "lord", "king", "prince", "duke", "captain", "general",
            "james", "john", "william", "robert", "david", "michael", "thomas", "charles",
            "jack", "leo", "max", "alex", "sam", "ben", "tom", "adam", "dan", "joe",
            "harry", "ron", "dumbledore", "snape", "draco", "sirius", "remus", "vernon",
            "george", "fred", "arthur", "percy", "neville", "oliver", "marcus")

        // Check for gender indicators
        val isFemale = femaleIndicators.any { indicator ->
            lowerName.startsWith(indicator) ||
            lowerName.endsWith(indicator) ||
            lowerName.contains(" $indicator") ||
            lowerName.contains("$indicator ")
        }
        val isMale = maleIndicators.any { indicator ->
            lowerName.startsWith(indicator) ||
            lowerName.endsWith(indicator) ||
            lowerName.contains(" $indicator") ||
            lowerName.contains("$indicator ")
        }

        when {
            isFemale -> traits.add("female")
            isMale -> traits.add("male")
            else -> traits.add("adult") // Default to adult for age matching
        }

        // Age hints from titles
        when {
            lowerName.contains("young") || lowerName.contains("boy") || lowerName.contains("girl") -> traits.add("young")
            lowerName.contains("old") || lowerName.contains("elder") || lowerName.contains("grandmother") ||
            lowerName.contains("grandfather") -> traits.add("elderly")
        }

        // Additional traits based on name patterns
        if (lowerName.contains("captain") || lowerName.contains("commander") || lowerName.contains("general")) {
            traits.add("authoritative")
            traits.add("commanding")
        }
        if (lowerName.contains("dr") || lowerName.contains("professor") || lowerName.contains("doctor")) {
            traits.add("educated")
            traits.add("middle-aged")
        }
        if (lowerName.contains("aunt") || lowerName.contains("uncle")) {
            traits.add("middle-aged")
            traits.add("familial")
        }
        if (lowerName.contains("narrator")) {
            traits.add("neutral")
            traits.add("narrative_voice")
        }

        traits.add("story_character")
        return traits
    }

    private fun stubPass2ExtractTraits(characterName: String): List<String> = inferTraitsFromName(characterName)

    private fun stubPass3InferPersonality(traits: List<String>): List<String> {
        if (traits.isEmpty()) return listOf("neutral_personality", "adult")

        val personality = mutableListOf<String>()

        // Carry forward gender/age traits for speaker matching
        if (traits.any { it.contains("female") || it.contains("woman") || it.contains("lady") }) {
            personality.add("female")
        }
        if (traits.any { it.contains("male") || it.contains("man") }) {
            personality.add("male")
        }
        if (traits.any { it.contains("young") || it.contains("child") || it.contains("teen") }) {
            personality.add("young")
        }
        if (traits.any { it.contains("old") || it.contains("elder") || it.contains("senior") }) {
            personality.add("older")
        }
        if (traits.any { it.contains("authoritative") || it.contains("commanding") }) {
            personality.add("authoritative")
        }

        // Add default personality if nothing matched
        if (personality.isEmpty()) {
            personality.add("adult")
            personality.add("neutral_personality")
        }

        return personality
    }

    /**
     * Pass-3 Stub: Infer traits from name and create default voice profile for 1-4 characters.
     */
    private fun stubPass3(
        char1Name: String,
        char2Name: String? = null,
        char3Name: String? = null,
        char4Name: String? = null
    ): List<Pair<String, Pair<List<String>, Map<String, Any>>>> {
        val results = mutableListOf<Pair<String, Pair<List<String>, Map<String, Any>>>>()
        results.add(Pair(char1Name, stubSingleCharacterTraitsAndProfile(char1Name)))
        char2Name?.let { results.add(Pair(it, stubSingleCharacterTraitsAndProfile(it))) }
        char3Name?.let { results.add(Pair(it, stubSingleCharacterTraitsAndProfile(it))) }
        char4Name?.let { results.add(Pair(it, stubSingleCharacterTraitsAndProfile(it))) }
        return results
    }

    /**
     * Generate traits and voice profile for a single character using name-based heuristics.
     */
    private fun stubSingleCharacterTraitsAndProfile(characterName: String): Pair<List<String>, Map<String, Any>> {
        val traits = inferTraitsFromName(characterName)

        // Determine voice profile based on inferred traits
        val isMale = traits.any { it.contains("male") && !it.contains("female") }
        val isFemale = traits.any { it.contains("female") }
        val isElderly = traits.any { it.contains("elderly") || it.contains("old") }
        val isYoung = traits.any { it.contains("young") || it.contains("child") }

        val gender = when {
            isFemale -> "female"
            isMale -> "male"
            else -> "neutral"
        }

        val age = when {
            isYoung -> "young"
            isElderly -> "elderly"
            else -> "middle-aged"
        }

        // Assign speaker_id based on gender and age (VCTK corpus 0-108)
        val speakerId = when {
            gender == "male" && age == "young" -> (0..20).random()
            gender == "male" && age == "middle-aged" -> (21..45).random()
            gender == "male" && age == "elderly" -> (46..55).random()
            gender == "female" && age == "young" -> (56..75).random()
            gender == "female" && age == "middle-aged" -> (76..95).random()
            gender == "female" && age == "elderly" -> (96..108).random()
            else -> 45 // Default neutral
        }

        val voiceProfile = mapOf(
            "pitch" to 1.0,
            "speed" to 1.0,
            "energy" to 0.7,
            "gender" to gender,
            "age" to age,
            "tone" to "neutral",
            "accent" to "neutral",
            "speaker_id" to speakerId
        )

        return Pair(traits, voiceProfile)
    }

    /**
     * Regex-based fallback for Pass-2 dialog extraction.
     * Uses pattern matching to find quoted text and attribute to nearest character.
     */
    private fun stubPass2ExtractDialogs(pageText: String, characterNames: List<String>): List<Qwen3Model.ExtractedDialogEntry> {
        val dialogs = mutableListOf<Qwen3Model.ExtractedDialogEntry>()

        // Pattern to find quoted text (double or single quotes)
        val quotePattern = Regex(""""([^"]+)"|'([^']+)'""")
        val matches = quotePattern.findAll(pageText)

        // Attribution patterns to look for speaker identification
        val attributionPatterns = listOf(
            Regex("""(\w+)\s+said""", RegexOption.IGNORE_CASE),
            Regex("""said\s+(\w+)""", RegexOption.IGNORE_CASE),
            Regex("""(\w+)\s+asked""", RegexOption.IGNORE_CASE),
            Regex("""asked\s+(\w+)""", RegexOption.IGNORE_CASE),
            Regex("""(\w+)\s+replied""", RegexOption.IGNORE_CASE),
            Regex("""replied\s+(\w+)""", RegexOption.IGNORE_CASE),
            Regex("""(\w+)\s+whispered""", RegexOption.IGNORE_CASE),
            Regex("""(\w+)\s+shouted""", RegexOption.IGNORE_CASE),
            Regex("""(\w+)\s+muttered""", RegexOption.IGNORE_CASE),
            Regex("""(\w+)\s+exclaimed""", RegexOption.IGNORE_CASE),
            Regex("""(\w+):""", RegexOption.IGNORE_CASE)
        )

        var lastNarratorEnd = 0

        for (match in matches) {
            val quoteStart = match.range.first
            val quoteEnd = match.range.last
            val quoteText = match.groupValues[1].ifEmpty { match.groupValues[2] }

            if (quoteText.isBlank()) continue

            // Extract narrator text before this quote (if any significant text)
            if (quoteStart > lastNarratorEnd + 10) {
                val narratorText = pageText.substring(lastNarratorEnd, quoteStart).trim()
                    .replace(Regex("\\s+"), " ")
                if (narratorText.length > 20) {
                    dialogs.add(Qwen3Model.ExtractedDialogEntry(
                        speaker = "Narrator",
                        text = narratorText.take(500), // Limit narrator text length
                        emotion = "neutral",
                        intensity = 0.3f
                    ))
                }
            }
            lastNarratorEnd = quoteEnd + 1

            // Find speaker using proximity search (200 chars before/after quote)
            val searchStart = maxOf(0, quoteStart - 200)
            val searchEnd = minOf(pageText.length, quoteEnd + 200)
            val contextBefore = pageText.substring(searchStart, quoteStart)
            val contextAfter = pageText.substring(quoteEnd + 1, searchEnd)

            var speaker = "Unknown"

            // First try attribution patterns in context
            for (pattern in attributionPatterns) {
                val beforeMatch = pattern.find(contextBefore)
                val afterMatch = pattern.find(contextAfter)

                val matchedName = beforeMatch?.groupValues?.getOrNull(1)
                    ?: afterMatch?.groupValues?.getOrNull(1)

                if (matchedName != null) {
                    // Check if matched name is one of the known characters
                    val foundCharacter = characterNames.find {
                        it.contains(matchedName, ignoreCase = true) ||
                        matchedName.contains(it.split(" ").firstOrNull() ?: "", ignoreCase = true)
                    }
                    if (foundCharacter != null) {
                        speaker = foundCharacter
                        break
                    }
                }
            }

            // If no attribution found, look for nearest character name
            if (speaker == "Unknown") {
                var nearestDistance = Int.MAX_VALUE
                for (charName in characterNames) {
                    val nameParts = charName.split(" ")
                    for (part in nameParts) {
                        if (part.length < 2) continue
                        val beforeIdx = contextBefore.lastIndexOf(part, ignoreCase = true)
                        val afterIdx = contextAfter.indexOf(part, ignoreCase = true)

                        if (beforeIdx >= 0) {
                            val distance = contextBefore.length - beforeIdx
                            if (distance < nearestDistance) {
                                nearestDistance = distance
                                speaker = charName
                            }
                        }
                        if (afterIdx >= 0 && afterIdx < nearestDistance) {
                            nearestDistance = afterIdx
                            speaker = charName
                        }
                    }
                }
            }

            dialogs.add(Qwen3Model.ExtractedDialogEntry(
                speaker = speaker,
                text = quoteText,
                emotion = "neutral",
                intensity = 0.5f
            ))
        }

        // Add any remaining narrator text after last quote
        if (lastNarratorEnd < pageText.length - 20) {
            val narratorText = pageText.substring(lastNarratorEnd).trim()
                .replace(Regex("\\s+"), " ")
            if (narratorText.length > 20) {
                dialogs.add(Qwen3Model.ExtractedDialogEntry(
                    speaker = "Narrator",
                    text = narratorText.take(500),
                    emotion = "neutral",
                    intensity = 0.3f
                ))
            }
        }

        return dialogs
    }

    // ==================== End Multi-Pass Character Analysis API ====================

    private fun stubDetectCharactersOnPage(pageText: String): List<String> {
        // Common words to exclude from character detection
        val excludeWords = setOf(
            "The", "This", "That", "There", "These", "Those", "Chapter", "Part", "Book",
            "Page", "Section", "Introduction", "Prologue", "Epilogue", "Contents",
            "And", "But", "For", "Not", "You", "All", "Can", "Her", "Was", "One",
            "Our", "Out", "Day", "Had", "Has", "His", "How", "Its", "May", "New",
            "Now", "Old", "See", "Way", "Who", "Boy", "Did", "Get", "Let", "Put",
            "Say", "She", "Too", "Use", "Yes", "Yet", "Here", "Just", "Know", "Like",
            "Made", "Make", "More", "Much", "Must", "Only", "Over", "Such", "Take",
            "Than", "Them", "Then", "Very", "When", "Well", "What", "With", "About",
            "After", "Again", "Could", "Every", "First", "Found", "Great", "House",
            "Little", "Never", "Other", "Place", "Right", "Small", "Sound", "Still",
            "World", "Would", "Write", "Years", "Being", "Where", "While", "Before"
        )

        // Pattern 1: Find capitalized words (potential names)
        val words = pageText.split(Regex("[\\s,.!?;:\"'()\\[\\]]+")).filter { word ->
            word.length > 2 &&
            word[0].isUpperCase() &&
            word.substring(1).all { c -> c.isLowerCase() } &&
            word !in excludeWords
        }

        // Pattern 2: Find words following speech verbs (said, asked, replied, etc.)
        val speechPattern = Regex("(?:said|asked|replied|answered|exclaimed|whispered|shouted|cried|muttered|spoke)\\s+([A-Z][a-z]+)", RegexOption.IGNORE_CASE)
        val speechNames = speechPattern.findAll(pageText).mapNotNull { it.groupValues.getOrNull(1) }.toList()

        // Pattern 3: Find names before "said" etc.
        val beforeSpeechPattern = Regex("([A-Z][a-z]+)\\s+(?:said|asked|replied|answered|exclaimed|whispered|shouted|cried|muttered|spoke)")
        val beforeSpeechNames = beforeSpeechPattern.findAll(pageText).mapNotNull { it.groupValues.getOrNull(1) }.toList()

        // Combine and filter
        val allNames = (words + speechNames + beforeSpeechNames)
            .filter { it !in excludeWords }
            .groupingBy { it }
            .eachCount()
            .filter { it.value >= 1 }
            .entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key }

        return allNames
    }

    private fun stubExtractCharactersAndTraitsInSegment(
        segmentText: String,
        skipNamesWithTraits: Collection<String>,
        namesNeedingTraits: Collection<String>
    ): List<Pair<String, List<String>>> {
        val skipSet = skipNamesWithTraits.map { it.lowercase() }.toSet()
        val names = stubDetectCharactersOnPage(segmentText).filter { it.lowercase() !in skipSet }.distinctBy { it.lowercase() }
        return names.map { it to stubInferTraits(it) }
    }

    private fun stubInferTraits(characterName: String): List<String> = listOf("story_character", "narrative")

    private fun stubSuggestVoiceProfiles(charactersWithTraitsJson: String): String? {
        return try {
            val arr = gson.fromJson(charactersWithTraitsJson, com.google.gson.JsonArray::class.java) ?: return null
            val list = arr.map { el ->
                val name = el.asJsonObject?.get("name")?.asString ?: "Unknown"
                mapOf("name" to name, "voice_profile" to mapOf("pitch" to 1.0, "speed" to 1.0, "energy" to 1.0, "emotion_bias" to emptyMap<String, Double>()))
            }
            gson.toJson(mapOf("characters" to list))
        } catch (_: Exception) { null }
    }

    private fun stubAnalyzeChapter(chapterText: String): ChapterAnalysisResponse {
        // Improved stub that extracts basic information from text
        val paragraphs = chapterText.split("\n\n", "\n").filter { it.isNotBlank() }
        val firstParagraph = paragraphs.firstOrNull() ?: ""

        // Use improved character detection
        val characterCandidates = stubDetectCharactersOnPage(chapterText)

        // Extract dialogs with proximity-based speaker detection
        val dialogPattern = Regex("\"([^\"]+)\"")
        val speechVerbPattern = Regex("([A-Z][a-z]+)\\s+(?:said|asked|replied|answered|exclaimed|whispered|shouted|cried|muttered|spoke|declared|announced|inquired|questioned|responded|screamed|yelled|begged|pleaded|demanded|suggested|warned|promised|admitted|denied|insisted|explained|continued|added|agreed|disagreed|nodded|sighed|laughed|smiled|frowned|groaned|moaned|gasped|sobbed)(?:[.,;:!?]|\\s)", RegexOption.IGNORE_CASE)
        val characterSet = characterCandidates.map { it.lowercase() }.toSet()

        var prevQuoteEnd = 0
        val dialogs = dialogPattern.findAll(chapterText).map { matchResult ->
            val dialogText = matchResult.groupValues[1]
            val quoteStart = matchResult.range.first

            // Look for speaker in the text between previous quote and current quote
            val contextBefore = chapterText.substring(prevQuoteEnd, quoteStart)
            prevQuoteEnd = matchResult.range.last + 1

            // First try: look for "Name said" pattern in context before quote
            var speaker: String? = null
            speechVerbPattern.findAll(contextBefore).lastOrNull()?.let { match ->
                val candidateName = match.groupValues.getOrNull(1)
                if (candidateName != null && candidateName.lowercase() in characterSet) {
                    speaker = candidateName
                }
            }

            // Second try: look for any character name in context (prefer last occurrence)
            if (speaker == null) {
                for (candidateName in characterCandidates) {
                    if (contextBefore.contains(candidateName)) {
                        speaker = candidateName
                    }
                }
            }

            // Fallback: use most recent speaker or first character
            Dialog(
                speaker = speaker ?: characterCandidates.firstOrNull() ?: "Unknown",
                dialog = dialogText,
                emotion = "neutral",
                intensity = 0.5f
            )
        }.toList()

        // Create emotional arc based on paragraph count
        val emotionalArc = when {
            paragraphs.size > 10 -> listOf(
                com.dramebaz.app.data.models.EmotionalSegment("start", "curiosity", 0.4f),
                com.dramebaz.app.data.models.EmotionalSegment("middle", "tension", 0.6f),
                com.dramebaz.app.data.models.EmotionalSegment("end", "resolution", 0.5f)
            )
            paragraphs.size > 5 -> listOf(
                com.dramebaz.app.data.models.EmotionalSegment("start", "curiosity", 0.4f),
                com.dramebaz.app.data.models.EmotionalSegment("end", "neutral", 0.5f)
            )
            else -> listOf(
                com.dramebaz.app.data.models.EmotionalSegment("start", "neutral", 0.5f)
            )
        }

        // Create character list
        val characters = (characterCandidates.take(3) + listOf("Narrator")).map { name ->
            com.dramebaz.app.ai.llm.CharacterStub(
                name = name,
                traits = listOf("story_character"),
                voiceProfile = mapOf(
                    "pitch" to 1.0,
                    "speed" to 1.0,
                    "energy" to 1.0
                )
            )
        }

        // Extract main events from paragraphs
        val mainEvents = paragraphs.take(3).mapIndexed { index, para ->
            "Event ${index + 1}: ${para.take(60)}..."
        }

        return ChapterAnalysisResponse(
            chapterSummary = ChapterSummary(
                title = "Chapter",
                shortSummary = firstParagraph.take(150) + if (firstParagraph.length > 150) "..." else "",
                mainEvents = mainEvents,
                emotionalArc = emotionalArc
            ),
            characters = characters,
            dialogs = dialogs,
            soundCues = emptyList()
        )
    }

    private fun stubExtendedAnalysisJson(chapterText: String): String {
        // Improved stub that extracts actual themes and vocabulary from text
        val words = chapterText.lowercase().split(Regex("\\s+")).filter {
            it.length > 4 && it.all { c -> c.isLetter() }
        }

        // Extract themes based on common words
        val themeKeywords = mapOf(
            "love" to "romance",
            "war" to "conflict",
            "death" to "mortality",
            "power" to "authority",
            "freedom" to "liberty",
            "betrayal" to "treachery",
            "journey" to "adventure",
            "home" to "belonging"
        )

        val detectedThemes = themeKeywords.entries
            .filter { (keyword, _) -> chapterText.lowercase().contains(keyword) }
            .map { it.value }
            .take(3)
            .ifEmpty { listOf("narrative", "character_development") }

        // Extract vocabulary (uncommon words)
        val commonWords = setOf("the", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "by", "from", "as", "is", "was", "are", "were", "been", "be", "have", "has", "had", "do", "does", "did", "will", "would", "could", "should", "may", "might", "must", "can", "this", "that", "these", "those", "a", "an")
        val vocabulary = words.filter { it !in commonWords }
            .groupingBy { it }
            .eachCount()
            .filter { it.value >= 2 }
            .entries
            .sortedByDescending { it.value }
            .take(5)
            .map { (word, count) ->
                mapOf(
                    "word" to word,
                    "definition" to "Appears $count times in the text",
                    "frequency" to count
                )
            }

        val obj = mapOf(
            "themes" to detectedThemes,
            "symbols" to listOf("narrative_symbol", "character_symbol"),
            "foreshadowing" to listOf("Text suggests future developments in: ${chapterText.take(100)}..."),
            "vocabulary" to vocabulary.ifEmpty {
                listOf(
                    mapOf("word" to "narrative", "definition" to "A spoken or written account of connected events")
                )
            }
        )
        return gson.toJson(obj)
    }

    fun toJson(response: ChapterAnalysisResponse): String = gson.toJson(response)

    fun fromJson(json: String): ChapterAnalysisResponse? = try {
        gson.fromJson(json, ChapterAnalysisResponse::class.java)
    } catch (e: Exception) {
        AppLogger.e("QwenStub", "Failed to parse ChapterAnalysisResponse", e)
        null
    }

    /**
     * T12.1: Generate a story based on user prompt.
     */
    suspend fun generateStory(userPrompt: String): String = withContext(Dispatchers.IO) {
        ensureInitialized()
        val result = try {
            withTimeout(LLM_TIMEOUT_MS) {
                llamaModel?.generateStory(userPrompt)
            }
        } catch (e: TimeoutCancellationException) {
            AppLogger.w("QwenStub", "Story generation timed out after ${LLM_TIMEOUT_MS}ms, using fallback")
            null
        } catch (e: Exception) {
            AppLogger.e("QwenStub", "Error generating story, using fallback", e)
            null
        }
        result ?: stubGenerateStory(userPrompt)
    }

    private fun stubGenerateStory(prompt: String): String {
        // Improved stub that generates a basic story structure based on the prompt
        val normalizedPrompt = prompt.lowercase()

        // Detect story type from prompt
        val storyType = when {
            normalizedPrompt.contains("adventure") || normalizedPrompt.contains("quest") -> "adventure"
            normalizedPrompt.contains("mystery") || normalizedPrompt.contains("detective") -> "mystery"
            normalizedPrompt.contains("romance") || normalizedPrompt.contains("love") -> "romance"
            normalizedPrompt.contains("horror") || normalizedPrompt.contains("scary") -> "horror"
            normalizedPrompt.contains("fantasy") || normalizedPrompt.contains("magic") -> "fantasy"
            normalizedPrompt.contains("sci-fi") || normalizedPrompt.contains("space") -> "science_fiction"
            else -> "general"
        }

        val storyTemplates = mapOf(
            "adventure" to """
                Chapter 1: The Call to Adventure

                The hero received an unexpected message that would change everything. "$prompt"

                With determination in their heart, they packed their belongings and set out on a journey that would test their courage and resolve. The path ahead was uncertain, but the goal was clear.

                Chapter 2: Trials and Tribulations

                Along the way, the hero encountered numerous challenges. Each obstacle seemed insurmountable, but with perseverance and the help of newfound allies, they overcame every trial. The journey was long, but every step brought them closer to their destination.

                Chapter 3: The Resolution

                In the end, the hero achieved their goal. The quest that began with "$prompt" had come to a satisfying conclusion. They returned home, forever changed by the experiences they had endured.

                The end.
            """,
            "mystery" to """
                Chapter 1: The Discovery

                It started with a simple observation: "$prompt"

                Something didn't add up. The pieces of the puzzle were scattered, and it would take careful investigation to put them together. The detective began their work, examining every clue with meticulous attention.

                Chapter 2: Unraveling the Mystery

                As the investigation deepened, more questions arose. Each answer led to new mysteries. The truth was hidden beneath layers of deception, but the detective was determined to uncover it.

                Chapter 3: The Truth Revealed

                Finally, all the pieces fell into place. The mystery that began with "$prompt" was solved. Justice was served, and the truth was revealed to all.

                The end.
            """,
            "general" to """
                Chapter 1: The Beginning

                "$prompt"

                This was how it all started. The protagonist found themselves at a crossroads, facing a decision that would shape their future. The world around them was full of possibilities, each path leading to a different outcome.

                Chapter 2: The Journey

                As events unfolded, the protagonist discovered new aspects of themselves and the world. They met people who would become important in their life, faced challenges that tested their character, and learned valuable lessons along the way.

                Chapter 3: The Conclusion

                The story that began with "$prompt" reached its natural conclusion. The protagonist had grown and changed, and the world was different because of their actions. Some questions were answered, while others remained open, leaving room for future adventures.

                The end.
            """
        )

        return storyTemplates[storyType] ?: storyTemplates["general"]!!
    }

    /**
     * AUG-010: Extract key moments for a character from chapter text.
     */
    suspend fun extractKeyMomentsForCharacter(characterName: String, chapterText: String, chapterTitle: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                llamaModel?.extractKeyMomentsForCharacter(characterName, chapterText, chapterTitle)
            } ?: stubExtractKeyMoments(characterName, chapterTitle)
        } catch (e: TimeoutCancellationException) {
            AppLogger.w("QwenStub", "extractKeyMomentsForCharacter timed out, using fallback")
            stubExtractKeyMoments(characterName, chapterTitle)
        }
    }

    private fun stubExtractKeyMoments(characterName: String, chapterTitle: String): List<Map<String, String>> {
        // Simple stub: return a generic moment
        return listOf(
            mapOf(
                "chapter" to chapterTitle,
                "moment" to "$characterName appears in this chapter",
                "significance" to "Character introduction or development"
            )
        )
    }

    /**
     * AUG-011: Extract relationships for a character.
     */
    suspend fun extractRelationshipsForCharacter(characterName: String, chapterText: String, allCharacterNames: List<String>): List<Map<String, String>> = withContext(Dispatchers.IO) {
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                llamaModel?.extractRelationshipsForCharacter(characterName, chapterText, allCharacterNames)
            } ?: stubExtractRelationships(characterName, allCharacterNames)
        } catch (e: TimeoutCancellationException) {
            AppLogger.w("QwenStub", "extractRelationshipsForCharacter timed out, using fallback")
            stubExtractRelationships(characterName, allCharacterNames)
        }
    }

    private fun stubExtractRelationships(characterName: String, allCharacterNames: List<String>): List<Map<String, String>> {
        // Simple stub: detect proximity-based relationships
        val relationships = mutableListOf<Map<String, String>>()
        for (otherName in allCharacterNames.take(5)) {
            if (!otherName.equals(characterName, ignoreCase = true)) {
                relationships.add(
                    mapOf(
                        "character" to otherName,
                        "relationship" to "other",
                        "nature" to "Appears together in the story"
                    )
                )
            }
        }
        return relationships
    }
}
