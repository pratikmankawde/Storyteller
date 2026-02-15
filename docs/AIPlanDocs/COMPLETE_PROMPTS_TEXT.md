# Complete Prompt Text - Copy/Paste Ready

## 1. CHARACTER EXTRACTION PROMPT

### System Prompt
```
You are a character name extraction engine. Extract ONLY character names that appear in the provided story text.
```

### User Prompt Template
```
OUTPUT FORMAT (valid JSON only):
{"characters": ["Name1", "Name2", "Name3"]}

TEXT:
${input.text}
```

### Configuration
- Temperature: 0.1f
- Token Budget: 3500 (prompt+input) + 100 (output)

---

## 2. DIALOG EXTRACTION PROMPT

### System Prompt
```
You are a dialog extraction engine. Read the text sequentially and extract all the dialogs of the given characters in the story excerpt.
```

### User Prompt Template
```
Extract all dialogs for: ${characterNames}

OUTPUT FORMAT (valid JSON array):
[{"<character_name>": "<dialog_text>"}]

Example:
[{"Harry": "I'm not going back"}, {"Hermione": "We need to study"}, {"Harry": "Later"}]

TEXT:
${input.text}
```

### Configuration
- Temperature: 0.15f
- Token Budget: 1800 (prompt+input) + 2200 (output)

---

## 3. VOICE PROFILE PROMPT

### System Prompt
```
You are a voice casting director. Suggest a voice profile for characters based ONLY on their depiction in the story.
```

### User Prompt Template
```
Suggest voice profiles for: ${characterNames}

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
${input.dialogContext}
```

### Configuration
- Temperature: 0.2f
- Token Budget: 2500 (prompt+input) + 1500 (output)

---

## 4. BATCHED ANALYSIS PROMPT

### System Prompt
```
You are a JSON extraction engine. Output EXACTLY ONE valid JSON object containing all characters. Extract ONLY characters who SPEAK dialog. Ignore locations, objects, creatures, and non-speaking entities.
```

### User Prompt Template
```
Extract characters from the story text below.

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
- V = Voice profile as "Gender,Age,Accent" (e.g. "male,young,neutral" or "female,elderly,British")

TEXT:
${input.text}

JSON:
```

### Configuration
- Temperature: Configurable (see BatchedPipelineConfig)
- Token Budget: Configurable (see BatchedPipelineConfig)

---

## Expected JSON Outputs

### Character Extraction
```json
{"characters": ["Jax", "Zane", "Kael", "Mina"]}
```

### Dialog Extraction
```json
[
  {"Jax": "See? I told you the landing gear was optional."},
  {"Zane": "We're missing a wing, Jax,"},
  {"Kael": "Movement at twelve o'clock,"},
  {"Mina": "You seek the Pulse,"}
]
```

### Voice Profile
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

### Batched Analysis
```json
{
  "Jax": {
    "D": [
      "See? I told you the landing gear was optional.",
      "Zane, cause a distraction. Lyra, find its weak spot. Kael, try not to get shattered."
    ],
    "T": ["pilot", "confident", "experienced", "commanding"],
    "V": "male,young,neutral"
  },
  "Zane": {
    "D": ["We're missing a wing, Jax,"],
    "T": ["co-pilot", "concerned", "cautious"],
    "V": "male,young,neutral"
  },
  "Kael": {
    "D": ["Movement at twelve o'clock,", "Standard protocol?"],
    "T": ["navigator", "alert", "professional"],
    "V": "male,young,neutral"
  },
  "Mina": {
    "D": [
      "You seek the Pulse,",
      "But the path is guarded by the Crystalline Hydra. If you want to live, follow the silence, not the light."
    ],
    "T": ["mysterious", "wise", "cryptic"],
    "V": "female,middle-aged,neutral"
  }
}
```

---

## Response Parsing Notes

### Supported JSON Variations

**Dialog Format Variations:**
- Array of single-key objects: `[{"Jax": "text"}]`
- Object with "dialogs" key: `{"dialogs": [{"speaker": "Jax", "text": "text"}]}`
- Array of speaker/text objects: `[{"speaker": "Jax", "text": "text"}]`

**Voice Profile Format Variations:**
- String format: `"V": "male,young,neutral"`
- Object format: `"voice": {"gender": "male", "age": "young", "accent": "neutral"}`

**Field Name Variations:**
- Dialogs: "D", "d", "dialogs"
- Traits: "T", "t", "traits"
- Voice: "V", "v", "voice"

### Markdown Handling
Parser automatically removes:
- ` ```json ` prefix
- ` ``` ` prefix/suffix
- Common prefixes: "Here is the JSON:", "Output:", "Result:", "JSON:"

### Error Recovery
- Empty response → returns empty output
- Invalid JSON → logs error, returns empty output
- Duplicate keys → truncates at first duplicate
- Multiple objects → merges into single object

---

## Integration Example

```kotlin
// Create prompt instance
val prompt = CharacterExtractionPrompt()

// Prepare input
val input = CharacterExtractionPromptInput(
    text = chapterText,
    pageNumber = 1
)
val preparedInput = prompt.prepareInput(input)

// Build prompts
val systemPrompt = prompt.systemPrompt
val userPrompt = prompt.buildUserPrompt(preparedInput)

// Call LLM
val response = model.generateResponse(
    systemPrompt = systemPrompt,
    userPrompt = userPrompt,
    maxTokens = prompt.tokenBudget.maxOutputTokens,
    temperature = prompt.temperature
)

// Parse response
val output = prompt.parseResponse(response)
// output.characterNames contains extracted names
```

---

## Token Budget Reference

| Prompt | Prompt Tokens | Input Tokens | Output Tokens | Total |
|--------|---------------|--------------|---------------|-------|
| Character Extraction | 200 | 3500 | 100 | 3800 |
| Dialog Extraction | 200 | 1800 | 2200 | 4200 |
| Voice Profile | 200 | 2500 | 1500 | 4200 |
| Batched Analysis | Configurable | Configurable | Configurable | Configurable |


