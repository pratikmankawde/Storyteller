# Storyteller Prompts - Examples and Usage Patterns

## Example 1: Character Extraction

### Input
```kotlin
val input = CharacterExtractionPromptInput(
    text = """
        Jax looked at the damaged ship. "See? I told you the landing gear was optional."
        Zane shook his head. "We're missing a wing, Jax,"
        Kael pointed ahead. "Movement at twelve o'clock,"
    """,
    pageNumber = 1
)
```

### LLM Call
```
System: You are a character name extraction engine. Extract ONLY character names that appear in the provided story text.

User: OUTPUT FORMAT (valid JSON only):
{"characters": ["Name1", "Name2", "Name3"]}

TEXT:
[story text...]
```

### Expected Output
```json
{"characters": ["Jax", "Zane", "Kael"]}
```

### Parsed Result
```kotlin
CharacterExtractionPromptOutput(
    characterNames = listOf("Jax", "Zane", "Kael")
)
```

---

## Example 2: Dialog Extraction

### Input
```kotlin
val input = DialogExtractionPromptInput(
    text = """
        Jax looked at the damaged ship. "See? I told you the landing gear was optional."
        Zane shook his head. "We're missing a wing, Jax,"
        Kael pointed ahead. "Movement at twelve o'clock,"
    """,
    characterNames = listOf("Jax", "Zane", "Kael"),
    pageNumber = 1
)
```

### LLM Call
```
System: You are a dialog extraction engine. Read the text sequentially and extract all the dialogs of the given characters in the story excerpt.

User: Extract all dialogs for: Jax, Zane, Kael

OUTPUT FORMAT (valid JSON array):
[{"<character_name>": "<dialog_text>"}]

Example:
[{"Harry": "I'm not going back"}, {"Hermione": "We need to study"}, {"Harry": "Later"}]

TEXT:
[story text...]
```

### Expected Output
```json
[
  {"Jax": "See? I told you the landing gear was optional."},
  {"Zane": "We're missing a wing, Jax,"},
  {"Kael": "Movement at twelve o'clock,"}
]
```

### Parsed Result
```kotlin
DialogExtractionPromptOutput(
    dialogs = listOf(
        ExtractedDialogData("Jax", "See? I told you the landing gear was optional.", "neutral", 0.5f),
        ExtractedDialogData("Zane", "We're missing a wing, Jax,", "neutral", 0.5f),
        ExtractedDialogData("Kael", "Movement at twelve o'clock,", "neutral", 0.5f)
    )
)
```

---

## Example 3: Voice Profile Suggestion

### Input
```kotlin
val input = VoiceProfilePromptInput(
    characterNames = listOf("Jax", "Zane"),
    dialogContext = """
        Jax: "See? I told you the landing gear was optional."
        Zane: "We're missing a wing, Jax,"
        Jax: "Zane, cause a distraction. Lyra, find its weak spot."
    """
)
```

### LLM Call
```
System: You are a voice casting director. Suggest a voice profile for characters based ONLY on their depiction in the story.

User: Suggest voice profiles for: Jax, Zane

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
[dialogs...]
```

### Expected Output
```json
{
  "characters": [
    {
      "name": "Jax",
      "gender": "male",
      "age": "young",
      "tone": "confident, commanding",
      "accent": "neutral",
      "voice_profile": {
        "pitch": 1.0,
        "speed": 1.1,
        "energy": 1.2,
        "emotion_bias": {"neutral": 0.4, "confident": 0.6}
      }
    },
    {
      "name": "Zane",
      "gender": "male",
      "age": "young",
      "tone": "concerned, cautious",
      "accent": "neutral",
      "voice_profile": {
        "pitch": 0.95,
        "speed": 1.0,
        "energy": 0.9,
        "emotion_bias": {"neutral": 0.5, "worried": 0.5}
      }
    }
  ]
}
```

### Parsed Result
```kotlin
VoiceProfilePromptOutput(
    profiles = listOf(
        VoiceProfileData(
            characterName = "Jax",
            gender = "male",
            age = "young",
            tone = "confident, commanding",
            accent = "neutral",
            pitch = 1.0f,
            speed = 1.1f,
            energy = 1.2f,
            emotionBias = mapOf("neutral" to 0.4f, "confident" to 0.6f)
        ),
        VoiceProfileData(
            characterName = "Zane",
            gender = "male",
            age = "young",
            tone = "concerned, cautious",
            accent = "neutral",
            pitch = 0.95f,
            speed = 1.0f,
            energy = 0.9f,
            emotionBias = mapOf("neutral" to 0.5f, "worried" to 0.5f)
        )
    )
)
```

---

## Example 4: Batched Analysis

### Input
```kotlin
val input = BatchedAnalysisInput(
    text = """
        Jax looked at the damaged ship. "See? I told you the landing gear was optional."
        Zane shook his head. "We're missing a wing, Jax,"
        Kael pointed ahead. "Movement at twelve o'clock,"
        Mina appeared. "You seek the Pulse,"
    """,
    batchIndex = 1,
    totalBatches = 3
)
```

### LLM Call
```
System: You are a JSON extraction engine. Output EXACTLY ONE valid JSON object containing all characters. Extract ONLY characters who SPEAK dialog. Ignore locations, objects, creatures, and non-speaking entities.

User: Extract characters from the story text below.

RULES:
1. ONLY include characters who have quoted dialogs
2. DO NOT include locations, objects, creatures or entities that don't speak
3. Each character appears EXACTLY ONCE in the output
4. Output EXACTLY ONE JSON object containing ALL characters

OUTPUT FORMAT (single JSON object with all characters):
{
  "CharacterName1": {"D": ["dialog1", "dialog2"], "T": ["trait1", "trait2"], "V": "male,young,neutral"},
  "CharacterName2": {"D": ["dialog1"], "T": ["trait1"], "V": "female,middle-aged,neutral"}
}

KEYS:
- D = Array of exact quoted dialogs spoken by this character
- T = Array of character traits (age, gender, personality)
- V = Voice profile as "Gender,Age,Accent"

TEXT:
[story text...]

JSON:
```

### Expected Output
```json
{
  "Jax": {
    "D": ["See? I told you the landing gear was optional."],
    "T": ["pilot", "confident", "experienced"],
    "V": "male,young,neutral"
  },
  "Zane": {
    "D": ["We're missing a wing, Jax,"],
    "T": ["co-pilot", "concerned"],
    "V": "male,young,neutral"
  },
  "Kael": {
    "D": ["Movement at twelve o'clock,"],
    "T": ["navigator", "alert"],
    "V": "male,young,neutral"
  },
  "Mina": {
    "D": ["You seek the Pulse,"],
    "T": ["mysterious", "wise"],
    "V": "female,middle-aged,neutral"
  }
}
```

### Parsed Result
```kotlin
BatchedAnalysisOutput(
    characters = listOf(
        ExtractedCharacterData(
            name = "Jax",
            dialogs = listOf("See? I told you the landing gear was optional."),
            traits = listOf("pilot", "confident", "experienced"),
            voiceProfile = ExtractedVoiceProfile("male", "young", "neutral", 1.0f, 1.0f)
        ),
        // ... more characters
    )
)
```

---

## Usage in ChapterAnalysisTask

### Step-by-Step Execution
```kotlin
// Step 1: Character Extraction
val charNames = CharacterExtractionPrompt().buildUserPrompt(input)
// → Extract: ["Jax", "Zane", "Kael", "Mina"]

// Step 2: Dialog Extraction
val dialogs = DialogExtractionPrompt().buildUserPrompt(input)
// → Extract: [{"Jax": "..."}, {"Zane": "..."}, ...]

// Step 3: Voice Profile
val voices = VoiceProfilePrompt().buildUserPrompt(input)
// → Extract: {"characters": [{name: "Jax", ...}, ...]}

// Result: AccumulatedCharacterData for each character
```

### Checkpoint Save/Load
```kotlin
// After each step, checkpoint is saved:
AnalysisCheckpoint(
    bookId = 123,
    chapterId = 456,
    timestamp = System.currentTimeMillis(),
    contentHash = contentHash,
    lastCompletedStep = 0,  // Just completed character extraction
    characters = {...},
    totalDialogs = 5,
    pagesProcessed = 3
)

// On resume, load checkpoint and skip completed steps
val checkpoint = loadCheckpoint(contentHash)
if (checkpoint != null && checkpoint.lastCompletedStep >= 0) {
    // Skip character extraction, start with dialog extraction
}
```

---

## Common LLM Response Variations

### Dialog Format Variations
```json
// Format 1: Array of single-key objects
[{"Jax": "dialog1"}, {"Zane": "dialog2"}]

// Format 2: Object with "dialogs" key
{"dialogs": [{"speaker": "Jax", "text": "dialog1"}]}

// Format 3: Array of objects with speaker/text
[{"speaker": "Jax", "text": "dialog1"}]
```

### Voice Profile Format Variations
```json
// Format 1: String format (new)
"V": "male,young,neutral"

// Format 2: Object format (old)
"voice": {"gender": "male", "age": "young", "accent": "neutral"}
```

All variations are supported by the parsing logic!


