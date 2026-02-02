
    private val PASS1_SYSTEM_PROMPT = """You are a character extraction engine for TTS voice casting.
Extract ALL character names and suggest voice profiles for text-to-speech. Output valid JSON only."""

    private fun buildPass1Prompt(text: String): String = """Analyze this story text and extract ALL characters with their voice profiles.

EXTRACTION RULES:
- Extract ONLY proper names of people/beings who speak or act in the story
- Include characters with dialog (quoted speech) or performing actions
- DO NOT include places, objects, abstract concepts, or the narrator
- For each character, suggest a complete voice_profile for TTS

VOICE PROFILE PARAMETERS:
- pitch: 0.2-1.8 (0.8-0.9 for deep/commanding, 1.1-1.2 for bright/young)
- speed: 0.5-1.5 (0.8-0.9 for slow/deliberate, 1.1-1.2 for fast/excited)
- energy: 0.1-1.0 (0.5-0.7 for calm/reserved, 0.9-1.0 for energetic)
- gender: "male" | "female" | "neutral"
- age: "child" | "young" | "middle-aged" | "elderly"
- tone: brief 1-2 word description (e.g., "warm friendly", "gruff commanding")
- accent: "neutral" | "british" | "american" | "other"
- emotion_bias: map of emotion weights (e.g., {"joy": 0.3, "calm": 0.5})

SPEAKER_ID GUIDE (VCTK corpus 0-108):
- Male young: 0-20, middle-aged: 21-45, elderly: 46-55
- Female young: 56-75, middle-aged: 76-95, elderly: 96-108

OUTPUT FORMAT (valid JSON only):
{
  "characters": [
    {
      "name": "Character Name",
      "traits": ["trait1", "trait2"],
      "voice_profile": {
        "pitch": 1.0,
        "speed": 1.0,
        "energy": 0.7,
        "gender": "male",
        "age": "middle-aged",
        "tone": "warm friendly",
        "accent": "neutral",
        "emotion_bias": {"calm": 0.5},
        "speaker_id": 35
      }
    }
  ]
}

<TEXT>
$text
</TEXT>"""

    private val PASS2_SYSTEM_PROMPT = """You are a dialog extraction engine.
Extract quoted speech and attribute it to the correct speaker. Output valid JSON only."""

    private fun buildPass2Prompt(text: String, characterNames: List<String>): String {
        val charactersJson = gson.toJson(characterNames + listOf("Narrator"))
        return """Extract ALL dialogs and narrator text from this passage.

KNOWN CHARACTERS: $charactersJson

ATTRIBUTION RULES:
1. Use EXPLICIT attribution when available:
   - "X said/asked/replied/whispered" → speaker is X
   - Direct quotes immediately after character action → speaker is that character

2. Use CONTEXTUAL clues when no explicit attribution:
   - Pronoun references (he/she) → refer to most recently mentioned character
   - Dialog in a scene with only two characters → alternate speakers
   - If truly uncertain, use "Unknown"

3. NARRATOR for non-dialog prose:
   - Descriptions, scene-setting, internal thoughts (if not quoted)
   - Keep narrator segments reasonably sized (not entire paragraphs)

EMOTION DETECTION:
- Detect emotion from dialog content and attribution verbs
- Common emotions: neutral, happy, sad, angry, fearful, surprised, disgusted, excited, calm
- Intensity: 0.0-1.0 (how strong the emotion is)

OUTPUT FORMAT (valid JSON only):
{
  "dialogs": [
    {"speaker": "Character Name", "text": "Exact quoted text", "emotion": "neutral", "intensity": 0.5},
    {"speaker": "Narrator", "text": "Prose description...", "emotion": "neutral", "intensity": 0.3}
  ]
}

<TEXT>
$text
</TEXT>"""