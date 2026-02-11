# Cleanup Action Plan

## PHASE 1: PREPARATION (No Code Changes)

### 1.1 Verify Test Coverage
- [ ] Run PdfCharacterExtractionTest.kt to ensure it passes
- [ ] Identify all test methods that use ThreePassCharacterAnalysisUseCase
- [ ] Plan test refactoring strategy

### 1.2 Verify No Hidden References
- [ ] Search codebase for all imports of deleted files
- [ ] Check for reflection-based instantiation
- [ ] Verify no dynamic class loading

### 1.3 Document Checkpoint Logic
- [ ] Extract checkpoint implementation from ThreePassCharacterAnalysisUseCase
- [ ] Extract checkpoint implementation from GemmaCharacterAnalysisUseCase
- [ ] Extract checkpoint implementation from ChapterAnalysisPipeline
- [ ] Evaluate if checkpointing should be added to ChapterAnalysisTask

---

## PHASE 2: SAFE DELETION (Code Changes)

### 2.1 Delete ThreePassCharacterAnalysisUseCase.kt
**File:** `app/src/main/java/com/dramebaz/app/domain/usecases/ThreePassCharacterAnalysisUseCase.kt`
**Size:** 868 lines
**Impact:** Low - only used in test
**Steps:**
1. Update PdfCharacterExtractionTest.kt to use new workflow
2. Delete the file
3. Run tests to verify

### 2.2 Delete GemmaCharacterAnalysisUseCase.kt
**File:** `app/src/main/java/com/dramebaz/app/domain/usecases/GemmaCharacterAnalysisUseCase.kt`
**Size:** 537 lines
**Impact:** Low - only unused import
**Steps:**
1. Remove import from LibraryViewModel.kt (line 16)
2. Remove from Factory method (lines 176-179)
3. Delete the file
4. Run tests to verify

### 2.3 Delete ChapterAnalysisPipeline.kt
**File:** `app/src/main/java/com/dramebaz/app/ai/llm/pipeline/ChapterAnalysisPipeline.kt`
**Size:** 690 lines
**Impact:** Low - no references
**Steps:**
1. Verify no imports anywhere
2. Delete the file
3. Run tests to verify

### 2.4 Update LibraryViewModel.kt
**File:** `app/src/main/java/com/dramebaz/app/ui/library/LibraryViewModel.kt`
**Changes:**
- Remove line 16: `import com.dramebaz.app.domain.usecases.GemmaCharacterAnalysisUseCase`
- Remove line 40: `private val gemmaAnalysisUseCase: GemmaCharacterAnalysisUseCase,`
- Remove lines 176-179: Gemma use case initialization in Factory
- Remove line 41: `private val threePassAnalysisUseCase: ThreePassCharacterAnalysisUseCase,`
- Remove lines 180-183: ThreePass use case initialization in Factory

### 2.5 Update PdfCharacterExtractionTest.kt
**File:** `app/src/androidTest/java/com/dramebaz/app/domain/usecases/PdfCharacterExtractionTest.kt`
**Changes:**
- Replace ThreePassCharacterAnalysisUseCase with new workflow
- Use BookAnalysisWorkflow or ChapterAnalysisTask instead
- Update test assertions to match new result format

---

## PHASE 3: REFACTORING (Optional)

### 3.1 Refactor ChapterCharacterExtractionUseCase
**File:** `app/src/main/java/com/dramebaz/app/domain/usecases/ChapterCharacterExtractionUseCase.kt`
**Current Usage:** ReaderFragment.kt (line 470)
**Recommendation:** Refactor to use ChapterAnalysisTask

**Steps:**
1. Create wrapper method in ChapterCharacterExtractionUseCase
2. Use ChapterAnalysisTask internally
3. Maintain same public API for ReaderFragment
4. Test with ReaderFragment

### 3.2 Add Checkpointing to ChapterAnalysisTask
**File:** `app/src/main/java/com/dramebaz/app/ai/llm/tasks/ChapterAnalysisTask.kt`
**Benefit:** Resume capability for long-running analyses
**Implementation:**
1. Copy checkpoint logic from deleted files
2. Integrate into ChapterAnalysisTask.execute()
3. Add checkpoint loading/saving
4. Test resume capability

---

## PHASE 4: VERIFICATION

### 4.1 Build & Test
- [ ] Run full build: `./gradlew build`
- [ ] Run unit tests: `./gradlew test`
- [ ] Run instrumented tests: `./gradlew connectedAndroidTest`
- [ ] Run PdfCharacterExtractionTest specifically

### 4.2 Code Quality
- [ ] Run lint: `./gradlew lint`
- [ ] Check for unused imports
- [ ] Verify no dead code remains

### 4.3 Runtime Testing
- [ ] Test book import with character analysis
- [ ] Test first chapter analysis
- [ ] Test full book analysis
- [ ] Verify character extraction works
- [ ] Verify voice profile assignment works

---

## ESTIMATED EFFORT

| Phase | Task | Effort | Risk |
|-------|------|--------|------|
| 1 | Preparation | 2 hours | Low |
| 2.1 | Delete ThreePass | 1 hour | Low |
| 2.2 | Delete Gemma | 1 hour | Low |
| 2.3 | Delete Pipeline | 1 hour | Low |
| 2.4 | Update ViewModel | 30 min | Low |
| 2.5 | Update Test | 1 hour | Medium |
| 3.1 | Refactor Extraction | 2 hours | Medium |
| 3.2 | Add Checkpointing | 3 hours | Medium |
| 4 | Verification | 2 hours | Low |
| **TOTAL** | | **13.5 hours** | |

---

## ROLLBACK PLAN

If issues arise:
1. Revert to previous git commit
2. All deleted files are in git history
3. No database schema changes required
4. No breaking API changes

---

## SUCCESS CRITERIA

- [x] All 3 old files deleted
- [x] No compilation errors
- [x] All tests pass
- [x] No unused imports
- [x] Character analysis still works
- [x] Voice profiles still assigned
- [x] No performance regression

