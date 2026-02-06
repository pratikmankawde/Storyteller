package com.dramebaz.app.audio

import com.dramebaz.app.ai.tts.SherpaTtsEngine
import com.dramebaz.app.data.audio.PageAudioStorage
import com.dramebaz.app.data.db.CharacterDao
import com.dramebaz.app.data.db.CharacterPageMapping
import com.dramebaz.app.data.db.CharacterPageMappingDao
import com.dramebaz.app.data.models.Dialog
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AUG-043: Generates audio for individual page segments (dialogs and narration)
 * using character-specific speaker IDs.
 */
class SegmentAudioGenerator(
    private val ttsEngine: SherpaTtsEngine,
    private val pageAudioStorage: PageAudioStorage,
    private val characterDao: CharacterDao,
    private val characterPageMappingDao: CharacterPageMappingDao
) {
    private val tag = "SegmentAudioGenerator"
    private val defaultNarratorSpeakerId = 0  // Fallback speaker for narrator if not in database

    // Cache for narrator's speaker ID from database
    private var cachedNarratorSpeakerId: Int? = null

    /**
     * Data class for a segment to be generated.
     */
    data class PageSegment(
        val index: Int,
        val text: String,
        val characterName: String,  // "Narrator" for narration
        val isDialog: Boolean,
        val speakerId: Int?
    )

    /**
     * Generate audio for all segments on a page.
     * @param bookId Book ID
     * @param chapterId Chapter ID
     * @param pageNumber 1-based page number
     * @param pageText Full page text (used for narration extraction)
     * @param dialogs List of dialogs from chapter analysis that appear on this page
     * @param onSegmentGenerated Optional callback after each segment is generated
     * @return List of generated audio files in order
     */
    suspend fun generatePageAudio(
        bookId: Long,
        chapterId: Long,
        pageNumber: Int,
        pageText: String,
        dialogs: List<Dialog>,
        onSegmentGenerated: ((Int, Int) -> Unit)? = null
    ): List<File> = withContext(Dispatchers.IO) {
        AppLogger.d(tag, "Generating audio for page $pageNumber: ${dialogs.size} dialogs, ${pageText.length} chars")

        // Build ordered list of segments (dialogs interspersed with narration)
        val segments = buildPageSegments(bookId, pageText, dialogs)
        AppLogger.d(tag, "Built ${segments.size} segments for page $pageNumber")

        val generatedFiles = mutableListOf<File>()
        val mappings = mutableListOf<CharacterPageMapping>()

        for ((index, segment) in segments.withIndex()) {
            try {
                // Check if already generated
                val existing = pageAudioStorage.getSegmentAudioFile(
                    bookId, chapterId, pageNumber,
                    segment.index, segment.characterName,
                    segment.speakerId ?: defaultNarratorSpeakerId
                )
                if (existing != null) {
                    AppLogger.d(tag, "Segment $index already generated: ${existing.name}")
                    generatedFiles.add(existing)
                    onSegmentGenerated?.invoke(index + 1, segments.size)
                    continue
                }

                // Generate audio with character's speaker ID
                val speakerId = segment.speakerId ?: defaultNarratorSpeakerId
                val result = ttsEngine.speak(segment.text, null, null, speakerId)

                result.onSuccess { audioFile ->
                    audioFile?.let { file ->
                        // Save to persistent storage
                        val saved = pageAudioStorage.saveSegmentAudioFile(
                            bookId, chapterId, pageNumber,
                            segment.index, segment.characterName,
                            speakerId, file
                        )
                        generatedFiles.add(saved)

                        // Create mapping entry
                        val mapping = CharacterPageMapping(
                            bookId = bookId,
                            chapterId = chapterId,
                            pageNumber = pageNumber,
                            segmentIndex = segment.index,
                            characterName = segment.characterName,
                            speakerId = speakerId,
                            dialogText = segment.text,
                            isDialog = segment.isDialog,
                            firstAppearance = false,  // TODO: Track first appearance
                            audioGenerated = true,
                            audioPath = pageAudioStorage.getSegmentRelativePath(
                                bookId, chapterId, pageNumber,
                                segment.index, segment.characterName, speakerId
                            )
                        )
                        mappings.add(mapping)

                        AppLogger.d(tag, "Generated segment $index: ${segment.characterName} (speaker $speakerId)")
                    }
                }.onFailure { error ->
                    AppLogger.e(tag, "Failed to generate segment $index", error)
                }

                onSegmentGenerated?.invoke(index + 1, segments.size)
            } catch (e: Exception) {
                AppLogger.e(tag, "Error generating segment $index", e)
            }
        }

        // Save all mappings to database
        if (mappings.isNotEmpty()) {
            characterPageMappingDao.insertAll(mappings)
            AppLogger.i(tag, "Saved ${mappings.size} segment mappings for page $pageNumber")
        }

        generatedFiles
    }

    /**
     * Build an ordered list of segments from page text and dialogs.
     * Extracts narration between dialogs and assigns character speaker IDs.
     */
    private suspend fun buildPageSegments(
        bookId: Long,
        pageText: String,
        dialogs: List<Dialog>
    ): List<PageSegment> {
        val segments = mutableListOf<PageSegment>()
        var segmentIndex = 0
        var lastDialogEnd = 0

        // Get narrator's speaker ID from database (falls back to default if not found)
        val narratorSpeakerId = getNarratorSpeakerId(bookId)

        // Cache character speaker IDs
        val speakerIdCache = mutableMapOf<String, Int?>()

        AppLogger.d(tag, "buildPageSegments: pageText=${pageText.length} chars, dialogs=${dialogs.size}")

        for (dialog in dialogs) {
            val dialogText = dialog.dialog.trim()
            if (dialogText.isBlank()) continue

            // Find dialog position in page text (try multiple matching strategies)
            var dialogStart = pageText.indexOf(dialogText, lastDialogEnd)
            var matchedText = dialogText

            // Try without quotes if exact match fails
            if (dialogStart < 0) {
                val quoteChars = "\"\u201C\u201D'\u2018\u2019".toCharArray()  // ", ", ", ', ', '
                val withoutQuotes = dialogText.trim(*quoteChars)
                if (withoutQuotes.isNotBlank() && withoutQuotes.length > 5) {
                    dialogStart = pageText.indexOf(withoutQuotes, lastDialogEnd)
                    if (dialogStart >= 0) matchedText = withoutQuotes
                }
            }

            // Try prefix match for long dialogs
            if (dialogStart < 0 && dialogText.length > 30) {
                val prefix = dialogText.take(30)
                val prefixStart = pageText.indexOf(prefix, lastDialogEnd)
                if (prefixStart >= 0) {
                    dialogStart = prefixStart
                    // Find the end of the dialog in page text (look for closing quote or period)
                    val remainingText = pageText.substring(prefixStart)
                    val endMatch = Regex("""[.!?]["']?\s""").find(remainingText.take(dialogText.length + 50))
                    matchedText = if (endMatch != null) {
                        remainingText.take(endMatch.range.last + 1).trim()
                    } else {
                        dialogText  // Fall back to original length
                    }
                }
            }

            if (dialogStart < 0) {
                AppLogger.d(tag, "Dialog not found in page: '${dialogText.take(50)}...'")
                continue
            }

            // Extract narration before this dialog
            if (dialogStart > lastDialogEnd) {
                val narration = pageText.substring(lastDialogEnd, dialogStart).trim()
                if (narration.isNotBlank()) {
                    segments.add(PageSegment(
                        index = segmentIndex++,
                        text = narration,
                        characterName = "Narrator",
                        isDialog = false,
                        speakerId = narratorSpeakerId
                    ))
                }
            }

            // Add dialog segment with character's speaker ID
            val speaker = dialog.speaker.takeIf { it.isNotBlank() && it != "unknown" } ?: "Narrator"
            val speakerId = getSpeakerIdForCharacter(bookId, speaker, speakerIdCache)

            segments.add(PageSegment(
                index = segmentIndex++,
                text = matchedText,
                characterName = speaker,
                isDialog = true,
                speakerId = speakerId
            ))

            AppLogger.d(tag, "Added dialog segment: speaker=$speaker, speakerId=$speakerId, text='${matchedText.take(30)}...'")

            lastDialogEnd = dialogStart + matchedText.length
        }

        // Add any remaining narration after last dialog
        if (lastDialogEnd < pageText.length) {
            val narration = pageText.substring(lastDialogEnd).trim()
            if (narration.isNotBlank()) {
                segments.add(PageSegment(
                    index = segmentIndex++,
                    text = narration,
                    characterName = "Narrator",
                    isDialog = false,
                    speakerId = narratorSpeakerId
                ))
            }
        }

        // If no dialogs matched, treat whole page as narration
        if (segments.isEmpty() && pageText.isNotBlank()) {
            AppLogger.d(tag, "No dialogs matched, treating page as single narration segment")
            segments.add(PageSegment(
                index = 0,
                text = pageText.trim(),
                characterName = "Narrator",
                isDialog = false,
                speakerId = narratorSpeakerId
            ))
        }

        AppLogger.i(tag, "Built ${segments.size} segments from ${dialogs.size} dialogs")

        return segments
    }

    /**
     * Get speaker ID for a character, caching results.
     * For Narrator, looks up from database to use user-configured speaker ID.
     * If character has no speakerId, generates a deterministic one from name hash.
     */
    private suspend fun getSpeakerIdForCharacter(
        bookId: Long,
        characterName: String,
        cache: MutableMap<String, Int?>
    ): Int? {
        return cache.getOrPut(characterName) {
            val character = characterDao.getByBookIdAndName(bookId, characterName)
            val speakerId = character?.speakerId
            if (speakerId != null) {
                AppLogger.d(tag, "Found speakerId $speakerId for character '$characterName' in database")
                speakerId
            } else if (characterName.equals("Narrator", ignoreCase = true)) {
                // Narrator uses default speaker ID 0
                AppLogger.d(tag, "Using default narrator speaker ID 0")
                defaultNarratorSpeakerId
            } else {
                // Generate deterministic speaker ID from character name hash (1-903, avoiding 0 which is narrator)
                val generatedId = generateSpeakerIdFromName(characterName)
                AppLogger.d(tag, "Generated speakerId $generatedId for character '$characterName' from name hash")
                generatedId
            }
        }
    }

    /**
     * Generate a deterministic speaker ID from character name.
     * Uses hash to ensure same character always gets same voice.
     * Returns speaker ID in range 1-903 (avoiding 0 which is reserved for narrator).
     */
    private fun generateSpeakerIdFromName(name: String): Int {
        val hash = kotlin.math.abs(name.hashCode())
        return 1 + (hash % 903)  // Range 1-903, avoiding narrator's 0
    }

    /**
     * Get the narrator's speaker ID from database, with caching.
     */
    private suspend fun getNarratorSpeakerId(bookId: Long): Int {
        cachedNarratorSpeakerId?.let { return it }

        val narrator = characterDao.getByBookIdAndName(bookId, "Narrator")
        val speakerId = narrator?.speakerId ?: defaultNarratorSpeakerId
        cachedNarratorSpeakerId = speakerId
        return speakerId
    }

    /**
     * Regenerate audio for specific segments (e.g., after speaker change).
     * @param segments List of CharacterPageMapping entries to regenerate
     * @param newSpeakerId New speaker ID to use
     * @return Number of segments regenerated
     */
    suspend fun regenerateSegments(
        segments: List<CharacterPageMapping>,
        newSpeakerId: Int
    ): Int = withContext(Dispatchers.IO) {
        var regeneratedCount = 0

        for (segment in segments) {
            try {
                // Delete old audio file
                segment.audioPath?.let { path ->
                    val oldFile = File(pageAudioStorage.getSegmentAudioFile(
                        segment.bookId, segment.chapterId, segment.pageNumber,
                        segment.segmentIndex, segment.characterName,
                        segment.speakerId ?: defaultNarratorSpeakerId
                    )?.parentFile, path)
                    if (oldFile.exists()) oldFile.delete()
                }

                // Generate with new speaker ID
                val result = ttsEngine.speak(segment.dialogText, null, null, newSpeakerId)

                result.onSuccess { audioFile ->
                    audioFile?.let { file ->
                        pageAudioStorage.saveSegmentAudioFile(
                            segment.bookId, segment.chapterId, segment.pageNumber,
                            segment.segmentIndex, segment.characterName,
                            newSpeakerId, file
                        )

                        // Update mapping in database
                        characterPageMappingDao.updateAudioStatus(
                            segment.id,
                            true,
                            pageAudioStorage.getSegmentRelativePath(
                                segment.bookId, segment.chapterId, segment.pageNumber,
                                segment.segmentIndex, segment.characterName, newSpeakerId
                            )
                        )

                        regeneratedCount++
                        AppLogger.d(tag, "Regenerated segment ${segment.segmentIndex} for ${segment.characterName} with speaker $newSpeakerId")
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(tag, "Failed to regenerate segment ${segment.segmentIndex}", e)
            }
        }

        AppLogger.i(tag, "Regenerated $regeneratedCount of ${segments.size} segments")
        regeneratedCount
    }

    /**
     * Get dialogs from chapter analysis that appear on the given page.
     * Uses fuzzy matching to handle slight differences in dialog text.
     */
    fun getDialogsForPage(pageText: String, allDialogs: List<Dialog>?): List<Dialog> {
        if (allDialogs.isNullOrEmpty()) {
            AppLogger.d(tag, "getDialogsForPage: No dialogs provided")
            return emptyList()
        }

        val normalizedPageText = normalizeText(pageText)
        val matchedDialogs = allDialogs.filter { dialog ->
            if (dialog.dialog.isBlank()) return@filter false

            // Try exact match first
            if (pageText.contains(dialog.dialog)) return@filter true

            // Try normalized match (handles whitespace, quotes, etc.)
            val normalizedDialog = normalizeText(dialog.dialog)
            if (normalizedPageText.contains(normalizedDialog)) return@filter true

            // Try matching without quotes (LLM might include/exclude quotes)
            val quoteChars = "\"\u201C\u201D'\u2018\u2019".toCharArray()  // ", ", ", ', ', '
            val dialogWithoutQuotes = dialog.dialog.trim(*quoteChars)
            if (dialogWithoutQuotes.isNotBlank() && pageText.contains(dialogWithoutQuotes)) return@filter true

            // Try first 50 chars as fallback for long dialogs
            if (dialog.dialog.length > 50) {
                val prefix = dialog.dialog.take(50)
                if (pageText.contains(prefix)) return@filter true
            }

            false
        }

        AppLogger.d(tag, "getDialogsForPage: Matched ${matchedDialogs.size} of ${allDialogs.size} dialogs (pageText: ${pageText.length} chars)")
        return matchedDialogs
    }

    /**
     * Normalize text for fuzzy matching.
     */
    private fun normalizeText(text: String): String {
        return text
            .replace(Regex("\\s+"), " ")  // Collapse whitespace
            .replace(Regex("[\u201C\u201D\u2018\u2019\"']"), "'")  // Normalize quotes (", ", ', ', ", ')
            .trim()
            .lowercase()
    }
}
