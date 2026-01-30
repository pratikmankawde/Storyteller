package com.dramebaz.app.domain.usecases

import com.dramebaz.app.ai.llm.QwenStub
import com.dramebaz.app.ai.tts.SpeakerMatcher
import com.dramebaz.app.data.db.Character
import com.dramebaz.app.data.db.CharacterDao
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Workflow: one LLM call per segment to extract all characters and their traits. Characters already
 * extracted with traits are skipped in later segments; characters without traits are filled when
 * the LLM finds traits in a later segment. Then suggest voice profile (TTS JSON), assign speaker
 * via SpeakerMatcher, store by book.
 * @param onProgress Optional callback for granular progress (message). Called from IO thread; run UI updates on Main.
 * @param onPageProcessed Optional callback invoked after each segment is processed (for progress bar). Run on Main in caller.
 */
class ChapterCharacterExtractionUseCase(private val characterDao: CharacterDao) {
    private val tag = "ChapterCharExtract"
    private val gson = Gson()

    /** Page size in chars for page-by-page detection (keep under LLM context). Larger = fewer LLM calls, faster Phase 1. */
    private val pageSizeChars = 5000

    /**
     * Run extraction for one chapter: detect characters per page, infer traits for new ones,
     * suggest voice profiles, assign speakers, upsert characters for this book.
     * @param onProgress Optional callback(message) for progress dialog; invoke from Main in caller.
     * @param onPageProcessed Optional callback invoked after each page is processed; invoke from Main in caller.
     */
    suspend fun extractAndSave(
        bookId: Long,
        chapterText: String,
        chapterIndex: Int = 0,
        totalChapters: Int = 1,
        onProgress: ((String) -> Unit)? = null,
        onPageProcessed: (() -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val totalStartMs = System.currentTimeMillis()
        val trimmed = chapterText.trim()
        if (trimmed.isBlank()) {
            AppLogger.w(tag, "Chapter text empty, skipping extraction")
            return@withContext
        }
        val pages = splitIntoPages(trimmed)
        AppLogger.i(tag, "Extract start: chapter ${chapterIndex + 1}/$totalChapters, ${pages.size} segment(s)")
        onProgress?.invoke("Chapter ${chapterIndex + 1}/$totalChapters: extracting characters and traits (${pages.size} segments)…")

        val existingCharacters = characterDao.getByBookId(bookId).first()
        val nameToTraits = existingCharacters.associate { it.name to it.traits.split(",").map { t -> t.trim() }.filter { it.isNotBlank() } }.toMutableMap()

        // 1) One LLM call per segment: extract all characters + traits; skip already-known (with traits), fill traits for names that need them
        val phase1StartMs = System.currentTimeMillis()
        for ((index, segment) in pages.withIndex()) {
            try {
                AppLogger.d(tag, "Chapter ${chapterIndex + 1}: segment ${index + 1}/${pages.size} – calling LLM (extract characters + traits)")
                onProgress?.invoke("Chapter ${chapterIndex + 1}/$totalChapters: segment ${index + 1}/${pages.size}…")
                val skipNamesWithTraits = nameToTraits.filter { it.value.isNotEmpty() }.keys.toList()
                val namesNeedingTraits = nameToTraits.filter { it.value.isEmpty() }.keys.toList()
                val extracted = QwenStub.extractCharactersAndTraitsInSegment(segment, skipNamesWithTraits, namesNeedingTraits)
                onPageProcessed?.invoke()
                for ((name, traits) in extracted) {
                    val existing = nameToTraits[name]
                    if (existing == null) {
                        nameToTraits[name] = traits
                    } else if (existing.isEmpty() && traits.isNotEmpty()) {
                        nameToTraits[name] = traits
                    }
                    // else already have traits, keep
                }
                AppLogger.d(tag, "Chapter ${chapterIndex + 1}: segment ${index + 1}/${pages.size} – got ${extracted.size} chars")
            } catch (e: Exception) {
                AppLogger.e(tag, "Chapter ${chapterIndex + 1}: segment ${index + 1}/${pages.size} LLM failed", e)
                onPageProcessed?.invoke()
            }
        }
        val allNames = nameToTraits.keys.toSet()
        AppLogger.logPerformance(tag, "Phase 1: extract characters and traits (${pages.size} segments, one call per segment)", System.currentTimeMillis() - phase1StartMs)
        if (allNames.isEmpty()) {
            AppLogger.d(tag, "No characters detected in any segment")
            return@withContext
        }
        AppLogger.i(tag, "Chapter ${chapterIndex + 1}: detected ${allNames.size} names: $allNames")

        // Default trait for any character still without traits
        for (name in allNames) {
            if (nameToTraits[name].orEmpty().isEmpty()) {
                nameToTraits[name] = listOf("story_character")
            }
        }

        // 3) Build JSON for voice profile suggestion: [{"name": "...", "traits": [...]}]
        onProgress?.invoke("Chapter ${chapterIndex + 1}/$totalChapters: building voice profiles…")
        val phase3StartMs = System.currentTimeMillis()
        val charactersWithTraits = JsonArray()
        for (name in allNames) {
            val traits = nameToTraits[name] ?: emptyList()
            val obj = JsonObject().apply {
                addProperty("name", name)
                add("traits", gson.toJsonTree(traits))
            }
            charactersWithTraits.add(obj)
        }
        val inputJson = gson.toJson(charactersWithTraits)

        // 4) Suggest voice_profile (TTS JSON) for each character
        AppLogger.d(tag, "Chapter ${chapterIndex + 1}: calling suggestVoiceProfilesJson (${allNames.size} chars)")
        val voiceProfilesJson = QwenStub.suggestVoiceProfilesJson(inputJson)
        AppLogger.logPerformance(tag, "Phase 3: suggestVoiceProfilesJson (${allNames.size} chars)", System.currentTimeMillis() - phase3StartMs)
        val nameToVoiceProfile = parseVoiceProfilesResponse(voiceProfilesJson)

        // 5) Assign speaker per character via SpeakerMatcher, then upsert
        onProgress?.invoke("Chapter ${chapterIndex + 1}/$totalChapters: saving ${allNames.size} characters…")
        AppLogger.d(tag, "Chapter ${chapterIndex + 1}: upserting ${allNames.size} characters")
        val byName = existingCharacters.associateBy { it.name }
        for (name in allNames) {
            val traitsList = nameToTraits[name] ?: emptyList()
            val traitsStr = traitsList.joinToString(",")
            val voiceProfileJson = nameToVoiceProfile[name] ?: """{"pitch":1.0,"speed":1.0,"energy":1.0}"""
            val suggestedSpeakerId = SpeakerMatcher.suggestSpeakerIdFromTraitList(traitsList, null, name)

            val existing = byName[name]
            if (existing != null) {
                characterDao.update(
                    existing.copy(
                        traits = existing.traits.ifEmpty { traitsStr }.let { if (traitsStr.isNotBlank()) traitsStr else it },
                        voiceProfileJson = voiceProfileJson,
                        speakerId = suggestedSpeakerId ?: existing.speakerId
                    )
                )
            } else {
                characterDao.insert(
                    Character(
                        bookId = bookId,
                        name = name,
                        traits = traitsStr,
                        personalitySummary = "",
                        voiceProfileJson = voiceProfileJson,
                        speakerId = suggestedSpeakerId
                    )
                )
            }
        }
        AppLogger.logPerformance(tag, "extractAndSave total (chapter ${chapterIndex + 1}/$totalChapters, ${allNames.size} chars)", System.currentTimeMillis() - totalStartMs)
        AppLogger.i(tag, "Extract done: chapter ${chapterIndex + 1}/$totalChapters, ${allNames.size} characters, bookId=$bookId")
    }

    private fun splitIntoPages(text: String): List<String> {
        val list = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + pageSizeChars).coerceAtMost(text.length)
            if (end < text.length) {
                val segment = text.substring(start, end)
                val lastSpaceInSegment = segment.lastIndexOf(' ')
                if (lastSpaceInSegment > 0) end = start + lastSpaceInSegment
            }
            list.add(text.substring(start, end))
            start = end
        }
        return if (list.isEmpty()) listOf(text) else list
    }

    private fun parseVoiceProfilesResponse(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = gson.fromJson(json, JsonObject::class.java) ?: return emptyMap()
            val arr = obj.getAsJsonArray("characters") ?: return emptyMap()
            arr.mapNotNull { el ->
                val o = el.asJsonObject ?: return@mapNotNull null
                val name = o.get("name")?.asString ?: return@mapNotNull null
                val vp = o.get("voice_profile")?.asJsonObject ?: return@mapNotNull null
                name to vp.toString()
            }.toMap()
        } catch (e: Exception) {
            AppLogger.w(tag, "Failed to parse voice profiles JSON", e)
            emptyMap()
        }
    }
}
