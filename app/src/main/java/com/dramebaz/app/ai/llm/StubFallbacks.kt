package com.dramebaz.app.ai.llm

import com.dramebaz.app.data.models.Dialog
import com.dramebaz.app.data.models.EmotionalSegment
import com.dramebaz.app.data.models.ChapterSummary
import com.google.gson.Gson

/**
 * Stub fallback methods for LLM operations when the model is unavailable or fails.
 * 
 * These methods use regex-based heuristics and name-based inference to provide
 * basic functionality when the actual LLM model cannot be used.
 * 
 * Design Pattern: Utility class with static methods for fallback operations.
 */
object StubFallbacks {
    private val gson = Gson()

    // ==================== Character Detection ====================

    /**
     * Detect character names from page text using regex patterns.
     * Looks for capitalized words, speech verb patterns, etc.
     */
    fun detectCharactersOnPage(pageText: String): List<String> {
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

        // Pattern 2: Find words following speech verbs
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

    // ==================== Trait Inference ====================

    /**
     * Infer character traits from name using heuristics.
     * Looks for gender indicators, title prefixes, and common name patterns.
     */
    fun inferTraitsFromName(characterName: String): List<String> {
        val traits = mutableListOf<String>()
        val lowerName = characterName.lowercase()

        val femaleIndicators = listOf("mrs", "miss", "ms", "lady", "queen", "princess", "duchess", "countess",
            "aunt", "mother", "sister", "grandmother", "grandma", "daughter", "woman", "girl", "female",
            "mary", "sarah", "elizabeth", "anna", "emma", "lily", "rose", "jane", "alice",
            "ashley", "samantha", "katherine", "catherine", "hermione", "ginny", "molly", "petunia")
        val maleIndicators = listOf("mr", "sir", "lord", "king", "prince", "duke", "captain", "general",
            "james", "john", "william", "robert", "david", "michael", "thomas", "charles",
            "jack", "leo", "max", "alex", "sam", "ben", "tom", "adam", "dan", "joe",
            "harry", "ron", "dumbledore", "snape", "draco", "sirius", "remus", "vernon",
            "george", "fred", "arthur", "percy", "neville", "oliver", "marcus")

        // Check for gender indicators
        val isFemale = femaleIndicators.any { indicator ->
            lowerName.startsWith(indicator) || lowerName.endsWith(indicator) ||
            lowerName.contains(" $indicator") || lowerName.contains("$indicator ")
        }
        val isMale = maleIndicators.any { indicator ->
            lowerName.startsWith(indicator) || lowerName.endsWith(indicator) ||
            lowerName.contains(" $indicator") || lowerName.contains("$indicator ")
        }

        when {
            isFemale -> traits.add("female")
            isMale -> traits.add("male")
            else -> traits.add("adult")
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

    /**
     * Simple trait inference fallback.
     */
    fun inferTraits(characterName: String): List<String> = listOf("story_character", "narrative")

    // ==================== Voice Profile Generation ====================

    /**
     * Generate traits and voice profile for a single character using name-based heuristics.
     * Returns a Pair of (traits list, voice profile map).
     */
    fun singleCharacterTraitsAndProfile(characterName: String): Pair<List<String>, Map<String, Any>> {
        val traits = inferTraitsFromName(characterName)

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
            else -> 45
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

    // ==================== Dialog Extraction ====================

    /**
     * Regex-based fallback for Pass-2 dialog extraction.
     * Uses pattern matching to find quoted text and attribute to nearest character.
     */
    fun extractDialogsFromText(pageText: String, characterNames: List<String>): List<Qwen3Model.ExtractedDialogEntry> {
        val dialogs = mutableListOf<Qwen3Model.ExtractedDialogEntry>()

        val quotePattern = Regex(""""([^"]+)"|'([^']+)'""")
        val matches = quotePattern.findAll(pageText)

        val attributionPatterns = listOf(
            Regex("""(\w+)\s+said""", RegexOption.IGNORE_CASE),
            Regex("""said\s+(\w+)""", RegexOption.IGNORE_CASE),
            Regex("""(\w+)\s+asked""", RegexOption.IGNORE_CASE),
            Regex("""asked\s+(\w+)""", RegexOption.IGNORE_CASE),
            Regex("""(\w+)\s+replied""", RegexOption.IGNORE_CASE),
            Regex("""(\w+)\s+whispered""", RegexOption.IGNORE_CASE),
            Regex("""(\w+)\s+shouted""", RegexOption.IGNORE_CASE),
            Regex("""(\w+)\s+exclaimed""", RegexOption.IGNORE_CASE),
            Regex("""(\w+):""", RegexOption.IGNORE_CASE)
        )

        var lastNarratorEnd = 0

        for (match in matches) {
            val quoteStart = match.range.first
            val quoteEnd = match.range.last
            val quoteText = match.groupValues[1].ifEmpty { match.groupValues[2] }

            if (quoteText.isBlank()) continue

            // Extract narrator text before this quote
            if (quoteStart > lastNarratorEnd + 10) {
                val narratorText = pageText.substring(lastNarratorEnd, quoteStart).trim()
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
            lastNarratorEnd = quoteEnd + 1

            // Find speaker using proximity search
            val searchStart = maxOf(0, quoteStart - 200)
            val searchEnd = minOf(pageText.length, quoteEnd + 200)
            val contextBefore = pageText.substring(searchStart, quoteStart)
            val contextAfter = pageText.substring(quoteEnd + 1, searchEnd)

            var speaker = "Unknown"

            for (pattern in attributionPatterns) {
                val beforeMatch = pattern.find(contextBefore)
                val afterMatch = pattern.find(contextAfter)

                val matchedName = beforeMatch?.groupValues?.getOrNull(1)
                    ?: afterMatch?.groupValues?.getOrNull(1)

                if (matchedName != null) {
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

            if (speaker == "Unknown") {
                var nearestDistance = Int.MAX_VALUE
                for (charName in characterNames) {
                    for (part in charName.split(" ").filter { it.length >= 2 }) {
                        val beforeIdx = contextBefore.lastIndexOf(part, ignoreCase = true)
                        val afterIdx = contextAfter.indexOf(part, ignoreCase = true)

                        if (beforeIdx >= 0 && contextBefore.length - beforeIdx < nearestDistance) {
                            nearestDistance = contextBefore.length - beforeIdx
                            speaker = charName
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

        // Add remaining narrator text
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

    // ==================== Chapter Analysis ====================

    /**
     * Stub implementation for full chapter analysis.
     */
    fun analyzeChapter(chapterText: String): ChapterAnalysisResponse {
        val paragraphs = chapterText.split("\n\n", "\n").filter { it.isNotBlank() }
        val firstParagraph = paragraphs.firstOrNull() ?: ""
        val characterCandidates = detectCharactersOnPage(chapterText)

        val dialogPattern = Regex("\"([^\"]+)\"")
        val speechVerbPattern = Regex("([A-Z][a-z]+)\\s+(?:said|asked|replied|answered|exclaimed|whispered|shouted|cried|muttered|spoke)(?:[.,;:!?]|\\s)", RegexOption.IGNORE_CASE)
        val characterSet = characterCandidates.map { it.lowercase() }.toSet()

        var prevQuoteEnd = 0
        val dialogs = dialogPattern.findAll(chapterText).map { matchResult ->
            val dialogText = matchResult.groupValues[1]
            val quoteStart = matchResult.range.first
            val contextBefore = chapterText.substring(prevQuoteEnd, quoteStart)
            prevQuoteEnd = matchResult.range.last + 1

            var speaker: String? = null
            speechVerbPattern.findAll(contextBefore).lastOrNull()?.let { match ->
                val candidateName = match.groupValues.getOrNull(1)
                if (candidateName != null && candidateName.lowercase() in characterSet) {
                    speaker = candidateName
                }
            }

            if (speaker == null) {
                for (candidateName in characterCandidates) {
                    if (contextBefore.contains(candidateName)) {
                        speaker = candidateName
                    }
                }
            }

            Dialog(
                speaker = speaker ?: characterCandidates.firstOrNull() ?: "Unknown",
                dialog = dialogText,
                emotion = "neutral",
                intensity = 0.5f
            )
        }.toList()

        val emotionalArc = when {
            paragraphs.size > 10 -> listOf(
                EmotionalSegment("start", "curiosity", 0.4f),
                EmotionalSegment("middle", "tension", 0.6f),
                EmotionalSegment("end", "resolution", 0.5f)
            )
            paragraphs.size > 5 -> listOf(
                EmotionalSegment("start", "curiosity", 0.4f),
                EmotionalSegment("end", "neutral", 0.5f)
            )
            else -> listOf(EmotionalSegment("start", "neutral", 0.5f))
        }

        val characters = (characterCandidates.take(3) + listOf("Narrator")).map { name ->
            CharacterStub(name = name, traits = listOf("story_character"), voiceProfile = mapOf("pitch" to 1.0, "speed" to 1.0, "energy" to 1.0))
        }

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

    // ==================== Extended Analysis ====================

    /**
     * Generate extended analysis JSON for themes, symbols, vocabulary.
     */
    fun extendedAnalysisJson(chapterText: String): String {
        val words = chapterText.lowercase().split(Regex("\\s+")).filter {
            it.length > 4 && it.all { c -> c.isLetter() }
        }

        val themeKeywords = mapOf(
            "love" to "romance", "war" to "conflict", "death" to "mortality",
            "power" to "authority", "freedom" to "liberty", "betrayal" to "treachery",
            "journey" to "adventure", "home" to "belonging"
        )

        val detectedThemes = themeKeywords.entries
            .filter { (keyword, _) -> chapterText.lowercase().contains(keyword) }
            .map { it.value }
            .take(3)
            .ifEmpty { listOf("narrative", "character_development") }

        val commonWords = setOf("the", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "by", "from", "as", "is", "was", "are", "were", "been", "be", "have", "has", "had", "do", "does", "did", "will", "would", "could", "should", "may", "might", "must", "can", "this", "that", "these", "those", "a", "an")
        val vocabulary = words.filter { it !in commonWords }
            .groupingBy { it }
            .eachCount()
            .filter { it.value >= 2 }
            .entries
            .sortedByDescending { it.value }
            .take(5)
            .map { (word, count) -> mapOf("word" to word, "definition" to "Appears $count times", "frequency" to count) }

        val obj = mapOf(
            "themes" to detectedThemes,
            "symbols" to listOf("narrative_symbol", "character_symbol"),
            "foreshadowing" to listOf("Text suggests future developments"),
            "vocabulary" to vocabulary.ifEmpty { listOf(mapOf("word" to "narrative", "definition" to "A spoken or written account")) }
        )
        return gson.toJson(obj)
    }

    // ==================== Story Generation ====================

    /**
     * Generate a simple story based on prompt.
     */
    fun generateStory(prompt: String): String {
        val normalizedPrompt = prompt.lowercase()
        val storyType = when {
            normalizedPrompt.contains("adventure") || normalizedPrompt.contains("quest") -> "adventure"
            normalizedPrompt.contains("mystery") || normalizedPrompt.contains("detective") -> "mystery"
            else -> "general"
        }

        return when (storyType) {
            "adventure" -> """
                Chapter 1: The Call to Adventure

                The hero received an unexpected message that would change everything. "$prompt"

                With determination in their heart, they packed their belongings and set out on a journey.

                Chapter 2: The Resolution

                In the end, the hero achieved their goal. The quest that began with "$prompt" concluded successfully.

                The end.
            """.trimIndent()
            "mystery" -> """
                Chapter 1: The Discovery

                It started with a simple observation: "$prompt"

                The detective began their investigation, examining every clue carefully.

                Chapter 2: The Truth Revealed

                Finally, all the pieces fell into place. The mystery was solved.

                The end.
            """.trimIndent()
            else -> """
                Chapter 1: The Beginning

                "$prompt"

                This was how it all started. The world was full of possibilities.

                Chapter 2: The Conclusion

                The story reached its natural conclusion. The protagonist had grown and changed.

                The end.
            """.trimIndent()
        }
    }

    // ==================== Key Moments & Relationships ====================

    /**
     * Extract key moments for a character (stub).
     */
    fun extractKeyMoments(characterName: String, chapterTitle: String): List<Map<String, String>> {
        return listOf(
            mapOf(
                "chapter" to chapterTitle,
                "moment" to "$characterName appears in this chapter",
                "significance" to "Character introduction or development"
            )
        )
    }

    /**
     * Extract relationships for a character (stub).
     */
    fun extractRelationships(characterName: String, allCharacterNames: List<String>): List<Map<String, String>> {
        return allCharacterNames.take(5)
            .filter { !it.equals(characterName, ignoreCase = true) }
            .map { mapOf("character" to it, "relationship" to "other", "nature" to "Appears together in the story") }
    }

    // ==================== Utility ====================

    /**
     * Extract characters and traits in a segment for incremental analysis.
     */
    fun extractCharactersAndTraitsInSegment(
        segmentText: String,
        skipNamesWithTraits: Collection<String>,
        namesNeedingTraits: Collection<String>
    ): List<Pair<String, List<String>>> {
        val skipSet = skipNamesWithTraits.map { it.lowercase() }.toSet()
        val names = detectCharactersOnPage(segmentText).filter { it.lowercase() !in skipSet }.distinctBy { it.lowercase() }
        return names.map { it to inferTraits(it) }
    }

    /**
     * Suggest voice profiles from JSON.
     */
    fun suggestVoiceProfiles(charactersWithTraitsJson: String): String? {
        return try {
            val arr = gson.fromJson(charactersWithTraitsJson, com.google.gson.JsonArray::class.java) ?: return null
            val list = arr.map { el ->
                val name = el.asJsonObject?.get("name")?.asString ?: "Unknown"
                mapOf("name" to name, "voice_profile" to mapOf("pitch" to 1.0, "speed" to 1.0, "energy" to 1.0, "emotion_bias" to emptyMap<String, Double>()))
            }
            gson.toJson(mapOf("characters" to list))
        } catch (_: Exception) { null }
    }
}

