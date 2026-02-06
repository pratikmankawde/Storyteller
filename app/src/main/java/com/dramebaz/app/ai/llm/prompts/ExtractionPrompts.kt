package com.dramebaz.app.ai.llm.prompts

import com.google.gson.Gson

/**
 * Centralized prompt templates for character and dialog extraction passes.
 * Separates prompt logic from model implementation for better maintainability.
 *
 * Token Budget (4096 total):
 * - Pass-1: Prompt+Input 3500, Output 100
 * - Pass-2: Prompt+Input 1800, Output 2200
 * - Pass-3: Prompt+Input 2500, Output 1500
 */
object ExtractionPrompts {

    private val gson = Gson()
    private const val JSON_VALIDITY_REMINDER = "\nEnsure the JSON is valid and contains no trailing commas."

    // ==================== Pass-1: Character Name Extraction (Per Page) ====================
    // Token Budget: Prompt+Input 3500, Output 100

    val PASS1_SYSTEM_PROMPT = """You are a character name extraction engine. Extract ONLY character names that appear in the provided story text."""

    /**
     * Build Pass-1 prompt for character name extraction.
     * Uses simplified prompt to maximize input text budget.
     */
    fun buildPass1ExtractNamesPrompt(text: String): String {
        return """OUTPUT FORMAT (valid JSON only):
{"characters": ["Name1", "Name2", "Name3"]}

TEXT:
$text"""
    }

    /**
     * Legacy method for backward compatibility.
     */
    fun buildPass1ExtractNamesPrompt(text: String, maxInputChars: Int = 10000): String {
        val truncatedText = text.take(maxInputChars) + if (text.length > maxInputChars) "\n[...truncated]" else ""
        return buildPass1ExtractNamesPrompt(truncatedText)
    }
    
    // ==================== Pass-2: Dialog Extraction (For All Characters) ====================
    // Token Budget: Prompt+Input 1800, Output 2200

    val PASS2_SYSTEM_PROMPT = """You are a dialog extraction engine. Read the text sequentially and extract all the dialogs of the given characters in the story excerpt."""

    /**
     * Build Pass-2 prompt for dialog extraction.
     * @param text The cleaned story text
     * @param characterNames List of character names appearing on this page (from Pass-1)
     */
    fun buildPass2ExtractDialogsPrompt(text: String, characterNames: List<String>): String {
        val characterNamesStr = characterNames.joinToString(", ")

        return """Extract all dialogs for: $characterNamesStr

OUTPUT FORMAT (valid JSON array):
[{"<character_name>": "<dialog_text>"}]

Example:
[{"Harry": "I'm not going back"}, {"Hermione": "We need to study"}, {"Harry": "Later"}]

TEXT:
$text"""
    }

    /**
     * Legacy method for backward compatibility.
     */
    fun buildPass2ExtractDialogsPrompt(text: String, characterNames: List<String>, maxInputChars: Int): String {
        val truncatedText = text.take(maxInputChars) + if (text.length > maxInputChars) "\n[...truncated]" else ""
        return buildPass2ExtractDialogsPrompt(truncatedText, characterNames)
    }
    
    // ==================== Pass-3: Voice Profile Suggestion ====================
    // Token Budget: Prompt+Input 2500, Output 1500

    val PASS3_SYSTEM_PROMPT = """You are a voice casting director. Suggest a voice profile for characters based ONLY on their depiction in the story."""

    /**
     * Build Pass-3 prompt for voice profile suggestion.
     * @param characterNames List of character names to generate profiles for
     * @param dialogContext Sample dialogs/context for each character
     */
    fun buildPass3VoiceProfilePrompt(characterNames: List<String>, dialogContext: String): String {
        val characterNamesStr = characterNames.joinToString(", ")

        return """Suggest voice profiles for: $characterNamesStr

VOICE PROFILE PARAMETERS (all values 0.5-1.5):
- pitch: 0.5=very low, 1.0=normal, 1.5=very high
- speed: 0.5=very slow, 1.0=normal, 1.5=very fast
- energy: 0.5=calm/subdued, 1.0=normal, 1.5=very energetic

OUTPUT FORMAT (valid JSON):
{
  "characters": [
    {
      "name": "<character_name>",
      "gender": "male|female",
      "age": "child|young|middle-aged|elderly",
      "tone": "brief description",
      "accent": "neutral|british|southern|etc",
      "voice_profile": {
        "pitch": 1.0,
        "speed": 1.0,
        "energy": 1.0,
        "emotion_bias": {"neutral": 0.5, "happy": 0.2}
      }
    }
  ]
}

DIALOGS/CONTEXT:
$dialogContext"""
    }

    /**
     * Legacy method for backward compatibility with single character.
     */
    fun buildPass3TraitsPrompt(characterName: String, context: String): String {
        return buildPass3VoiceProfilePrompt(listOf(characterName), context)
    }
}

