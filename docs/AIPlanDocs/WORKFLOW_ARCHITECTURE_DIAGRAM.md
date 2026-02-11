# Workflow Architecture Diagram

## CURRENT ACTIVE WORKFLOW (KEEP)

```
┌─────────────────────────────────────────────────────────────────┐
│                    BookAnalysisWorkflow                          │
│  - analyzeFirstChapter()                                         │
│  - analyzeAllChapters()                                          │
│  - analyzeChapter() [private]                                    │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│                   ChapterAnalysisTask                            │
│  - taskId: String                                               │
│  - displayName: String                                          │
│  - estimatedDurationSeconds: Int                                │
│  - execute(model, progressCallback): TaskResult                 │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│                   AnalysisExecutor                               │
│  - execute(task, options, onProgress)                           │
│  - Chooses: foreground (>60s) vs background (<60s)              │
│  - Manages LLM model lifecycle                                  │
│  - Persists results via TaskResultPersister                     │
└────────────────────────┬────────────────────────────────────────┘
                         │
        ┌────────────────┴────────────────┐
        ▼                                 ▼
┌──────────────────────┐      ┌──────────────────────┐
│ AnalysisBackground   │      │ AnalysisForeground   │
│ Runner               │      │ Service              │
│ (< 60s tasks)        │      │ (>= 60s tasks)       │
└──────────────────────┘      └──────────────────────┘
        │                                 │
        └────────────────┬────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│              ChapterAnalysisTask.execute()                       │
│                                                                  │
│  Step 1: CharacterExtractionStep                               │
│  ├─ Uses: CharacterExtractionPassV2                            │
│  ├─ Input: cleanedPages                                        │
│  └─ Output: characters map                                     │
│                                                                  │
│  Step 2: DialogExtractionStep                                  │
│  ├─ Uses: DialogExtractionPassV2                               │
│  ├─ Input: cleanedPages + characters                           │
│  └─ Output: dialogs per character                              │
│                                                                  │
│  Step 3: VoiceProfileStep                                      │
│  ├─ Uses: VoiceProfilePassV2                                   │
│  ├─ Input: characters + dialogs                                │
│  ├─ Output: voice profiles + speaker IDs                       │
│  └─ Uses: SpeakerMatcher for speaker assignment                │
└─────────────────────────────────────────────────────────────────┘
```

---

## OLD IMPLEMENTATIONS (DELETE)

```
┌──────────────────────────────────────────────────────────────┐
│  ThreePassCharacterAnalysisUseCase.kt (868 lines)            │
│  ❌ DEAD CODE - Only referenced in test                      │
│                                                               │
│  Methods:                                                    │
│  - analyzeChapter()                                          │
│  - runPass1() / runPass2() / runPass3()                      │
│  - loadCheckpoint() / saveCheckpoint()                       │
│                                                               │
│  Checkpoint: 24h expiry, per-page resume                     │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│  GemmaCharacterAnalysisUseCase.kt (537 lines)                │
│  ❌ DEAD CODE - Unused import in LibraryViewModel            │
│                                                               │
│  Methods:                                                    │
│  - analyzeChapter()                                          │
│  - initializeEngine()                                        │
│  - loadCheckpoint() / saveCheckpoint()                       │
│                                                               │
│  Checkpoint: 24h expiry, per-segment resume                  │
│  Uses: Deprecated LiteRtLmEngine                             │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│  ChapterAnalysisPipeline.kt (690 lines)                      │
│  ❌ DEAD CODE - No references anywhere                       │
│                                                               │
│  Methods:                                                    │
│  - analyzeChapter()                                          │
│  - runPass1() / runPass2() / runPass3()                      │
│  - loadCheckpoint() / saveCheckpoint()                       │
│                                                               │
│  Checkpoint: 24h expiry, per-page resume                     │
│  Replaced by: Modular pipeline (CharacterExtractionStep)     │
└──────────────────────────────────────────────────────────────┘
```

---

## CHECKPOINT LOGIC COMPARISON

```
┌─────────────────────────────────────────────────────────────┐
│                    Checkpoint Features                       │
├─────────────────────────────────────────────────────────────┤
│ Feature              │ ThreePass │ Gemma │ Pipeline         │
├──────────────────────┼───────────┼───────┼──────────────────┤
│ Checkpoint File      │ {id}_ch   │ gemma │ pipeline_{id}    │
│ Expiry               │ 24 hours  │ 24h   │ 24 hours         │
│ Hash Validation      │ Yes       │ Yes   │ Yes              │
│ Resume Granularity   │ Per-page  │ Per-  │ Per-page         │
│                      │           │ seg   │                  │
│ Mutex Protection     │ Yes       │ Yes   │ Yes              │
│ Pass Tracking        │ Yes       │ Yes   │ Yes              │
└─────────────────────────────────────────────────────────────┘

Current Workflow (ChapterAnalysisTask): ❌ NO CHECKPOINTING
```

---

## DEPENDENCY GRAPH

```
BookAnalysisWorkflow
    ├─ ChapterAnalysisTask ✅
    │   ├─ CharacterExtractionStep ✅
    │   │   └─ CharacterExtractionPassV2 ✅
    │   ├─ DialogExtractionStep ✅
    │   │   └─ DialogExtractionPassV2 ✅
    │   └─ VoiceProfileStep ✅
    │       └─ VoiceProfilePassV2 ✅
    │
    └─ AnalysisExecutor ✅
        ├─ LlmModelHolder ✅
        ├─ AnalysisBackgroundRunner ✅
        └─ AnalysisForegroundService ✅

ReaderFragment
    └─ ChapterCharacterExtractionUseCase ⚠️ (REFACTOR)

LibraryViewModel
    ├─ ThreePassCharacterAnalysisUseCase ❌ (DELETE)
    └─ GemmaCharacterAnalysisUseCase ❌ (DELETE)

PdfCharacterExtractionTest
    └─ ThreePassCharacterAnalysisUseCase ❌ (DELETE)
```

---

## SUMMARY

**Active Workflow:** ChapterAnalysisTask → AnalysisExecutor → 3 Steps
**Dead Code:** 3 files (2,095 lines total)
**Partially Used:** 1 file (ChapterCharacterExtractionUseCase)
**Checkpoint Logic:** Present in all 3 old files, missing in current workflow

