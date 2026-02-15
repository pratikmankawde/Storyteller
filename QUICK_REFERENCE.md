# Storyteller Prompts - Quick Reference

## File Locations

| Component | File Path |
|-----------|-----------|
| Character Extraction | `app/src/main/java/com/dramebaz/app/ai/llm/prompts/CharacterExtractionPrompt.kt` |
| Dialog Extraction | `app/src/main/java/com/dramebaz/app/ai/llm/prompts/DialogExtractionPrompt.kt` |
| Voice Profile | `app/src/main/java/com/dramebaz/app/ai/llm/prompts/VoiceProfilePrompt.kt` |
| Batched Analysis | `app/src/main/java/com/dramebaz/app/ai/llm/prompts/BatchedAnalysisPrompt.kt` |
| Chapter Analysis Task | `app/src/main/java/com/dramebaz/app/ai/llm/tasks/ChapterAnalysisTask.kt` |
| PDF Extractor | `app/src/main/java/com/dramebaz/app/pdf/PdfExtractor.kt` |
| Data Classes | `app/src/main/java/com/dramebaz/app/ai/llm/prompts/PromptInputOutput.kt` |
| Example Results | `app/src/main/assets/demo/SpaceStoryAnalysis.json` |

---

## Prompt Summary Table

| Prompt | System Prompt | Temperature | Token Budget | Output Format |
|--------|---------------|-------------|--------------|---------------|
| **Character Extraction** | "Extract ONLY character names" | 0.1f | 3500+100 | `{"characters": [...]}` |
| **Dialog Extraction** | "Extract all dialogs of given characters" | 0.15f | 1800+2200 | `[{"name": "text"}]` |
| **Voice Profile** | "Suggest voice profile based on depiction" | 0.2f | 2500+1500 | `{"characters": [...]}` |
| **Batched Analysis** | "Output EXACTLY ONE JSON object" | Config | Config | `{"CharName": {"D": [...], "T": [...], "V": "..."}}` |

---

## Input/Output Classes Quick Map

### Character Extraction
```
Input:  CharacterExtractionPromptInput(text, pageNumber)
Output: CharacterExtractionPromptOutput(characterNames: List<String>)
```

### Dialog Extraction
```
Input:  DialogExtractionPromptInput(text, characterNames, pageNumber)
Output: DialogExtractionPromptOutput(dialogs: List<ExtractedDialogData>)
        ExtractedDialogData(speaker, text, emotion, intensity)
```

### Voice Profile
```
Input:  VoiceProfilePromptInput(characterNames, dialogContext)
Output: VoiceProfilePromptOutput(profiles: List<VoiceProfileData>)
        VoiceProfileData(characterName, gender, age, tone, accent, pitch, speed, energy, emotionBias)
```

### Batched Analysis
```
Input:  BatchedAnalysisInput(text, batchIndex, totalBatches)
Output: BatchedAnalysisOutput(characters: List<ExtractedCharacterData>)
        ExtractedCharacterData(name, dialogs, traits, voiceProfile)
        ExtractedVoiceProfile(gender, age, accent, pitch, speed)
```

---

## JSON Output Formats

### Character Extraction Output
```json
{"characters": ["Jax", "Zane", "Kael"]}
```

### Dialog Extraction Output
```json
[
  {"Jax": "See? I told you the landing gear was optional."},
  {"Zane": "We're missing a wing, Jax,"}
]
```

### Voice Profile Output
```json
{
  "characters": [
    {
      "name": "Jax",
      "gender": "male",
      "age": "young",
      "tone": "confident",
      "accent": "neutral",
      "voice_profile": {
        "pitch": 1.0,
        "speed": 1.1,
        "energy": 1.2,
        "emotion_bias": {"neutral": 0.4, "confident": 0.6}
      }
    }
  ]
}
```

### Batched Analysis Output
```json
{
  "Jax": {
    "D": ["dialog1", "dialog2"],
    "T": ["trait1", "trait2"],
    "V": "male,young,neutral"
  },
  "Zane": {
    "D": ["dialog1"],
    "T": ["trait1"],
    "V": "male,young,neutral"
  }
}
```

---

## Key Implementation Details

### Response Parsing Features
- ✅ Removes markdown code blocks (```json, ```)
- ✅ Handles multiple JSON objects (JSONL format)
- ✅ Merges separate objects into single structure
- ✅ Truncates at duplicate keys (LLM repetition)
- ✅ Supports multiple field name variations (D/d/dialogs, T/t/traits, V/v/voice)
- ✅ Extracts JSON from text with prefixes ("Here is the JSON:", etc.)

### Checkpoint Persistence
- **Location:** `${appContext.cacheDir}/chapter_analysis_checkpoints/{bookId}_{chapterId}.json`
- **TTL:** 24 hours
- **Validation:** Content hash check, corruption detection
- **Resume:** Skips completed steps, continues from last checkpoint

### Token Budget Management
- Input text truncated to fit budget
- Paragraph boundaries respected when truncating
- Each prompt has specific allocations for prompt, input, output

### Temperature Settings
- **0.1f:** Character Extraction (deterministic)
- **0.15f:** Dialog Extraction (low variance)
- **0.2f:** Voice Profile (slightly creative)
- **Config:** Batched Analysis (configurable)

---

## Analysis Workflow Steps

### Three-Pass Workflow (Standard)
```
1. CharacterExtractionStep
   Input: List<String> (pages)
   Output: List<String> (character names)

2. DialogExtractionStep
   Input: List<String> (pages), List<String> (character names)
   Output: List<DialogWithPage> (dialogs with page numbers)

3. VoiceProfileStep
   Input: List<String> (character names), String (all dialogs)
   Output: Map<String, VoiceProfileData> (voice profiles)

Final: Map<String, AccumulatedCharacterData>
```

### Batched Workflow (Alternative)
```
1. Split text into batches
2. For each batch:
   - BatchedChapterAnalysisPass
   - Input: String (batch text)
   - Output: List<ExtractedCharacterData>
3. Merge results across batches
4. Final: Map<String, AccumulatedCharacterData>
```

---

## Common Patterns

### Creating a Prompt Instance
```kotlin
val prompt = CharacterExtractionPrompt()
val input = CharacterExtractionPromptInput(text = "...", pageNumber = 1)
val preparedInput = prompt.prepareInput(input)
val userPrompt = prompt.buildUserPrompt(preparedInput)
val response = model.generateResponse(
    systemPrompt = prompt.systemPrompt,
    userPrompt = userPrompt,
    maxTokens = prompt.tokenBudget.maxOutputTokens,
    temperature = prompt.temperature
)
val output = prompt.parseResponse(response)
```

### Handling Checkpoint
```kotlin
val checkpoint = loadCheckpoint(contentHash)
val startStep = if (checkpoint != null) checkpoint.lastCompletedStep + 1 else 0
// Skip completed steps, resume from startStep
```

### Merging Batched Results
```kotlin
val allCharacters = mutableMapOf<String, AccumulatedCharacterData>()
for (batch in batches) {
    val output = batchedAnalysisPass.execute(model, batch)
    for (char in output.characters) {
        // Merge character data across batches
    }
}
```

---

## Debugging Tips

### Enable Logging
- All prompts log to `AppLogger` with TAG
- Response parsing logs raw response, extracted JSON, parse results
- Checkpoint operations log load/save/delete events

### Common Issues
1. **Empty output:** Check if LLM response is blank or invalid JSON
2. **Duplicate keys:** Batched analysis truncates at first duplicate
3. **Wrong format:** Parser tries multiple format variations
4. **Checkpoint expired:** Deletes if >24 hours old or content changed

### Validation Checks
- Character names: Non-empty, distinct (case-insensitive)
- Dialogs: Non-empty text, valid speaker
- Voice profiles: Gender, age, accent, pitch/speed/energy in range
- Traits: Non-empty strings

---

## Related Documentation

- **PROMPTS_AND_WORKFLOW_ANALYSIS.md** - Complete prompt text and configuration
- **ANALYSIS_WORKFLOW_DETAILS.md** - Data structures and pipeline architecture
- **PROMPT_EXAMPLES_AND_USAGE.md** - Practical examples and usage patterns


