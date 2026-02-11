# Detailed Analysis Findings

## CURRENT WORKFLOW ARCHITECTURE

### Flow: BookAnalysisWorkflow → ChapterAnalysisTask → AnalysisExecutor

```
BookAnalysisWorkflow.analyzeChapter()
    ↓
Creates ChapterAnalysisTask(bookId, chapterId, cleanedPages)
    ↓
AnalysisExecutor.execute(task)
    ├─ Loads LLM model via LlmModelHolder
    ├─ Decides: foreground (>60s) vs background (<60s)
    └─ Executes task.execute(model, progressCallback)
        ↓
    ChapterAnalysisTask.execute()
        ├─ CharacterExtractionStep.execute()
        │   └─ CharacterExtractionPassV2 (uses CharacterExtractionPrompt)
        ├─ DialogExtractionStep.execute()
        │   └─ DialogExtractionPassV2 (uses DialogExtractionPrompt)
        └─ VoiceProfileStep.execute()
            └─ VoiceProfilePassV2 (uses VoiceProfilePrompt)
```

### Key Files in Current Workflow

**tasks/ChapterAnalysisTask.kt**
- Implements AnalysisTask interface
- Wraps 3-pass workflow
- Returns TaskResult with character data

**executor/AnalysisExecutor.kt**
- Facade for task execution
- Manages foreground/background decision
- Handles model lifecycle
- Persists results via TaskResultPersister

**pipeline/PipelineModels.kt**
- CharacterExtractionStep (lines 109-149)
- DialogExtractionStep (lines 155-199)
- VoiceProfileStep (lines 205-240)

**pipeline/passes/**
- CharacterExtractionPassV2.kt
- DialogExtractionPassV2.kt
- VoiceProfilePassV2.kt
- TraitsExtractionPassV2.kt

---

## DEAD CODE ANALYSIS

### ThreePassCharacterAnalysisUseCase.kt (690 lines)
**References Found:**
- PdfCharacterExtractionTest.kt (line 37, 58) - Test only
- LibraryViewModel.kt (line 41, 180-183) - Imported but never called

**Why Dead:**
- BookAnalysisWorkflow uses ChapterAnalysisTask instead
- No active code path calls analyzeChapter()
- Checkpoint logic is superseded

**Checkpointing Features:**
- AnalysisCheckpoint data class with pass tracking
- loadCheckpoint(), saveCheckpoint(), deleteCheckpoint()
- 24-hour expiry validation
- Per-page resume capability

### GemmaCharacterAnalysisUseCase.kt (537 lines)
**References Found:**
- LibraryViewModel.kt (line 16, 40, 176-179) - Imported but never called

**Why Dead:**
- Uses deprecated LiteRtLmEngine
- Replaced by ChapterAnalysisTask workflow
- No active code path calls analyzeChapter()

**Checkpointing Features:**
- Similar to ThreePass: loadCheckpoint, saveCheckpoint
- Checkpoint expiry validation
- Per-segment resume capability

### ChapterAnalysisPipeline.kt (690 lines)
**References Found:**
- NONE - completely unused

**Why Dead:**
- Replaced by modular pipeline (CharacterExtractionStep, etc.)
- AnalysisService.kt uses MultipassPipeline instead
- No imports or references anywhere

**Checkpointing Features:**
- AnalysisCheckpoint with pass/page tracking
- Comprehensive checkpoint management
- 24-hour expiry
- Per-page resume

---

## PARTIALLY USED CODE

### ChapterCharacterExtractionUseCase.kt
**Active References:**
- ReaderFragment.kt (line 470) - extractAndSave() called
- Used for background extraction while reading

**Current Usage:**
```kotlin
val extractionUseCase = ChapterCharacterExtractionUseCase(app.db.characterDao())
extractionUseCase.extractAndSave(bookId, chapterText, chapterIndex, totalChapters)
```

**Decision:** KEEP but consider refactoring to use ChapterAnalysisTask

---

## CHECKPOINT LOGIC COMPARISON

All three old implementations have checkpoint logic:

| Feature | ThreePass | Gemma | Pipeline |
|---------|-----------|-------|----------|
| Checkpoint file | {bookId}_ch{idx}.json | gemma_checkpoint_{bookId}_{idx}.json | pipeline_{bookId}_{chapterId}.json |
| Expiry | 24 hours | 24 hours | 24 hours |
| Hash validation | Yes | Yes | Yes |
| Resume granularity | Per-page | Per-segment | Per-page |
| Mutex protection | Yes | Yes | Yes |

**Current workflow (ChapterAnalysisTask):** No checkpointing implemented

---

## RECOMMENDATIONS

1. **DELETE** ThreePassCharacterAnalysisUseCase.kt
2. **DELETE** GemmaCharacterAnalysisUseCase.kt  
3. **DELETE** ChapterAnalysisPipeline.kt
4. **UPDATE** PdfCharacterExtractionTest.kt to use new workflow
5. **UPDATE** LibraryViewModel.kt to remove unused imports
6. **CONSIDER** Adding checkpointing to ChapterAnalysisTask
7. **REFACTOR** ChapterCharacterExtractionUseCase to use ChapterAnalysisTask

