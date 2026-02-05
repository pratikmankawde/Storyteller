package com.dramebaz.app.ai.llm.prompts

import com.google.gson.Gson

/**
 * Centralized prompt templates for character and dialog extraction passes.
 * Separates prompt logic from model implementation for better maintainability.
 */
object ExtractionPrompts {
    
    private val gson = Gson()
    private const val JSON_VALIDITY_REMINDER = "\nEnsure the JSON is valid and contains no trailing commas."
    
    // ==================== Pass-1: Character Name Extraction ====================
    
    val PASS1_SYSTEM_PROMPT = """You are a character name extraction engine. Extract ONLY character names that appear in the provided text."""
    
    fun buildPass1ExtractNamesPrompt(text: String, maxInputChars: Int = 10000): String {
        val truncatedText = text.take(maxInputChars) + if (text.length > maxInputChars) "\n[...truncated]" else ""
        return """STRICT RULES:
- Extract ONLY proper names explicitly written in the text (e.g., "Harry Potter", "Hermione", "Mr. Dursley")
- Do NOT include pronouns (he, she, they, etc.)
- Do NOT include generic descriptions (the boy, the woman, the teacher)
- Do NOT include group references (the family, the crowd, the students)
- Do NOT include titles alone (Professor, Sir, Madam) unless used as the character's actual name
- Do NOT infer or guess names not explicitly mentioned
- Do NOT split full names: if "Harry Potter" appears, do NOT list "Potter" separately
- Do NOT include names of characters who are only mentioned but not present/acting in the scene
- Include a name only if the character speaks, acts, or is directly described in this specific page

OUTPUT FORMAT (valid JSON only):
{"characters": ["Name1", "Name2", "Name3"]}

TEXT:
$truncatedText
$JSON_VALIDITY_REMINDER"""
    }
    
    // ==================== Pass-2: Dialog Extraction ====================
    
    val PASS2_SYSTEM_PROMPT = """You are a dialog extraction engine. Extract quoted speech and attribute it to the correct speaker. Output valid JSON only."""
    
    fun buildPass2ExtractDialogsPrompt(text: String, characterNames: List<String>, maxInputChars: Int = 10000): String {
        val truncatedText = text.take(maxInputChars) + if (text.length > maxInputChars) "\n[...truncated]" else ""
        val charactersWithNarrator = characterNames + listOf("Narrator")
        val charactersJson = gson.toJson(charactersWithNarrator)
        
        return """CHARACTERS ON THIS PAGE: $charactersJson

EXTRACTION RULES:
1. DIALOGS - Extract text within quotation marks ("..." or '...'):
   - Attribute each dialog to the nearest character name appearing BEFORE or AFTER the quote (within ~200 chars)
   - Use attribution patterns: "said [Name]", "[Name] said", "[Name]:", "[Name] asked", "[Name] replied", "whispered", "shouted", "muttered", etc.
   - If a pronoun (he/she/they) refers to a recently mentioned character, attribute to that character
   - If speaker cannot be determined, use "Unknown"

2. NARRATOR TEXT - Extract descriptive prose between dialogs:
   - Scene descriptions, action descriptions, internal thoughts (if not in quotes)
   - Attribute narrator text to "Narrator"
   - Keep narrator segments reasonably sized (1-3 sentences each)

3. EMOTION DETECTION - For each segment:
   - Infer emotion: neutral, happy, sad, angry, surprised, fearful, excited, worried, curious, defiant
   - Estimate intensity: 0.0 (very mild) to 1.0 (very intense)
   - Use context clues: exclamation marks, word choice, described actions

4. ORDERING - Maintain the order of appearance in the text

OUTPUT FORMAT (valid JSON only):
{
  "dialogs": [
    {"speaker": "Character Name", "text": "Exact quoted speech or narrator text", "emotion": "neutral", "intensity": 0.5},
    {"speaker": "Narrator", "text": "Descriptive prose between dialogs", "emotion": "neutral", "intensity": 0.3}
  ]
}

TEXT:
$truncatedText
$JSON_VALIDITY_REMINDER"""
    }
    
    // ==================== Pass-3: Traits and Voice Profile ====================
    
    val PASS3_SYSTEM_PROMPT = "You are a character analyst for TTS voice casting. Extract observable traits and suggest voice profile. JSON only."
    
    fun buildPass3TraitsPrompt(characterName: String, context: String): String {
        return """CHARACTER: "$characterName"

TEXT:
$context

EXTRACT CONCISE TRAITS (1-2 words only):
- Examples: "gravelly voice", "nervous fidgeting", "dry humor", "rambling", "high-pitched", "slow pacing"
- DO NOT write verbose descriptions like "TTS Voice Traits: Pitch: Low..."

TRAIT → VOICE MAPPING:
- "gravelly/deep/commanding" → pitch: 0.8-0.9
- "bright/light/young" → pitch: 1.1-1.2
- "fast-paced/rambling/excited" → speed: 1.1-1.2
- "slow/deliberate/monotone" → speed: 0.8-0.9
- "energetic/dynamic/intense" → energy: 0.9-1.0
- "calm/stoic/reserved" → energy: 0.5-0.7

SPEAKER_ID GUIDE (VCTK 0-108): Male young 0-20, middle-aged 21-45, elderly 46-55; Female young 56-75, middle-aged 76-95, elderly 96-108

OUTPUT FORMAT (valid JSON only):
{
  "character": "$characterName",
  "traits": ["trait1", "trait2", "trait3"],
  "voice_profile": {"pitch": 1.0, "speed": 1.0, "energy": 0.7, "gender": "male|female", "age": "child|young|middle-aged|elderly", "tone": "brief description", "speaker_id": 45}
}
$JSON_VALIDITY_REMINDER"""
    }
}

