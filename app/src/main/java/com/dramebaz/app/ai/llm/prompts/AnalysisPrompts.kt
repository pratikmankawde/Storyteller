package com.dramebaz.app.ai.llm.prompts

/**
 * Centralized prompt templates for chapter analysis operations.
 * Includes full analysis, extended analysis, and character detection prompts.
 */
object AnalysisPrompts {
    
    private const val JSON_VALIDITY_REMINDER = "\nEnsure the JSON is valid and contains no trailing commas."
    
    // ==================== Chapter Analysis ====================
    
    val ANALYSIS_SYSTEM_PROMPT = """You are a fiction story analyzer. The input is a NARRATIVE STORY EXCERPT from a novel or book.

STORY STRUCTURE:
- CHARACTERS/ACTORS: People or beings with names who speak dialog or perform actions in the story
- NARRATOR: The storytelling voice (do NOT extract as a character)
- DIALOGS: Quoted speech between characters
- SETTINGS: Places and locations (do NOT extract as characters)

Your task: Extract ONLY the CHARACTERS (people/beings who are actors in the story).

Output valid JSON only. No commentary."""
    
    fun buildAnalysisPrompt(chapterText: String, maxInputChars: Int = 10000): String {
        val text = chapterText.take(maxInputChars) + if (chapterText.length > maxInputChars) "\n[...truncated]" else ""
        return """Analyze this FICTION STORY chapter and extract characters, dialogs, sounds, and summary.

CHARACTERS - Extract ONLY people/beings who are ACTORS in the story:
✅ EXTRACT these (characters with names who speak or act):
   - Named people: "Harry Potter", "Hermione Granger", "Mr. Dursley", "Uncle Vernon"
   - Titled characters: "Professor Dumbledore", "Mrs. Weasley", "Dr. Watson"
   - Characters with dialog (anyone who speaks in quotes)
   - Characters performing actions or mentioned by other characters

❌ DO NOT EXTRACT these:
   - Places/locations: "Hogwarts", "London", "the house", "Privet Drive"
   - Objects: "wand", "letter", "car", "door"
   - Abstract concepts: "magic", "love", "fear", "darkness"
   - The narrator or narrative voice
   - Vague references: "someone", "everyone", "they", "people"
   - Common nouns: "boy", "girl", "man", "woman" (unless it's their actual name/title)

Return ONLY valid JSON:
{
  "chapter_summary": {"title": "Chapter Title", "short_summary": "Brief summary", "main_events": ["event1", "event2"], "emotional_arc": [{"segment": "start", "emotion": "curious", "intensity": 0.5}]},
  "characters": [{"name": "Character Full Name", "traits": ["male/female", "young/adult/old", "personality trait"], "voice_profile": {"pitch": 1.0, "speed": 1.0, "energy": 1.0}}],
  "dialogs": [{"speaker": "Character Name", "dialog": "Exact quoted speech", "emotion": "neutral", "intensity": 0.5, "confidence": 0.9}],
  "sound_cues": [{"event": "door slam", "sound_prompt": "loud wooden bang", "duration": 1.0, "category": "effect"}]
}

<STORY_CHAPTER>
$text
</STORY_CHAPTER>
$JSON_VALIDITY_REMINDER"""
    }
    
    // ==================== Extended Analysis ====================
    
    val EXTENDED_ANALYSIS_SYSTEM_PROMPT = """You are an extraction engine. Your only job is to read the text and output valid JSON. Do not add commentary. Do not guess missing information."""
    
    fun buildExtendedAnalysisPrompt(chapterText: String, maxInputChars: Int = 10000): String {
        val text = chapterText.take(maxInputChars) + if (chapterText.length > maxInputChars) "\n[...truncated]" else ""
        return """Extract themes, symbols, foreshadowing, and vocabulary. Return ONLY valid JSON in this exact format:

{"themes": ["string"], "symbols": ["string"], "foreshadowing": ["string"], "vocabulary": [{"word": "string", "definition": "string"}]}

<CHAPTER_TEXT>
$text
</CHAPTER_TEXT>
$JSON_VALIDITY_REMINDER"""
    }
    
    // ==================== Full Analysis (Combined) ====================
    
    val FULL_ANALYSIS_SYSTEM_PROMPT = """You are a fiction story analyzer. Extract ALL requested information in ONE valid JSON. No commentary."""
    
    fun buildFullAnalysisPrompt(chapterText: String, maxInputChars: Int = 10000): String {
        val text = chapterText.take(maxInputChars) + if (chapterText.length > maxInputChars) "\n[...truncated]" else ""
        return """Analyze this FICTION STORY chapter and return ONE valid JSON with ALL of these fields:

1. chapter_summary: {"title": "Chapter Title", "short_summary": "Brief summary", "main_events": ["event1", "event2"], "emotional_arc": [{"segment": "start", "emotion": "curious", "intensity": 0.5}]}
2. characters: [{"name": "Character Full Name", "traits": ["male/female", "young/adult/old", "personality trait"], "voice_profile": {"pitch": 1.0, "speed": 1.0, "energy": 1.0}}]
   - Extract ONLY people/beings who are ACTORS (speak or act). NOT places, objects, narrator, or vague words.
3. dialogs: [{"speaker": "Character Name", "dialog": "Exact quoted speech", "emotion": "neutral", "intensity": 0.5, "confidence": 0.9}]
4. sound_cues: [{"event": "door slam", "sound_prompt": "loud wooden bang", "duration": 1.0, "category": "effect"}]
5. themes: ["string"]
6. symbols: ["string"]
7. foreshadowing: ["string"]
8. vocabulary: [{"word": "string", "definition": "string"}]

Return ONLY valid JSON in this exact shape (all keys required):
{"chapter_summary": {...}, "characters": [...], "dialogs": [...], "sound_cues": [...], "themes": [], "symbols": [], "foreshadowing": [], "vocabulary": []}

<STORY_CHAPTER>
$text
</STORY_CHAPTER>
$JSON_VALIDITY_REMINDER"""
    }
    
    // ==================== Character Traits Extraction ====================
    
    fun buildExtractTraitsPrompt(characterName: String, chapterText: String, maxInputChars: Int = 10000): String {
        val text = chapterText.take(maxInputChars) + if (chapterText.length > maxInputChars) "\n[...truncated]" else ""
        return """STRICT RULES:
- Extract ONLY traits directly stated or shown in the text
- Include physical descriptions if explicitly mentioned (e.g., "tall", "red hair", "scarred")
- Include behavioral traits if explicitly shown (e.g., "spoke softly", "slammed the door", "laughed nervously")
- Include speech patterns if demonstrated (e.g., "stutters", "uses formal language", "speaks with accent")
- Include emotional states if explicitly described (e.g., "angry", "frightened", "cheerful")
- Do NOT infer personality from actions
- Do NOT add interpretations or assumptions
- Do NOT include traits of other characters
- If no traits are found for this character on this page, return an empty list

OUTPUT FORMAT (valid JSON only):
{"character": "$characterName", "traits": ["trait1", "trait2", "trait3"]}

TEXT:
$text
$JSON_VALIDITY_REMINDER"""
    }
}

