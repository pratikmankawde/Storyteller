# Detailed File-by-File Breakdown

## CURRENT WORKFLOW FILES (KEEP ALL)

### 1. ChapterAnalysisTask.kt ✅
**Path:** `app/src/main/java/com/dramebaz/app/ai/llm/tasks/ChapterAnalysisTask.kt`
**Lines:** 137
**Purpose:** Wraps 3-pass analysis into AnalysisTask interface
**Used By:** BookAnalysisWorkflow.analyzeChapter() (line 314)
**Key Methods:**
- `execute(model, progressCallback)` - Runs 3 steps sequentially
- `buildResultData(context)` - Serializes results

**Steps Executed:**
1. CharacterExtractionStep
2. DialogExtractionStep
3. VoiceProfileStep

---

### 2. AnalysisExecutor.kt ✅
**Path:** `app/src/main/java/com/dramebaz/app/ai/llm/executor/AnalysisExecutor.kt`
**Lines:** 285
**Purpose:** High-level executor for analysis tasks
**Used By:** BookAnalysisWorkflow.getOrCreateExecutor() (line 417)
**Key Methods:**
- `execute(task, options, onProgress)` - Main entry point
- `executeForeground(task, model, onProgress)` - Via AnalysisForegroundService
- `executeBackground(task, model, onProgress)` - Via AnalysisBackgroundRunner
- `ensureModelLoaded()` - Gets model from LlmModelHolder

**Decision Logic:**
- Tasks > 60s: Foreground (with wake lock)
- Tasks < 60s: Background (simple coroutine)

---

### 3. PipelineModels.kt ✅
**Path:** `app/src/main/java/com/dramebaz/app/ai/llm/pipeline/PipelineModels.kt`
**Lines:** 240+
**Purpose:** Defines 3 pipeline steps
**Used By:** ChapterAnalysisTask (lines 68-70)

**Classes:**
- `CharacterExtractionStep` (lines 109-149)
  - Uses CharacterExtractionPassV2
  - Accumulates unique character names
  
- `DialogExtractionStep` (lines 155-199)
  - Uses DialogExtractionPassV2
  - Extracts dialogs per page
  
- `VoiceProfileStep` (lines 205-240)
  - Uses VoiceProfilePassV2
  - Processes characters in batches of 4
  - Assigns speaker IDs via SpeakerMatcher

---

### 4. V2 Pass Implementations ✅
**Path:** `app/src/main/java/com/dramebaz/app/ai/llm/pipeline/passes/`

**CharacterExtractionPassV2.kt**
- Uses CharacterExtractionPrompt
- Returns list of character names

**DialogExtractionPassV2.kt**
- Uses DialogExtractionPrompt
- Returns dialogs with speaker attribution

**VoiceProfilePassV2.kt**
- Uses VoiceProfilePrompt
- Returns voice profiles for characters

**TraitsExtractionPassV2.kt**
- Uses TraitsExtractionPrompt
- Returns character traits

---

## OLD/DUPLICATE FILES (DELETE)

### 1. ThreePassCharacterAnalysisUseCase.kt ❌
**Path:** `app/src/main/java/com/dramebaz/app/domain/usecases/`
**Lines:** 868
**References:**
- PdfCharacterExtractionTest.kt (line 37, 58) - Test only
- LibraryViewModel.kt (line 41, 180-183) - Imported but never called

**Key Methods:**
- `analyzeChapter()` - Main 3-pass workflow
- `runPass1()` - Character extraction per page
- `runPass2()` - Dialog extraction
- `runPass3()` - Voice profile + traits

**Checkpoint Methods:**
- `loadCheckpoint()` - Resume from checkpoint
- `saveCheckpoint()` - Save progress
- `deleteCheckpoint()` - Clean up on completion

**Why Delete:**
- BookAnalysisWorkflow uses ChapterAnalysisTask instead
- No active code path calls this
- Checkpoint logic is superseded

---

### 2. GemmaCharacterAnalysisUseCase.kt ❌
**Path:** `app/src/main/java/com/dramebaz/app/domain/usecases/`
**Lines:** 537
**References:**
- LibraryViewModel.kt (line 16, 40, 176-179) - Imported but never called

**Key Methods:**
- `analyzeChapter()` - 2-pass workflow
- `initializeEngine()` - LiteRtLmEngine setup
- `pass1ExtractCharacters()` - Character extraction
- `pass2ExtractDialogs()` - Dialog extraction

**Checkpoint Methods:**
- `loadCheckpoint()` - Resume capability
- `saveCheckpoint()` - Progress tracking
- `deleteCheckpoint()` - Cleanup

**Why Delete:**
- Uses deprecated LiteRtLmEngine
- Replaced by ChapterAnalysisTask workflow
- No active code path calls this

---

### 3. ChapterAnalysisPipeline.kt ❌
**Path:** `app/src/main/java/com/dramebaz/app/ai/llm/pipeline/`
**Lines:** 690
**References:** NONE - completely unused

**Key Methods:**
- `analyzeChapter()` - Main 3-pass workflow
- `runPass1()` - Character extraction
- `runPass2()` - Dialog extraction
- `runPass3()` - Voice profile suggestion

**Checkpoint Methods:**
- `loadCheckpoint()` - Resume from checkpoint
- `saveCheckpoint()` - Save progress
- `deleteCheckpoint()` - Cleanup

**Why Delete:**
- Replaced by modular pipeline (CharacterExtractionStep, etc.)
- AnalysisService.kt uses MultipassPipeline instead
- No imports or references anywhere

---

## PARTIALLY USED FILE (REFACTOR)

### ChapterCharacterExtractionUseCase.kt ⚠️
**Path:** `app/src/main/java/com/dramebaz/app/domain/usecases/`
**Lines:** 200+
**References:**
- ReaderFragment.kt (line 470) - extractAndSave() called

**Current Usage:**
```kotlin
val extractionUseCase = ChapterCharacterExtractionUseCase(app.db.characterDao())
extractionUseCase.extractAndSave(bookId, chapterText, chapterIndex, totalChapters)
```

**Recommendation:** Refactor to use ChapterAnalysisTask instead

