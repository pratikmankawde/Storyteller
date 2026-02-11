# Chapter Analysis Pipeline - Detailed Exploration Report

## 1. CHECKPOINT DATA STRUCTURE & PERSISTENCE

### AnalysisCheckpoint Data Class
Located in `ChapterAnalysisTask.kt` (lines 104-113):
```kotlin
data class AnalysisCheckpoint(
    val bookId: Long,
    val chapterId: Long,
    val timestamp: Long,
    val contentHash: Int,                    // Hash of chapter content for validation
    val lastCompletedStep: Int,              // -1=none, 0=CharExt, 1=DialogExt, 2=complete
    val characters: Map<String, SerializableCharacterData>,
    val totalDialogs: Int,
    val pagesProcessed: Int
)
```

### Checkpoint Persistence Methods
- **Location**: `ChapterAnalysisTask.kt` lines 144-220
- **File Storage**: `{appContext.cacheDir}/chapter_analysis_checkpoints/{bookId}_{chapterId}.json`
- **Expiry**: 24 hours (CHECKPOINT_EXPIRY_HOURS = 24L)
- **Atomic Writes**: Write to temp file, then rename for safety

### Key Checkpoint Features
1. **Content Hash Validation**: Detects if chapter content changed since checkpoint
2. **Expiry Check**: Deletes checkpoints older than 24 hours
3. **Mutex Protection**: Thread-safe with `checkpointMutex.withLock`
4. **Resume Capability**: Loads checkpoint and resumes from `lastCompletedStep + 1`

### Serialization Classes
- `SerializableCharacterData`: Immutable version of AccumulatedCharacterData
- `SerializableDialog`: Dialog with pageNumber, text, emotion, intensity
- `SerializableVoiceProfile`: Voice profile data for JSON serialization

---

## 2. PROMPTDEFINITION INTERFACE & STRUCTURE

### PromptDefinition Interface
Located in `app/src/main/java/com/dramebaz/app/ai/llm/prompts/PromptDefinition.kt`:

```kotlin
interface PromptDefinition<I, O> {
    val promptId: String                    // e.g., "character_extraction_v1"
    val displayName: String                 // Human-readable name
    val purpose: String                     // What this prompt does
    val tokenBudget: TokenBudget           // Token allocation
    val systemPrompt: String                // System prompt for LLM
    val temperature: Float                  // Default 0.15f (deterministic)
    
    fun buildUserPrompt(input: I): String   // Build user prompt from input
    fun parseResponse(response: String): O  // Parse LLM response
    fun prepareInput(input: I): I = input   // Optional: truncate to fit budget
}
```

### Existing Prompt Implementations
1. **CharacterExtractionPrompt** (Pass 1)
   - Input: `CharacterExtractionPromptInput(text, pageNumber)`
   - Output: `CharacterExtractionPromptOutput(characterNames: List<String>)`
   - Token Budget: 3500 prompt+input, 100 output
   - Temperature: 0.1f (very deterministic)

2. **DialogExtractionPrompt** (Pass 2)
   - Input: `DialogExtractionPromptInput(text, characterNames, pageNumber)`
   - Output: `DialogExtractionPromptOutput(dialogs: List<ExtractedDialogData>)`
   - Token Budget: 1800 prompt+input, 2200 output
   - Temperature: 0.15f

3. **TraitsExtractionPrompt** (Pass 3)
   - Input: `TraitsExtractionPromptInput(characterName, contextText)`
   - Output: `TraitsExtractionPromptOutput(characterName, traits: List<String>)`
   - Token Budget: 2500 prompt+input, 1500 output

### TokenBudget Class
Located in `PromptDefinition.kt` (lines 57-142):
```kotlin
data class TokenBudget(
    val promptTokens: Int,      // Fixed tokens for prompt template
    val inputTokens: Int,       // Max tokens for input text
    val outputTokens: Int       // Max tokens for LLM output
) {
    val totalTokens: Int get() = promptTokens + inputTokens + outputTokens
    val maxInputChars: Int get() = inputTokens * CHARS_PER_TOKEN  // ~4 chars/token
    val maxOutputChars: Int get() = outputTokens * CHARS_PER_TOKEN
}
```

---

## 3. PASSCONFIG CLASS & PARAMETERS

### PassConfig Data Class
Located in `AnalysisPass.kt` (lines 49-64):

```kotlin
data class PassConfig(
    val maxTokens: Int = 1024,              // Max output tokens
    val temperature: Float = 0.15f,         // 0.0=deterministic, 2.0=creative
    val maxSegmentChars: Int = 10_000,      // Max input chars per segment
    val maxRetries: Int = 3,                // Retry attempts on failure
    val tokenReductionOnRetry: Int = 500    // Tokens to reduce per retry
)
```

### Pre-configured PassConfig Instances
- **PASS1_CHARACTER_EXTRACTION**: maxTokens=256, temp=0.1f, maxSegmentChars=12_000
- **PASS2_DIALOG_EXTRACTION**: maxTokens=1024, temp=0.15f, maxSegmentChars=6_000
- **PASS3_TRAITS_EXTRACTION**: maxTokens=384, temp=0.1f, maxSegmentChars=10_000

### Retry Logic
- Implemented in `BaseExtractionPass.execute()` (lines 37-87)
- On token limit error: reduces prompt by `tokenReductionOnRetry` tokens
- Falls back to `getDefaultOutput()` after max retries exhausted

---

## 4. RESULTS MERGING & DATABASE PERSISTENCE

### Result Merging Flow
1. **ChapterAnalysisTask.buildResultData()** (lines 403-414)
   - Serializes characters to JSON
   - Returns Map<String, Any> with keys:
     - KEY_CHARACTERS: JSON string of all characters
     - KEY_CHARACTER_COUNT: Number of characters
     - KEY_DIALOG_COUNT: Total dialogs
     - KEY_BOOK_ID, KEY_CHAPTER_ID

2. **Step Completion Callback** (line 362)
   - Called after each step completes
   - Invokes `onStepCompleted(stepIndex, stepName, characters)`
   - Allows saving to DB after each step (not just at end)

### Database Persistence
Located in `CharacterResultPersister.kt`:
- **saveCharacter()**: Inserts or updates Character entity
- **Traits**: Stored as comma-separated string
- **VoiceProfile**: Stored as JSON
- **Dialogs**: Stored as JSON array with pageNumber, text, emotion, intensity
- **SpeakerId**: Optional speaker ID (0-108 for VCTK model)

### Character Entity
Located in `Character.kt`:
```kotlin
@Entity(tableName = "characters")
data class Character(
    val id: Long = 0,
    val bookId: Long,
    val name: String,
    val traits: String,                     // Comma-separated or JSON
    val personalitySummary: String = "",
    val voiceProfileJson: String? = null,   // JSON serialized
    val speakerId: Int? = null,             // 0-108 for VCTK
    val dialogsJson: String? = null         // JSON array
)
```

---

## 5. TASK EXECUTION FLOW IN ANALYSISEXECUTOR

### AnalysisExecutor.execute() Flow
Located in `AnalysisExecutor.kt` (lines 110-159):

1. **Model Loading** (line 118)
   - Calls `ensureModelLoaded()` via LlmModelHolder
   - Returns null if model fails to load

2. **Execution Mode Decision** (lines 129-133)
   - If `estimatedDurationSeconds > 60`: Use Foreground Service (with wake lock)
   - If `estimatedDurationSeconds <= 60`: Use Background Runner (simple coroutine)
   - Can be overridden with `forceForeground` or `forceBackground` options

3. **Task Execution** (lines 138-142)
   - Calls `task.execute(model, progressCallback)`
   - Returns TaskResult with success, duration, resultData, error

4. **Result Persistence** (lines 145-149)
   - If `autoPersist=true` and result successful
   - Calls `resultPersister.persist(result.resultData)`
   - Returns count of persisted items

### ExecutionOptions
```kotlin
data class ExecutionOptions(
    val forceForeground: Boolean = false,   // Force foreground service
    val forceBackground: Boolean = false,   // Force background runner
    val autoPersist: Boolean = true         // Auto-persist results to DB
)
```

### Background vs Foreground
- **Background** (AnalysisBackgroundRunner): Simple coroutine, no wake lock, no notification
- **Foreground** (AnalysisForegroundService): Android Service, wake lock, persistent notification

---

## 6. PIPELINE STEPS & CONTEXT

### ChapterAnalysisContext
Located in `PipelineModels.kt` (lines 82-90):
```kotlin
data class ChapterAnalysisContext(
    override val contentHash: Int,
    val bookId: Long,
    val chapterId: Long,
    val cleanedPages: List<String>,
    val characters: MutableMap<String, AccumulatedCharacterData> = mutableMapOf(),
    val totalDialogs: Int = 0,
    val pagesProcessed: Int = 0
) : PipelineContext
```

### AccumulatedCharacterData
Located in `PipelineModels.kt` (lines 95-102):
```kotlin
data class AccumulatedCharacterData(
    val name: String,
    val pagesAppearing: MutableSet<Int> = mutableSetOf(),
    val dialogs: MutableList<DialogWithPage> = mutableListOf(),
    var traits: List<String> = emptyList(),
    var voiceProfile: VoiceProfileData? = null,
    var speakerId: Int? = null
)
```

### Three Pipeline Steps
1. **CharacterExtractionStep**: Extracts character names from each page
2. **DialogExtractionStep**: Extracts dialogs for characters on each page
3. **VoiceProfileStep**: Suggests voice profiles for characters (batched)

---

## 7. TOKEN BUDGET MANAGEMENT

### TokenBudgetManager
Located in `TokenBudgetManager.kt`:
- **CHARS_PER_TOKEN**: 4 (conservative estimate for English)
- **TOTAL_TOKEN_BUDGET**: 4096 tokens shared across all passes
- **prepareInputText()**: Truncates at paragraph/sentence boundary to fit budget
- **estimateTokens()**: Calculates token count from character count

### Token Allocation
- **Pass 1**: 3500 prompt+input, 100 output (total 3600)
- **Pass 2**: 1800 prompt+input, 2200 output (total 4000)
- **Pass 3**: 2500 prompt+input, 1500 output (total 4000)

---

## 8. BASEEXTRACTIONPASS IMPLEMENTATION

### BaseExtractionPass<I, O>
Located in `BaseExtractionPass.kt` (lines 20-106):
- Generic base class for extraction passes using PromptDefinition
- Handles input preparation, LLM invocation, retry logic, response parsing

### Execute Flow
1. Prepare input via `promptDefinition.prepareInput(input)`
2. Build user prompt via `promptDefinition.buildUserPrompt(preparedInput)`
3. Invoke LLM with timeout (10 minutes)
4. On token limit error: reduce prompt and retry
5. Parse response via `promptDefinition.parseResponse(response)`
6. Return default output if all retries fail

### Concrete Implementations
- **CharacterExtractionPassV2**: Returns empty list on failure
- **DialogExtractionPassV2**: Returns empty dialogs list on failure
- **TraitsExtractionPassV2**: Returns empty traits on failure

---

## 9. BACKGROUND TASK PATTERNS

### AnalysisBackgroundRunner
Located in `AnalysisBackgroundRunner.kt`:
- Simple coroutine-based runner (NOT an Android Service)
- No wake lock, no notification
- Suitable for tasks < 60 seconds
- Uses SupervisorJob + Dispatchers.IO

### AnalysisForegroundService
Located in `AnalysisForegroundService.kt`:
- Android Foreground Service for long-running tasks (>= 60 seconds)
- Acquires wake lock to prevent CPU sleep
- Shows persistent notification with progress
- Broadcasts progress and completion events
- Two modes: task execution mode and simple mode

---

## 10. KEY DESIGN PATTERNS

### Strategy Pattern
- **PromptDefinition**: Different prompts can be swapped
- **AnalysisPass**: Different passes implement same interface
- **LlmModel**: Different model implementations (GGUF, Gemma, etc.)

### Template Method Pattern
- **BaseExtractionPass**: Defines execution flow, subclasses provide defaults

### Facade Pattern
- **AnalysisExecutor**: Simplified interface to analysis subsystem
- **ChapterAnalysisTask**: Wraps 3-pass workflow into single task

### Command Pattern
- **AnalysisTask**: Encapsulates analysis workflow as executable object

---

## 11. INTEGRATION POINTS

### BookAnalysisWorkflow Integration
- Creates ChapterAnalysisTask with step completion callback
- Passes appContext for checkpoint persistence
- Calls executor.execute() with ExecutionOptions
- Saves characters after each step via callback

### Database Integration
- CharacterDao: Insert/update characters
- CharacterPageMappingDao: Track character appearances per page
- Results persisted via CharacterResultPersister

### Model Integration
- LlmModel interface: generateResponse(systemPrompt, userPrompt, maxTokens, temperature)
- Implementations: GgufEngineImpl, Gemma3nModel, etc.


