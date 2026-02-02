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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Workflow: one LLM call per segment to extract all characters and their traits. Segments can be
 * processed in parallel (up to maxConcurrentSegments). Then suggest voice profile (TTS JSON),
 * assign speaker via SpeakerMatcher, store by book.
 * @param onProgress Optional callback for granular progress (message). Called from IO thread; run UI updates on Main.
 * @param onPageProcessed Optional callback invoked after each segment is processed (for progress bar). Run on Main in caller.
 *
 * AUG-033: Optimized with dynamic concurrency based on available RAM, request queuing, and performance metrics.
 *
 * Note: For detailed 3-pass character analysis with dialog extraction and voice profiles,
 * use [ThreePassCharacterAnalysisUseCase] which is invoked by the "Analyse Chapters" button.
 */
class ChapterCharacterExtractionUseCase(private val characterDao: CharacterDao) {

    private val tag = "ChapterCharExtract"
    private val gson = Gson()

    /** Segment size in chars (8k–12k ideal for Qwen3-1.7B on mobile: fast inference, avoids RAM pressure). */
    private val pageSizeChars = 10000

    /**
     * AUG-033: Dynamically adjusted concurrency based on available RAM.
     * Base value is 2, adjusted up to 4 if >1GB RAM available, or down to 1 if <500MB available.
     */
    private val maxConcurrentSegments: Int
        get() {
            val runtime = Runtime.getRuntime()
            val usedMem = runtime.totalMemory() - runtime.freeMemory()
            val maxMem = runtime.maxMemory()
            val availableMb = (maxMem - usedMem) / (1024 * 1024)
            return when {
                availableMb > 1024 -> 4  // >1GB available: use more parallelism
                availableMb > 512 -> 2   // 512MB-1GB: default
                else -> 1                 // <512MB: reduce concurrency
            }.also { AppLogger.d(tag, "AUG-033: Dynamic concurrency = $it (available RAM: ${availableMb}MB)") }
        }

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

        // 1) Single-pass: when LLM is available, one analyzeChapter call returns characters with traits + voice_profile
        val phase1StartMs = System.currentTimeMillis()
        onProgress?.invoke("Chapter ${chapterIndex + 1}/$totalChapters: analyzing chapter…")

        val combinedText = pages.joinToString("\n\n").take(50000)
        var usedSinglePass = false
        var singlePassVoiceProfiles: MutableMap<String, String>? = null

        if (QwenStub.isUsingLlama()) {
            try {
                AppLogger.d(tag, "Chapter ${chapterIndex + 1}: single-pass analyzeChapter (${combinedText.length} chars)")
                val resp = QwenStub.analyzeChapter(combinedText)
                val chars = resp.characters
                if (!chars.isNullOrEmpty()) {
                    for (c in chars) {
                        val name = c.name.takeIf { it.isNotBlank() } ?: continue
                        val traits = c.traits?.filter { it.isNotBlank() } ?: emptyList()
                        val existing = nameToTraits[name]
                        if (existing == null) nameToTraits[name] = traits
                        else if (existing.isEmpty() && traits.isNotEmpty()) nameToTraits[name] = traits
                        if (c.voiceProfile != null) {
                            if (singlePassVoiceProfiles == null) singlePassVoiceProfiles = mutableMapOf()
                            singlePassVoiceProfiles[name] = gson.toJson(c.voiceProfile)
                        }
                    }
                    usedSinglePass = true
                    pages.forEach { _ -> onPageProcessed?.invoke() }
                    AppLogger.d(tag, "Chapter ${chapterIndex + 1}: single-pass got ${chars.size} characters with traits/voice")
                }
            } catch (e: Exception) {
                AppLogger.w(tag, "Chapter ${chapterIndex + 1}: single-pass failed, falling back to batched extraction", e)
            }
        }

        if (!usedSinglePass) {
            try {
                AppLogger.d(tag, "Chapter ${chapterIndex + 1}: calling batched LLM for whole chapter (${combinedText.length} chars)")
                val extracted = QwenStub.extractCharactersAndTraitsInSegment(combinedText, emptyList(), emptyList())
                AppLogger.d(tag, "Chapter ${chapterIndex + 1}: batched extraction got ${extracted.size} characters")

                for ((name, traits) in extracted) {
                    val existing = nameToTraits[name]
                    if (existing == null) {
                        nameToTraits[name] = traits
                    } else if (existing.isEmpty() && traits.isNotEmpty()) {
                        nameToTraits[name] = traits
                    }
                }

                pages.forEach { _ -> onPageProcessed?.invoke() }
            } catch (e: Exception) {
                AppLogger.e(tag, "Chapter ${chapterIndex + 1}: batched LLM call failed, falling back to per-segment", e)

            // Fallback to per-segment processing if batch fails
            val semaphore = Semaphore(maxConcurrentSegments)
            val segmentResults = coroutineScope {
                pages.mapIndexed { index, segment ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            try {
                                val segExtracted = QwenStub.extractCharactersAndTraitsInSegment(segment, emptyList(), emptyList())
                                onPageProcessed?.invoke()
                                segExtracted
                            } catch (ex: Exception) {
                                onPageProcessed?.invoke()
                                emptyList()
                            }
                        }
                    }
                }.awaitAll()
            }
            for (seg in segmentResults) {
                for ((name, traits) in seg) {
                    val existing = nameToTraits[name]
                    if (existing == null) nameToTraits[name] = traits
                    else if (existing.isEmpty() && traits.isNotEmpty()) nameToTraits[name] = traits
                }
            }
            }
        }
        val allNames = nameToTraits.keys.toSet()
        AppLogger.logPerformance(tag, "Phase 1: extract characters and traits (${pages.size} segments, parallel max=$maxConcurrentSegments)", System.currentTimeMillis() - phase1StartMs)
        if (allNames.isEmpty()) {
            AppLogger.d(tag, "No characters detected in any segment")
            return@withContext
        }
        AppLogger.i(tag, "Chapter ${chapterIndex + 1}: detected ${allNames.size} names: $allNames")

        // AUG-009: Retry trait inference for characters with empty traits
        val namesNeedingTraits = allNames.filter { nameToTraits[it].orEmpty().isEmpty() }
        if (namesNeedingTraits.isNotEmpty()) {
            AppLogger.d(tag, "AUG-009: ${namesNeedingTraits.size} characters need trait inference: $namesNeedingTraits")
            onProgress?.invoke("Chapter ${chapterIndex + 1}/$totalChapters: inferring traits for ${namesNeedingTraits.size} characters...")

            for (name in namesNeedingTraits) {
                try {
                    // Find context (dialogs/mentions) for this character
                    val excerpt = extractCharacterContext(trimmed, name)
                    if (excerpt.isNotBlank()) {
                        val inferredTraits = QwenStub.inferTraitsForCharacter(name, excerpt)
                        if (inferredTraits.isNotEmpty()) {
                            nameToTraits[name] = inferredTraits
                            AppLogger.d(tag, "AUG-009: Inferred traits for $name: $inferredTraits")
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e(tag, "AUG-009: Failed to infer traits for $name", e)
                }
            }
        }

        // Default trait for any character still without traits (after retry)
        for (name in allNames) {
            if (nameToTraits[name].orEmpty().isEmpty()) {
                // Use heuristics based on name
                val inferredFromName = inferTraitsFromName(name)
                nameToTraits[name] = inferredFromName.ifEmpty { listOf("story_character") }
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

        // 4) Voice profiles: use single-pass result if available, else one LLM call
        val nameToVoiceProfile = if (usedSinglePass && !singlePassVoiceProfiles.isNullOrEmpty()) {
            AppLogger.d(tag, "Chapter ${chapterIndex + 1}: using voice profiles from single-pass (${singlePassVoiceProfiles.size} chars)")
            singlePassVoiceProfiles
        } else {
            AppLogger.d(tag, "Chapter ${chapterIndex + 1}: calling suggestVoiceProfilesJson (${allNames.size} chars)")
            val voiceProfilesJson = QwenStub.suggestVoiceProfilesJson(inputJson)
            AppLogger.logPerformance(tag, "Phase 3: suggestVoiceProfilesJson (${allNames.size} chars)", System.currentTimeMillis() - phase3StartMs)
            parseVoiceProfilesResponse(voiceProfilesJson)
        }

        // 5) Assign speaker per character via SpeakerMatcher, then upsert
        // AUG-043: Ensure every character gets a speaker ID (never null)
        onProgress?.invoke("Chapter ${chapterIndex + 1}/$totalChapters: saving ${allNames.size} characters…")
        AppLogger.d(tag, "Chapter ${chapterIndex + 1}: upserting ${allNames.size} characters")
        val byName = existingCharacters.associateBy { it.name }
        for (name in allNames) {
            val traitsList = nameToTraits[name] ?: emptyList()
            val traitsStr = traitsList.joinToString(",")
            val voiceProfileJson = nameToVoiceProfile[name] ?: """{"pitch":1.0,"speed":1.0,"energy":1.0}"""

            // AUG-043: Always assign a speaker ID - use trait-based matching first, then fallback to default
            var suggestedSpeakerId = SpeakerMatcher.suggestSpeakerIdFromTraitList(traitsList, null, name)
            if (suggestedSpeakerId == null) {
                // Fallback: assign a default speaker based on simple heuristics
                suggestedSpeakerId = getDefaultSpeakerId(name, traitsList)
                AppLogger.d(tag, "AUG-043: No trait match for '$name', using default speaker $suggestedSpeakerId")
            }

            val existing = byName[name]
            if (existing != null) {
                // Only update speakerId if it was null before (preserve user selections)
                val finalSpeakerId = existing.speakerId ?: suggestedSpeakerId
                characterDao.update(
                    existing.copy(
                        traits = existing.traits.ifEmpty { traitsStr }.let { if (traitsStr.isNotBlank()) traitsStr else it },
                        voiceProfileJson = voiceProfileJson,
                        speakerId = finalSpeakerId
                    )
                )
                AppLogger.d(tag, "Updated character '$name': speakerId=$finalSpeakerId, traits=$traitsStr")
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
                AppLogger.d(tag, "Inserted character '$name': speakerId=$suggestedSpeakerId, traits=$traitsStr")
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

    /**
     * AUG-043: Get a default speaker ID when trait-based matching fails.
     * Uses simple heuristics to ensure every character gets a voice.
     * @param name Character name
     * @param traits List of character traits
     * @return Speaker ID (0-108 for VCTK)
     */
    private fun getDefaultSpeakerId(name: String, traits: List<String>): Int {
        // Check for gender hints in traits
        val femaleTraits = setOf("female", "woman", "lady", "girl", "she", "her", "feminine")
        val maleTraits = setOf("male", "man", "gentleman", "boy", "he", "him", "masculine")

        val isFemale = traits.any { it.lowercase() in femaleTraits }
        val isMale = traits.any { it.lowercase() in maleTraits }

        // Also check name for common gender indicators
        val lowerName = name.lowercase()
        val femaleNameIndicators = listOf("mrs", "ms", "miss", "lady", "queen", "princess", "duchess",
            "mary", "jane", "elizabeth", "anna", "sarah", "emma", "sophia", "olivia", "isabella")
        val maleNameIndicators = listOf("mr", "sir", "lord", "king", "prince", "duke", "captain",
            "james", "john", "william", "robert", "david", "michael", "thomas", "charles")

        val femaleFromName = femaleNameIndicators.any { lowerName.contains(it) }
        val maleFromName = maleNameIndicators.any { lowerName.contains(it) }

        // Default speaker IDs: spread across different voice types
        // Female speakers: 10, 12, 15, 17, 22, 25 (varied female voices)
        // Male speakers: 5, 8, 11, 14, 20, 23 (varied male voices)
        // Neutral/Narrator: 0 (default neutral voice)
        val femaleSpeakers = listOf(10, 12, 15, 17, 22, 25)
        val maleSpeakers = listOf(5, 8, 11, 14, 20, 23)

        return when {
            isFemale || femaleFromName -> {
                // Use hash of name to pick from female speakers for variety
                val hash = kotlin.math.abs(name.hashCode())
                femaleSpeakers[hash % femaleSpeakers.size]
            }
            isMale || maleFromName -> {
                // Use hash of name to pick from male speakers for variety
                val hash = kotlin.math.abs(name.hashCode())
                maleSpeakers[hash % maleSpeakers.size]
            }
            else -> {
                // No gender indicator: use hash of name to get consistent speaker
                val hash = kotlin.math.abs(name.hashCode())
                hash % 109  // 0-108 for VCTK
            }
        }
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

    /**
     * AUG-009: Extract surrounding context for a character (dialogs, descriptions, actions).
     * Returns up to 2000 chars of relevant text.
     */
    private fun extractCharacterContext(chapterText: String, characterName: String): String {
        val contextParts = mutableListOf<String>()
        val lines = chapterText.split("\n")

        for ((idx, line) in lines.withIndex()) {
            if (line.contains(characterName, ignoreCase = true)) {
                // Include this line plus one before and one after
                val start = (idx - 1).coerceAtLeast(0)
                val end = (idx + 2).coerceAtMost(lines.size)
                val snippet = lines.subList(start, end).joinToString("\n")
                contextParts.add(snippet)
                if (contextParts.sumOf { it.length } > 2000) break
            }
        }

        return contextParts.joinToString("\n...\n").take(2000)
    }

    /**
     * AUG-009: Infer basic traits from character name using heuristics.
     */
    private fun inferTraitsFromName(name: String): List<String> {
        val traits = mutableListOf<String>()
        val lowerName = name.lowercase()

        // Gender hints from common names/titles
        val femaleIndicators = listOf("mrs", "ms", "miss", "lady", "queen", "princess", "duchess",
            "mary", "jane", "elizabeth", "anna", "sarah", "emma", "sophia", "olivia", "isabella")
        val maleIndicators = listOf("mr", "sir", "lord", "king", "prince", "duke", "captain", "general",
            "james", "john", "william", "robert", "david", "michael", "thomas", "charles")

        if (femaleIndicators.any { lowerName.contains(it) }) {
            traits.add("female")
        } else if (maleIndicators.any { lowerName.contains(it) }) {
            traits.add("male")
        }

        // Title-based traits
        if (lowerName.contains("dr") || lowerName.contains("doctor") || lowerName.contains("professor")) {
            traits.add("educated")
            traits.add("authoritative")
        }
        if (lowerName.contains("captain") || lowerName.contains("general") || lowerName.contains("commander")) {
            traits.add("military")
            traits.add("commanding")
        }
        if (lowerName.contains("king") || lowerName.contains("queen") || lowerName.contains("prince") || lowerName.contains("princess")) {
            traits.add("noble")
            traits.add("regal")
        }

        return traits
    }
}
