package com.dramebaz.app.ai.llm.pipeline

import android.content.Context
import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.prompts.ExtractionPrompts
import com.dramebaz.app.ai.tts.SpeakerMatcher
import com.dramebaz.app.data.db.AppDatabase
import com.dramebaz.app.data.db.Character
import com.dramebaz.app.data.db.CharacterPageMapping
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Chapter Analysis Pipeline implementing the 3-pass workflow:
 *
 * Pass-1: Character Name Extraction (Per Page)
 *   - Token Budget: Prompt+Input 3500, Output 100
 *   - Run on each page, accumulate unique character names
 *   - Create character-page mapping
 *
 * Pass-2: Dialog Extraction (For All Characters)
 *   - Token Budget: Prompt+Input 1800, Output 2200
 *   - Extract dialogs for characters found in Pass-1
 *   - Build character → dialogs mapping
 *
 * Pass-3: Voice Profile Suggestion
 *   - Token Budget: Prompt+Input 2500, Output 1500
 *   - Generate voice profiles for each character
 *   - Assign TTS speaker IDs from LibriTTS catalog (0-903)
 *
 * Features:
 * - Text cleaning before LLM analysis (remove headers/footers, normalize whitespace)
 * - Truncation at paragraph/sentence boundaries to fill 90%+ of token budget
 * - Checkpointing after each step of each pass
 * - 10-minute timeout per LLM call
 * - Incremental database saves
 */
class ChapterAnalysisPipeline(
    private val context: Context,
    private val database: AppDatabase,
    private val llmModel: LlmModel
) {
    companion object {
        private const val TAG = "ChapterAnalysisPipeline"
        private const val CHECKPOINT_DIR = "analysis_checkpoints"
        private const val CHECKPOINT_EXPIRY_MS = 24 * 60 * 60 * 1000L // 24 hours
        private const val LLM_TIMEOUT_MS = 10 * 60 * 1000L // 10 minutes
    }

    private val gson = Gson()
    private val checkpointMutex = Mutex()
    private val characterDao = database.characterDao()
    private val characterPageMappingDao = database.characterPageMappingDao()

    // ==================== Data Classes ====================

    /** Accumulated character data during analysis */
    data class AccumulatedCharacter(
        val name: String,
        val pagesAppearing: MutableSet<Int> = mutableSetOf(),
        val dialogs: MutableList<ExtractedDialogEntry> = mutableListOf(),
        var voiceProfile: VoiceProfileData? = null,
        var speakerId: Int? = null
    )

    data class ExtractedDialogEntry(
        val pageNumber: Int,
        val text: String,
        val emotion: String = "neutral",
        val intensity: Float = 0.5f
    )

    data class VoiceProfileData(
        val pitch: Float = 1.0f,
        val speed: Float = 1.0f,
        val energy: Float = 1.0f,
        val gender: String = "male",
        val age: String = "adult",
        val tone: String = "",
        val emotionBias: Map<String, Float> = emptyMap()
    )

    /** Checkpoint for resuming interrupted analysis */
    data class AnalysisCheckpoint(
        val bookId: Long,
        val chapterId: Long,
        val timestamp: Long,
        val chapterTextHash: Int,
        val lastCompletedPass: Int,  // 0=none, 1=Pass-1, 2=Pass-2, 3=complete
        val lastCompletedPage: Int,
        val accumulatedCharacters: Map<String, AccumulatedCharacter>,
        val pass3CompletedCharacters: Set<String> = emptySet()
    )

    /** Result of the pipeline analysis */
    data class PipelineResult(
        val characters: List<AccumulatedCharacter>,
        val totalDialogs: Int,
        val pagesProcessed: Int,
        val isComplete: Boolean
    )

    // ==================== Main Entry Point ====================

    /**
     * Analyze a chapter using the 3-pass workflow.
     *
     * @param bookId Book ID for database operations
     * @param chapterId Chapter ID for database operations
     * @param pages List of page texts (extracted from PDF)
     * @param onProgress Progress callback with message
     * @return PipelineResult with analyzed characters
     */
    suspend fun analyzeChapter(
        bookId: Long,
        chapterId: Long,
        pages: List<String>,
        onProgress: ((String) -> Unit)? = null
    ): PipelineResult = withContext(Dispatchers.IO) {
        val chapterTextHash = pages.joinToString("").hashCode()

        // Try to load existing checkpoint
        val checkpoint = loadCheckpoint(bookId, chapterId, chapterTextHash)

        // Initialize accumulated characters from checkpoint or empty
        val accumulatedCharacters = checkpoint?.accumulatedCharacters?.toMutableMap()
            ?: mutableMapOf()
        val pass3CompletedChars = checkpoint?.pass3CompletedCharacters?.toMutableSet()
            ?: mutableSetOf()

        // Determine where to resume
        val startPass = checkpoint?.lastCompletedPass?.let { it + 1 } ?: 1
        val startPage = if (startPass == (checkpoint?.lastCompletedPass ?: 0) + 1) 0
                        else (checkpoint?.lastCompletedPage ?: -1) + 1

        AppLogger.i(TAG, "Starting analysis: bookId=$bookId, chapterId=$chapterId, " +
                "pages=${pages.size}, resumePass=$startPass, resumePage=$startPage")

        // Clean all pages
        val cleanedPages = pages.map { TextCleaner.cleanPage(it) }

        // ==================== Pass-1: Character Extraction ====================
        if (startPass <= 1) {
            onProgress?.invoke("Pass-1: Extracting characters...")
            runPass1(bookId, chapterId, cleanedPages, accumulatedCharacters,
                    chapterTextHash, startPage, onProgress)
        }

        // ==================== Pass-2: Dialog Extraction ====================
        if (startPass <= 2) {
            onProgress?.invoke("Pass-2: Extracting dialogs...")
            runPass2(bookId, chapterId, cleanedPages, accumulatedCharacters,
                    chapterTextHash, onProgress)
        }

        // ==================== Pass-3: Voice Profile Suggestion ====================
        if (startPass <= 3) {
            onProgress?.invoke("Pass-3: Generating voice profiles...")
            runPass3(bookId, chapterId, accumulatedCharacters, pass3CompletedChars,
                    chapterTextHash, cleanedPages.size, onProgress)
        }

        // Delete checkpoint on successful completion
        deleteCheckpoint(bookId, chapterId)

        val totalDialogs = accumulatedCharacters.values.sumOf { it.dialogs.size }
        onProgress?.invoke("Analysis complete: ${accumulatedCharacters.size} characters, $totalDialogs dialogs")

        PipelineResult(
            characters = accumulatedCharacters.values.toList(),
            totalDialogs = totalDialogs,
            pagesProcessed = cleanedPages.size,
            isComplete = true
        )
    }


    // ==================== Pass-1: Character Extraction ====================

    private suspend fun runPass1(
        bookId: Long,
        chapterId: Long,
        cleanedPages: List<String>,
        accumulatedCharacters: MutableMap<String, AccumulatedCharacter>,
        chapterTextHash: Int,
        startPage: Int,
        onProgress: ((String) -> Unit)?
    ) {
        for ((pageIndex, pageText) in cleanedPages.withIndex()) {
            if (pageIndex < startPage) continue

            val pageNum = pageIndex + 1
            onProgress?.invoke("Pass-1: Page $pageNum/${cleanedPages.size}")

            if (pageText.length < 50) {
                AppLogger.d(TAG, "Page $pageNum too short, skipping")
                continue
            }

            // Prepare input with token budget
            val preparedText = TokenBudgetManager.prepareInputText(
                pageText,
                TokenBudgetManager.PASS1_CHARACTER_EXTRACTION
            )

            try {
                val characterNames = withTimeout(LLM_TIMEOUT_MS) {
                    extractCharacterNames(preparedText)
                }

                // Update accumulated characters
                for (name in characterNames) {
                    val normalizedName = name.trim()
                    if (normalizedName.length < 2) continue

                    val existing = accumulatedCharacters[normalizedName.lowercase()]
                    if (existing != null) {
                        existing.pagesAppearing.add(pageNum)
                    } else {
                        accumulatedCharacters[normalizedName.lowercase()] = AccumulatedCharacter(
                            name = normalizedName,
                            pagesAppearing = mutableSetOf(pageNum)
                        )
                        // Save new character to database immediately
                        saveCharacterToDatabase(bookId, normalizedName)
                    }
                }

                AppLogger.d(TAG, "Page $pageNum: Found ${characterNames.size} characters")

                // Save checkpoint
                saveCheckpoint(AnalysisCheckpoint(
                    bookId = bookId,
                    chapterId = chapterId,
                    timestamp = System.currentTimeMillis(),
                    chapterTextHash = chapterTextHash,
                    lastCompletedPass = 1,
                    lastCompletedPage = pageIndex,
                    accumulatedCharacters = accumulatedCharacters.toMap()
                ))

            } catch (e: Exception) {
                AppLogger.e(TAG, "Pass-1 failed for page $pageNum", e)
            }
        }
    }

    private suspend fun extractCharacterNames(text: String): List<String> {
        val userPrompt = ExtractionPrompts.buildPass1ExtractNamesPrompt(text)

        val response = llmModel.generateResponse(
            systemPrompt = ExtractionPrompts.PASS1_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            maxTokens = TokenBudgetManager.PASS1_CHARACTER_EXTRACTION.outputTokens,
            temperature = 0.1f
        )

        return parseCharacterNamesFromResponse(response)
    }

    private fun parseCharacterNamesFromResponse(response: String): List<String> {
        return try {
            val json = extractJsonFromResponse(response)
            val map = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return emptyList()

            @Suppress("UNCHECKED_CAST")
            val characters = (map["characters"] as? List<*>) ?: (map["names"] as? List<*>)

            characters?.mapNotNull { entry ->
                when (entry) {
                    is String -> entry.trim().takeIf { it.isNotBlank() }
                    else -> null
                }
            }?.distinctBy { it.lowercase() } ?: emptyList()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse character names", e)
            emptyList()
        }
    }

    // ==================== Pass-2: Dialog Extraction ====================

    private suspend fun runPass2(
        bookId: Long,
        chapterId: Long,
        cleanedPages: List<String>,
        accumulatedCharacters: MutableMap<String, AccumulatedCharacter>,
        chapterTextHash: Int,
        onProgress: ((String) -> Unit)?
    ) {
        for ((pageIndex, pageText) in cleanedPages.withIndex()) {
            val pageNum = pageIndex + 1

            // Get characters appearing on this page
            val charactersOnPage = accumulatedCharacters.values
                .filter { it.pagesAppearing.contains(pageNum) }
                .map { it.name }

            if (charactersOnPage.isEmpty()) {
                AppLogger.d(TAG, "Page $pageNum: No characters, skipping Pass-2")
                continue
            }

            onProgress?.invoke("Pass-2: Page $pageNum/${cleanedPages.size}")

            // Prepare input with token budget
            val preparedText = TokenBudgetManager.prepareInputText(
                pageText,
                TokenBudgetManager.PASS2_DIALOG_EXTRACTION
            )

            try {
                val dialogs = withTimeout(LLM_TIMEOUT_MS) {
                    extractDialogs(preparedText, charactersOnPage)
                }

                // Add dialogs to accumulated characters
                for (dialog in dialogs) {
                    val charKey = dialog.first.lowercase()
                    val charData = accumulatedCharacters[charKey]
                    charData?.dialogs?.add(ExtractedDialogEntry(
                        pageNumber = pageNum,
                        text = dialog.second
                    ))

                    // Save to character page mapping
                    saveCharacterPageMapping(bookId, chapterId, pageNum,
                        dialog.first, dialog.second, charData?.speakerId)
                }

                AppLogger.d(TAG, "Page $pageNum: Extracted ${dialogs.size} dialogs")

                // Save checkpoint
                saveCheckpoint(AnalysisCheckpoint(
                    bookId = bookId,
                    chapterId = chapterId,
                    timestamp = System.currentTimeMillis(),
                    chapterTextHash = chapterTextHash,
                    lastCompletedPass = 2,
                    lastCompletedPage = pageIndex,
                    accumulatedCharacters = accumulatedCharacters.toMap()
                ))

            } catch (e: Exception) {
                AppLogger.e(TAG, "Pass-2 failed for page $pageNum", e)
            }
        }
    }

    private suspend fun extractDialogs(text: String, characterNames: List<String>): List<Pair<String, String>> {
        val userPrompt = ExtractionPrompts.buildPass2ExtractDialogsPrompt(text, characterNames)

        val response = llmModel.generateResponse(
            systemPrompt = ExtractionPrompts.PASS2_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            maxTokens = TokenBudgetManager.PASS2_DIALOG_EXTRACTION.outputTokens,
            temperature = 0.15f
        )

        return parseDialogsFromResponse(response)
    }

    private fun parseDialogsFromResponse(response: String): List<Pair<String, String>> {
        return try {
            val json = extractJsonFromResponse(response)
            val result = mutableListOf<Pair<String, String>>()

            // Parse array format: [{"Character": "dialog"}, ...]
            if (json.startsWith("[")) {
                val list = gson.fromJson(json, List::class.java) as? List<*>
                list?.forEach { item ->
                    val map = item as? Map<*, *> ?: return@forEach
                    map.entries.firstOrNull()?.let { entry ->
                        val speaker = entry.key?.toString()?.trim() ?: return@let
                        val dialog = entry.value?.toString()?.trim() ?: return@let
                        if (speaker.isNotBlank() && dialog.isNotBlank()) {
                            result.add(Pair(speaker, dialog))
                        }
                    }
                }
            }

            result
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse dialogs", e)
            emptyList()
        }
    }



    // ==================== Pass-3: Voice Profile Suggestion ====================

    private suspend fun runPass3(
        bookId: Long,
        chapterId: Long,
        accumulatedCharacters: MutableMap<String, AccumulatedCharacter>,
        pass3CompletedChars: MutableSet<String>,
        chapterTextHash: Int,
        totalPages: Int,
        onProgress: ((String) -> Unit)?
    ) {
        val charactersToProcess = accumulatedCharacters.values
            .filter { !pass3CompletedChars.contains(it.name.lowercase()) }
            .toList()

        if (charactersToProcess.isEmpty()) {
            AppLogger.d(TAG, "Pass-3: All characters already processed")
            return
        }

        // Process characters in batches of 4
        val batches = charactersToProcess.chunked(4)

        for ((batchIndex, batch) in batches.withIndex()) {
            onProgress?.invoke("Pass-3: Characters ${batchIndex * 4 + 1}-${minOf((batchIndex + 1) * 4, charactersToProcess.size)}/${charactersToProcess.size}")

            val characterNames = batch.map { it.name }

            // Build dialog context from accumulated dialogs
            val dialogContext = batch.joinToString("\n\n") { char ->
                val dialogs = char.dialogs.take(5).joinToString("\n") { d -> "${char.name}: \"${d.text}\"" }
                "Character: ${char.name}\nDialogs:\n$dialogs"
            }

            // Prepare input with token budget
            val preparedContext = TokenBudgetManager.prepareInputText(
                dialogContext,
                TokenBudgetManager.PASS3_VOICE_PROFILE
            )

            try {
                val profiles = withTimeout(LLM_TIMEOUT_MS) {
                    extractVoiceProfiles(characterNames, preparedContext)
                }

                // Update accumulated characters with profiles
                for (profile in profiles) {
                    val charKey = profile.first.lowercase()
                    val charData = accumulatedCharacters[charKey]
                    if (charData != null) {
                        charData.voiceProfile = profile.second

                        // Assign speaker ID using SpeakerMatcher
                        val speakerId = SpeakerMatcher.suggestSpeakerId(
                            traits = "${profile.second.gender}, ${profile.second.age}, ${profile.second.tone}",
                            personalitySummary = null,
                            name = charData.name
                        )
                        charData.speakerId = speakerId

                        // Update character in database with voice profile and speaker ID
                        updateCharacterWithVoiceProfile(bookId, charData)

                        pass3CompletedChars.add(charKey)
                    }
                }

                AppLogger.d(TAG, "Pass-3 batch ${batchIndex + 1}: Processed ${profiles.size} profiles")

                // Save checkpoint
                saveCheckpoint(AnalysisCheckpoint(
                    bookId = bookId,
                    chapterId = chapterId,
                    timestamp = System.currentTimeMillis(),
                    chapterTextHash = chapterTextHash,
                    lastCompletedPass = 3,
                    lastCompletedPage = totalPages - 1,
                    accumulatedCharacters = accumulatedCharacters.toMap(),
                    pass3CompletedCharacters = pass3CompletedChars.toSet()
                ))

            } catch (e: Exception) {
                AppLogger.e(TAG, "Pass-3 failed for batch ${batchIndex + 1}", e)
            }
        }
    }

    private suspend fun extractVoiceProfiles(
        characterNames: List<String>,
        dialogContext: String
    ): List<Pair<String, VoiceProfileData>> {
        val userPrompt = ExtractionPrompts.buildPass3VoiceProfilePrompt(characterNames, dialogContext)

        val response = llmModel.generateResponse(
            systemPrompt = ExtractionPrompts.PASS3_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            maxTokens = TokenBudgetManager.PASS3_VOICE_PROFILE.outputTokens,
            temperature = 0.2f
        )

        return parseVoiceProfilesFromResponse(response)
    }

    private fun parseVoiceProfilesFromResponse(response: String): List<Pair<String, VoiceProfileData>> {
        return try {
            val json = extractJsonFromResponse(response)
            val map = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return emptyList()

            @Suppress("UNCHECKED_CAST")
            val characters = map["characters"] as? List<*> ?: return emptyList()

            characters.mapNotNull { item ->
                val charMap = item as? Map<*, *> ?: return@mapNotNull null
                val name = charMap["name"]?.toString() ?: return@mapNotNull null
                val voiceMap = charMap["voice_profile"] as? Map<*, *> ?: emptyMap<String, Any>()

                @Suppress("UNCHECKED_CAST")
                val emotionBias = (voiceMap["emotion_bias"] as? Map<*, *>)
                    ?.mapNotNull { (k, v) ->
                        val key = k?.toString() ?: return@mapNotNull null
                        val value = (v as? Number)?.toFloat() ?: return@mapNotNull null
                        key to value
                    }?.toMap() ?: emptyMap()

                Pair(name, VoiceProfileData(
                    pitch = (voiceMap["pitch"] as? Number)?.toFloat() ?: 1.0f,
                    speed = (voiceMap["speed"] as? Number)?.toFloat() ?: 1.0f,
                    energy = (voiceMap["energy"] as? Number)?.toFloat() ?: 1.0f,
                    gender = charMap["gender"]?.toString() ?: "male",
                    age = charMap["age"]?.toString() ?: "adult",
                    tone = charMap["tone"]?.toString() ?: "",
                    emotionBias = emotionBias
                ))
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse voice profiles", e)
            emptyList()
        }
    }

    // ==================== Database Operations ====================

    private suspend fun saveCharacterToDatabase(bookId: Long, name: String) {
        try {
            val existing = characterDao.getByBookIdAndName(bookId, name)
            if (existing == null) {
                characterDao.insert(Character(
                    bookId = bookId,
                    name = name,
                    traits = "[]"
                ))
                AppLogger.d(TAG, "Saved new character: $name")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save character $name", e)
        }
    }

    private suspend fun saveCharacterPageMapping(
        bookId: Long,
        chapterId: Long,
        pageNumber: Int,
        characterName: String,
        dialogText: String,
        speakerId: Int?
    ) {
        try {
            // Get current segment count for this page
            val existingSegments = characterPageMappingDao.getSegmentsForPage(bookId, chapterId, pageNumber)
            val segmentIndex = existingSegments.size

            characterPageMappingDao.insert(CharacterPageMapping(
                bookId = bookId,
                chapterId = chapterId,
                pageNumber = pageNumber,
                segmentIndex = segmentIndex,
                characterName = characterName,
                speakerId = speakerId,
                dialogText = dialogText,
                isDialog = true,
                firstAppearance = segmentIndex == 0
            ))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save page mapping", e)
        }
    }

    private suspend fun updateCharacterWithVoiceProfile(bookId: Long, charData: AccumulatedCharacter) {
        try {
            val existing = characterDao.getByBookIdAndName(bookId, charData.name)
            if (existing != null) {
                val voiceProfileJson = charData.voiceProfile?.let { gson.toJson(it) }
                val dialogsJson = gson.toJson(charData.dialogs)

                characterDao.update(existing.copy(
                    voiceProfileJson = voiceProfileJson,
                    speakerId = charData.speakerId,
                    dialogsJson = dialogsJson
                ))

                // Update speaker ID in page mappings
                charData.speakerId?.let { speakerId ->
                    characterPageMappingDao.updateSpeakerForCharacter(bookId, charData.name, speakerId)
                }

                AppLogger.d(TAG, "Updated character ${charData.name} with voice profile (speakerId=${charData.speakerId})")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to update character ${charData.name}", e)
        }
    }

    // ==================== Checkpoint Management ====================

    private fun getCheckpointFile(bookId: Long, chapterId: Long): File {
        val checkpointDir = File(context.filesDir, CHECKPOINT_DIR)
        return File(checkpointDir, "pipeline_${bookId}_${chapterId}.json")
    }

    private suspend fun loadCheckpoint(
        bookId: Long,
        chapterId: Long,
        chapterTextHash: Int
    ): AnalysisCheckpoint? = checkpointMutex.withLock {
        try {
            val file = getCheckpointFile(bookId, chapterId)
            if (!file.exists()) return@withLock null

            val checkpoint = gson.fromJson(file.readText(), AnalysisCheckpoint::class.java)

            // Validate checkpoint
            if (checkpoint.chapterTextHash != chapterTextHash) {
                AppLogger.d(TAG, "Checkpoint invalid: chapter content changed")
                file.delete()
                return@withLock null
            }

            if (System.currentTimeMillis() - checkpoint.timestamp > CHECKPOINT_EXPIRY_MS) {
                AppLogger.d(TAG, "Checkpoint expired")
                file.delete()
                return@withLock null
            }

            AppLogger.i(TAG, "Loaded checkpoint: pass=${checkpoint.lastCompletedPass}, page=${checkpoint.lastCompletedPage}")
            checkpoint
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load checkpoint", e)
            null
        }
    }

    private suspend fun saveCheckpoint(checkpoint: AnalysisCheckpoint) = checkpointMutex.withLock {
        try {
            val file = getCheckpointFile(checkpoint.bookId, checkpoint.chapterId)
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(checkpoint))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save checkpoint", e)
        }
    }

    private suspend fun deleteCheckpoint(bookId: Long, chapterId: Long) = checkpointMutex.withLock {
        try {
            val file = getCheckpointFile(bookId, chapterId)
            if (file.exists()) {
                file.delete()
                AppLogger.d(TAG, "Deleted checkpoint for book=$bookId, chapter=$chapterId")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to delete checkpoint", e)
        }
    }

    // ==================== Utility Methods ====================

    private fun extractJsonFromResponse(response: String): String {
        var json = response.trim()
        if (json.startsWith("```json")) json = json.removePrefix("```json").trim()
        if (json.startsWith("```")) json = json.removePrefix("```").trim()
        if (json.endsWith("```")) json = json.removeSuffix("```").trim()

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
}