[ ] NAME:Current Task List
DESCRIPTION:Root task for conversation __NEW_AGENT__

-[/] NAME:Implement Task List from augTaskList.json
DESCRIPTION:Go through all 45 tasks in the task list and implement them, building in release mode after each task

-[x] NAME:PHASE 1: Critical Bug Fixes & Safety
DESCRIPTION:AUG-001 to AUG-005 - All already implemented: service connection uses context?, PDF import throws exception, LLM has timeout, PlaybackEngine uses caller scope, stub has proximity-based speaker detection

-[x] NAME:PHASE 2: Character & Dialog Integration
DESCRIPTION:AUG-006
✓ (Trigger extraction on first read), AUG-007
✓ (Navigate to characters after extraction), AUG-008
✓ (Auto merge after extraction), AUG-009
✓ (Trait inference with retry & heuristics), AUG-010
✓ (Key moments extraction), AUG-011
✓ (Relationships extraction)

-[x] NAME:PHASE 3: TTS Enhancement & Voice Control
DESCRIPTION:AUG-012
✓ (Energy post-processing), AUG-013
✓ (Prosody hints with speaker selection for pitch), AUG-014
✓ (Smart speaker filtering with scores), AUG-015
✓ (Voice preview UI), AUG-016
✓ (Speaker pitch categorization)

-[x] NAME:PHASE 4: Playback & Sync Improvements
DESCRIPTION:AUG-017
✓ (Text-audio sync with actual durations), AUG-018
✓ (Playback progress persistence), AUG-019
✓ (Pre-generation constants), AUG-020
✓ (Smart bookmark context capture), AUG-021
✓ (Playback speed control UI)

-[x] NAME:PHASE 5: UI/UX Enhancements
DESCRIPTION:AUG-022
✓ (Emotional arc visualization), AUG-023
✓ (Character relationships UI), AUG-024
✓ (Key moments timeline), AUG-025
✓ (Progress indicators with cancellation), AUG-026
✓ (Character search/filtering), AUG-027
✓ (Reading statistics dashboard)

-[x] NAME:PHASE 6: Data Quality & Intelligence
DESCRIPTION:AUG-028
✓ (Vocabulary builder with definitions), AUG-029
✓ (Themes and symbols analysis), AUG-030
✓ (Smart chapter summaries), AUG-031
✓ (Character voice consistency checker), AUG-032
✓ (Dialog attribution confidence scoring)

-[x] NAME:PHASE 7: Performance & Optimization
DESCRIPTION:AUG-033
✓ (Dynamic concurrency based on RAM), AUG-034
✓ (LRU cache eviction with 100MB limit), AUG-035
✓ (Composite database indexes with migration), AUG-036
✓ (Lazy loading via novel pages), AUG-037
✓ (80% progress pre-analysis trigger)

-[x] NAME:PHASE 8: Error Handling & Logging
DESCRIPTION:All tasks completed: AUG-038
✓ (Standardized logging with AppLogger), AUG-039
✓ (Comprehensive error handling with AppExceptions + ErrorHandler + ErrorDialog), AUG-040
✓ (Input validation with InputValidator), AUG-041
✓ (Graceful degradation with DegradedModeManager). Build successful.
-
-[x] NAME:AUG-038: Standardize Logging Throughout Codebase
DESCRIPTION:Replaced all android.util.Log calls with AppLogger for consistent logging across entire codebase. Files fixed: OnnxQwenModel.kt, AudioMixer.kt, PlaybackEngine.kt, SfxEngine.kt, ChapterAnalysisService.kt, MergeCharactersUseCase.kt, TestActivity.kt, QwenStub.kt, PdfExtractor.kt, TextAudioSync.kt, SpeakerSelectionFragment.kt, VoicePreviewFragment.kt, NovelPageSplitter.kt, ReaderFragment.kt
-
-[x] NAME:AUG-039: Implement Comprehensive Error Handling
DESCRIPTION:Created comprehensive error handling system with AppExceptions sealed class hierarchy (ErrorType enum, FileIOException, DatabaseException, LLMException, TTSException, OutOfMemoryException, ValidationException), ErrorHandler utility with exponential backoff retry logic, ErrorDialog reusable component with Material Design 3. Updated LibraryFragment, ReaderFragment to use ErrorDialog. Updated ImportException to extend FileIOException.
-
-[x] NAME:AUG-040: Add Input Validation and Sanitization
DESCRIPTION:Created InputValidator utility with: story prompt validation (min 10, max 1000 chars), file import validation (size, format, existence), LLM prompt sanitization (control chars, length limit), TTS text sanitization (normalize quotes, remove emoji). Updated StoryGenerationFragment with prompt validation and character counter. Updated ImportBookUseCase with file validation. Added helper text and character counter to story generation UI.
-
-[x] NAME:AUG-041: Implement Graceful Degradation for Model Failures
DESCRIPTION:Created DegradedModeManager to track LLM (ONNX/STUB_FALLBACK) and TTS (FULL/DISABLED) modes. Updated QwenStub to report mode to DegradedModeManager with failure reasons. Updated SherpaTtsEngine to report mode and added retryInit() method. Added degraded mode banner to ReaderFragment layouts (fragment_reader.xml, fragment_reader_novel.xml) showing warning when running in limited mode with retry button. Added early TTS availability check in playCurrentPage() with user-friendly message and retry option. Added warning colors to colors.xml.

-[/] NAME:PHASE 9: Testing & Validation
DESCRIPTION:AUG-042 to AUG-045 - Unit tests, integration tests, UI tests, performance benchmarks
-
-[x] NAME:AUG-042: Create Unit Tests for Critical Business Logic
DESCRIPTION:Created unit tests for critical business logic: ProsodyControllerTest (12 tests for TTS params, pitch levels), InputValidatorTest (18 tests for validation/sanitization), ErrorHandlerTest (15 tests for error classification/retry), DegradedModeManagerTest (11 tests for degraded mode tracking), VoiceProfileMapperTest, SpeakerMatcherTest. All 77 tests pass. Used MockK to mock android.util.Log for JVM unit tests.
-
-[x] NAME:AUG-043: Add Integration Tests for Playback Pipeline
DESCRIPTION:Integration tests for playback pipeline are comprehensive. Existing tests cover: AudioPlaybackTest (13 tests for engine init, narration/dialog, queue, pre-synthesis, playback flow, stop/pause/resume, speaker IDs, progress callbacks), AudioMixerTest (5 tests for mixer, volumes, themes), TtsGenerationTest (11 tests for TTS synthesis). Added 5 new tests: seekTo, speedMultiplier, emptyQueue, mixedEmotions (prosody), rapidStopStart (stress test). Total: 34 integration tests.
-
-[x] NAME:AUG-044: Add UI Tests for Critical User Flows
DESCRIPTION:Created comprehensive Espresso UI tests in CriticalUserFlowsTest.kt (14 tests): Library display (toolbar, FAB, empty/recycler state), Navigation (settings, story generation, book detail), Story generation (UI elements, prompt validation), Full book flow, Toolbar menu accessibility, Import FAB clickable, Navigation back tests, Scroll tests. Added Espresso contrib/intents/navigation-testing dependencies. Updated AllModelTests suite to include UI tests.
-
-[/] NAME:AUG-045: Create Performance Benchmarks
DESCRIPTION:Establish performance benchmarks: LLM analysis time, TTS generation time, character extraction time, database query time, UI render time. Framework: Android Benchmark library.

-[ ] NAME:Feature check
DESCRIPTION:Check if all the features suggested in Features.md are implemented. Update the feature list with a checkmark for completed features.

-[ ] NAME:In the usertasks.md file, add list of resources you need me to download.
DESCRIPTION:

-[ ] NAME:Always use multiple agents
DESCRIPTION:

-[ ] NAME:Always pre-process 1 next chapter in advance. Do this processing in background. Add this task to augTaskList.json in approprite phase.
DESCRIPTION:

-[ ] NAME:App crashes while Anlaysing Chapters. Locate and fix the issue.
DESCRIPTION:High Priority

-[ ] NAME:Show progress on the 'Analyse Chapters' dialog with more verbosity. Fix the text, steps, and progress counters. Add this task to augTaskList.json in approprite phase.
DESCRIPTION:

-[ ] NAME:Create a more natural page rendering. Each rendered page should contain atleast half the text of the original pdf page. Use same or better fonts (matching theme of the book). Copy fonts from Window's font library. or here: D:\Boxed\UI\Fonts, or from the internet. Add this task to augTaskList.json in approprite phase.
DESCRIPTION:

-[ ] NAME:Update the feature list with improvements made so far.
DESCRIPTION:

-[ ] NAME:The control panel on book reading page is not working at all.
DESCRIPTION:

-[ ] NAME:Reading activity have pink botton with no text on it. What is it for?
DESCRIPTION:

-[ ] NAME:Trigger analysis of first chapter when the book is loaded. If multiple books are loaded, enqueue analysis if them. The ''Analyse Chapter button should reflect the latest status of analysis. Doesn't matter which workflow triggred it'. Add this task to augTaskList.json in approprite phase.
DESCRIPTION:

-[/] NAME:Fix Analyse Chapters crash
DESCRIPTION:Fix the crash when clicking 'Analyse Chapters' button. Issues: 1) Service runs in separate process causing Application cast issues, 2) BroadcastReceiver not receiving broadcasts from separate process

-[ ] NAME:Complete AUG-045 Performance Benchmarks
DESCRIPTION:The PerformanceBenchmarks.kt file was already created. Verify it builds and runs correctly.

-[ ] NAME:Build release and verify Phase 9 complete
DESCRIPTION:Build in release mode and verify all Phase 9 tasks (AUG-042 to AUG-045) are complete
