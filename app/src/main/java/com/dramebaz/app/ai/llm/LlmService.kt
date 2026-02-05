package com.dramebaz.app.ai.llm

import android.content.Context
import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.models.LlmModelFactory
import com.dramebaz.app.utils.AppLogger
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
 * - Uses LlmModelFactory to create the appropriate model (Qwen3 or Gemma 3n)
 * - Uses StubFallbacks for fallback when LLM is unavailable
 * - Uses Qwen3Model directly for 3-pass character analysis
 */
object LlmService {
    private const val TAG = "LlmService"
    private const val LLM_TIMEOUT_MS = 120_000L

    private var appContext: Context? = null
    private var llmModel: LlmModel? = null
    private var qwenModel: Qwen3Model? = null  // Direct reference for 3-pass workflow
    private val initMutex = Mutex()
    private var initialized = false

    /** Set once from Application.onCreate so LLM can initialize lazily on first use. */
    fun setApplicationContext(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Ensure LLM is initialized (lazy init on first use).
     */
    suspend fun ensureInitialized(): Boolean = initMutex.withLock {
        if (initialized) return@withLock llmModel?.isModelLoaded() == true

        val ctx = appContext ?: return@withLock false
        initialized = true

        return@withLock withContext(Dispatchers.IO) {
            try {
                // Try to create and load the default model
                qwenModel = Qwen3Model(ctx)
                val loaded = qwenModel?.loadModel() ?: false
                if (loaded) {
                    AppLogger.i(TAG, "✅ Qwen3Model loaded successfully")
                    llmModel = LlmModelFactory.createDefaultModel(ctx)
                } else {
                    AppLogger.w(TAG, "⚠️ Qwen3Model failed to load, trying factory default")
                    llmModel = LlmModelFactory.createDefaultModel(ctx)
                    llmModel?.loadModel()
                }
                llmModel?.isModelLoaded() == true
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
     * Release LLM resources.
     */
    fun release() {
        qwenModel?.release()
        qwenModel = null
        llmModel?.release()
        llmModel = null
        initialized = false
    }

    /**
     * Check if the model is ready for analysis.
     */
    fun isReady(): Boolean = qwenModel?.isModelLoaded() == true || llmModel?.isModelLoaded() == true

    /**
     * Check if running in full LLM mode (not stub fallback).
     */
    fun isUsingLlm(): Boolean = isReady()

    /** Alias for backward compatibility */
    fun isUsingLlama(): Boolean = qwenModel?.isModelLoaded() == true

    /**
     * Returns the execution provider: "GPU (Vulkan)" or "CPU" or "unknown"
     */
    fun getExecutionProvider(): String = qwenModel?.getExecutionProvider()
        ?: llmModel?.getExecutionProvider()
        ?: "unknown"

    /**
     * Returns true if using GPU for inference.
     */
    fun isUsingGpu(): Boolean = qwenModel?.isUsingGpu() == true || llmModel?.isUsingGpu() == true

    /**
     * Retry loading the model. Call this if user wants to retry after failure.
     */
    suspend fun retryLoadModel(context: Context): Boolean = initMutex.withLock {
        release()
        initialized = false
        return@withLock withContext(Dispatchers.IO) {
            qwenModel = Qwen3Model(context)
            val loaded = qwenModel?.loadModel() ?: false
            if (loaded) {
                llmModel = LlmModelFactory.createDefaultModel(context)
            }
            loaded
        }
    }
    
    // ==================== Analysis Methods ====================

    /**
     * Analyze chapter for summary, characters, dialogs, and sound cues.
     */
    suspend fun analyzeChapter(chapterText: String): ChapterAnalysisResponse = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                qwenModel?.analyzeChapter(chapterText)
            } ?: StubFallbacks.analyzeChapter(chapterText)
        } catch (e: TimeoutCancellationException) {
            AppLogger.w(TAG, "analyzeChapter timed out, using fallback")
            StubFallbacks.analyzeChapter(chapterText)
        } catch (e: Exception) {
            AppLogger.e(TAG, "analyzeChapter error, using fallback", e)
            StubFallbacks.analyzeChapter(chapterText)
        }
    }

    /**
     * Extended analysis for themes, symbols, vocabulary, foreshadowing.
     */
    suspend fun extendedAnalysisJson(chapterText: String): String = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                qwenModel?.extendedAnalysisJson(chapterText)
            } ?: StubFallbacks.extendedAnalysisJson(chapterText)
        } catch (e: TimeoutCancellationException) {
            AppLogger.w(TAG, "extendedAnalysisJson timed out, using fallback")
            StubFallbacks.extendedAnalysisJson(chapterText)
        } catch (e: Exception) {
            AppLogger.e(TAG, "extendedAnalysisJson error, using fallback", e)
            StubFallbacks.extendedAnalysisJson(chapterText)
        }
    }

    // ==================== Character Extraction Methods ====================

    /**
     * Pass 1: Extract character names from text.
     */
    suspend fun pass1ExtractCharacterNames(chapterText: String): List<String> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                qwenModel?.extractCharacterNames(chapterText)
            } ?: StubFallbacks.detectCharactersOnPage(chapterText)
        } catch (e: Exception) {
            AppLogger.w(TAG, "pass1ExtractCharacterNames failed, using fallback", e)
            StubFallbacks.detectCharactersOnPage(chapterText)
        }
    }

    /**
     * Pass 2: Extract dialogs from a page with speaker attribution.
     */
    suspend fun pass2ExtractDialogs(pageText: String, characterNames: List<String>): List<Qwen3Model.ExtractedDialogEntry> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            val result = withTimeout(LLM_TIMEOUT_MS) {
                qwenModel?.extractDialogsFromPage(pageText, characterNames)
            }
            // Use fallback if result is null or empty (model not loaded)
            if (result.isNullOrEmpty()) {
                StubFallbacks.extractDialogsFromText(pageText, characterNames)
            } else {
                result
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "pass2ExtractDialogs failed, using fallback", e)
            StubFallbacks.extractDialogsFromText(pageText, characterNames)
        }
    }

    /**
     * Extract traits and voice profile for characters.
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
                qwenModel?.pass3ExtractTraitsAndVoiceProfile(
                    char1Name, char1Context,
                    char2Name, char2Context,
                    char3Name, char3Context,
                    char4Name, char4Context
                )
            } ?: buildStubPass3Result(char1Name, char2Name, char3Name, char4Name)
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
     */
    suspend fun detectCharactersOnPage(pageText: String): List<String> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            val result = withTimeout(LLM_TIMEOUT_MS) {
                qwenModel?.detectCharactersOnPage(pageText)
            }
            // Use fallback if result is null or empty
            if (result.isNullOrEmpty()) {
                StubFallbacks.detectCharactersOnPage(pageText)
            } else {
                result
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "detectCharactersOnPage failed, using fallback", e)
            StubFallbacks.detectCharactersOnPage(pageText)
        }
    }

    /**
     * Infer traits for a character from an excerpt.
     */
    suspend fun inferTraitsForCharacter(characterName: String, excerpt: String): List<String> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            val result = withTimeout(LLM_TIMEOUT_MS) {
                qwenModel?.inferTraitsForCharacter(characterName, excerpt)
            }
            // Use fallback if result is null or empty
            if (result.isNullOrEmpty()) {
                StubFallbacks.inferTraitsFromName(characterName)
            } else {
                result
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "inferTraitsForCharacter failed, using fallback", e)
            StubFallbacks.inferTraitsFromName(characterName)
        }
    }
    
    // ==================== Story Generation ====================

    /**
     * Generate a story based on user prompt.
     */
    suspend fun generateStory(userPrompt: String): String = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            val result = withTimeout(LLM_TIMEOUT_MS) {
                qwenModel?.generateStory(userPrompt)
            }
            // Use fallback if result is null or empty
            if (result.isNullOrEmpty()) {
                StubFallbacks.generateStory(userPrompt)
            } else {
                result
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "generateStory failed, using fallback", e)
            StubFallbacks.generateStory(userPrompt)
        }
    }

    // ==================== Key Moments & Relationships ====================

    /**
     * Extract key moments for a character in a chapter.
     */
    suspend fun extractKeyMomentsForCharacter(
        characterName: String,
        chapterText: String,
        chapterTitle: String
    ): List<Map<String, String>> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            val result = withTimeout(LLM_TIMEOUT_MS) {
                qwenModel?.extractKeyMomentsForCharacter(characterName, chapterText, chapterTitle)
            }
            // Use fallback if result is null or empty
            if (result.isNullOrEmpty()) {
                StubFallbacks.extractKeyMoments(characterName, chapterTitle)
            } else {
                result
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "extractKeyMomentsForCharacter failed, using fallback", e)
            StubFallbacks.extractKeyMoments(characterName, chapterTitle)
        }
    }

    /**
     * Extract relationships for a character.
     */
    suspend fun extractRelationshipsForCharacter(
        characterName: String,
        chapterText: String,
        allCharacterNames: List<String>
    ): List<Map<String, String>> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            val result = withTimeout(LLM_TIMEOUT_MS) {
                qwenModel?.extractRelationshipsForCharacter(characterName, chapterText, allCharacterNames)
            }
            // Use fallback if result is null or empty
            if (result.isNullOrEmpty()) {
                StubFallbacks.extractRelationships(characterName, allCharacterNames)
            } else {
                result
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "extractRelationshipsForCharacter failed, using fallback", e)
            StubFallbacks.extractRelationships(characterName, allCharacterNames)
        }
    }

    /**
     * Suggest voice profiles from JSON.
     */
    suspend fun suggestVoiceProfilesJson(charactersWithTraitsJson: String): String? = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(LLM_TIMEOUT_MS) {
                qwenModel?.suggestVoiceProfilesJson(charactersWithTraitsJson)
            } ?: StubFallbacks.suggestVoiceProfiles(charactersWithTraitsJson)
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
     */
    suspend fun extractCharactersAndTraitsInSegment(
        segmentText: String,
        skipNamesWithTraits: Collection<String>,
        namesNeedingTraits: Collection<String>
    ): List<Pair<String, List<String>>> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            val result = withTimeout(LLM_TIMEOUT_MS) {
                qwenModel?.extractCharactersAndTraitsInSegment(segmentText, skipNamesWithTraits, namesNeedingTraits)
            }
            // Use fallback if result is null or empty
            if (result.isNullOrEmpty()) {
                StubFallbacks.extractCharactersAndTraitsInSegment(segmentText, skipNamesWithTraits, namesNeedingTraits)
            } else {
                result
            }
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

    // ==================== Serialization ====================

    /**
     * Convert ChapterAnalysisResponse to JSON string.
     */
    fun toJson(response: ChapterAnalysisResponse): String =
        com.google.gson.Gson().toJson(response)
}

