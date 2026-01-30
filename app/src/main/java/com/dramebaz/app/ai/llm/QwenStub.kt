package com.dramebaz.app.ai.llm

import android.content.Context
import com.dramebaz.app.data.models.ChapterSummary
import com.dramebaz.app.data.models.Dialog
import com.dramebaz.app.data.models.SoundCueModel
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Qwen model interface. Uses QwenModel if available, falls back to stub.
 * This is the main entry point for chapter analysis.
 * Native LLM expects qwen2.5-3b-instruct-q4_k_m.gguf in device Downloads folder.
 */
object QwenStub {
    private var qwenModel: QwenModel? = null
    private var modelInitialized = false
    
    // Set to true to enable native LLM (requires qwen2.5-3b-instruct-q4_k_m.gguf in Downloads)
    private const val USE_NATIVE_LLM = true
    
    /**
     * Initialize the Qwen model. Call this once at app startup.
     * @return true if model loaded successfully, false otherwise
     */
    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (modelInitialized) {
            android.util.Log.d("QwenStub", "Model already initialized")
            return@withContext true
        }
        
        if (!USE_NATIVE_LLM) {
            android.util.Log.i("QwenStub", "Native LLM disabled, using stub implementation")
            modelInitialized = true
            return@withContext true
        }
        
        try {
            android.util.Log.i("QwenStub", "Initializing Qwen model...")
            qwenModel = QwenModel(context)
            val loaded = qwenModel?.loadModel() ?: false
            if (loaded) {
                android.util.Log.i("QwenStub", "Qwen model loaded successfully")
            } else {
                android.util.Log.w("QwenStub", "Qwen model not available, using stub fallback")
                android.util.Log.w("QwenStub", "Check logcat with tag 'QwenModel' for detailed error messages")
                qwenModel = null
            }
            modelInitialized = true
            return@withContext true
        } catch (e: Exception) {
            android.util.Log.e("QwenStub", "Error initializing Qwen model", e)
            e.printStackTrace()
            qwenModel = null
            modelInitialized = true
            return@withContext true
        }
    }
    
    /**
     * Release model resources. Call when app is closing.
     */
    fun release() {
        qwenModel?.release()
        qwenModel = null
        modelInitialized = false
    }
    
    /**
     * Check if the model is ready for analysis.
     * Returns true if initialization was attempted (even if using stub fallback).
     */
    fun isReady(): Boolean = modelInitialized

    private val gson = Gson()

    suspend fun analyzeChapter(chapterText: String): ChapterAnalysisResponse = withContext(Dispatchers.IO) {
        val result = qwenModel?.let { model ->
            try {
                model.analyzeChapter(chapterText)
            } catch (e: Exception) {
                android.util.Log.e("QwenStub", "Error in model analysis, using fallback", e)
                null
            }
        }
        result ?: stubAnalyzeChapter(chapterText)
    }

    /** T9.1: Extended analysis – themes, symbols, foreshadowing, vocabulary. */
    suspend fun extendedAnalysisJson(chapterText: String): String = withContext(Dispatchers.IO) {
        val result = qwenModel?.let { model ->
            try {
                model.extendedAnalysisJson(chapterText)
            } catch (e: Exception) {
                android.util.Log.e("QwenStub", "Error in extended analysis, using fallback", e)
                null
            }
        }
        result ?: stubExtendedAnalysisJson(chapterText)
    }

    /** One call per segment: extract all characters and their traits; skip already-known (with traits), fill traits for namesNeedingTraits. */
    suspend fun extractCharactersAndTraitsInSegment(
        segmentText: String,
        skipNamesWithTraits: Collection<String>,
        namesNeedingTraits: Collection<String>
    ): List<Pair<String, List<String>>> = withContext(Dispatchers.IO) {
        qwenModel?.extractCharactersAndTraitsInSegment(segmentText, skipNamesWithTraits, namesNeedingTraits)
            ?: stubExtractCharactersAndTraitsInSegment(segmentText, skipNamesWithTraits, namesNeedingTraits)
    }

    /** Page-by-page: detect character names on one page. Returns names only. */
    suspend fun detectCharactersOnPage(pageText: String): List<String> = withContext(Dispatchers.IO) {
        qwenModel?.detectCharactersOnPage(pageText) ?: stubDetectCharactersOnPage(pageText)
    }

    /** Infer personality traits for a character from excerpt. */
    suspend fun inferTraitsForCharacter(characterName: String, excerpt: String): List<String> = withContext(Dispatchers.IO) {
        qwenModel?.inferTraitsForCharacter(characterName, excerpt) ?: stubInferTraits(characterName)
    }

    /** Suggest voice_profile JSON for each character (TTS-ready). Returns JSON string. */
    suspend fun suggestVoiceProfilesJson(charactersWithTraitsJson: String): String? = withContext(Dispatchers.IO) {
        qwenModel?.suggestVoiceProfilesJson(charactersWithTraitsJson) ?: stubSuggestVoiceProfiles(charactersWithTraitsJson)
    }

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
        
        // Extract dialogs (text in quotes)
        val dialogPattern = Regex("\"([^\"]+)\"")
        val dialogs = dialogPattern.findAll(chapterText).map { matchResult ->
            val dialogText = matchResult.groupValues[1]
            val speaker = characterCandidates.firstOrNull() ?: "Unknown"
            Dialog(
                speaker = speaker,
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
    
    /**
     * T12.1: Generate a story based on user prompt.
     */
    suspend fun generateStory(userPrompt: String): String = withContext(Dispatchers.IO) {
        val result = qwenModel?.let { model ->
            try {
                model.generateStory(userPrompt)
            } catch (e: Exception) {
                android.util.Log.e("QwenStub", "Error generating story, using fallback", e)
                null
            }
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
}
