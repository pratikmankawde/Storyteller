package com.dramebaz.app.domain.usecases

import android.content.Context
import com.dramebaz.app.ai.llm.LlmService
import com.dramebaz.app.ai.tts.LibrittsSpeakerCatalog
import com.dramebaz.app.ai.tts.SpeakerMatcher
import com.dramebaz.app.data.db.Character
import com.dramebaz.app.data.db.CharacterDao
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Three-Pass Character Analysis Workflow for Chapter Processing.
 *
 * Processes text page-by-page (~10,000 characters per segment):
 * - Pass-1: Extract character names from each page, track page appearances
 * - Pass-2: Extract dialogs and narrator text from each page (2 characters at a time)
 * - Pass-3: Extract traits AND suggest voice profile using aggregated context (2 characters at a time)
 *
 * Pass-3 optimization:
 * - Uses aggregated context from ALL pages where character appears (up to 10,000 chars)
 * - Extracts concise 1-2 word traits (behavioral, speech, personality-indicator)
 * - Maps traits directly to voice profile parameters (pitch, speed, energy, speaker_id)
 * - Processes 2 characters at a time for efficiency
 *
 * Features:
 * - Sequential page processing (no parallel processing)
 * - Context aggregation for richer trait extraction
 * - Dialog extraction with speaker attribution and emotion detection
 * - Fallback to name-based trait inference for characters with no extracted traits
 * - SpeakerMatcher integration for VCTK speaker ID assignment (0-108)
 * - Database persistence with existing character trait updates
 * - Checkpoint-based persistence with resume capability
 */
class ThreePassCharacterAnalysisUseCase(
    private val characterDao: CharacterDao,
    private val context: Context? = null  // Optional: enables checkpoint persistence
) {

    companion object {
        private const val TAG = "ThreePassCharAnalysis"
        private const val PAGE_SIZE_CHARS = 10000
        private const val MAX_CONTEXT_PER_CHAR = 10000
        private const val CHECKPOINT_DIR = "analysis_checkpoints"
        private const val CHECKPOINT_EXPIRY_HOURS = 24L
    }

    private val gson = Gson()
    private val checkpointMutex = Mutex()  // Thread-safety for checkpoint operations

    /**
     * Data class holding the complete analysis result for a single character.
     */
    data class CharacterAnalysisResult(
        val name: String,
        val traits: List<String>,
        val personality: List<String>,
        val voiceProfile: Map<String, Any>,
        val dialogs: List<ExtractedDialog> = emptyList()
    )

    /**
     * Extracted dialog entry with page number for persistence.
     */
    data class ExtractedDialog(
        val pageNumber: Int,
        val text: String,
        val emotion: String = "neutral",
        val intensity: Float = 0.5f
    )

    /**
     * Checkpoint data for resuming interrupted analysis.
     */
    data class AnalysisCheckpoint(
        val bookId: Long,
        val chapterIndex: Int,
        val timestamp: Long,
        val chapterTextHash: Int,  // To detect if chapter content changed
        val lastCompletedPass: Int,  // 0=none, 1=Pass-1, 2=Pass-2, 3=complete
        val lastCompletedPage: Int,  // Last page index completed for Pass-1/Pass-2
        val accumulatedCharacters: Map<String, SerializableAccumulatedCharacter>,
        val pass3CompletedCharacters: Set<String> = emptySet()  // Character names that completed Pass-3
    )

    /**
     * Serializable version of AccumulatedCharacter for JSON persistence.
     */
    data class SerializableAccumulatedCharacter(
        val name: String,
        val pageTexts: List<String> = emptyList(),
        val voiceProfile: Map<String, Any> = emptyMap(),
        val dialogs: List<ExtractedDialog> = emptyList(),
        val traits: List<String> = emptyList()  // Populated after Pass-3
    )

    /**
     * Accumulated character data during page-by-page processing.
     * Tracks all page texts where the character appears for context aggregation.
     */
    private data class AccumulatedCharacter(
        val name: String,
        val pageTexts: MutableList<String> = mutableListOf(),
        var voiceProfile: Map<String, Any> = emptyMap(),
        val dialogs: MutableList<ExtractedDialog> = mutableListOf(),
        var traits: List<String> = emptyList()  // Populated after Pass-3
    )

    /**
     * Split chapter text into pages of approximately PAGE_SIZE_CHARS characters.
     * Tries to split at paragraph/sentence boundaries for cleaner text.
     */
    private fun splitIntoPages(chapterText: String): List<String> {
        if (chapterText.length <= PAGE_SIZE_CHARS) {
            return listOf(chapterText)
        }

        val pages = mutableListOf<String>()
        var remaining = chapterText

        while (remaining.isNotEmpty()) {
            if (remaining.length <= PAGE_SIZE_CHARS) {
                pages.add(remaining)
                break
            }

            // Find a good break point (paragraph or sentence end)
            var breakPoint = PAGE_SIZE_CHARS
            val searchRange = (PAGE_SIZE_CHARS * 0.8).toInt() until PAGE_SIZE_CHARS

            // Try to find paragraph break first
            val paragraphBreak = remaining.lastIndexOf("\n\n", PAGE_SIZE_CHARS)
            if (paragraphBreak > searchRange.first) {
                breakPoint = paragraphBreak + 2
            } else {
                // Try sentence break
                val sentenceBreaks = listOf(". ", "! ", "? ", ".\n", "!\n", "?\n")
                for (delimiter in sentenceBreaks) {
                    val idx = remaining.lastIndexOf(delimiter, PAGE_SIZE_CHARS)
                    if (idx > searchRange.first) {
                        breakPoint = idx + delimiter.length
                        break
                    }
                }
            }

            pages.add(remaining.substring(0, breakPoint).trim())
            remaining = remaining.substring(breakPoint).trim()
        }

        AppLogger.d(TAG, "Split chapter into ${pages.size} pages (avg ${chapterText.length / pages.size} chars/page)")
        return pages
    }

    // ==================== Checkpoint Persistence Methods ====================

    /**
     * Get the checkpoint file for a specific book/chapter combination.
     */
    private fun getCheckpointFile(bookId: Long, chapterIndex: Int): File? {
        val cacheDir = context?.cacheDir ?: return null
        val checkpointDir = File(cacheDir, CHECKPOINT_DIR)
        if (!checkpointDir.exists()) {
            checkpointDir.mkdirs()
        }
        return File(checkpointDir, "${bookId}_ch${chapterIndex}.json")
    }

    /**
     * Load an existing checkpoint if valid (not expired, matching chapter hash).
     * Returns null if no valid checkpoint exists.
     */
    private suspend fun loadCheckpoint(bookId: Long, chapterIndex: Int, chapterTextHash: Int): AnalysisCheckpoint? =
        checkpointMutex.withLock {
            val file = getCheckpointFile(bookId, chapterIndex) ?: return@withLock null
            if (!file.exists()) {
                AppLogger.d(TAG, "📁 No checkpoint found for book=$bookId, chapter=$chapterIndex")
                return@withLock null
            }

            try {
                val json = file.readText()
                val checkpoint = gson.fromJson(json, AnalysisCheckpoint::class.java)

                // Check if checkpoint is expired (>24 hours old)
                val ageHours = (System.currentTimeMillis() - checkpoint.timestamp) / (1000 * 60 * 60)
                if (ageHours > CHECKPOINT_EXPIRY_HOURS) {
                    AppLogger.i(TAG, "🗑️ Checkpoint expired (${ageHours}h old), deleting")
                    file.delete()
                    return@withLock null
                }

                // Check if chapter content changed (hash mismatch)
                if (checkpoint.chapterTextHash != chapterTextHash) {
                    AppLogger.i(TAG, "🗑️ Checkpoint invalid (chapter content changed), deleting")
                    file.delete()
                    return@withLock null
                }

                AppLogger.i(TAG, "✅ Loaded valid checkpoint: pass=${checkpoint.lastCompletedPass}, page=${checkpoint.lastCompletedPage}, chars=${checkpoint.accumulatedCharacters.size}")
                checkpoint
            } catch (e: Exception) {
                AppLogger.w(TAG, "⚠️ Failed to load checkpoint, deleting corrupted file", e)
                file.delete()
                null
            }
        }

    /**
     * Save checkpoint to file atomically.
     */
    private suspend fun saveCheckpoint(checkpoint: AnalysisCheckpoint) = checkpointMutex.withLock {
        val file = getCheckpointFile(checkpoint.bookId, checkpoint.chapterIndex) ?: return@withLock
        try {
            val json = gson.toJson(checkpoint)
            // Write to temp file first, then rename for atomicity
            val tempFile = File(file.parent, "${file.name}.tmp")
            tempFile.writeText(json)
            tempFile.renameTo(file)
            AppLogger.d(TAG, "💾 Checkpoint saved: pass=${checkpoint.lastCompletedPass}, page=${checkpoint.lastCompletedPage}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "❌ Failed to save checkpoint", e)
        }
    }

    /**
     * Delete checkpoint file after successful completion.
     */
    private suspend fun deleteCheckpoint(bookId: Long, chapterIndex: Int) = checkpointMutex.withLock {
        val file = getCheckpointFile(bookId, chapterIndex) ?: return@withLock
        if (file.exists()) {
            file.delete()
            AppLogger.i(TAG, "🗑️ Checkpoint deleted after successful completion")
        }
    }

    /**
     * Convert accumulated characters to serializable format for checkpoint.
     */
    private fun toSerializable(accumulatedCharacters: Map<String, AccumulatedCharacter>): Map<String, SerializableAccumulatedCharacter> {
        return accumulatedCharacters.mapValues { (_, char) ->
            SerializableAccumulatedCharacter(
                name = char.name,
                pageTexts = char.pageTexts.toList(),
                voiceProfile = char.voiceProfile,
                dialogs = char.dialogs.toList(),
                traits = char.traits
            )
        }
    }

    /**
     * Restore accumulated characters from checkpoint.
     */
    private fun fromSerializable(serialized: Map<String, SerializableAccumulatedCharacter>): MutableMap<String, AccumulatedCharacter> {
        return serialized.mapValues { (_, ser) ->
            AccumulatedCharacter(
                name = ser.name,
                pageTexts = ser.pageTexts.toMutableList(),
                voiceProfile = ser.voiceProfile,
                dialogs = ser.dialogs.toMutableList(),
                traits = ser.traits
            )
        }.toMutableMap()
    }

    // ==================== End Checkpoint Methods ====================

    // ==================== Incremental Database Persistence ====================

    /**
     * Save newly discovered characters to database incrementally during Pass-1.
     * Creates character records with minimal data (name only, empty traits) so they
     * appear in the UI immediately. These records will be updated with full data
     * after Pass-3 completes.
     *
     * @param bookId Book ID for the characters
     * @param newCharacterNames List of new character names discovered in this page
     */
    private suspend fun saveCharactersIncrementally(bookId: Long, newCharacterNames: List<String>) {
        if (newCharacterNames.isEmpty()) return

        val existingCharacters = characterDao.getByBookId(bookId).first()
        val existingNames = existingCharacters.map { it.name.lowercase() }.toSet()

        for (name in newCharacterNames) {
            if (name.lowercase() !in existingNames) {
                // Insert new character with minimal data - will be updated in Pass-3
                val newCharacter = Character(
                    bookId = bookId,
                    name = name,
                    traits = "",  // Empty - will be populated in Pass-3
                    personalitySummary = "",
                    voiceProfileJson = null,
                    speakerId = null,
                    dialogsJson = null
                )
                characterDao.insert(newCharacter)
                AppLogger.d(TAG, "💾 Incremental save: '$name' (Pass-1 discovery)")
            }
        }
    }

    /**
     * Update character records with dialog data after Pass-2.
     * Merges new dialogs with any existing dialogs for the character.
     *
     * @param bookId Book ID for the characters
     * @param characterDialogs Map of character name -> list of dialogs extracted from this page
     */
    private suspend fun updateCharactersWithDialogs(
        bookId: Long,
        characterDialogs: Map<String, List<ExtractedDialog>>
    ) {
        if (characterDialogs.isEmpty()) return

        val existingCharacters = characterDao.getByBookId(bookId).first()
        val byName = existingCharacters.associateBy { it.name.lowercase() }

        for ((characterName, newDialogs) in characterDialogs) {
            val existing = byName[characterName.lowercase()] ?: continue

            // Merge new dialogs with existing
            val mergedDialogsJson = if (existing.dialogsJson.isNullOrBlank()) {
                gson.toJson(newDialogs)
            } else {
                try {
                    val existingDialogs = gson.fromJson(existing.dialogsJson, Array<ExtractedDialog>::class.java).toMutableList()
                    // Avoid duplicates
                    val uniqueNewDialogs = newDialogs.filter { newDialog ->
                        existingDialogs.none { it.pageNumber == newDialog.pageNumber && it.text == newDialog.text }
                    }
                    existingDialogs.addAll(uniqueNewDialogs)
                    gson.toJson(existingDialogs)
                } catch (e: Exception) {
                    gson.toJson(newDialogs)
                }
            }

            characterDao.update(existing.copy(dialogsJson = mergedDialogsJson))
            AppLogger.d(TAG, "💾 Incremental update: '${existing.name}' with ${newDialogs.size} dialogs (Pass-2)")
        }
    }

    // ==================== End Incremental Persistence ====================

    /**
     * Run the complete 3-pass analysis workflow for a chapter.
     * Processes text page-by-page with context aggregation for Pass-3.
     *
     * @param bookId Book ID for storing characters
     * @param chapterText Full chapter text to analyze
     * @param chapterIndex Current chapter index (for logging)
     * @param totalChapters Total chapters in book (for logging)
     * @param onProgress Progress callback with message
     * @param onCharacterProcessed Callback when a character completes all passes
     * @return List of analyzed characters
     */
    suspend fun analyzeChapter(
        bookId: Long,
        chapterText: String,
        chapterIndex: Int = 0,
        totalChapters: Int = 1,
        onProgress: ((String) -> Unit)? = null,
        onCharacterProcessed: ((String) -> Unit)? = null
    ): List<CharacterAnalysisResult> = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "🚀 analyzeChapter() ENTERED - bookId=$bookId, chapterIndex=$chapterIndex, textLen=${chapterText.length}")
        val totalStartMs = System.currentTimeMillis()
        val trimmed = chapterText.trim()

        if (trimmed.isBlank()) {
            AppLogger.w(TAG, "⚠️ Chapter text empty, skipping analysis")
            return@withContext emptyList()
        }

        // Calculate chapter text hash for checkpoint validation
        val chapterTextHash = trimmed.hashCode()

        // Try to load existing checkpoint
        val checkpoint = loadCheckpoint(bookId, chapterIndex, chapterTextHash)
        val resumingFromCheckpoint = checkpoint != null

        // Determine starting state based on checkpoint
        val pass1StartPageIndex: Int
        val pass2StartPageIndex: Int
        val pass3CompletedChars: MutableSet<String>
        val accumulatedCharacters: MutableMap<String, AccumulatedCharacter>

        if (checkpoint != null) {
            AppLogger.i(TAG, "📂 Resuming from checkpoint: pass=${checkpoint.lastCompletedPass}, page=${checkpoint.lastCompletedPage}")
            onProgress?.invoke("Chapter ${chapterIndex + 1}/$totalChapters: Resuming from checkpoint...")
            accumulatedCharacters = fromSerializable(checkpoint.accumulatedCharacters)
            pass3CompletedChars = checkpoint.pass3CompletedCharacters.toMutableSet()

            when (checkpoint.lastCompletedPass) {
                0 -> {
                    // No passes completed, start fresh
                    pass1StartPageIndex = 0
                    pass2StartPageIndex = 0
                }
                1 -> {
                    // Pass-1 was in progress, resume from last completed page + 1
                    pass1StartPageIndex = checkpoint.lastCompletedPage + 1
                    pass2StartPageIndex = 0  // Pass-2 not started yet
                }
                2 -> {
                    // Pass-1 & Pass-2 complete for all pages up to lastCompletedPage
                    pass1StartPageIndex = checkpoint.lastCompletedPage + 1
                    pass2StartPageIndex = checkpoint.lastCompletedPage + 1
                }
                else -> {
                    // Pass-3 was in progress or complete
                    pass1StartPageIndex = Int.MAX_VALUE  // Skip Pass-1
                    pass2StartPageIndex = Int.MAX_VALUE  // Skip Pass-2
                }
            }
        } else {
            AppLogger.i(TAG, "📖 Starting fresh 3-pass page-by-page analysis for chapter ${chapterIndex + 1}/$totalChapters")
            accumulatedCharacters = mutableMapOf()
            pass3CompletedChars = mutableSetOf()
            pass1StartPageIndex = 0
            pass2StartPageIndex = 0
        }

        AppLogger.d(TAG, "   Text preview: ${trimmed.take(200).replace("\n", " ")}...")

        // Split chapter into pages
        val pages = splitIntoPages(trimmed)
        AppLogger.i(TAG, "📄 Chapter split into ${pages.size} pages")

        // ============ Process Each Page Sequentially (Pass-1 and Pass-2) ============
        if (pass1StartPageIndex < pages.size) {
            AppLogger.i(TAG, "🔄 Starting page-by-page processing (Pass-1 + Pass-2) from page ${pass1StartPageIndex + 1}...")
        }
        pages.forEachIndexed { pageIndex, pageText ->
            // Skip pages already processed based on checkpoint
            if (pageIndex < pass1StartPageIndex && pageIndex < pass2StartPageIndex) {
                AppLogger.d(TAG, "📄 Skipping page ${pageIndex + 1}/${pages.size} (already processed)")
                return@forEachIndexed
            }
            val pageNum = pageIndex + 1
            val pageStartMs = System.currentTimeMillis()
            AppLogger.i(TAG, "📄 Processing page $pageNum/${pages.size} (${pageText.length} chars)")
            onProgress?.invoke("Chapter ${chapterIndex + 1}/$totalChapters: Pass 1 - Page $pageNum/${pages.size} - Extracting characters...")

            // Pass 1: Extract character names from this page (skip if already done)
            val pageCharacterNames: List<String>
            if (pageIndex < pass1StartPageIndex) {
                // This page's Pass-1 was already completed via checkpoint
                pageCharacterNames = accumulatedCharacters.values
                    .filter { it.pageTexts.any { pt -> pt == pageText } }
                    .map { it.name }
                AppLogger.d(TAG, "   Pass-1: Skipped (checkpoint), ${pageCharacterNames.size} characters from previous run")
            } else {
                AppLogger.d(TAG, "   Pass-1: Extracting character names...")
                val pass1StartMs = System.currentTimeMillis()
                // Truncate to 5000 chars for Pass-1 - character names appear early in text
                // and don't require full context. Reduces prompt processing by ~50%.
                val truncatedPageText = pageText.take(5000)
                pageCharacterNames = try {
                    LlmService.pass1ExtractCharacterNames(truncatedPageText)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "   ❌ Pass-1 FAILED for page $pageNum", e)
                    emptyList()
                }
                AppLogger.d(TAG, "   Pass-1: Found ${pageCharacterNames.size} characters in ${System.currentTimeMillis() - pass1StartMs}ms: $pageCharacterNames")

                // Add new characters to accumulated map and track page texts
                val newlyDiscoveredNames = mutableListOf<String>()
                for (name in pageCharacterNames) {
                    val key = name.lowercase()
                    if (!accumulatedCharacters.containsKey(key)) {
                        accumulatedCharacters[key] = AccumulatedCharacter(name)
                        newlyDiscoveredNames.add(name)
                        AppLogger.d(TAG, "   New character discovered: $name")
                    }
                    // Track this page text for context aggregation in Pass-3
                    val accChar = accumulatedCharacters[key]
                    if (accChar != null && accChar.pageTexts.sumOf { it.length } < MAX_CONTEXT_PER_CHAR) {
                        accChar.pageTexts.add(pageText)
                    }
                }

                // Save newly discovered characters to database immediately for UI visibility
                if (newlyDiscoveredNames.isNotEmpty()) {
                    saveCharactersIncrementally(bookId, newlyDiscoveredNames)
                }

                // Save checkpoint after Pass-1 for this page
                if (context != null) {
                    saveCheckpoint(AnalysisCheckpoint(
                        bookId = bookId,
                        chapterIndex = chapterIndex,
                        timestamp = System.currentTimeMillis(),
                        chapterTextHash = chapterTextHash,
                        lastCompletedPass = 1,
                        lastCompletedPage = pageIndex,
                        accumulatedCharacters = toSerializable(accumulatedCharacters),
                        pass3CompletedCharacters = pass3CompletedChars
                    ))
                }
            }

            if (pageCharacterNames.isEmpty()) {
                AppLogger.d(TAG, "   Page $pageNum: No characters found, skipping Pass-2")
                return@forEachIndexed
            }

            // Pass-2: Extract dialogs from this page (skip if already done)
            if (pageIndex < pass2StartPageIndex) {
                AppLogger.d(TAG, "   Pass-2: Skipped (checkpoint)")
            } else {
                AppLogger.d(TAG, "   Pass-2: Extracting dialogs...")
                onProgress?.invoke("Chapter ${chapterIndex + 1}/$totalChapters: Pass 2 - Page $pageNum/${pages.size} - Extracting dialogs...")
                val pass2StartMs = System.currentTimeMillis()

                val dialogEntries = try {
                    LlmService.pass2ExtractDialogs(pageText, pageCharacterNames)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "   ❌ Pass-2 FAILED for page $pageNum", e)
                    emptyList()
                }

                // Merge dialogs into accumulated characters and track for incremental DB update
                val pageDialogsByCharacter = mutableMapOf<String, MutableList<ExtractedDialog>>()
                for (entry in dialogEntries) {
                    val speakerKey = entry.speaker.lowercase()
                    val accChar = accumulatedCharacters[speakerKey]
                    val dialog = ExtractedDialog(
                        pageNumber = pageNum,
                        text = entry.text,
                        emotion = entry.emotion,
                        intensity = entry.intensity
                    )
                    if (accChar != null) {
                        accChar.dialogs.add(dialog)
                        pageDialogsByCharacter.getOrPut(accChar.name) { mutableListOf() }.add(dialog)
                    } else if (speakerKey == "narrator") {
                        // Create or get Narrator as a character
                        val narratorKey = "narrator"
                        if (!accumulatedCharacters.containsKey(narratorKey)) {
                            accumulatedCharacters[narratorKey] = AccumulatedCharacter("Narrator")
                            // Also save Narrator to database if newly created
                            saveCharactersIncrementally(bookId, listOf("Narrator"))
                        }
                        accumulatedCharacters[narratorKey]?.dialogs?.add(dialog)
                        pageDialogsByCharacter.getOrPut("Narrator") { mutableListOf() }.add(dialog)
                    }
                    // Note: "Unknown" speaker dialogs are skipped for now
                }
                AppLogger.d(TAG, "   Pass-2: Extracted ${dialogEntries.size} dialogs in ${System.currentTimeMillis() - pass2StartMs}ms")

                // Update character records in database with new dialogs
                if (pageDialogsByCharacter.isNotEmpty()) {
                    updateCharactersWithDialogs(bookId, pageDialogsByCharacter)
                }

                // Save checkpoint after Pass-2 for this page
                if (context != null) {
                    saveCheckpoint(AnalysisCheckpoint(
                        bookId = bookId,
                        chapterIndex = chapterIndex,
                        timestamp = System.currentTimeMillis(),
                        chapterTextHash = chapterTextHash,
                        lastCompletedPass = 2,
                        lastCompletedPage = pageIndex,
                        accumulatedCharacters = toSerializable(accumulatedCharacters),
                        pass3CompletedCharacters = pass3CompletedChars
                    ))
                }
            }
            AppLogger.i(TAG, "✅ Page $pageNum complete in ${System.currentTimeMillis() - pageStartMs}ms")
        }

        // ============ After all pages: Run Pass-3 for all accumulated characters ============
        AppLogger.i(TAG, "📊 All pages processed. Accumulated characters: ${accumulatedCharacters.keys}")
        val characterList = accumulatedCharacters.values.toList()
        if (characterList.isEmpty()) {
            AppLogger.w(TAG, "⚠️ No characters found in chapter, skipping Pass-3")
            deleteCheckpoint(bookId, chapterIndex)  // Clean up empty checkpoint
            return@withContext emptyList()
        }

        // Filter out characters already processed in Pass-3 (from checkpoint)
        val charsToProcess = characterList.filter { it.name.lowercase() !in pass3CompletedChars }
        val alreadyProcessedCount = characterList.size - charsToProcess.size
        if (alreadyProcessedCount > 0) {
            AppLogger.i(TAG, "📂 Skipping $alreadyProcessedCount characters already processed in Pass-3")
        }

        AppLogger.i(TAG, "🎭 Starting Pass-3 for ${charsToProcess.size} characters: ${charsToProcess.map { it.name }}")
        onProgress?.invoke("Chapter ${chapterIndex + 1}/$totalChapters: Pass 3 - Analyzing ${charsToProcess.size} characters...")

        // Pass-3: Extract traits + voice profile using aggregated context (2 characters at a time)
        val pass3StartMs = System.currentTimeMillis()
        val pass3Results = mutableListOf<CharacterAnalysisResult>()
        var completedCount = alreadyProcessedCount

        // Add results for already-processed characters from checkpoint
        for (charKey in pass3CompletedChars) {
            val accChar = accumulatedCharacters[charKey] ?: continue
            pass3Results.add(CharacterAnalysisResult(
                name = accChar.name,
                traits = accChar.traits,
                personality = emptyList(),
                voiceProfile = accChar.voiceProfile,
                dialogs = accChar.dialogs.toList()
            ))
        }

        // Process remaining characters in batches of 4 for efficiency
        var i = 0
        while (i < charsToProcess.size) {
            val char1 = charsToProcess[i]
            val char2 = if (i + 1 < charsToProcess.size) charsToProcess[i + 1] else null
            val char3 = if (i + 2 < charsToProcess.size) charsToProcess[i + 2] else null
            val char4 = if (i + 3 < charsToProcess.size) charsToProcess[i + 3] else null

            // Build aggregated context for each character (full context preserved up to MAX_CONTEXT_PER_CHAR)
            val context1 = char1.pageTexts.joinToString("\n\n---\n\n").take(MAX_CONTEXT_PER_CHAR)
            val context2 = char2?.pageTexts?.joinToString("\n\n---\n\n")?.take(MAX_CONTEXT_PER_CHAR)
            val context3 = char3?.pageTexts?.joinToString("\n\n---\n\n")?.take(MAX_CONTEXT_PER_CHAR)
            val context4 = char4?.pageTexts?.joinToString("\n\n---\n\n")?.take(MAX_CONTEXT_PER_CHAR)

            val charNames = listOfNotNull(char1, char2, char3, char4).joinToString(", ") { "'${it.name}'" }
            val contextLengths = listOfNotNull(context1.length, context2?.length, context3?.length, context4?.length).joinToString(", ")
            AppLogger.d(TAG, "   Pass-3: Processing $charNames (context: $contextLengths chars)")

            try {
                val results = LlmService.pass3ExtractTraitsAndVoiceProfile(
                    char1Name = char1.name,
                    char1Context = context1,
                    char2Name = char2?.name,
                    char2Context = context2,
                    char3Name = char3?.name,
                    char3Context = context3,
                    char4Name = char4?.name,
                    char4Context = context4
                )

                // Process results for each character
                for ((name, data) in results) {
                    val (traits, voiceProfile) = data
                    val accChar = accumulatedCharacters[name.lowercase()] ?: continue

                    // Apply fallback if no traits
                    val finalTraits = if (traits.isEmpty()) {
                        AppLogger.d(TAG, "   Pass-3: '$name' no traits, using fallback")
                        LlmService.inferTraitsFromName(name)
                    } else {
                        traits
                    }

                    // Update accumulated character for checkpoint
                    accChar.traits = finalTraits
                    accChar.voiceProfile = voiceProfile
                    pass3CompletedChars.add(name.lowercase())

                    completedCount++
                    AppLogger.d(TAG, "   Pass-3: '$name' -> ${finalTraits.size} traits, voiceProfile keys: ${voiceProfile.keys}")
                    onProgress?.invoke("Chapter ${chapterIndex + 1}/$totalChapters: Pass 3 - $completedCount/${characterList.size} characters")
                    onCharacterProcessed?.invoke(name)

                    pass3Results.add(CharacterAnalysisResult(
                        name = name,
                        traits = finalTraits,
                        personality = emptyList(),
                        voiceProfile = voiceProfile,
                        dialogs = accChar.dialogs.toList()
                    ))
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "   ❌ Pass-3 FAILED for $charNames", e)
                // Add fallback results for failed characters
                listOfNotNull(char1, char2, char3, char4).forEach { failedChar ->
                    val fallbackTraits = LlmService.inferTraitsFromName(failedChar.name)
                    failedChar.traits = fallbackTraits
                    pass3CompletedChars.add(failedChar.name.lowercase())
                    completedCount++
                    pass3Results.add(CharacterAnalysisResult(
                        name = failedChar.name,
                        traits = fallbackTraits,
                        personality = emptyList(),
                        voiceProfile = emptyMap(),
                        dialogs = failedChar.dialogs.toList()
                    ))
                }
            }

            // Save checkpoint after each character batch completes Pass-3
            if (context != null) {
                saveCheckpoint(AnalysisCheckpoint(
                    bookId = bookId,
                    chapterIndex = chapterIndex,
                    timestamp = System.currentTimeMillis(),
                    chapterTextHash = chapterTextHash,
                    lastCompletedPass = 3,
                    lastCompletedPage = pages.size - 1,  // All pages complete
                    accumulatedCharacters = toSerializable(accumulatedCharacters),
                    pass3CompletedCharacters = pass3CompletedChars.toSet()
                ))
            }

            i += 4 // Move to next batch of 4
        }
        AppLogger.i(TAG, "✅ Pass-3 complete in ${System.currentTimeMillis() - pass3StartMs}ms, results: ${pass3Results.size}")

        // Save to database
        AppLogger.i(TAG, "💾 Saving ${pass3Results.size} characters to database...")
        onProgress?.invoke("Chapter ${chapterIndex + 1}/$totalChapters: Saving ${pass3Results.size} characters...")
        try {
            saveCharacters(bookId, pass3Results)
            AppLogger.i(TAG, "✅ Characters saved successfully")
            // Delete checkpoint after successful database save
            deleteCheckpoint(bookId, chapterIndex)
        } catch (e: Exception) {
            AppLogger.e(TAG, "❌ saveCharacters FAILED", e)
            throw e
        }

        val totalDurationMs = System.currentTimeMillis() - totalStartMs
        AppLogger.i(TAG, "🎉 analyzeChapter() COMPLETE - ${pass3Results.size} characters in ${totalDurationMs}ms")
        AppLogger.logPerformance(TAG, "Total 3-pass analysis (chapter ${chapterIndex + 1})", totalDurationMs)
        pass3Results
    }

    /**
     * Save characters to database, merging traits with existing characters.
     */
    private suspend fun saveCharacters(bookId: Long, results: List<CharacterAnalysisResult>) {
        val existingCharacters = characterDao.getByBookId(bookId).first()
        val byName = existingCharacters.associateBy { it.name.lowercase() }

        for (result in results) {
            val existing = byName[result.name.lowercase()]

            // Prepare traits and personality as comma-separated strings
            val traitsStr: String
            val personalitySummary: String

            if (existing != null) {
                // Merge traits: combine old + new, remove duplicates
                val oldTraits = existing.traits.split(",").map { it.trim() }.filter { it.isNotBlank() }
                val mergedTraits = (oldTraits + result.traits).distinct()
                traitsStr = mergedTraits.joinToString(",")

                // Merge personality
                val oldPersonality = existing.personalitySummary.split(";").map { it.trim() }.filter { it.isNotBlank() }
                val mergedPersonality = (oldPersonality + result.personality).distinct()
                personalitySummary = mergedPersonality.joinToString("; ")
            } else {
                traitsStr = result.traits.joinToString(",")
                personalitySummary = result.personality.joinToString("; ")
            }

            // Extract gender/age from voice_profile for enhanced speaker matching
            val enhancedTraits = result.traits.toMutableList()
            val gender = result.voiceProfile["gender"]?.toString()?.lowercase()
            val age = result.voiceProfile["age"]?.toString()?.lowercase()
            if (gender != null && gender !in enhancedTraits.map { it.lowercase() }) {
                enhancedTraits.add(gender)
            }
            if (age != null && age !in enhancedTraits.map { it.lowercase() }) {
                enhancedTraits.add(age)
            }

            // Suggest speaker ID using SpeakerMatcher
            val suggestedSpeakerId = SpeakerMatcher.suggestSpeakerIdFromTraitList(
                enhancedTraits,
                personalitySummary,
                result.name
            ) ?: run {
                // Deterministic fallback based on character name hash
                // This ensures the same character always gets the same speaker
                val nameHash = kotlin.math.abs(result.name.hashCode())
                val speakerRange = LibrittsSpeakerCatalog.MAX_SPEAKER_ID - LibrittsSpeakerCatalog.MIN_SPEAKER_ID + 1
                LibrittsSpeakerCatalog.MIN_SPEAKER_ID + (nameHash % speakerRange)
            }

            val voiceProfileJson = gson.toJson(result.voiceProfile)

            // Serialize dialogs to JSON for storage
            val dialogsJson = if (result.dialogs.isNotEmpty()) {
                gson.toJson(result.dialogs)
            } else {
                null
            }

            if (existing != null) {
                // Merge dialogs with existing (append new, avoid exact duplicates)
                val mergedDialogsJson = if (dialogsJson != null) {
                    if (existing.dialogsJson.isNullOrBlank()) {
                        dialogsJson
                    } else {
                        // Parse and merge dialogs
                        try {
                            val existingDialogs = gson.fromJson(existing.dialogsJson, Array<ExtractedDialog>::class.java).toMutableList()
                            val newDialogs = result.dialogs.filter { newDialog ->
                                existingDialogs.none { it.pageNumber == newDialog.pageNumber && it.text == newDialog.text }
                            }
                            existingDialogs.addAll(newDialogs)
                            gson.toJson(existingDialogs)
                        } catch (e: Exception) {
                            dialogsJson // Fallback to just new dialogs if parsing fails
                        }
                    }
                } else {
                    existing.dialogsJson
                }

                // Update existing character with merged data
                characterDao.update(
                    existing.copy(
                        traits = traitsStr,
                        personalitySummary = personalitySummary,
                        voiceProfileJson = voiceProfileJson,
                        speakerId = suggestedSpeakerId,
                        dialogsJson = mergedDialogsJson
                    )
                )
                AppLogger.d(TAG, "Updated character: ${result.name} (speakerId=$suggestedSpeakerId, dialogs=${result.dialogs.size})")
            } else {
                // Insert new character
                val newCharacter = Character(
                    bookId = bookId,
                    name = result.name,
                    traits = traitsStr,
                    personalitySummary = personalitySummary,
                    voiceProfileJson = voiceProfileJson,
                    speakerId = suggestedSpeakerId,
                    dialogsJson = dialogsJson
                )
                characterDao.insert(newCharacter)
                AppLogger.d(TAG, "Inserted character: ${result.name} (speakerId=$suggestedSpeakerId, dialogs=${result.dialogs.size})")
            }
        }
    }

    /**
     * Delete all checkpoints for a book.
     * Called when a book is deleted to clean up checkpoint files.
     * @return Number of checkpoint files deleted
     */
    fun deleteCheckpointsForBook(bookId: Long): Int {
        val cacheDir = context?.cacheDir ?: return 0
        val checkpointDir = File(cacheDir, CHECKPOINT_DIR)
        if (!checkpointDir.exists()) return 0

        var deletedCount = 0
        checkpointDir.listFiles()?.forEach { file ->
            // Files are named: {bookId}_ch{chapterIndex}.json
            if (file.name.startsWith("${bookId}_ch")) {
                if (file.delete()) {
                    deletedCount++
                }
            }
        }
        AppLogger.i(TAG, "Deleted $deletedCount 3-pass checkpoints for book $bookId")
        return deletedCount
    }
}
