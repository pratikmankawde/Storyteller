# Chapter Analysis Pipeline - Architecture & Patterns

## EXECUTION FLOW DIAGRAM

```
BookAnalysisWorkflow.analyzeChapter()
    │
    ├─ Prepare chapter pages (clean PDF text)
    │
    ├─ Create ChapterAnalysisTask(
    │    bookId, chapterId, cleanedPages,
    │    appContext,  // for checkpoint persistence
    │    onStepCompleted  // callback to save to DB
    │  )
    │
    └─ AnalysisExecutor.execute(task, options)
        │
        ├─ ensureModelLoaded() → LlmModel
        │
        ├─ Decide execution mode:
        │  ├─ If estimatedDurationSeconds > 60: Foreground Service
        │  └─ If estimatedDurationSeconds <= 60: Background Runner
        │
        └─ task.execute(model, progressCallback)
            │
            ├─ Load checkpoint (if exists & valid)
            │
            ├─ For each step (skip already completed):
            │  │
            │  ├─ CharacterExtractionStep
            │  │  └─ For each page:
            │  │     └─ CharacterExtractionPassV2.execute()
            │  │        ├─ CharacterExtractionPrompt.buildUserPrompt()
            │  │        ├─ model.generateResponse()
            │  │        └─ CharacterExtractionPrompt.parseResponse()
            │  │
            │  ├─ DialogExtractionStep
            │  │  └─ For each page:
            │  │     └─ DialogExtractionPassV2.execute()
            │  │        ├─ DialogExtractionPrompt.buildUserPrompt()
            │  │        ├─ model.generateResponse()
            │  │        └─ DialogExtractionPrompt.parseResponse()
            │  │
            │  ├─ VoiceProfileStep
            │  │  └─ For each character (batched):
            │  │     └─ VoiceProfilePassV2.execute()
            │  │
            │  ├─ Save checkpoint after step
            │  │
            │  └─ onStepCompleted callback
            │     └─ saveCharactersAfterStep(bookId, stepIndex, characters)
            │
            ├─ Delete checkpoint on success
            │
            └─ Return TaskResult(success, resultData, duration)
                │
                └─ AnalysisExecutor.persist(resultData)
                   └─ CharacterResultPersister.persist()
                      └─ CharacterDao.insert/update()
```

---

## CHECKPOINT LIFECYCLE

```
┌─────────────────────────────────────────────────────────────┐
│                    CHECKPOINT LIFECYCLE                      │
└─────────────────────────────────────────────────────────────┘

1. LOAD CHECKPOINT
   ├─ Check if file exists: {cacheDir}/chapter_analysis_checkpoints/{bookId}_{chapterId}.json
   ├─ Validate timestamp: if > 24 hours old → DELETE
   ├─ Validate contentHash: if mismatch → DELETE
   └─ Return AnalysisCheckpoint or null

2. RESUME FROM CHECKPOINT
   ├─ startStepIndex = checkpoint.lastCompletedStep + 1
   ├─ Restore context from serialized data
   └─ Skip steps 0 to startStepIndex-1

3. SAVE CHECKPOINT (after each step except last)
   ├─ Serialize context.characters to SerializableCharacterData
   ├─ Create AnalysisCheckpoint with:
   │  ├─ lastCompletedStep = current step index
   │  ├─ timestamp = System.currentTimeMillis()
   │  ├─ contentHash = hash of cleanedPages
   │  └─ characters = serialized map
   ├─ Write to temp file
   └─ Atomic rename: temp → final

4. DELETE CHECKPOINT
   └─ On successful completion, delete checkpoint file
```

---

## PASS EXECUTION PATTERN

```
┌─────────────────────────────────────────────────────────────┐
│              BASEEXTRACTIONPASS EXECUTION                    │
└─────────────────────────────────────────────────────────────┘

execute(model, input, config)
    │
    ├─ prepareInput(input)
    │  └─ Truncate to fit tokenBudget.maxInputChars
    │
    ├─ buildUserPrompt(preparedInput)
    │  └─ Format prompt with input data
    │
    ├─ RETRY LOOP (maxRetries = 3)
    │  │
    │  ├─ model.generateResponse(
    │  │    systemPrompt,
    │  │    userPrompt,
    │  │    maxTokens = tokenBudget.outputTokens,
    │  │    temperature = promptDefinition.temperature
    │  │  )
    │  │
    │  ├─ Check for token limit error
    │  │  ├─ If yes: reducePrompt() and retry
    │  │  └─ If no: continue
    │  │
    │  └─ parseResponse(response)
    │     └─ Parse JSON and return output
    │
    └─ On all retries failed: getDefaultOutput()
```

---

## PROMPT DEFINITION PATTERN

```
┌─────────────────────────────────────────────────────────────┐
│              PROMPTDEFINITION INTERFACE                      │
└─────────────────────────────────────────────────────────────┘

interface PromptDefinition<I, O> {
    // Metadata
    promptId: String                    // Unique identifier
    displayName: String                 // UI display name
    purpose: String                     // What it does
    tokenBudget: TokenBudget           // Token allocation
    systemPrompt: String                // System prompt
    temperature: Float                  // Sampling temperature
    
    // Methods
    buildUserPrompt(input: I): String   // Format user prompt
    parseResponse(response: String): O  // Parse LLM output
    prepareInput(input: I): I           // Optional: truncate input
}

IMPLEMENTATIONS:
├─ CharacterExtractionPrompt
│  ├─ Input: CharacterExtractionPromptInput(text, pageNumber)
│  ├─ Output: CharacterExtractionPromptOutput(characterNames)
│  └─ TokenBudget: 3500 prompt+input, 100 output
│
├─ DialogExtractionPrompt
│  ├─ Input: DialogExtractionPromptInput(text, characterNames, pageNumber)
│  ├─ Output: DialogExtractionPromptOutput(dialogs)
│  └─ TokenBudget: 1800 prompt+input, 2200 output
│
└─ TraitsExtractionPrompt
   ├─ Input: TraitsExtractionPromptInput(characterName, contextText)
   ├─ Output: TraitsExtractionPromptOutput(characterName, traits)
   └─ TokenBudget: 2500 prompt+input, 1500 output
```

---

## CONTEXT ACCUMULATION PATTERN

```
┌─────────────────────────────────────────────────────────────┐
│           CHAPTERANALYSISCONTEXT ACCUMULATION                │
└─────────────────────────────────────────────────────────────┘

Initial Context:
├─ contentHash: hash of all cleanedPages
├─ bookId, chapterId
├─ cleanedPages: List<String>
└─ characters: MutableMap<String, AccumulatedCharacterData> = {}

STEP 1: CHARACTER EXTRACTION
├─ For each page:
│  ├─ Extract character names
│  └─ Add to context.characters[name.lowercase()]
│     ├─ pagesAppearing.add(pageIndex)
│     └─ Create if not exists
└─ Result: characters with pagesAppearing populated

STEP 2: DIALOG EXTRACTION
├─ For each page:
│  ├─ Get characters appearing on this page
│  ├─ Extract dialogs for those characters
│  └─ Add to context.characters[name].dialogs
│     ├─ pageNumber, text, emotion, intensity
│     └─ Accumulate totalDialogs count
└─ Result: characters with dialogs populated

STEP 3: VOICE PROFILE
├─ For each character (batched):
│  ├─ Extract voice profile
│  └─ Set context.characters[name].voiceProfile
│     ├─ gender, age, tone, pitch, speed, energy
│     └─ Match to speaker ID (0-108)
└─ Result: characters with voiceProfile populated
```

---

## SERIALIZATION PATTERN

```
┌─────────────────────────────────────────────────────────────┐
│         SERIALIZATION FOR CHECKPOINT & DATABASE              │
└─────────────────────────────────────────────────────────────┘

RUNTIME (AccumulatedCharacterData):
├─ name: String
├─ pagesAppearing: MutableSet<Int>
├─ dialogs: MutableList<DialogWithPage>
│  └─ DialogWithPage(pageNumber, text, emotion, intensity)
├─ traits: List<String>
├─ voiceProfile: VoiceProfileData?
│  └─ VoiceProfileData(characterName, gender, age, tone, pitch, speed, energy)
└─ speakerId: Int?

CHECKPOINT (SerializableCharacterData):
├─ name: String
├─ pagesAppearing: List<Int>  // Converted from Set
├─ dialogs: List<SerializableDialog>
│  └─ SerializableDialog(pageNumber, text, emotion, intensity)
├─ traits: List<String>
├─ voiceProfile: SerializableVoiceProfile?
│  └─ SerializableVoiceProfile(characterName, gender, age, tone, pitch, speed, energy)
└─ speakerId: Int?

DATABASE (Character entity):
├─ id: Long
├─ bookId: Long
├─ name: String
├─ traits: String  // Comma-separated or JSON
├─ personalitySummary: String
├─ voiceProfileJson: String?  // JSON serialized
├─ speakerId: Int?
└─ dialogsJson: String?  // JSON array
```

---

## EXECUTION MODE DECISION

```
┌─────────────────────────────────────────────────────────────┐
│         FOREGROUND VS BACKGROUND EXECUTION                   │
└─────────────────────────────────────────────────────────────┘

DECISION LOGIC (AnalysisExecutor.execute):
├─ If options.forceForeground = true → FOREGROUND
├─ Else if options.forceBackground = true → BACKGROUND
└─ Else if estimatedDurationSeconds > 60 → FOREGROUND
   Else → BACKGROUND

BACKGROUND RUNNER (< 60 seconds):
├─ AnalysisBackgroundRunner
├─ Simple coroutine (SupervisorJob + Dispatchers.IO)
├─ NO wake lock
├─ NO notification
└─ Suitable when app is in foreground

FOREGROUND SERVICE (>= 60 seconds):
├─ AnalysisForegroundService
├─ Android Foreground Service
├─ Acquires wake lock (prevents CPU sleep)
├─ Shows persistent notification
├─ Broadcasts progress & completion
└─ Suitable for long-running tasks

ESTIMATED DURATION:
└─ ChapterAnalysisTask.estimatedDurationSeconds
   = (cleanedPages.size * 30).coerceAtLeast(120)
   = ~30 seconds per page, minimum 120 seconds
```

---

## RESULT PERSISTENCE FLOW

```
┌─────────────────────────────────────────────────────────────┐
│            RESULT PERSISTENCE FLOW                           │
└─────────────────────────────────────────────────────────────┘

ChapterAnalysisTask.buildResultData():
├─ Serialize context.characters to JSON
└─ Return Map<String, Any>:
   ├─ KEY_CHARACTERS: JSON string
   ├─ KEY_CHARACTER_COUNT: Int
   ├─ KEY_DIALOG_COUNT: Int
   ├─ KEY_BOOK_ID: Long
   └─ KEY_CHAPTER_ID: Long

AnalysisExecutor.execute():
├─ If options.autoPersist = true:
│  └─ resultPersister.persist(result.resultData)
│     └─ CharacterResultPersister.persist()
│        └─ For each character:
│           ├─ CharacterDao.getByBookIdAndName()
│           ├─ If exists: update with new traits, voiceProfile, dialogs
│           └─ If not exists: insert new character
│
└─ Return ExecutionResult with persistedCount

STEP COMPLETION CALLBACK:
├─ Called after each step completes
├─ onStepCompleted(stepIndex, stepName, characters)
└─ Allows saving to DB after each step (not just at end)
   └─ saveCharactersAfterStep(bookId, stepIndex, stepName, characters)
```

---

## KEY INTERFACES & CONTRACTS

### AnalysisTask Interface
```kotlin
interface AnalysisTask {
    val taskId: String
    val displayName: String
    val estimatedDurationSeconds: Int
    suspend fun execute(model: LlmModel, progressCallback: ((TaskProgress) -> Unit)?): TaskResult
}
```

### PipelineStep Interface
```kotlin
interface PipelineStep<T : PipelineContext> {
    val name: String
    suspend fun execute(
        model: LlmModel,
        context: T,
        config: PassConfig,
        onSegmentProgress: StepProgressCallback? = null
    ): T
}
```

### AnalysisPass Interface
```kotlin
interface AnalysisPass<I, O> {
    val passId: String
    val displayName: String
    suspend fun execute(model: LlmModel, input: I, config: PassConfig): O
}
```

### LlmModel Interface
```kotlin
interface LlmModel {
    suspend fun loadModel(): Boolean
    fun isModelLoaded(): Boolean
    fun release()
    suspend fun generateResponse(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 1024,
        temperature: Float = 0.7f
    ): String
}
```


