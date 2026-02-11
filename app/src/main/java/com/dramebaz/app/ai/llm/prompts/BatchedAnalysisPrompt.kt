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

    override val systemPrompt: String = """You are a JSON extraction engine. Extract ONLY characters who SPEAK dialog. Ignore locations, objects, creatures, and non-speaking entities."""

    override fun buildUserPrompt(input: BatchedAnalysisInput): String {
        return """Extract Character names, their Dialogs(D), their Traits(T) and their inferred Voice/Speaking Profile(V) from the story text below.
RULES:
1. ONLY include characters who have quoted dialogs
2. DO NOT include locations, objects, creatures or entities that don't speak
3. In the output json, each discovered character must appears EXACTLY ONCE
4. Read the ENTIRE text before outputting

FORMAT: {"<Character-Name>":{"D":["dialog1","dialog2", ...],"T":["trait1", "trait2", ...],"V":"Gender,Age,Accent,Pitch,Speed"}, ... }

KEYS:
- D = Array of ALL quoted dialogs spoken by the keyed Character
- T = Array of Character's physical traits and personality
- V = Their [Gender,Age,Accent]. Options: (male|female, child|young|middle-aged|elderly, neutral|English|American|Asian...)

TEXT:
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
     * Supports both new "v" string format ("male,young,neutral") and old "voice" object format.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseVoiceProfile(charMap: Map<String, Any>): ExtractedVoiceProfile? {
        // Try new "V" or "v" string format: "male,young,neutral"
        val vString = (charMap["V"] ?: charMap["v"])?.toString()
        if (vString != null && vString.contains(",")) {
            val parts = vString.split(",").map { it.trim() }
            if (parts.size >= 3) {
                return ExtractedVoiceProfile(
                    gender = parts[0].ifBlank { "male" },
                    age = parts[1].ifBlank { "middle-aged" },
                    accent = parts[2].ifBlank { "neutral" },
                    pitch = 1.0f,
                    speed = 1.0f
                )
            }
        }

        // Try old "voice" object format
        val voiceMap = charMap["voice"] as? Map<String, Any>
        if (voiceMap != null) {
            return ExtractedVoiceProfile(
                gender = voiceMap["gender"]?.toString() ?: "male",
                age = voiceMap["age"]?.toString() ?: "middle-aged",
                accent = voiceMap["accent"]?.toString() ?: "neutral",
                pitch = (voiceMap["pitch"] as? Number)?.toFloat() ?: 1.0f,
                speed = (voiceMap["speed"] as? Number)?.toFloat() ?: 1.0f
            )
        }

        return null
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
