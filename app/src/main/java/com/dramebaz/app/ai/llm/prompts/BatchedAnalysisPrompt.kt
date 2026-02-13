package com.dramebaz.app.ai.llm.prompts

import com.dramebaz.app.ai.llm.pipeline.BatchedPipelineConfig
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Unified extraction prompt for batched chapter analysis.
 *
 * Extracts ALL of the following in ONE LLM call per batch:
 * - Characters (names only)
 * - Character Traits and Personalities
 * - Voice Profiles (pitch, speed, accent, gender, age)
 * - Dialogs (exact text)
 *
 * Token Budget configuration is centralized in BatchedPipelineConfig.
 */
class BatchedAnalysisPrompt : PromptDefinition<BatchedAnalysisInput, BatchedAnalysisOutput> {

    companion object {
        private const val TAG = "BatchedAnalysisPrompt"

        /** Token budget for batched analysis - uses centralized BatchedPipelineConfig */
        val BATCHED_ANALYSIS_BUDGET = BatchedPipelineConfig.TOKEN_BUDGET
    }

    private val gson = Gson()

    override val promptId: String = "batched_analysis_v1"
    override val displayName: String = "Batched Chapter Analysis"
    override val purpose: String = "Extract characters, their dialogs in the story, their traits and their inferred voice profile"
    override val tokenBudget: TokenBudget = BATCHED_ANALYSIS_BUDGET
    override val temperature: Float = BatchedPipelineConfig.TEMPERATURE

    override val systemPrompt: String = """You are a Story analysis engine. Output one complete and valid JSON object as requested in the user prompt, from the given Story excerpt."""

    override fun buildUserPrompt(input: BatchedAnalysisInput): String {
        return """Extract all the characters, dialogs spoken by them, their traits and inferred voice profile from the given Story excerpt.
RULES:
1. ONLY include Characters who have quoted dialogs.
2. DO NOT classify locations, objects, creatures or entities that don't speak as Characters.
3. Do not repeat Characters in the output.
4. Attribute dialogs by Character name and pronouns referring them. Each dialog belongs to only one Character.
5. Identify Character traits explicitly mentioned in the story by the Narrator.
6. Based on the traits, infer a voice profile.

Keys for output:
D:Array of exact quoted dialogs spoken by current Character
T:Array of Character traits (personalities, adjectives)
V:Voice profile as a tuple of "Gender,Age,Accent,Pitch,Speed".
Possible values:
Gender (inferred from pronouns): male|female
Age (explicitly mentioned or inferred): child|young|young-adult|middle-aged|elderly
Accent (inferred from the dialogs): neutral|british|american|asian
Pitch (of voice) within the range: 0.5-1.5
Speed (speed of speaking) within the range: 0.5-2.0

OUTPUT FORMAT:
{
  "CharacterName1": {"D": ["this character's first dialog", "their next dialog"], "T": ["trait", "another trait"], "V": "Gender,Age,Accent,Pitch,Speed"},
  "CharacterName2": {"D": ["this character's first dialog"], "T": ["trait"], "V": "Gender,Age,Accent,Pitch,Speed"}
}

Story Excerpt:
${input.text}

JSON:"""
    }

    override fun prepareInput(input: BatchedAnalysisInput): BatchedAnalysisInput {
        val maxChars = tokenBudget.maxInputChars
        val truncatedText = if (input.text.length > maxChars) {
            // Truncate at paragraph boundary
            com.dramebaz.app.ai.llm.pipeline.TextCleaner.truncateAtParagraphBoundary(input.text, maxChars)
        } else {
            input.text
        }
        return input.copy(text = truncatedText)
    }

    override fun parseResponse(response: String): BatchedAnalysisOutput {
        // Log raw response for debugging
        AppLogger.d(TAG, "=== RAW LLM RESPONSE (${response.length} chars) ===")
        AppLogger.d(TAG, "Raw response (first 1000 chars): ${response.take(1000)}")
        if (response.length > 1000) {
            AppLogger.d(TAG, "Raw response (last 500 chars): ...${response.takeLast(500)}")
        }

        if (response.isBlank()) {
            AppLogger.w(TAG, "Empty response from LLM")
            return BatchedAnalysisOutput(emptyList())
        }

        return try {
            var json = extractJsonFromResponse(response)
            AppLogger.d(TAG, "=== EXTRACTED JSON (${json.length} chars) ===")
            AppLogger.d(TAG, "Extracted JSON (first 500): ${json.take(500)}")

            if (json.isBlank() || json == "{}") {
                AppLogger.w(TAG, "Empty or invalid JSON extracted from response")
                return BatchedAnalysisOutput(emptyList())
            }

            // Pre-process: truncate JSON at first duplicate key to handle LLM repetition
            json = truncateAtDuplicateKey(json)
            AppLogger.d(TAG, "=== AFTER DEDUP (${json.length} chars) ===")

            val characters = mutableListOf<ExtractedCharacterData>()

            // Parse the flat JSON structure: {"character-name": {...}, ...}
            @Suppress("UNCHECKED_CAST")
            val rootMap = try {
                gson.fromJson(json, Map::class.java) as? Map<String, Any>
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to parse JSON as Map: ${e.message}", e)
                null
            }

            if (rootMap == null) {
                AppLogger.w(TAG, "Failed to parse root JSON object - rootMap is null")
                return BatchedAnalysisOutput(emptyList())
            }

            AppLogger.d(TAG, "Root map has ${rootMap.size} keys: ${rootMap.keys}")

            for ((characterName, characterData) in rootMap) {
                AppLogger.d(TAG, "Processing character '$characterName', data type: ${characterData?.javaClass?.simpleName}")

                @Suppress("UNCHECKED_CAST")
                val charMap = characterData as? Map<String, Any>
                if (charMap == null) {
                    AppLogger.w(TAG, "Character '$characterName' data is not a Map, skipping. Value: $characterData")
                    continue
                }

                AppLogger.d(TAG, "Character '$characterName' has keys: ${charMap.keys}")

                // Parse dialogs - support "D" (new format), "d", and "dialogs" (old format)
                @Suppress("UNCHECKED_CAST")
                val dialogsRaw = charMap["D"] ?: charMap["d"] ?: charMap["dialogs"]
                val dialogs = (dialogsRaw as? List<*>)
                    ?.mapNotNull { it?.toString() }
                    ?: emptyList()
                AppLogger.d(TAG, "  - dialogs: ${dialogs.size} items")

                // Parse traits - support "T" (new format), "t", and "traits" (old format)
                @Suppress("UNCHECKED_CAST")
                val traitsRaw = charMap["T"] ?: charMap["t"] ?: charMap["traits"]
                val traits = (traitsRaw as? List<*>)
                    ?.mapNotNull { it?.toString() }
                    ?: emptyList()
                AppLogger.d(TAG, "  - traits: ${traits.size} items")

                // Parse voice profile - support both new "v" string format and old "voice" object format
                val voiceProfile = parseVoiceProfile(charMap)
                AppLogger.d(TAG, "  - voice: ${voiceProfile != null}")

                characters.add(ExtractedCharacterData(
                    name = characterName,
                    dialogs = dialogs,
                    traits = traits,
                    voiceProfile = voiceProfile
                ))

                AppLogger.i(TAG, "✓ Parsed character '$characterName': ${dialogs.size} dialogs, ${traits.size} traits, voice=${voiceProfile != null}")
            }

            AppLogger.i(TAG, "=== PARSE RESULT: ${characters.size} characters extracted ===")
            if (characters.isEmpty()) {
                AppLogger.w(TAG, "No characters extracted from response!")
            } else {
                characters.forEach { char ->
                    AppLogger.d(TAG, "  - ${char.name}: ${char.dialogs.size} dialogs, ${char.traits.size} traits")
                }
            }

            BatchedAnalysisOutput(characters)

        } catch (e: JsonSyntaxException) {
            AppLogger.e(TAG, "JSON syntax error parsing response", e)
            AppLogger.e(TAG, "Stack trace: ${e.stackTraceToString()}")
            BatchedAnalysisOutput(emptyList())
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to parse batched analysis response: ${e.message}", e)
            AppLogger.e(TAG, "Stack trace: ${e.stackTraceToString()}")
            BatchedAnalysisOutput(emptyList())
        }
    }

    /**
     * Parse voice profile from character data map.
     * Supports:
     * - New 5-part "V" string format: "Gender,Age,Accent,Pitch,Speed" (e.g., "male,young,neutral,1.0,1.2")
     * - Legacy 3-part "V" string format: "Gender,Age,Accent" (e.g., "male,young,neutral")
     * - Old "voice" object format with individual fields
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseVoiceProfile(charMap: Map<String, Any>): ExtractedVoiceProfile? {
        // Try "V" or "v" string format
        val vString = (charMap["V"] ?: charMap["v"])?.toString()
        if (vString != null && vString.contains(",")) {
            val parts = vString.split(",").map { it.trim() }
            if (parts.size >= 3) {
                // Extract pitch (4th part) and speed (5th part) if available
                val pitch = if (parts.size >= 4) {
                    parseFloatInRange(parts[3], default = 1.0f, min = 0.5f, max = 1.5f)
                } else 1.0f

                val speed = if (parts.size >= 5) {
                    parseFloatInRange(parts[4], default = 1.0f, min = 0.5f, max = 2.0f)
                } else 1.0f

                return ExtractedVoiceProfile(
                    gender = normalizeGender(parts[0]),
                    age = normalizeAge(parts[1]),
                    accent = normalizeAccent(parts[2]),
                    pitch = pitch,
                    speed = speed
                )
            }
        }

        // Try old "voice" object format
        val voiceMap = charMap["voice"] as? Map<String, Any>
        if (voiceMap != null) {
            return ExtractedVoiceProfile(
                gender = normalizeGender(voiceMap["gender"]?.toString()),
                age = normalizeAge(voiceMap["age"]?.toString()),
                accent = normalizeAccent(voiceMap["accent"]?.toString()),
                pitch = (voiceMap["pitch"] as? Number)?.toFloat() ?: 1.0f,
                speed = (voiceMap["speed"] as? Number)?.toFloat() ?: 1.0f
            )
        }

        return null
    }

    /**
     * Parse a float value with range validation.
     * Returns the parsed value clamped to [min, max], or default if parsing fails.
     */
    private fun parseFloatInRange(value: String, default: Float, min: Float, max: Float): Float {
        return try {
            val parsed = value.toFloat()
            parsed.coerceIn(min, max)
        } catch (e: NumberFormatException) {
            AppLogger.w(TAG, "Failed to parse float value '$value', using default $default")
            default
        }
    }

    /**
     * Normalize gender value to expected format.
     */
    private fun normalizeGender(value: String?): String {
        return when (value?.lowercase()?.trim()) {
            "male", "m" -> "male"
            "female", "f" -> "female"
            else -> "male" // Default
        }
    }

    /**
     * Normalize age value to expected format.
     */
    private fun normalizeAge(value: String?): String {
        return when (value?.lowercase()?.trim()) {
            "child" -> "child"
            "young" -> "young"
            "young-adult", "youngadult", "young adult" -> "young-adult"
            "middle-aged", "middleaged", "middle aged", "adult" -> "middle-aged"
            "elderly", "old" -> "elderly"
            else -> "middle-aged" // Default
        }
    }

    /**
     * Normalize accent value to expected format.
     */
    private fun normalizeAccent(value: String?): String {
        return when (value?.lowercase()?.trim()) {
            "neutral", "none", "" -> "neutral"
            "british", "uk" -> "british"
            "american", "us" -> "american"
            "asian" -> "asian"
            else -> value?.trim()?.ifBlank { "neutral" } ?: "neutral"
        }
    }

    /**
     * Truncate JSON at the first duplicate key to handle LLM repetition issues.
     * The LLM sometimes generates valid JSON then starts repeating characters.
     */
    private fun truncateAtDuplicateKey(json: String): String {
        val seenKeys = mutableSetOf<String>()
        var depth = 0
        var inString = false
        var escape = false
        var keyStart = -1
        var currentKey: String? = null
        var lastValidEnd = -1

        for (i in json.indices) {
            val c = json[i]

            if (escape) {
                escape = false
                continue
            }

            if (c == '\\') {
                escape = true
                continue
            }

            if (c == '"') {
                if (!inString) {
                    inString = true
                    if (depth == 1) {
                        keyStart = i + 1  // Start of key (after quote)
                    }
                } else {
                    inString = false
                    if (depth == 1 && keyStart >= 0) {
                        // End of a top-level key
                        currentKey = json.substring(keyStart, i)
                        keyStart = -1
                    }
                }
                continue
            }

            if (inString) continue

            when (c) {
                '{' -> {
                    depth++
                    if (depth == 2 && currentKey != null) {
                        // Starting a character object - check for duplicate
                        if (currentKey in seenKeys) {
                            AppLogger.w(TAG, "Found duplicate key '$currentKey' at position $i, truncating")
                            // Find the position just before this key started (the comma or opening brace)
                            val truncatePos = findTruncatePosition(json, i, currentKey)
                            val truncated = json.substring(0, truncatePos) + "}"
                            AppLogger.d(TAG, "Truncated JSON at position $truncatePos (was ${json.length} chars)")
                            return truncated
                        }
                        seenKeys.add(currentKey)
                    }
                }
                '}' -> {
                    if (depth == 2) {
                        // End of a character object - this is a valid point
                        lastValidEnd = i
                    }
                    depth--
                }
            }
        }

        // If we have a lastValidEnd and the JSON doesn't end properly, truncate there
        if (lastValidEnd > 0 && !json.trimEnd().endsWith("}")) {
            AppLogger.w(TAG, "JSON doesn't end properly, truncating at last valid position $lastValidEnd")
            return json.substring(0, lastValidEnd + 1) + "}"
        }

        return json
    }

    /**
     * Find the position to truncate before a duplicate key.
     */
    private fun findTruncatePosition(json: String, currentPos: Int, duplicateKey: String): Int {
        // Search backwards for the comma or opening brace before this key
        var pos = currentPos
        var depth = 0
        while (pos > 0) {
            pos--
            val c = json[pos]
            when (c) {
                '}' -> depth++
                '{' -> {
                    if (depth == 0) {
                        // This is the opening brace of the duplicate key's object
                        // Continue searching for the comma before the key
                    }
                    depth--
                }
                ',' -> if (depth == 0) {
                    // Found the comma before the duplicate entry
                    return pos
                }
            }
        }
        return currentPos
    }

    private fun extractJsonFromResponse(response: String): String {
        var json = response.trim()

        // Remove markdown code blocks
        if (json.startsWith("```json")) json = json.removePrefix("```json").trim()
        if (json.startsWith("```")) json = json.removePrefix("```").trim()
        if (json.endsWith("```")) json = json.removeSuffix("```").trim()

        // Remove common LLM prefixes like "Here is the JSON:" or "Output:"
        val prefixPatterns = listOf(
            "here is the json",
            "here's the json",
            "output:",
            "result:",
            "json:"
        )
        for (prefix in prefixPatterns) {
            val idx = json.lowercase().indexOf(prefix)
            if (idx >= 0 && idx < 50) {
                json = json.substring(idx + prefix.length).trim()
            }
        }

        // Check for multiple JSON objects (JSONL format - one object per line)
        // This happens when LLM outputs each character as a separate JSON object
        val jsonObjects = extractMultipleJsonObjects(json)
        if (jsonObjects.size > 1) {
            AppLogger.d(TAG, "Found ${jsonObjects.size} separate JSON objects, merging them")
            return mergeJsonObjects(jsonObjects)
        }

        // Find the JSON object boundaries
        val objStart = json.indexOf('{')
        val objEnd = json.lastIndexOf('}')

        AppLogger.d(TAG, "extractJsonFromResponse: objStart=$objStart, objEnd=$objEnd, length=${json.length}")

        return if (objStart >= 0 && objEnd > objStart) {
            val extracted = json.substring(objStart, objEnd + 1)
            AppLogger.d(TAG, "Extracted JSON substring from $objStart to $objEnd")
            extracted
        } else {
            AppLogger.w(TAG, "Could not find JSON object boundaries in response")
            "{}"
        }
    }

    /**
     * Extract multiple JSON objects from a response.
     * Handles JSONL format (one JSON object per line) or multiple objects separated by whitespace.
     */
    private fun extractMultipleJsonObjects(text: String): List<String> {
        val objects = mutableListOf<String>()
        var depth = 0
        var inString = false
        var escape = false
        var objectStart = -1

        for (i in text.indices) {
            val c = text[i]

            if (escape) {
                escape = false
                continue
            }

            if (c == '\\' && inString) {
                escape = true
                continue
            }

            if (c == '"') {
                inString = !inString
                continue
            }

            if (inString) continue

            when (c) {
                '{' -> {
                    if (depth == 0) {
                        objectStart = i
                    }
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && objectStart >= 0) {
                        val obj = text.substring(objectStart, i + 1)
                        objects.add(obj)
                        objectStart = -1
                    }
                }
            }
        }

        return objects
    }

    /**
     * Merge multiple JSON objects into a single object.
     * Each input object should have character names as keys.
     * {"Jax": {...}} + {"Zane": {...}} -> {"Jax": {...}, "Zane": {...}}
     */
    @Suppress("UNCHECKED_CAST")
    private fun mergeJsonObjects(jsonObjects: List<String>): String {
        val merged = mutableMapOf<String, Any>()

        for (jsonStr in jsonObjects) {
            try {
                val obj = gson.fromJson(jsonStr, Map::class.java) as? Map<String, Any>
                if (obj != null) {
                    for ((key, value) in obj) {
                        if (!merged.containsKey(key)) {
                            merged[key] = value
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to parse individual JSON object: ${jsonStr.take(100)}", e)
            }
        }

        val result = gson.toJson(merged)
        AppLogger.d(TAG, "Merged ${jsonObjects.size} JSON objects into single object with ${merged.size} keys")
        return result
    }
}

// ==================== Input/Output Data Classes ====================

/** Input for batched analysis prompt */
data class BatchedAnalysisInput(
    val text: String,
    val batchIndex: Int = 0,
    val totalBatches: Int = 1
)

/** Output from batched analysis prompt */
data class BatchedAnalysisOutput(
    val characters: List<ExtractedCharacterData>
)

/** Extracted character data from a single batch */
data class ExtractedCharacterData(
    val name: String,
    val dialogs: List<String> = emptyList(),
    val traits: List<String> = emptyList(),
    val voiceProfile: ExtractedVoiceProfile? = null
)

/** Extracted voice profile */
data class ExtractedVoiceProfile(
    val gender: String = "male",
    val age: String = "middle-aged",
    val accent: String = "neutral",
    val pitch: Float = 1.0f,
    val speed: Float = 1.0f
)
