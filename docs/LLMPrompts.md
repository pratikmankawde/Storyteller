# LLM Prompts Reference

This document lists all LLM prompts currently in use in the Storyteller app. Each prompt is used with the Qwen3-1.7B-Q4_K_M GGUF model via llama.cpp.

## Code Architecture

The LLM code is organized in `app/src/main/java/com/dramebaz/app/ai/llm/` with the following structure:

```
ai/llm/
├── models/
│   ├── LlmModel.kt           # Strategy interface for all LLM models
│   ├── LlmModelFactory.kt    # Factory for creating model instances
│   ├── Qwen3ModelImpl.kt     # Adapter wrapping Qwen3Model
│   └── LiteRtLmEngineImpl.kt # Adapter wrapping LiteRtLmEngine
├── prompts/
│   ├── ExtractionPrompts.kt  # Pass 1-3 character extraction prompts
│   ├── AnalysisPrompts.kt    # Chapter/extended analysis prompts
│   └── StoryPrompts.kt       # Story generation, key moments, relationships prompts
├── LlmService.kt             # Facade pattern - entry point for LLM operations
├── LlmTypes.kt               # Common type definitions
├── QwenStub.kt               # Backward-compatible entry point
├── Qwen3Model.kt             # llama.cpp model implementation
└── LlamaNative.kt            # JNI bindings for llama.cpp
```

**Design Patterns:**
- **Strategy Pattern**: `LlmModel` interface for interchangeable model implementations
- **Factory Pattern**: `LlmModelFactory` creates model instances based on device capabilities
- **Adapter Pattern**: `Qwen3ModelImpl` and `LiteRtLmEngineImpl` wrap existing implementations
- **Facade Pattern**: `LlmService` provides simplified interface to LLM subsystem

## Character Analysis Workflow

The "Analyse Chapters" button uses the **3-Pass Character Analysis** workflow (see Prompts #9, #11, #14):
1. **Pass 1 - Name Extraction**: Extract character names from each page (~10,000 chars), track which pages each character appears on
2. **Pass 2 - Dialog Extraction**: Extract dialogs and narrator text with speaker attribution and emotion detection (processes 2 characters at a time)
3. **Pass 3 - Traits + Voice Profile**: Extract concise 1-2 word traits AND suggest TTS voice profile using aggregated context from all pages (processes 2 characters at a time)

### 3-Pass Optimization

The 3-Pass workflow optimizes character analysis by:
- **Removing Pass-2 trait extraction** - traits are now extracted in Pass-3 with aggregated context
- **Context aggregation** - Pass-3 uses text from ALL pages where a character appears (up to 10,000 chars)
- **Concise traits** - Extracts 1-2 word trait phrases (e.g., "gravelly voice", "nervous fidgeting")
- **Batch processing** - Processes 2 characters at a time in Pass-2 and Pass-3 for efficiency
- **Sequential processing** - No parallel processing for mobile performance stability
- **~33% faster** than the previous 4-pass workflow

**Note:** Extended analysis (themes, symbols, vocabulary) is triggered separately from the **Insights tab** using the "Analyze" button, which calls `extendedAnalysisJson()` (Prompt #2).

## Prompt Format

All prompts use the ChatML format with `/no_think` directive to disable chain-of-thought reasoning:

```
<|im_start|>system
{system_prompt} /no_think<|im_end|>
<|im_start|>user
{user_prompt}<|im_end|>
<|im_start|>assistant
```

All JSON-output prompts include a validity reminder: `"Ensure the JSON is valid and contains no trailing commas."`

---

## 1. Chapter Analysis (Basic)

**Function:** `analyzeChapter()` via `QwenStub.analyzeChapter()`

**Use Case:** Extract characters, dialogs, sound cues, and chapter summary from a story chapter. Used during book import to analyze each chapter.

**Expected Output:**
```json
{
  "chapter_summary": {
    "title": "Chapter Title",
    "short_summary": "Brief summary",
    "main_events": ["event1", "event2"],
    "emotional_arc": [{"segment": "start", "emotion": "curious", "intensity": 0.5}]
  },
  "characters": [{"name": "Character Name", "traits": ["male", "adult", "stern"], "voice_profile": {"pitch": 1.0, "speed": 1.0, "energy": 1.0}}],
  "dialogs": [{"speaker": "Character Name", "dialog": "Exact quoted speech", "emotion": "neutral", "intensity": 0.5, "confidence": 0.9}],
  "sound_cues": [{"event": "door slam", "sound_prompt": "loud wooden bang", "duration": 1.0, "category": "effect"}]
}
```

### System Prompt
```
You are a fiction story analyzer. The input is a NARRATIVE STORY EXCERPT from a novel or book.

STORY STRUCTURE:
- CHARACTERS/ACTORS: People or beings with names who speak dialog or perform actions in the story
- NARRATOR: The storytelling voice (do NOT extract as a character)
- DIALOGS: Quoted speech between characters
- SETTINGS: Places and locations (do NOT extract as characters)

Your task: Extract ONLY the CHARACTERS (people/beings who are actors in the story).

Output valid JSON only. No commentary.
```

### User Prompt
```
Analyze this FICTION STORY chapter and extract characters, dialogs, sounds, and summary.

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
{chapter_text}
</STORY_CHAPTER>
```

---

## 2. Extended Analysis (Themes/Symbols/Vocabulary)

**Function:** `extendedAnalysisJson()` via `QwenStub.extendedAnalysisJson()`

**Use Case:** Extract literary elements (themes, symbols, foreshadowing, vocabulary) separately from character/dialog analysis. Used as second pass after basic analysis.

**Expected Output:**
```json
{
  "themes": ["coming of age", "good vs evil"],
  "symbols": ["the scar", "the wand"],
  "foreshadowing": ["mention of dark times ahead"],
  "vocabulary": [{"word": "muggle", "definition": "non-magical person"}]
}
```

### System Prompt
```
You are an extraction engine. Your only job is to read the text and output valid JSON. Do not add commentary. Do not guess missing information.
```

### User Prompt
```
Extract themes, symbols, foreshadowing, and vocabulary. Return ONLY valid JSON in this exact format:

{"themes": ["string"], "symbols": ["string"], "foreshadowing": ["string"], "vocabulary": [{"word": "string", "definition": "string"}]}

<CHAPTER_TEXT>
{chapter_text}
</CHAPTER_TEXT>
```

---

## 3. Character & Traits Extraction (Segmented)

**Function:** `extractCharactersAndTraitsInSegment()` via `QwenStub.extractCharactersAndTraitsInSegment()`

**Use Case:** Extract characters and their traits from a text segment, with ability to skip already-extracted characters and focus on characters needing traits. Used for incremental character building across multiple segments.

**Expected Output:**
```json
{"characters": [{"name": "Character Name", "traits": ["male", "adult", "stern"]}]}
```

### System Prompt
```
You are a fiction story character extractor. The input is a NARRATIVE STORY EXCERPT from a novel.

CHARACTERS are people or beings who are ACTORS in the story - they speak dialog or perform actions.
DO NOT extract: places, objects, concepts, the narrator, or vague references like "someone".

Output valid JSON only.
```

### User Prompt
```
Extract CHARACTERS (story actors) from this fiction text.

✅ EXTRACT: Named people who speak or act (e.g., "Harry Potter", "Mrs. Dursley", "Professor McGonagall")
❌ DO NOT EXTRACT: Places ("Hogwarts"), objects ("wand"), concepts ("magic"), narrator, or vague words ("someone", "boy")
{skip_line}{need_traits_line}

For each character, include voice traits: gender (male/female), age (child/young/adult/elderly), personality.

Return ONLY valid JSON:
{"characters": [{"name": "Character Name", "traits": ["male", "adult", "stern"]}]}

<STORY_TEXT>
{segment_text}
</STORY_TEXT>
```

---

## 4. Character Detection on Page

**Function:** `detectCharactersOnPage()` via `QwenStub.detectCharactersOnPage()`

**Use Case:** Quick detection of character names on a single page. Returns only names without traits. Used for initial character discovery before trait extraction.

**Expected Output:**
```json
{"names": ["Harry", "Dumbledore", "Mr. Dursley"]}
```

### System Prompt
```
You are a fiction story character detector. The input is a NARRATIVE STORY page.

Extract ONLY character names - people or beings who are ACTORS in the story.
DO NOT extract: places, objects, concepts, the narrator, or common nouns.

Output valid JSON only.
```

### User Prompt
```
List all CHARACTER NAMES from this story page.

✅ EXTRACT: Named people/beings who speak or act (e.g., "Harry", "Dumbledore", "Mr. Dursley")
❌ DO NOT EXTRACT: Places, objects, concepts, narrator references, or vague words

Return ONLY valid JSON:
{"names": ["Character1", "Character2"]}
If no characters found: {"names": []}

<STORY_PAGE>
{page_text}
</STORY_PAGE>
```

---

## 5. Trait Inference for Character

**Function:** `inferTraitsForCharacter()` via `QwenStub.inferTraitsForCharacter()`

**Use Case:** Infer voice-relevant traits (gender, age, accent) for a specific character from a text excerpt. Used when a character is detected but needs trait information for TTS voice selection.

**Expected Output:**
```json
{"traits": ["male", "elderly", "British accent", "wise"]}
```

### System Prompt
```
You are an extraction engine. Your only job is to output valid JSON. Do not add commentary.
```

### User Prompt
```
Infer voice-relevant traits for "{character_name}" from the excerpt (gender, age, accent when inferable). Return ONLY valid JSON:
{"traits": ["trait1", "trait2"]}

<EXCERPT>
{excerpt_text}
</EXCERPT>
```

---

## 6. Voice Profile Suggestion (Batch)

**Function:** `suggestVoiceProfilesJson()` via `QwenStub.suggestVoiceProfilesJson()`

**Use Case:** Suggest TTS voice parameters (pitch, speed, energy, emotion bias) for multiple characters at once. Input is a JSON array of characters with their traits.

**Expected Output:**
```json
{"characters": [{"name": "Harry", "voice_profile": {"pitch": 1.0, "speed": 1.0, "energy": 1.0, "emotion_bias": {"happy": 0.3, "neutral": 0.7}}}]}
```

### System Prompt
```
You are an extraction engine. Your only job is to output valid JSON. Do not add commentary.
```

### User Prompt
```
For each character suggest TTS profile: pitch, speed, energy (0.5-1.5), emotion_bias. Return ONLY valid JSON in this exact format:
{"characters": [{"name": "string", "voice_profile": {"pitch": 1.0, "speed": 1.0, "energy": 1.0, "emotion_bias": {}}}]}

<CHARACTERS>
{characters_with_traits_json}
</CHARACTERS>
```

---

## 7. Key Moments Extraction

**Function:** `extractKeyMomentsForCharacter()` via `QwenStub.extractKeyMomentsForCharacter()`

**Use Case:** Extract 2-3 significant moments for a specific character within a chapter. Key moments include important events, decisions, revelations, or emotional scenes. Used to build character timeline.

**Expected Output:**
```json
{"moments": [{"chapter": "Chapter 1", "moment": "Harry discovers he's a wizard", "significance": "Life-changing revelation"}]}
```

### System Prompt
```
You are an extraction engine. Your only job is to output valid JSON. Do not add commentary.
```

### User Prompt
```
Extract 2-3 key moments for "{character_name}" in this chapter. Key moments are significant events, decisions, revelations, or emotional scenes involving this character.
Return ONLY valid JSON:
{"moments": [{"chapter": "{chapter_title}", "moment": "brief description", "significance": "why it matters"}]}

<TEXT>
{chapter_text}
</TEXT>
```

---

## 8. Relationship Extraction

**Function:** `extractRelationshipsForCharacter()` via `QwenStub.extractRelationshipsForCharacter()`

**Use Case:** Extract relationships between a specific character and other characters in the chapter. Identifies relationship types (family, friend, enemy, romantic, professional). Used to build character relationship graph.

**Expected Output:**
```json
{"relationships": [{"character": "Ron", "relationship": "friend", "nature": "Best friend and loyal companion"}]}
```

### System Prompt
```
You are an extraction engine. Your only job is to output valid JSON. Do not add commentary.
```

### User Prompt
```
Extract relationships between "{character_name}" and other characters: {other_character_names}
Relationship types: family, friend, enemy, romantic, professional, other.
Return ONLY valid JSON:
{"relationships": [{"character": "other character name", "relationship": "type", "nature": "brief description"}]}

<TEXT>
{chapter_text}
</TEXT>
```

---

## 9. Pass-1: Character Name Extraction (3-Pass Workflow)

**Function:** `extractCharacterNames()` via `QwenStub.pass1ExtractCharacterNames()`

**Use Case:** First pass of the 3-pass character analysis workflow. Extracts ONLY character names from text, with strict rules to avoid pronouns, generic descriptions, and group references. Processes text page-by-page (~10,000 chars). **Also tracks which pages each character appears on** for context aggregation in Pass-3.

**Expected Output:**
```json
{"characters": ["Harry Potter", "Hermione Granger", "Mr. Dursley"]}
```

### System Prompt
```
You are a character name extraction engine. Extract ONLY character names that appear in the provided text.
```

### User Prompt
```
STRICT RULES:
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
{chapter_text}
```

---

## 10. Pass-2 (Old): Explicit Trait Extraction (DEPRECATED)

> ⚠️ **DEPRECATED:** Pass-2 trait extraction has been removed in the 3-pass workflow. Traits are now extracted in Pass-3 using aggregated context from all pages where the character appears.

**Function:** `extractTraitsForCharacter()` via `QwenStub.pass2ExtractTraitsForCharacter()` (no longer called)

**Use Case:** ~~Second pass of the 4-pass workflow.~~ This pass is now removed. Pass-3 extracts concise traits with better context from aggregated page text.

**Expected Output:**
```json
{"character": "Harry Potter", "traits": ["black hair", "green eyes", "speaks softly", "nervous"]}
```

### System Prompt
```
You are a trait extraction engine. Extract ONLY the explicitly stated traits for the character "{character_name}" from the provided text.
```

### User Prompt
```
STRICT RULES:
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
{"character": "{character_name}", "traits": ["trait1", "trait2", "trait3"]}

TEXT:
{chapter_text}
```

---

## 11. Pass-2: Dialog Extraction (3-Pass Workflow)

**Function:** `extractDialogsFromPage()` via `QwenStub.pass2ExtractDialogs()`

**Use Case:** Second pass of the 3-pass workflow. Extracts quoted speech and narrator text from each page, attributing dialogs to the correct speaker using proximity search and attribution patterns. Also detects emotion and intensity for each segment. **Processes 2 characters at a time** for efficiency. Runs sequentially for each page after Pass-1.

**Expected Output:**
```json
{
  "dialogs": [
    {"speaker": "Harry Potter", "text": "I'm not going back there.", "emotion": "defiant", "intensity": 0.7},
    {"speaker": "Narrator", "text": "The room fell silent as everyone turned to look.", "emotion": "neutral", "intensity": 0.3},
    {"speaker": "Uncle Vernon", "text": "What did you just say, boy?", "emotion": "angry", "intensity": 0.8}
  ]
}
```

### System Prompt
```
You are a dialog extraction engine. Extract quoted speech and attribute it to the correct speaker. Output valid JSON only.
```

### User Prompt
```
CHARACTERS ON THIS PAGE: ["Harry Potter", "Uncle Vernon", "Aunt Petunia"]

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
{page_text}
```

---

## 12. Pass-3: Personality Inference (DEPRECATED)

> ⚠️ **DEPRECATED:** Pass-3 has been replaced by V7_Combined (Prompt #14). The personality inference step is no longer needed as V7_Combined maps traits directly to voice profiles.

**Function:** `inferPersonalityFromTraits()` via `QwenStub.pass3InferPersonalityFromTraits()`

**Use Case:** ~~Third pass of the 5-pass workflow.~~ This pass is now skipped. V7_Combined extracts rich traits and suggests voice profiles directly without intermediate personality inference.

**Expected Output:**
```json
{"character": "Harry Potter", "personality": ["brave and determined", "humble despite fame", "loyal to friends", "struggles with destiny", "protective of loved ones"]}
```

### System Prompt
```
You are a personality analysis engine. Infer the personality of "{character_name}" based ONLY on the traits provided below.
```

### User Prompt
```
TRAITS:
{traits_list}

STRICT RULES:
- Base your inference ONLY on the provided traits
- Do NOT introduce new traits not in the list
- Do NOT contradict the provided traits
- Synthesize the traits into coherent personality descriptors
- Keep descriptions concise and grounded in the evidence
- Provide 3-5 personality points maximum
- If traits list is empty or insufficient, provide minimal inference (e.g., ["minor character", "limited information"])

OUTPUT FORMAT (valid JSON only):
{"character": "{character_name}", "personality": ["personality_point1", "personality_point2", "personality_point3"]}

TRAITS:
{traits_json}
```

---

## 13. Pass-4: Voice Profile Suggestion (DEPRECATED)

> ⚠️ **DEPRECATED:** Pass-4 has been merged into V7_Combined (Prompt #14). Voice profiles are now suggested directly from traits in a single LLM call.

**Function:** `suggestVoiceProfileForPersonality()` via `QwenStub.pass4SuggestVoiceProfile()`

**Use Case:** ~~Fourth pass of the 5-pass workflow.~~ This pass is now merged into V7_Combined which extracts traits and suggests voice profiles in a single optimized call.

**Expected Output:**
```json
{
  "character": "Harry Potter",
  "voice_profile": {
    "pitch": 1.0,
    "speed": 1.0,
    "energy": 1.1,
    "gender": "male",
    "age": "young",
    "tone": "warm and earnest",
    "accent": "British",
    "emotion_bias": {"happy": 0.2, "sad": 0.2, "angry": 0.1, "neutral": 0.5}
  }
}
```

### System Prompt
```
You are a voice casting director. Suggest a voice profile for "{character_name}" based ONLY on the personality description below.
```

### User Prompt
```
PERSONALITY:
{personality_list}

STRICT RULES:
- Base suggestions ONLY on the provided personality description
- Do NOT invent new personality traits
- Suggest specific voice qualities: pitch (low/medium/high), speed (slow/medium/fast), tone (warm/cold/neutral/energetic)
- Infer likely gender if personality suggests it (e.g., "masculine", "feminine", "neutral")
- Infer likely age range if personality suggests it (e.g., "young", "middle-aged", "elderly")
- Suggest accent or regional qualities if personality implies them (e.g., "formal British", "casual American", "neutral")
- Suggest emotional tendencies (e.g., "tends toward cheerful", "often serious", "emotionally varied")
- Output must be compatible with TTS parameters: pitch (0.5-1.5), speed (0.5-1.5), energy (0.5-1.5)

OUTPUT FORMAT (valid JSON only):
{
  "character": "{character_name}",
  "voice_profile": {
    "pitch": 1.0,
    "speed": 1.0,
    "energy": 1.0,
    "gender": "male|female|neutral",
    "age": "young|middle-aged|elderly",
    "tone": "description",
    "accent": "description or neutral",
    "emotion_bias": {"happy": 0.3, "sad": 0.1, "angry": 0.2, "neutral": 0.4}
  }
}

PERSONALITY:
{personality_json}
```

---

## 14. Pass-3: Traits + Voice Profile (3-Pass Workflow)

**Function:** `pass3ExtractTraitsAndVoiceProfile()` via `QwenStub.pass3ExtractTraitsAndVoiceProfile()`

**Use Case:** Final pass of the 3-pass workflow. Extracts **concise 1-2 word traits** AND suggests TTS voice profile in a **single LLM call**. Uses **aggregated context from ALL pages** where the character appears (up to 10,000 chars). **Processes 2 characters at a time** in a single LLM call for efficiency.

**Benefits:**
- **~33% faster** than the previous 4-pass workflow
- **Aggregated context** - uses text from all pages where character appears
- **Concise traits** - extracts 1-2 word phrases (e.g., "gravelly voice", "nervous fidgeting")
- **Batch processing** - processes 2 characters per LLM call
- Maps traits directly to voice profile parameters
- Includes VCTK speaker_id (0-108) for voice assignment
- 100% JSON validity rate in benchmarks

**Expected Output (Single Character):**
```json
{
  "character": "Jax",
  "traits": ["swagger", "gravelly voice", "cocky confidence", "dynamic pacing"],
  "voice_profile": {
    "pitch": 0.85,
    "speed": 1.1,
    "energy": 0.9,
    "gender": "male",
    "age": "middle-aged",
    "tone": "gravelly and charismatic",
    "accent": "neutral",
    "speaker_id": 35
  }
}
```

**Expected Output (Two Characters):**
```json
{
  "Jax": {
    "traits": ["swagger", "gravelly voice", "cocky confidence"],
    "voice_profile": {"pitch": 0.85, "speed": 1.1, "energy": 0.9, "gender": "male", "age": "middle-aged", "speaker_id": 35}
  },
  "Luna": {
    "traits": ["soft-spoken", "calm demeanor", "thoughtful pauses"],
    "voice_profile": {"pitch": 1.15, "speed": 0.9, "energy": 0.6, "gender": "female", "age": "young", "speaker_id": 65}
  }
}
```

### System Prompt
```
You are a character analyst for TTS voice casting. Extract observable traits and suggest voice profile. JSON only.
```

### User Prompt (Single Character)
```
Character: "{character_name}"

EXTRACT CONCISE TRAITS (1-2 words only) from aggregated text (all pages where character appears):

BEHAVIORAL: "swagger", "nervous fidgeting", "deliberate movements"
SPEECH: "gravelly voice", "rambling", "monotone", "high-pitched", "staccato rhythm"
PERSONALITY: "cocky confidence", "anxious energy", "deadpan humor"

MAP TRAITS TO VOICE PROFILE:
- deep/commanding/gruff → pitch: 0.8-0.9
- bright/young/cheerful → pitch: 1.1-1.2
- fast-paced/excited → speed: 1.1-1.2
- slow/deliberate → speed: 0.8-0.9
- energetic/intense → energy: 0.9-1.0
- calm/reserved → energy: 0.5-0.7

OUTPUT (valid JSON only):
{
  "character": "{character_name}",
  "traits": ["trait1", "trait2", "trait3"],
  "voice_profile": {"pitch": 1.0, "speed": 1.0, "energy": 0.7, "gender": "male|female", "age": "child|young|middle-aged|elderly", "speaker_id": 45}
}

SPEAKER_ID GUIDE (VCTK 0-108):
- Male young: 0-20, middle-aged: 21-45, elderly: 46-55
- Female young: 56-75, middle-aged: 76-95, elderly: 96-108

TEXT (aggregated from all pages):
{aggregated_context}
```

### User Prompt (Two Characters)
```
Analyze TWO characters: "{char1_name}" and "{char2_name}"

EXTRACT CONCISE TRAITS (1-2 words only) for each character:
BEHAVIORAL: "swagger", "nervous fidgeting", "deliberate movements"
SPEECH: "gravelly voice", "rambling", "monotone", "high-pitched"
PERSONALITY: "cocky confidence", "anxious energy", "deadpan humor"

OUTPUT (valid JSON only):
{
  "{char1_name}": {"traits": ["trait1", "trait2"], "voice_profile": {"pitch": 1.0, "speed": 1.0, "energy": 0.7, "gender": "male|female", "age": "...", "speaker_id": 45}},
  "{char2_name}": {"traits": ["trait1", "trait2"], "voice_profile": {"pitch": 1.0, "speed": 1.0, "energy": 0.7, "gender": "male|female", "age": "...", "speaker_id": 65}}
}

CONTEXT FOR {char1_name}:
{char1_aggregated_context}

CONTEXT FOR {char2_name}:
{char2_aggregated_context}
```

**Parameters:**
- Single character: `max_tokens=384`, `temperature=0.1`
- Two characters: `max_tokens=640`, `temperature=0.1`

---

## 15. Story Generation

**Function:** `generateStory()` via `QwenStub.generateStory()`

**Use Case:** Generate a complete, engaging story based on a user prompt. Unlike other prompts, this does NOT output JSON - it outputs pure story text. Used for the story generation feature.

**Expected Output:** Plain text story (no JSON), approximately 1000+ words with dialogue, character development, and descriptive scenes.

### System Prompt
```
You are a creative story writer. Your task is to generate a complete, engaging story based on the user's prompt.
Rules:
1. Generate ONLY story content - no explanations, no meta-commentary, no JSON
2. Write a complete story with a beginning, middle, and end
3. Include dialogue, character development, and descriptive scenes
4. Make the story engaging and well-written
5. The story should be substantial (at least 1000 words)
6. Write in third person narrative style
7. Do not include any instructions or notes, only the story text itself.
Generate the story now:
```

### User Prompt
```
{user_story_prompt}
```

**Note:** This prompt uses `jsonMode = false` and a higher temperature (0.3) for creative output.

---
