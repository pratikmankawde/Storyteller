# Complete Analysis Files List

## DIRECTORY: app/src/main/java/com/dramebaz/app/ai/llm/tasks/

| File | Status | Used By | Action |
|------|--------|---------|--------|
| AnalysisTask.kt | ✅ ACTIVE | ChapterAnalysisTask, AnalysisExecutor | KEEP |
| ChapterAnalysisTask.kt | ✅ ACTIVE | BookAnalysisWorkflow | KEEP |
| TraitsExtractionTask.kt | ✅ ACTIVE | BookAnalysisWorkflow | KEEP |

---

## DIRECTORY: app/src/main/java/com/dramebaz/app/ai/llm/executor/

| File | Status | Used By | Action |
|------|--------|---------|--------|
| AnalysisExecutor.kt | ✅ ACTIVE | BookAnalysisWorkflow | KEEP |

---

## DIRECTORY: app/src/main/java/com/dramebaz/app/ai/llm/pipeline/

| File | Status | Used By | Action |
|------|--------|---------|--------|
| AnalysisPass.kt | ✅ ACTIVE | Pipeline infrastructure | KEEP |
| AnalysisService.kt | ✅ ACTIVE | Modular pipeline | KEEP |
| BaseExtractionPass.kt | ✅ ACTIVE | V2 passes | KEEP |
| ChapterAnalysisPass.kt | ✅ ACTIVE | Pipeline | KEEP |
| ChapterAnalysisPipeline.kt | ❌ DEAD | NONE | DELETE |
| CharacterDetectionPass.kt | ✅ ACTIVE | Pipeline | KEEP |
| CharacterExtractionPass.kt | ✅ ACTIVE | Pipeline | KEEP |
| DialogExtractionPass.kt | ✅ ACTIVE | Pipeline | KEEP |
| ExtendedAnalysisPass.kt | ✅ ACTIVE | Pipeline | KEEP |
| ForeshadowingDetectionPass.kt | ✅ ACTIVE | Pipeline | KEEP |
| InferTraitsPass.kt | ✅ ACTIVE | Pipeline | KEEP |
| KeyMomentsExtractionPass.kt | ✅ ACTIVE | Pipeline | KEEP |
| ModularWorkflow.kt | ✅ ACTIVE | Pipeline | KEEP |
| MultipassPipeline.kt | ✅ ACTIVE | AnalysisService | KEEP |
| PersonalityInferencePass.kt | ✅ ACTIVE | Pipeline | KEEP |
| PipelineModels.kt | ✅ ACTIVE | ChapterAnalysisTask | KEEP |
| PlotPointExtractionPass.kt | ✅ ACTIVE | Pipeline | KEEP |
| RelationshipsExtractionPass.kt | ✅ ACTIVE | Pipeline | KEEP |
| ScenePromptGenerationPass.kt | ✅ ACTIVE | Pipeline | KEEP |
| StoryGenerationPass.kt | ✅ ACTIVE | Pipeline | KEEP |
| StoryRemixPass.kt | ✅ ACTIVE | Pipeline | KEEP |
| TextCleaner.kt | ✅ ACTIVE | Pipeline | KEEP |
| ThemeAnalysisPass.kt | ✅ ACTIVE | Pipeline | KEEP |
| TokenBudgetManager.kt | ✅ ACTIVE | Pipeline | KEEP |
| TraitsExtractionPass.kt | ✅ ACTIVE | Pipeline | KEEP |
| VoiceProfileSuggestionPass.kt | ✅ ACTIVE | Pipeline | KEEP |
| WorkflowConfig.kt | ✅ ACTIVE | Pipeline | KEEP |

---

## DIRECTORY: app/src/main/java/com/dramebaz/app/ai/llm/pipeline/passes/

| File | Status | Used By | Action |
|------|--------|---------|--------|
| CharacterExtractionPassV2.kt | ✅ ACTIVE | CharacterExtractionStep | KEEP |
| DialogExtractionPassV2.kt | ✅ ACTIVE | DialogExtractionStep | KEEP |
| KeyMomentsPassV2.kt | ✅ ACTIVE | Pipeline | KEEP |
| RelationshipsPassV2.kt | ✅ ACTIVE | Pipeline | KEEP |
| TraitsExtractionPassV2.kt | ✅ ACTIVE | VoiceProfileStep | KEEP |
| VoiceProfilePassV2.kt | ✅ ACTIVE | VoiceProfileStep | KEEP |

---

## DIRECTORY: app/src/main/java/com/dramebaz/app/domain/usecases/

| File | Status | Used By | Action |
|------|--------|---------|--------|
| ThreePassCharacterAnalysisUseCase.kt | ❌ DEAD | Test only | DELETE |
| GemmaCharacterAnalysisUseCase.kt | ❌ DEAD | Unused import | DELETE |
| ChapterCharacterExtractionUseCase.kt | ⚠️ PARTIAL | ReaderFragment | REFACTOR |
| BookAnalysisWorkflow.kt | ✅ ACTIVE | AnalysisQueueManager | KEEP |
| AnalysisQueueManager.kt | ✅ ACTIVE | Main workflow | KEEP |

---

## SUMMARY

**Total Analysis Files:** 50+
- **KEEP:** 47 files
- **DELETE:** 3 files (ThreePass, Gemma, ChapterAnalysisPipeline)
- **REFACTOR:** 1 file (ChapterCharacterExtraction)

**Checkpoint Logic Found In:**
- ThreePassCharacterAnalysisUseCase.kt (DELETE)
- GemmaCharacterAnalysisUseCase.kt (DELETE)
- ChapterAnalysisPipeline.kt (DELETE)
- ChapterAnalysisTask.kt (KEEP - but no checkpointing yet)

