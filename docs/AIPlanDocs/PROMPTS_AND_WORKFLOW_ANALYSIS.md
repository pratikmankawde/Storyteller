# Storyteller Prompts and Analysis Workflow - Complete Reference

## Overview
The Storyteller app uses a multi-pass LLM analysis pipeline to extract characters, dialogs, traits, and voice profiles from story text. This document provides the exact prompts and expected output formats.

---

## 1. CHARACTER EXTRACTION PROMPT

**File:** `app/src/main/java/com/dramebaz/app/ai/llm/prompts/CharacterExtractionPrompt.kt`

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
- **Prompt ID:** `character_extraction_v1`
- **Temperature:** 0.1f (deterministic)
- **Token Budget:** 3500 prompt+input, 100 output
- **Input Class:** `CharacterExtractionPromptInput(text: String, pageNumber: Int)`
- **Output Class:** `CharacterExtractionPromptOutput(characterNames: List<String>)`

### Expected JSON Output
```json
{"characters": ["Jax", "Zane", "Kael", "Mina"]}
```

---

## 2. DIALOG EXTRACTION PROMPT

**File:** `app/src/main/java/com/dramebaz/app/ai/llm/prompts/DialogExtractionPrompt.kt`

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
- **Prompt ID:** `dialog_extraction_v1`
- **Temperature:** 0.15f
- **Token Budget:** 1800 prompt+input, 2200 output
- **Input Class:** `DialogExtractionPromptInput(text: String, characterNames: List<String>, pageNumber: Int)`
- **Output Class:** `DialogExtractionPromptOutput(dialogs: List<ExtractedDialogData>)`

### Expected JSON Output
```json
[
  {"Jax": "See? I told you the landing gear was optional."},
  {"Zane": "We're missing a wing, Jax,"},
  {"Kael": "Movement at twelve o'clock,"}
]
```

### ExtractedDialogData Structure
```kotlin
data class ExtractedDialogData(
    val speaker: String,
    val text: String,
    val emotion: String = "neutral",
    val intensity: Float = 0.5f
)
```

---

## 3. VOICE PROFILE PROMPT

**File:** `app/src/main/java/com/dramebaz/app/ai/llm/prompts/VoiceProfilePrompt.kt`

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
- **Prompt ID:** `voice_profile_v1`
- **Temperature:** 0.2f
- **Token Budget:** 2500 prompt+input, 1500 output
- **Input Class:** `VoiceProfilePromptInput(characterNames: List<String>, dialogContext: String)`
- **Output Class:** `VoiceProfilePromptOutput(profiles: List<VoiceProfileData>)`

### VoiceProfileData Structure
```kotlin
data class VoiceProfileData(
    val characterName: String,
    val gender: String = "male",
    val age: String = "adult",
    val tone: String = "",
    val accent: String = "neutral",
    val pitch: Float = 1.0f,
    val speed: Float = 1.0f,
    val energy: Float = 1.0f,
    val emotionBias: Map<String, Float> = emptyMap()
)
```

---

## 4. BATCHED ANALYSIS PROMPT

**File:** `app/src/main/java/com/dramebaz/app/ai/llm/prompts/BatchedAnalysisPrompt.kt`

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
- **Prompt ID:** `batched_analysis_v1`
- **Temperature:** Configurable via `BatchedPipelineConfig.TEMPERATURE`
- **Token Budget:** Centralized in `BatchedPipelineConfig.TOKEN_BUDGET`
- **Input Class:** `BatchedAnalysisInput(text: String, batchIndex: Int, totalBatches: Int)`
- **Output Class:** `BatchedAnalysisOutput(characters: List<ExtractedCharacterData>)`

### Expected JSON Output
```json
{
  "Jax": {
    "D": ["See? I told you the landing gear was optional.", "Let's get out of here,"],
    "T": ["pilot", "confident", "experienced"],
    "V": "male,young,neutral"
  },
  "Zane": {
    "D": ["We're missing a wing, Jax,"],
    "T": ["co-pilot", "concerned"],
    "V": "male,young,neutral"
  }
}
```

### ExtractedCharacterData Structure
```kotlin
data class ExtractedCharacterData(
    val name: String,
    val dialogs: List<String> = emptyList(),
    val traits: List<String> = emptyList(),
    val voiceProfile: ExtractedVoiceProfile? = null
)

data class ExtractedVoiceProfile(
    val gender: String = "male",
    val age: String = "middle-aged",
    val accent: String = "neutral",
    val pitch: Float = 1.0f,
    val speed: Float = 1.0f
)
```

---

## 5. CHAPTER ANALYSIS TASK WORKFLOW

**File:** `app/src/main/java/com/dramebaz/app/ai/llm/tasks/ChapterAnalysisTask.kt`

### Execution Flow
1. **Load/Create Context:** Checkpoint-based persistence with 24-hour expiry
2. **Step 1 - Character Extraction:** Extract character names from all pages
3. **Step 2 - Dialog Extraction:** Extract dialogs for identified characters
4. **Step 3 - Voice Profile:** Suggest voice profiles based on dialogs
5. **Save Results:** Serialize to JSON and persist to database

### Checkpoint Data Structure
```kotlin
data class AnalysisCheckpoint(
    val bookId: Long,
    val chapterId: Long,
    val timestamp: Long,
    val contentHash: Int,
    val lastCompletedStep: Int,  // -1=none, 0=CharExt, 1=DialogExt, 2=complete
    val characters: Map<String, SerializableCharacterData>,
    val totalDialogs: Int,
    val pagesProcessed: Int
)
```

---

## 6. PDF EXTRACTION

**File:** `app/src/main/java/com/dramebaz/app/pdf/PdfExtractor.kt`

### Process
- Uses PDFBox library for text extraction
- Extracts one page at a time
- Returns `List<String>` where each element is a page's text
- Handles initialization, validation, and error recovery

### Usage
```kotlin
val extractor = PdfExtractor(context)
val pages = extractor.extractText(pdfFile)  // List<String>
```

---

## 7. EXPECTED RESULTS FORMAT

**File:** `app/src/main/assets/demo/SpaceStoryAnalysis.json`

```json
{
  "characters": ["Jax", "Zane", "Kael", "Mina", "Lyra", "Crystalline Hydra"],
  "chapters": [
    {
      "chapter": 1,
      "title": "Chapter 1",
      "characters": ["Jax", "Zane", "Kael", "Mina", "Crystalline Hydra"],
      "dialogs": [
        {"speaker": "Jax", "text": "See? I told you the landing gear was optional."},
        {"speaker": "Zane", "text": "We're missing a wing, Jax,"}
      ]
    }
  ]
}
```

---

## Key Implementation Details

### Response Parsing
All prompts include robust JSON extraction that:
- Removes markdown code blocks (```json, ```)
- Handles multiple JSON objects (JSONL format)
- Merges separate objects into single structure
- Truncates at duplicate keys (LLM repetition handling)
- Supports multiple field name variations (D/d/dialogs, T/t/traits, V/v/voice)

### Token Budget Management
- Input text is truncated to fit token budget
- Paragraph boundaries are respected when truncating
- Each prompt has specific token allocations for prompt, input, and output

### Temperature Settings
- **Character Extraction:** 0.1f (deterministic)
- **Dialog Extraction:** 0.15f (low variance)
- **Voice Profile:** 0.2f (slightly creative)
- **Batched Analysis:** Configurable


