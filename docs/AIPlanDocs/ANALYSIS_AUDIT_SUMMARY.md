# Analysis Audit Summary

## EXECUTIVE SUMMARY

The codebase has **3 old/duplicate analysis implementations** that should be deleted, and **1 active modern workflow** that should be kept and potentially enhanced.

---

## CURRENT ACTIVE WORKFLOW ✅

**Entry Point:** `BookAnalysisWorkflow.kt`

**Flow:**
```
BookAnalysisWorkflow.analyzeChapter()
  → ChapterAnalysisTask (wraps 3-pass workflow)
    → AnalysisExecutor (manages execution)
      → CharacterExtractionStep (Pass 1)
      → DialogExtractionStep (Pass 2)
      → VoiceProfileStep (Pass 3)
```

**Key Files:**
- `app/src/main/java/com/dramebaz/app/ai/llm/tasks/ChapterAnalysisTask.kt`
- `app/src/main/java/com/dramebaz/app/ai/llm/executor/AnalysisExecutor.kt`
- `app/src/main/java/com/dramebaz/app/ai/llm/pipeline/PipelineModels.kt` (Steps)
- `app/src/main/java/com/dramebaz/app/ai/llm/pipeline/passes/` (V2 implementations)

---

## FILES TO DELETE ❌

### 1. ThreePassCharacterAnalysisUseCase.kt
- **Path:** `app/src/main/java/com/dramebaz/app/domain/usecases/`
- **Size:** 868 lines
- **References:** Only in test (PdfCharacterExtractionTest.kt)
- **Reason:** Replaced by ChapterAnalysisTask workflow
- **Checkpointing:** Has checkpoint logic (24h expiry, per-page resume)

### 2. GemmaCharacterAnalysisUseCase.kt
- **Path:** `app/src/main/java/com/dramebaz/app/domain/usecases/`
- **Size:** 537 lines
- **References:** Unused import in LibraryViewModel.kt
- **Reason:** Uses deprecated LiteRtLmEngine, replaced by new workflow
- **Checkpointing:** Has checkpoint logic (24h expiry, per-segment resume)

### 3. ChapterAnalysisPipeline.kt
- **Path:** `app/src/main/java/com/dramebaz/app/ai/llm/pipeline/`
- **Size:** 690 lines
- **References:** NONE - completely unused
- **Reason:** Replaced by modular pipeline (CharacterExtractionStep, etc.)
- **Checkpointing:** Has checkpoint logic (24h expiry, per-page resume)

---

## FILES TO REFACTOR ⚠️

### ChapterCharacterExtractionUseCase.kt
- **Path:** `app/src/main/java/com/dramebaz/app/domain/usecases/`
- **Status:** Partially used in ReaderFragment.kt (line 470)
- **Current Use:** Background extraction while reading
- **Recommendation:** Refactor to use ChapterAnalysisTask instead

---

## CHECKPOINT LOGIC ANALYSIS

All 3 old implementations have checkpoint logic:

**Common Features:**
- 24-hour expiry validation
- Content hash validation (detects chapter changes)
- Mutex-protected file I/O
- Resume capability (per-page or per-segment)

**Checkpoint Files:**
- ThreePass: `{bookId}_ch{chapterIndex}.json`
- Gemma: `gemma_checkpoint_{bookId}_{chapterIndex}.json`
- Pipeline: `pipeline_{bookId}_{chapterId}.json`

**Current Workflow (ChapterAnalysisTask):** No checkpointing implemented

---

## RECOMMENDATIONS

### Immediate Actions
1. Delete ThreePassCharacterAnalysisUseCase.kt
2. Delete GemmaCharacterAnalysisUseCase.kt
3. Delete ChapterAnalysisPipeline.kt
4. Update PdfCharacterExtractionTest.kt to use new workflow
5. Remove unused imports from LibraryViewModel.kt

### Future Enhancements
1. Add checkpointing to ChapterAnalysisTask (reuse logic from deleted files)
2. Refactor ChapterCharacterExtractionUseCase to use ChapterAnalysisTask
3. Consider adding resume capability for long-running analyses

---

## IMPACT ANALYSIS

**Deleting 3 files will:**
- Remove ~2,095 lines of dead code
- Eliminate duplicate checkpoint implementations
- Reduce maintenance burden
- Clarify the single active workflow

**No breaking changes** - these files are not actively used in production code.

---

## VERIFICATION CHECKLIST

- [x] Identified current active workflow
- [x] Located all old implementations
- [x] Verified no active references (except tests/unused imports)
- [x] Analyzed checkpoint logic
- [x] Documented all findings
- [x] Created deletion recommendations

