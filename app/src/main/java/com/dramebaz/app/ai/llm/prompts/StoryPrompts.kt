package com.dramebaz.app.ai.llm.prompts

/**
 * Centralized prompt templates for story generation.
 */
object StoryPrompts {
    
    val STORY_GENERATION_SYSTEM_PROMPT = """You are a creative story writer. Your task is to generate a complete, engaging story based on the user's prompt.
Rules:
1. Generate ONLY story content - no explanations, no meta-commentary, no JSON
2. Write a complete story with a beginning, middle, and end
3. Include dialogue, character development, and descriptive scenes
4. Make the story engaging and well-written
5. The story should be substantial (at least 1000 words)
6. Write in third person narrative style
7. Do not include any instructions or notes, only the story text itself.
Generate the story now:"""
    
    fun buildStoryPrompt(userPrompt: String): String {
        return userPrompt
    }
    
    // ==================== Key Moments Extraction ====================
    
    private const val JSON_VALIDITY_REMINDER = "\nEnsure the JSON is valid and contains no trailing commas."
    
    val KEY_MOMENTS_SYSTEM_PROMPT = """You are an extraction engine. Your only job is to output valid JSON. Do not add commentary."""
    
    fun buildKeyMomentsPrompt(characterName: String, chapterText: String, chapterTitle: String, maxInputChars: Int = 10000): String {
        val text = chapterText.take(maxInputChars) + if (chapterText.length > maxInputChars) "\n[...truncated]" else ""
        return """Extract 2-3 key moments for "$characterName" in this chapter. Key moments are significant events, decisions, revelations, or emotional scenes involving this character.
Return ONLY valid JSON:
{"moments": [{"chapter": "$chapterTitle", "moment": "brief description", "significance": "why it matters"}]}

<TEXT>
$text
</TEXT>
$JSON_VALIDITY_REMINDER"""
    }
    
    // ==================== Relationships Extraction ====================
    
    val RELATIONSHIPS_SYSTEM_PROMPT = """You are an extraction engine. Your only job is to output valid JSON. Do not add commentary."""
    
    fun buildRelationshipsPrompt(characterName: String, chapterText: String, otherCharacters: List<String>, maxInputChars: Int = 10000): String {
        val text = chapterText.take(maxInputChars) + if (chapterText.length > maxInputChars) "\n[...truncated]" else ""
        val otherNames = otherCharacters.filter { !it.equals(characterName, ignoreCase = true) }.take(20).joinToString(", ")
        return """Extract relationships between "$characterName" and other characters: $otherNames
Relationship types: family, friend, enemy, romantic, professional, other.
Return ONLY valid JSON:
{"relationships": [{"character": "other character name", "relationship": "type", "nature": "brief description"}]}

<TEXT>
$text
</TEXT>
$JSON_VALIDITY_REMINDER"""
    }
    
    // ==================== Voice Profile Suggestion ====================
    
    val VOICE_PROFILE_SYSTEM_PROMPT = """You are an extraction engine. Your only job is to output valid JSON. Do not add commentary."""
    
    fun buildVoiceProfilesPrompt(charactersWithTraitsJson: String): String {
        return """For each character suggest TTS profile: pitch, speed, energy (0.5-1.5), emotion_bias. Return ONLY valid JSON in this exact format:
{"characters": [{"name": "string", "voice_profile": {"pitch": 1.0, "speed": 1.0, "energy": 1.0, "emotion_bias": {}}}]}

<CHARACTERS>
$charactersWithTraitsJson
</CHARACTERS>
$JSON_VALIDITY_REMINDER"""
    }
    
    // ==================== Infer Traits ====================
    
    val INFER_TRAITS_SYSTEM_PROMPT = """You are an extraction engine. Your only job is to output valid JSON. Do not add commentary."""
    
    fun buildInferTraitsPrompt(characterName: String, excerpt: String): String {
        val text = excerpt.take(5000) + if (excerpt.length > 5000) "\n[...truncated]" else ""
        return """Infer voice-relevant traits for "$characterName" from the excerpt (gender, age, accent when inferable). Return ONLY valid JSON:
{"traits": ["trait1", "trait2"]}

<EXCERPT>
$text
</EXCERPT>
$JSON_VALIDITY_REMINDER"""
    }
    
    // ==================== Character Detection ====================
    
    val DETECT_CHARACTERS_SYSTEM_PROMPT = """You are a fiction story character detector. The input is a NARRATIVE STORY page.

Extract ONLY character names - people or beings who are ACTORS in the story.
DO NOT extract: places, objects, concepts, the narrator, or common nouns.

Output valid JSON only."""
    
    fun buildDetectCharactersPrompt(pageText: String, maxInputChars: Int = 10000): String {
        val text = pageText.take(maxInputChars) + if (pageText.length > maxInputChars) "\n[...truncated]" else ""
        return """List all CHARACTER NAMES from this story page.

✅ EXTRACT: Named people/beings who speak or act (e.g., "Harry", "Dumbledore", "Mr. Dursley")
❌ DO NOT EXTRACT: Places, objects, concepts, narrator references, or vague words

Return ONLY valid JSON:
{"names": ["Character1", "Character2"]}
If no characters found: {"names": []}

<STORY_PAGE>
$text
</STORY_PAGE>
$JSON_VALIDITY_REMINDER"""
    }
}

