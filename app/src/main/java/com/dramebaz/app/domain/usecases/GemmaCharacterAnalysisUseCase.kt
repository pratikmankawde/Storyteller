package com.dramebaz.app.domain.usecases

import android.content.Context
import com.dramebaz.app.ai.llm.LiteRtLmEngine
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
 * Two-Pass Character Analysis Workflow using Gemma 3n E2B Lite model.
 *
 * Workflow:
 * - Pass 1: Extract character names only; output {"characters": ["Name1", "Name2", ...]}
 * - Pass 2: Extract dialogs as array of {character: dialog} mappings, in order of appearance
 * - Random LibriTTS speaker IDs (0–903) are assigned to each character when no traits match
 *
 * Features:
 * - Uses LiteRT-LM for on-device Gemma model inference
 * - GPU acceleration with CPU fallback
 * - Text segmentation with boundary-aware splitting
 * - Token limit error handling with retry logic
 * - SpeakerMatcher integration for VCTK speaker ID assignment (0-108)
 * - Database persistence with checkpoint resume capability
 */
class GemmaCharacterAnalysisUseCase(
    private val characterDao: CharacterDao,
    private val context: Context
) {
    companion object {
        private const val TAG = "GemmaCharAnalysis"
        private const val CHECKPOINT_DIR = "gemma_analysis_checkpoints"
        private const val CHECKPOINT_EXPIRY_HOURS = 24L
    }

    private val gson = Gson()
    private val checkpointMutex = Mutex()
    private var engine: LiteRtLmEngine? = null
    private var engineInitialized = false

    /**
     * Data class holding the complete analysis result for a single character.
     */
    data class CharacterAnalysisResult(
        val name: String,
        val traits: List<String>,
        val voiceProfile: Map<String, Any>,
        val dialogs: List<ExtractedDialog> = emptyList()
    )

    /**
     * Extracted dialog entry with segment index for persistence.
     */
    data class ExtractedDialog(
        val segmentIndex: Int,
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
        val chapterTextHash: Int,
        val lastCompletedPass: Int,  // 0=none, 1=Pass-1, 2=complete
        val lastCompletedSegment: Int,
        val extractedCharacters: Map<String, SerializableCharacter>,
        val pass2CompletedSegments: Set<Int> = emptySet()
    )

    data class SerializableCharacter(
        val name: String,
        val traits: List<String> = emptyList(),
        val voiceProfile: Map<String, Any> = emptyMap(),
        val dialogs: List<ExtractedDialog> = emptyList()
    )

    /**
     * Initialize the Gemma engine. Call before running analysis.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (engineInitialized) return@withContext true

        try {
            engine = LiteRtLmEngine(context)
            val success = engine!!.initialize()
            engineInitialized = success
            if (success) {
                AppLogger.i(TAG, "Gemma engine initialized: ${engine!!.getExecutionProvider()}")
            } else {
                AppLogger.e(TAG, "Failed to initialize Gemma engine")
            }
            success
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error initializing Gemma engine", e)
            false
        }
    }

    fun isReady(): Boolean = engineInitialized && engine?.isModelLoaded() == true

    fun isUsingGpu(): Boolean = engine?.isUsingGpu() ?: false

    fun getExecutionProvider(): String = engine?.getExecutionProvider() ?: "Not initialized"

    /**
     * Run the complete 2-pass analysis workflow for a chapter.
     *
     * @param bookId Book ID for storing characters
     * @param chapterText Full chapter text to analyze
     * @param chapterIndex Current chapter index (for logging)
     * @param totalChapters Total chapters in book (for logging)
     * @param onProgress Progress callback with message
     * @param onCharacterProcessed Callback when a character is processed
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
        AppLogger.i(TAG, "🚀 analyzeChapter() - bookId=$bookId, chapter=${chapterIndex + 1}/$totalChapters")
        val totalStartMs = System.currentTimeMillis()
        val trimmed = chapterText.trim()

        if (trimmed.isBlank()) {
            AppLogger.w(TAG, "⚠️ Chapter text empty, skipping")
            return@withContext emptyList()
        }

        if (!initialize()) {
            AppLogger.e(TAG, "❌ Gemma engine not initialized")
            return@withContext emptyList()
        }

        // Report which backend is being used (GPU or CPU)
        val backendInfo = engine!!.getExecutionProvider()
        AppLogger.i(TAG, "🖥️ Using backend: $backendInfo")
        onProgress?.invoke("Chapter ${chapterIndex + 1}/$totalChapters: Initializing ($backendInfo)...")

        val chapterTextHash = trimmed.hashCode()
        val checkpoint = loadCheckpoint(bookId, chapterIndex, chapterTextHash)

        // Initialize state from checkpoint or fresh
        val extractedCharacters = checkpoint?.extractedCharacters?.toMutableMap() ?: mutableMapOf()
        val pass2CompletedSegments = checkpoint?.pass2CompletedSegments?.toMutableSet() ?: mutableSetOf()
        val pass1StartSegment = checkpoint?.let {
            if (it.lastCompletedPass >= 1) Int.MAX_VALUE else it.lastCompletedSegment + 1
        } ?: 0

        // Segment text for Pass 1 (~3000 tokens each)
        val pass1Segments = engine!!.segmentTextForPass1(trimmed)
        AppLogger.i(TAG, "📄 Text split into ${pass1Segments.size} segments for Pass 1")

        // ============ Pass 1: Extract characters and voice profiles ============
        if (pass1StartSegment < pass1Segments.size) {
            AppLogger.i(TAG, "🔄 Starting Pass 1 from segment ${pass1StartSegment + 1}/${pass1Segments.size}")
            onProgress?.invoke("Chapter ${chapterIndex + 1}/$totalChapters: Pass 1 - Extracting characters...")

            for (segmentIndex in pass1StartSegment until pass1Segments.size) {
                val segmentText = pass1Segments[segmentIndex]
                AppLogger.d(TAG, "   Pass-1: Segment ${segmentIndex + 1}/${pass1Segments.size} (${segmentText.length} chars)")
                onProgress?.invoke("Chapter ${chapterIndex + 1}/$totalChapters: Pass 1 - Segment ${segmentIndex + 1}/${pass1Segments.size} - Extracting characters...")

                try {
                    val characters = engine!!.pass1ExtractCharactersAndVoiceProfiles(segmentText)

                    for (charResult in characters) {
                        val key = charResult.name.lowercase()
                        if (!extractedCharacters.containsKey(key)) {
                            extractedCharacters[key] = SerializableCharacter(
                                name = charResult.name,
                                traits = charResult.traits,
                                voiceProfile = charResult.voiceProfile
                            )
                            // Save to database immediately for UI visibility
                            saveCharacterToDatabase(bookId, charResult.name, charResult.traits, charResult.voiceProfile)
                            onCharacterProcessed?.invoke(charResult.name)
                            AppLogger.d(TAG, "   ✅ New character: ${charResult.name}")
                        }
                    }

                    // Save checkpoint after each segment
                    saveCheckpoint(AnalysisCheckpoint(
                        bookId = bookId,
                        chapterIndex = chapterIndex,
                        timestamp = System.currentTimeMillis(),
                        chapterTextHash = chapterTextHash,
                        lastCompletedPass = 1,
                        lastCompletedSegment = segmentIndex,
                        extractedCharacters = extractedCharacters.toMap(),
                        pass2CompletedSegments = pass2CompletedSegments.toSet()
                    ))
                } catch (e: Exception) {
                    AppLogger.e(TAG, "   ❌ Pass-1 failed for segment ${segmentIndex + 1}", e)
                }
            }
        }

        val characterNames = extractedCharacters.values.map { it.name }
        AppLogger.i(TAG, "📊 Pass 1 complete: ${characterNames.size} characters found")

        // Save Pass 1 results to database
        onProgress?.invoke("Chapter ${chapterIndex + 1}/$totalChapters: Pass 1 complete - Saving ${characterNames.size} characters...")
        for ((_, charData) in extractedCharacters) {
            saveCharacterToDatabase(bookId, charData.name, charData.traits, charData.voiceProfile)
        }
        AppLogger.i(TAG, "💾 Pass 1 saved to database: ${characterNames.size} characters")

        // ============ Pass 2: Extract dialogs with speaker attribution ============
        // Segment text for Pass 2 (~1500 tokens each)
        val pass2Segments = engine!!.segmentTextForPass2(trimmed)
        AppLogger.i(TAG, "🔄 Starting Pass 2: ${pass2Segments.size} segments")
        onProgress?.invoke("Chapter ${chapterIndex + 1}/$totalChapters: Pass 2 - Extracting dialogs (${pass2Segments.size} segments)...")

        for (segmentIndex in 0 until pass2Segments.size) {
            if (segmentIndex in pass2CompletedSegments) {
                AppLogger.d(TAG, "   Pass-2: Segment ${segmentIndex + 1} already processed, skipping")
                continue
            }

            val segmentText = pass2Segments[segmentIndex]
            AppLogger.d(TAG, "   Pass-2: Segment ${segmentIndex + 1}/${pass2Segments.size} (${segmentText.length} chars)")
            onProgress?.invoke("Chapter ${chapterIndex + 1}/$totalChapters: Pass 2 - Segment ${segmentIndex + 1}/${pass2Segments.size} - Extracting dialogs...")

            try {
                val dialogs = engine!!.pass2ExtractDialogs(segmentText, characterNames + listOf("Narrator"))

                // Assign dialogs to characters
                for (dialog in dialogs) {
                    val speakerKey = dialog.speaker.lowercase()
                    val charData = extractedCharacters[speakerKey]
                    if (charData != null) {
                        val updatedDialogs = charData.dialogs + ExtractedDialog(
                            segmentIndex = segmentIndex,
                            text = dialog.text,
                            emotion = dialog.emotion,
                            intensity = dialog.intensity
                        )
                        extractedCharacters[speakerKey] = charData.copy(dialogs = updatedDialogs)
                    } else if (speakerKey == "narrator") {
                        // Add Narrator if not exists
                        val narratorData = extractedCharacters.getOrPut("narrator") {
                            SerializableCharacter(
                                name = "Narrator",
                                traits = listOf("narrative_voice", "neutral"),
                                voiceProfile = mapOf(
                                    "pitch" to 1.0, "speed" to 1.0, "energy" to 0.5,
                                    "gender" to "neutral", "age" to "middle-aged",
                                    "tone" to "neutral", "accent" to "neutral", "speaker_id" to 45
                                )
                            )
                        }
                        val updatedDialogs = narratorData.dialogs + ExtractedDialog(
                            segmentIndex = segmentIndex, text = dialog.text,
                            emotion = dialog.emotion, intensity = dialog.intensity
                        )
                        extractedCharacters["narrator"] = narratorData.copy(dialogs = updatedDialogs)
                    }
                }

                pass2CompletedSegments.add(segmentIndex)

                // Save checkpoint after each segment
                saveCheckpoint(AnalysisCheckpoint(
                    bookId = bookId,
                    chapterIndex = chapterIndex,
                    timestamp = System.currentTimeMillis(),
                    chapterTextHash = chapterTextHash,
                    lastCompletedPass = 2,
                    lastCompletedSegment = segmentIndex,
                    extractedCharacters = extractedCharacters.toMap(),
                    pass2CompletedSegments = pass2CompletedSegments.toSet()
                ))

                AppLogger.d(TAG, "   Pass-2: Extracted ${dialogs.size} dialogs from segment ${segmentIndex + 1}")
            } catch (e: Exception) {
                AppLogger.e(TAG, "   ❌ Pass-2 failed for segment ${segmentIndex + 1}", e)
            }
        }

        // Pass 2 complete - notify progress
        val totalDialogs = extractedCharacters.values.sumOf { it.dialogs.size }
        AppLogger.i(TAG, "📊 Pass 2 complete: $totalDialogs dialogs extracted")
        onProgress?.invoke("Chapter ${chapterIndex + 1}/$totalChapters: Pass 2 complete - $totalDialogs dialogs found")

        // ============ Final Database Persistence ============
        AppLogger.i(TAG, "💾 Persisting ${extractedCharacters.size} characters to database...")
        onProgress?.invoke("Chapter ${chapterIndex + 1}/$totalChapters: Saving ${extractedCharacters.size} characters to database...")

        val results = mutableListOf<CharacterAnalysisResult>()
        for ((_, charData) in extractedCharacters) {
            val result = CharacterAnalysisResult(
                name = charData.name,
                traits = charData.traits,
                voiceProfile = charData.voiceProfile,
                dialogs = charData.dialogs
            )
            results.add(result)
            saveCharacterFinal(bookId, result)
        }

        // Clear checkpoint on successful completion
        deleteCheckpoint(bookId, chapterIndex)

        val elapsed = System.currentTimeMillis() - totalStartMs
        AppLogger.i(TAG, "✅ Analysis complete: ${results.size} characters in ${elapsed}ms")
        results
    }

    // ==================== Database Persistence ====================

    /**
     * Save character to database during Pass 1 for immediate UI visibility.
     */
    private suspend fun saveCharacterToDatabase(
        bookId: Long,
        name: String,
        traits: List<String>,
        voiceProfile: Map<String, Any>
    ) {
        val existingCharacters = characterDao.getByBookId(bookId).first()
        val existing = existingCharacters.find { it.name.equals(name, ignoreCase = true) }

        if (existing == null) {
            val traitsStr = traits.joinToString(",")
            val voiceProfileJson = gson.toJson(voiceProfile)
            val speakerId = (voiceProfile["speaker_id"] as? Number)?.toInt()
                ?: (LibrittsSpeakerCatalog.MIN_SPEAKER_ID..LibrittsSpeakerCatalog.MAX_SPEAKER_ID).random()
            characterDao.insert(
                Character(
                    bookId = bookId,
                    name = name,
                    traits = traitsStr,
                    personalitySummary = "",
                    voiceProfileJson = voiceProfileJson,
                    speakerId = speakerId,
                    dialogsJson = null
                )
            )
            AppLogger.d(TAG, "💾 Saved new character: $name (speakerId=$speakerId)")
        }
    }

    /**
     * Final database persistence with voice profile and dialogs.
     */
    private suspend fun saveCharacterFinal(bookId: Long, result: CharacterAnalysisResult) {
        val existingCharacters = characterDao.getByBookId(bookId).first()
        val existing = existingCharacters.find { it.name.equals(result.name, ignoreCase = true) }

        val traitsStr = result.traits.joinToString(",")
        val voiceProfileJson = gson.toJson(result.voiceProfile)
        val dialogsJson = gson.toJson(result.dialogs)

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

        val suggestedSpeakerId = SpeakerMatcher.suggestSpeakerIdFromTraitList(
            enhancedTraits,
            "",
            result.name
        ) ?: (LibrittsSpeakerCatalog.MIN_SPEAKER_ID..LibrittsSpeakerCatalog.MAX_SPEAKER_ID).random()

        if (existing != null) {
            // Merge dialogs with existing ones
            val mergedDialogsJson = if (existing.dialogsJson.isNullOrBlank()) {
                dialogsJson
            } else {
                try {
                    val existingDialogs = gson.fromJson(existing.dialogsJson, Array<ExtractedDialog>::class.java).toMutableList()
                    val uniqueNewDialogs = result.dialogs.filter { newDialog ->
                        existingDialogs.none { it.segmentIndex == newDialog.segmentIndex && it.text == newDialog.text }
                    }
                    existingDialogs.addAll(uniqueNewDialogs)
                    gson.toJson(existingDialogs)
                } catch (e: Exception) {
                    dialogsJson
                }
            }

            characterDao.update(
                existing.copy(
                    traits = traitsStr,
                    voiceProfileJson = voiceProfileJson,
                    speakerId = suggestedSpeakerId ?: existing.speakerId,
                    dialogsJson = mergedDialogsJson
                )
            )
            AppLogger.d(TAG, "💾 Updated character: ${result.name} (speakerId=$suggestedSpeakerId)")
        } else {
            characterDao.insert(
                Character(
                    bookId = bookId,
                    name = result.name,
                    traits = traitsStr,
                    personalitySummary = "",
                    voiceProfileJson = voiceProfileJson,
                    speakerId = suggestedSpeakerId,
                    dialogsJson = dialogsJson
                )
            )
            AppLogger.d(TAG, "💾 Inserted character: ${result.name} (speakerId=$suggestedSpeakerId)")
        }
    }

    // ==================== Checkpoint Management ====================

    private fun getCheckpointFile(bookId: Long, chapterIndex: Int): File {
        val checkpointDir = File(context.filesDir, CHECKPOINT_DIR)
        return File(checkpointDir, "gemma_checkpoint_${bookId}_${chapterIndex}.json")
    }

    private suspend fun loadCheckpoint(bookId: Long, chapterIndex: Int, chapterTextHash: Int): AnalysisCheckpoint? {
        return checkpointMutex.withLock {
            val checkpointFile = getCheckpointFile(bookId, chapterIndex)
            if (!checkpointFile.exists()) return@withLock null

            try {
                val json = checkpointFile.readText()
                val checkpoint = gson.fromJson(json, AnalysisCheckpoint::class.java)

                // Validate checkpoint hash
                if (checkpoint.chapterTextHash != chapterTextHash) {
                    AppLogger.w(TAG, "Checkpoint hash mismatch, discarding")
                    checkpointFile.delete()
                    return@withLock null
                }

                // Check expiry
                val age = System.currentTimeMillis() - checkpoint.timestamp
                if (age > CHECKPOINT_EXPIRY_HOURS * 3600 * 1000) {
                    AppLogger.w(TAG, "Checkpoint expired, discarding")
                    checkpointFile.delete()
                    return@withLock null
                }

                AppLogger.i(TAG, "📂 Loaded checkpoint: pass=${checkpoint.lastCompletedPass}, segment=${checkpoint.lastCompletedSegment}")
                checkpoint
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load checkpoint", e)
                null
            }
        }
    }

    private suspend fun saveCheckpoint(checkpoint: AnalysisCheckpoint) {
        checkpointMutex.withLock {
            try {
                val checkpointFile = getCheckpointFile(checkpoint.bookId, checkpoint.chapterIndex)
                checkpointFile.parentFile?.mkdirs()
                val json = gson.toJson(checkpoint)
                checkpointFile.writeText(json)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to save checkpoint", e)
            }
        }
    }

    private suspend fun deleteCheckpoint(bookId: Long, chapterIndex: Int) {
        checkpointMutex.withLock {
            try {
                val checkpointFile = getCheckpointFile(bookId, chapterIndex)
                if (checkpointFile.exists()) {
                    checkpointFile.delete()
                    AppLogger.d(TAG, "🗑️ Deleted checkpoint for book=$bookId, chapter=$chapterIndex")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to delete checkpoint", e)
            }
        }
    }

    // ==================== Cleanup ====================

    /**
     * Release engine resources. Call when done with analysis.
     */
    fun release() {
        engine?.release()
        engine = null
        engineInitialized = false
        AppLogger.i(TAG, "Gemma engine released")
    }
}
