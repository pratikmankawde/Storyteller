# Storyteller Analysis Workflow - Detailed Implementation Guide

## Pipeline Architecture

### Three-Pass Analysis (ChapterAnalysisTask)
The standard workflow processes each chapter through 3 sequential passes:

```
Chapter Text (cleaned pages)
    ↓
[PASS 1] CharacterExtractionStep
    → Extracts character names from all pages
    → Output: List<String> (character names)
    ↓
[PASS 2] DialogExtractionStep
    → Extracts dialogs for identified characters
    → Output: List<DialogWithPage> (speaker, text, emotion, intensity, pageNumber)
    ↓
[PASS 3] VoiceProfileStep
    → Suggests voice profiles based on dialogs
    → Output: Map<String, VoiceProfileData>
    ↓
Final Result: Map<String, AccumulatedCharacterData>
```

### Batched Analysis (BatchedChapterAnalysisPass)
Alternative single-pass approach for large texts:

```
Chapter Text (split into batches)
    ↓
[BATCHED PASS] BatchedChapterAnalysisPass
    → Extracts characters, dialogs, traits, voice profiles in ONE call
    → Output: BatchedAnalysisOutput (List<ExtractedCharacterData>)
    ↓
Final Result: Merged character data across all batches
```

---

## Data Flow and Structures

### Input Data Classes

#### CharacterExtractionPromptInput
```kotlin
data class CharacterExtractionPromptInput(
    val text: String,           // Page text to analyze
    val pageNumber: Int = 0     // For tracking
)
```

#### DialogExtractionPromptInput
```kotlin
data class DialogExtractionPromptInput(
    val text: String,                      // Page text
    val characterNames: List<String>,      // Characters to find dialogs for
    val pageNumber: Int = 0
)
```

#### VoiceProfilePromptInput
```kotlin
data class VoiceProfilePromptInput(
    val characterNames: List<String>,      // Characters to profile
    val dialogContext: String              // All dialogs for context
)
```

#### BatchedAnalysisInput
```kotlin
data class BatchedAnalysisInput(
    val text: String,           // Batch text
    val batchIndex: Int = 0,    // Which batch (1 of N)
    val totalBatches: Int = 1   // Total batches
)
```

### Output Data Classes

#### CharacterExtractionPromptOutput
```kotlin
data class CharacterExtractionPromptOutput(
    val characterNames: List<String>
)
```

#### DialogExtractionPromptOutput
```kotlin
data class DialogExtractionPromptOutput(
    val dialogs: List<ExtractedDialogData>
)

data class ExtractedDialogData(
    val speaker: String,
    val text: String,
    val emotion: String = "neutral",
    val intensity: Float = 0.5f
)
```

#### VoiceProfilePromptOutput
```kotlin
data class VoiceProfilePromptOutput(
    val profiles: List<VoiceProfileData>
)

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

#### BatchedAnalysisOutput
```kotlin
data class BatchedAnalysisOutput(
    val characters: List<ExtractedCharacterData>
)

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

### Accumulated Character Data (Internal)
```kotlin
data class AccumulatedCharacterData(
    val name: String,
    val pagesAppearing: MutableSet<Int>,
    val dialogs: MutableList<DialogWithPage>,
    val traits: List<String>,
    val voiceProfile: VoiceProfileData?,
    val speakerId: Int?
)

data class DialogWithPage(
    val pageNumber: Int,
    val text: String,
    val emotion: String,
    val intensity: Float
)
```

---

## Checkpoint Persistence

### Purpose
- Resume interrupted analysis without re-processing
- Detect content changes (hash validation)
- Expire old checkpoints (24-hour TTL)

### Checkpoint File Location
```
${appContext.cacheDir}/chapter_analysis_checkpoints/{bookId}_{chapterId}.json
```

### Serialization Format
```kotlin
data class AnalysisCheckpoint(
    val bookId: Long,
    val chapterId: Long,
    val timestamp: Long,
    val contentHash: Int,
    val lastCompletedStep: Int,
    val characters: Map<String, SerializableCharacterData>,
    val totalDialogs: Int,
    val pagesProcessed: Int
)

data class SerializableCharacterData(
    val name: String,
    val pagesAppearing: List<Int>,
    val dialogs: List<SerializableDialog>,
    val traits: List<String>,
    val voiceProfile: SerializableVoiceProfile?,
    val speakerId: Int?
)

data class SerializableDialog(
    val pageNumber: Int,
    val text: String,
    val emotion: String,
    val intensity: Float
)

data class SerializableVoiceProfile(
    val characterName: String,
    val gender: String,
    val age: String,
    val tone: String,
    val pitch: Float,
    val speed: Float,
    val energy: Float
)
```

---

## Response Parsing Logic

### JSON Extraction Strategy
1. **Remove Markdown:** Strip ```json, ```, ``` markers
2. **Remove Prefixes:** Handle "Here is the JSON:", "Output:", etc.
3. **Detect Format:** Check for single object vs. multiple objects (JSONL)
4. **Merge Objects:** Combine separate JSON objects into single structure
5. **Truncate Duplicates:** Handle LLM repetition by truncating at first duplicate key
6. **Extract Boundaries:** Find first { and last } for object extraction

### Field Name Flexibility
The parser supports multiple field name variations:
- **Dialogs:** "D", "d", "dialogs"
- **Traits:** "T", "t", "traits"
- **Voice:** "V", "v", "voice"

This allows the LLM to use either short or long field names.

---

## Token Budget Configuration

### Standard Budgets
```kotlin
TokenBudget.PASS1_CHARACTER_EXTRACTION
    promptTokens: 200
    inputTokens: 3500
    outputTokens: 100

TokenBudget.PASS2_DIALOG_EXTRACTION
    promptTokens: 200
    inputTokens: 1800
    outputTokens: 2200

TokenBudget.PASS3_VOICE_PROFILE
    promptTokens: 200
    inputTokens: 2500
    outputTokens: 1500
```

### Batched Analysis Budget
Centralized in `BatchedPipelineConfig.TOKEN_BUDGET`

### Input Truncation
- Text is truncated to fit `tokenBudget.maxInputChars`
- Paragraph boundaries are respected when possible
- Truncation happens in `prepareInput()` before LLM call

---

## Error Handling and Recovery

### Checkpoint Validation
- **Content Hash Check:** Detects if chapter text changed
- **Expiry Check:** Deletes checkpoints older than 24 hours
- **Corruption Check:** Deletes invalid JSON checkpoints

### JSON Parsing Fallbacks
1. Try standard format ({"dialogs": [...]})
2. Try array format ([{speaker: text}, ...])
3. Try flat object format ({CharName: {...}, ...})
4. Return empty list if all fail

### LLM Response Issues
- **Empty Response:** Returns empty output
- **Invalid JSON:** Logs error, returns empty output
- **Duplicate Keys:** Truncates at first duplicate
- **Multiple Objects:** Merges into single object

---

## Integration Points

### BookAnalysisWorkflow
- Creates ChapterAnalysisTask for each chapter
- Calls task.execute() with progress callback
- Saves results to database after each step
- Handles checkpoint cleanup on book deletion

### AnalysisExecutor
- Manages task execution (foreground/background)
- Provides progress updates
- Handles cancellation and timeouts

### Database Integration
- Saves characters after each step
- Persists dialogs, traits, voice profiles
- Links characters to chapters and books


