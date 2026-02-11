# Analysis Workflow Audit Report

## CURRENT ACTIVE WORKFLOW

### Primary Entry Point: `BookAnalysisWorkflow.kt`
- **Location**: `app/src/main/java/com/dramebaz/app/domain/usecases/BookAnalysisWorkflow.kt`
- **Status**: ✅ ACTIVELY USED
- **Methods**:
  - `analyzeFirstChapter()` - Quick preview during import
  - `analyzeAllChapters()` - Full book analysis
  - `analyzeChapter()` - Single chapter analysis (private)

### Current 3-Pass Workflow Chain
1. **ChapterAnalysisTask.kt** (ACTIVE)
   - Location: `app/src/main/java/com/dramebaz/app/ai/llm/tasks/ChapterAnalysisTask.kt`
   - Implements `AnalysisTask` interface
   - Orchestrates 3 steps: CharacterExtractionStep → DialogExtractionStep → VoiceProfileStep
   - Used by: `BookAnalysisWorkflow.analyzeChapter()`

2. **AnalysisExecutor.kt** (ACTIVE)
   - Location: `app/src/main/java/com/dramebaz/app/ai/llm/executor/AnalysisExecutor.kt`
   - Chooses foreground vs background execution
   - Manages LLM model lifecycle
   - Handles result persistence

3. **Pipeline Steps** (ACTIVE - in PipelineModels.kt)
   - `CharacterExtractionStep` - Uses CharacterExtractionPassV2
   - `DialogExtractionStep` - Uses DialogExtractionPassV2
   - `VoiceProfileStep` - Uses VoiceProfilePassV2

4. **V2 Pass Implementations** (ACTIVE)
   - Location: `app/src/main/java/com/dramebaz/app/ai/llm/pipeline/passes/`
   - CharacterExtractionPassV2.kt
   - DialogExtractionPassV2.kt
   - VoiceProfilePassV2.kt
   - TraitsExtractionPassV2.kt

---

## OLD/DUPLICATE IMPLEMENTATIONS (SHOULD BE DELETED)

### 1. ThreePassCharacterAnalysisUseCase.kt
- **Location**: `app/src/main/java/com/dramebaz/app/domain/usecases/ThreePassCharacterAnalysisUseCase.kt`
- **Status**: ❌ DEAD CODE - NOT USED
- **Why Delete**: 
  - Replaced by ChapterAnalysisTask + AnalysisExecutor workflow
  - Only referenced in test: `PdfCharacterExtractionTest.kt`
  - LibraryViewModel imports it but doesn't use it
- **Checkpointing**: Has checkpoint logic (could be reused if needed)

### 2. GemmaCharacterAnalysisUseCase.kt
- **Location**: `app/src/main/java/com/dramebaz/app/domain/usecases/GemmaCharacterAnalysisUseCase.kt`
- **Status**: ❌ DEAD CODE - NOT USED
- **Why Delete**:
  - Replaced by ChapterAnalysisTask workflow
  - Only referenced in LibraryViewModel (not actually called)
  - Uses deprecated LiteRtLmEngine
- **Checkpointing**: Has checkpoint logic

### 3. ChapterCharacterExtractionUseCase.kt
- **Location**: `app/src/main/java/com/dramebaz/app/domain/usecases/ChapterCharacterExtractionUseCase.kt`
- **Status**: ⚠️ PARTIALLY USED
- **Why Keep/Delete**:
  - Used in ReaderFragment.kt (line 470) for background extraction
  - Provides fallback extraction when full analysis unavailable
  - Could be refactored to use ChapterAnalysisTask instead
- **Decision**: KEEP but refactor to use new workflow

### 4. ChapterAnalysisPipeline.kt
- **Location**: `app/src/main/java/com/dramebaz/app/ai/llm/pipeline/ChapterAnalysisPipeline.kt`
- **Status**: ❌ DEAD CODE - NOT USED
- **Why Delete**:
  - Replaced by modular pipeline (CharacterExtractionStep, etc.)
  - No references in active code
  - Has checkpoint logic but superseded by new approach
- **Checkpointing**: Has comprehensive checkpoint implementation

---

## SUMMARY TABLE

| File | Location | Status | Used By | Action |
|------|----------|--------|---------|--------|
| ChapterAnalysisTask.kt | tasks/ | ✅ ACTIVE | BookAnalysisWorkflow | KEEP |
| AnalysisExecutor.kt | executor/ | ✅ ACTIVE | BookAnalysisWorkflow | KEEP |
| CharacterExtractionStep | pipeline/ | ✅ ACTIVE | ChapterAnalysisTask | KEEP |
| DialogExtractionStep | pipeline/ | ✅ ACTIVE | ChapterAnalysisTask | KEEP |
| VoiceProfileStep | pipeline/ | ✅ ACTIVE | ChapterAnalysisTask | KEEP |
| *V2 Passes | pipeline/passes/ | ✅ ACTIVE | Steps | KEEP |
| ThreePassCharacterAnalysisUseCase | usecases/ | ❌ DEAD | Test only | DELETE |
| GemmaCharacterAnalysisUseCase | usecases/ | ❌ DEAD | Unused import | DELETE |
| ChapterCharacterExtractionUseCase | usecases/ | ⚠️ PARTIAL | ReaderFragment | REFACTOR |
| ChapterAnalysisPipeline | pipeline/ | ❌ DEAD | None | DELETE |

---

## NEXT STEPS

1. Delete ThreePassCharacterAnalysisUseCase.kt
2. Delete GemmaCharacterAnalysisUseCase.kt
3. Delete ChapterAnalysisPipeline.kt
4. Update PdfCharacterExtractionTest.kt to use new workflow
5. Update LibraryViewModel to remove unused imports
6. Refactor ChapterCharacterExtractionUseCase to use ChapterAnalysisTask

