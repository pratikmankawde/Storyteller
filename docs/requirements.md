# NovelReaderWeb Feature Implementation Requirements

## Overview
This document outlines the requirements for implementing features from NovelReaderWeb task lists into Dramebaz (Storyteller).

## Implementation Checklist (Check Before & After Each Task)

### Before Starting a Task
- [ ] Read the task's `reference_docs` to understand the feature from NovelReaderWeb
- [ ] Review `files_to_modify` to identify affected files
- [ ] Follow the `implementation_notes` and `work_items` for guidance
- [ ] Check dependencies are completed (refer to `docs/tasklists/README.md`)
- [ ] Update task `status` to "in_progress" in the JSON file

### During Implementation
- [ ] Write **modular, maintainable code** following existing patterns
- [ ] Add proper error handling using `AppLogger`
- [ ] Use Kotlin coroutines best practices (proper scopes, handle cancellation)
- [ ] Keep UI responsive (offload heavy work to background threads)
- [ ] Keep code in small units in dedicated directories

### After Completing a Task
- [ ] Verify all `acceptance_criteria` are met
- [ ] Ensure code builds without errors AND warnings
- [ ] Tests can run in parallel
- [ ] Mark task `status` as "completed" and `done: true` in JSON
- [ ] Fresh context for next task

## Key Constraints

### Architecture
- **Offline-first**: No network calls (all processing is local)
- **Existing models**: Use Gemma-3n LLM and en_US_libritts-high.onnx TTS models
- **Clean Architecture**: Follow domain/data/ui layer separation

### Kotlin Best Practices
- Use proper coroutine scopes (`viewModelScope`, `lifecycleScope`)
- Handle cancellation properly
- Use `Flow` and `StateFlow` for reactive data
- Use `suspend` functions for async operations

### UI Guidelines
- Keep UI responsive (no blocking main thread)
- Use `Dispatchers.IO` for I/O operations
- Use `Dispatchers.Default` for CPU-intensive work
- Follow Material Design 3 guidelines

## Dependency Priority Order

### Root Tasks (No Dependencies)
1. **SETTINGS-001** (HIGH) - Settings Bottom Sheet UI
2. **CHAP-001** (MEDIUM) - Chapter Manager Dialog
3. **UI-005** (LOW) - Fluid Page Turning Effects
4. **UI-006** (LOW) - Loading Shimmer Effect

### Phase 1: Quick Wins (20h)
1. AUDIO-002: TTS Emotion Modifiers (4h)
2. UI-001: Karaoke Text Highlighting (12h)
3. READ-001: Reading Mode Toggle (4h)

### Phase 2: Core Enhancements (26h)
4. AUDIO-001: Enhanced Director Pipeline (8h)
5. VIS-001: Scene Prompt Generation (6h)
6. VIS-002: Scene Image Generation (8h)
7. SETTINGS-001: Settings Bottom Sheet (5h)

### Phase 3: Advanced Features (37h)
8. STORY-001: Text-to-Story Generation (6h)
9. STORY-002: Image-to-Story Generation (5h)
10. STORY-003: Story Remix Mode (5h)
10. VOICE-001: Voice Selector Dialog (6h)
11. INSIGHTS-001: Emotional Arc Visualization (6h)
12. EXT-001: External Metadata Fetching (5h)
13. CHAP-001: Chapter Manager Dialog (6h)

### Phase 4: Polish & Extended Features (40h)
14. THEME-001: Generative UI Theme Analysis (6h)
15. VIS-003: Scene Animation (6h)
16. READ-002: Audio Buffer Pre-loading (4h)
17. UI-002: VoiceWaveform Visualizer (6h)
18. UI-003: Character Avatar Bubbles (4h)
19. INSIGHTS-003: Sentiment Distribution (3h)
20. INSIGHTS-004: Reading Level Analysis (2h)
21. SUMMARY-001: Time-Aware Smart Recaps (4h)
22. SETTINGS-004: System Benchmark (2h)

### Phase 5: Final Polish (30h)
23. UI-004: Shared Element Transitions (4h)
24. UI-005: Fluid Page Turning (3h)
25. UI-006: Loading Shimmer Effect (2h)
26. ARCH-001: LLM Strategy Pattern (5h)
27. ARCH-002: Play Asset Delivery (8h)
28. SUMMARY-002: Multi-Book Cross-Reference (4h)
29. READ-003: Chapter Lookahead Analysis (4h)

## Testing Requirements
- Unit tests for business logic in `app/src/test/java/`
- Instrumented tests for UI in `app/src/androidTest/`
- Tests must be able to run in parallel

## Task List Files
All task definitions are in `docs/tasklists/`:
- `SettingsTaskList.json`
- `ChapterManagementTaskList.json`
- `AudioPipelineTaskList.json`
- `UITaskList.json`
- `ReadingModesTaskList.json`
- `InsightsTaskList.json`
- `VisualizationTaskList.json`
- `StoryGeneratorTaskList.json`
- `ThemeTaskList.json`
- `SummaryTaskList.json`
- `ArchitectureTaskList.json`
- `ExternalDataTaskList.json`
- `VoiceManagementTaskList.json`

## Completed Tasks

- [x] SETTINGS-001: Settings Bottom Sheet UI (SettingsBottomSheet with tabs for Display, Audio, Features, About)
- [x] SETTINGS-002: Display Settings (ReadingSettings data class, night mode, font controls)
- [x] SETTINGS-003: Feature Toggles (FeatureSettings data class, toggle switches for TTS, waveform, karaoke)
- [x] SETTINGS-004: System Benchmark (BenchmarkDialog, CPU/memory metrics, model loading times)
- [x] CHAP-001: Chapter Manager Dialog (ChapterManagerDialog with move, rename, merge, split, delete operations)
- [x] UI-005: Fluid Page Turning Effects (Already implemented with FluidPageTurner)
- [x] UI-006: Loading Shimmer Effect (ShimmerLoadingView custom View)
- [x] UI-001: Karaoke Text Highlighting (KaraokeHighlighter for word-level sync with TTS playback)
- [x] UI-002: VoiceWaveform Visualizer (VoiceWaveformView with amplitude bars, integrated with PlaybackEngine)
- [x] ARCH-001: LLM Strategy Pattern (Already implemented - LlmModel interface, model implementations, factory)
- [x] CHAP-002: Batch Chapter Re-Analysis (Batch operations with progress dialog)
- [x] VIS-001: Scene Prompt Generation (ScenePrompt data class, LLM-powered prompt generation)
- [x] AUDIO-001: Enhanced Director Pipeline (AudioDirector with Producer-Consumer Kotlin Channels)
- [x] AUDIO-002: TTS Emotion Modifiers (Already implemented)
- [x] INS-001: Enhanced Emotional Arc Visualization (EmotionalArcView custom View with bezier curves)
- [x] INS-002: Foreshadowing Detection (ForeshadowingDetector with LLM, ForeshadowingView timeline)
- [x] INS-003: Sentiment Distribution (SentimentDistributionView horizontal segmented bar chart)
- [x] INS-004: Reading Level Analysis (ReadingLevel data class with Flesch-Kincaid formulas, complexity breakdown)
- [x] INS-005: Plot Outline Extraction (PlotPoint data class, PlotPointExtractor LLM-based, PlotOutlineView custom View with bezier story arc)
- [x] READ-001: Reading Mode Toggle (ReadingMode enum, mode-specific UI behavior in ReaderFragment, persisted mode preference)
- [x] READ-002: Audio Buffer Pre-loading (AudioBufferManager class with pre-loading for next 2 pages, progress-based triggering at 50%, memory management)
- [x] READ-003: Chapter Lookahead Analysis (ChapterLookaheadManager with 80% progress trigger, LLM-based next chapter pre-analysis, state management)
- [x] SUMMARY-001: Time-Aware Smart Recaps (RecapDepth enum with BRIEF/MEDIUM/DETAILED levels, TimeAwareRecapResult with character reminders, time-aware UI formatting)
- [x] VOICE-001: Voice Selector Dialog (VoiceSelectorDialog BottomSheetDialogFragment with gender filter, voice list, speed/energy sliders, test and save functionality)
- [x] THEME-001: Generative UI Theme Analysis (GeneratedTheme data class, ThemeGenerator LLM-based, DynamicThemeManager, mood-to-color/genre-to-font mappings, integrated in ReaderFragment)
- [x] VOICE-002: Voice Consistency Check (VoiceConsistencyChecker with speaker ID validation 0-903, ConsistencyResult/InvalidVoice data classes, Snackbar warning with "Fix" action in ReaderFragment, fallback suggestion via SpeakerMatcher)

## Next Tasks (Based on Dependency Graph)

- [x] STORY-001: Text-to-Story Generation (6h) - Already implemented (StoryGenerationFragment)
- [x] STORY-002: Image-to-Story Generation (5h) - StoryGenerationFragment updated with third "Image" mode button in MaterialButtonToggleGroup, image picker card with preview container, imagePickerLauncher using registerForActivityResult with GetContent(), copyImageToCache() helper, generateFromImage() method. LiteRtLmEngine.generateFromImage() using Contents.of(Content.ImageFile(), Content.Text()) for multimodal input with visionBackend=GPU. LlmService.generateStoryFromImage() facade with stub fallback. Gemma3nModel.generateStoryFromImage() wrapper.
- [ ] VIS-002: Scene Image Generation (8h) - Depends on VIS-001 ✅ (SKIPPED - requires network calls)
- [x] READ-001: Reading Mode Toggle (4h) - Depends on AUDIO-001 ✅
- [x] READ-002: Audio Buffer Pre-loading (4h) - Depends on READ-001 ✅
- [x] READ-003: Chapter Lookahead Analysis (4h) - Depends on READ-002 ✅
- [x] SUMMARY-001: Time-Aware Smart Recaps (4h) - Depends on INS-001 ✅
- [x] VOICE-001: Voice Selector Dialog (6h) - Depends on AUDIO-002 ✅
- [x] THEME-001: Generative UI Theme Analysis (6h) - Depends on INS-001 ✅
- [x] VOICE-002: Voice Consistency Check (4h) - VoiceConsistencyChecker with speaker ID validation (0-903 range), ConsistencyResult data class, Snackbar warning in ReaderFragment with "Fix" action, fallback speaker suggestion using SpeakerMatcher
- [x] STORY-003: Story Remix Mode (5h) - StoryPrompts.buildRemixPrompt(), LlmService.remixStory(), Qwen3Model.remixStory(), StubFallbacks.remixStory() with multiple transformation types (horror, comedy, villain_pov, romance), StoryGenerationFragment with mode toggle (New Story/Remix), book selection dialog, source book content loading and remix generation
- [x] SUMMARY-002: Multi-Book Cross-Reference (4h) - BookSeries entity with id/name/description/createdAt, BookSeriesDao with CRUD operations, Book entity updated with seriesId and seriesOrder foreign key fields, MIGRATION_8_9 for schema changes, BookDao.update() and getBySeriesId(), BookRepository.updateBook() and booksInSeries(), SeriesRecapResult data model, GetRecapUseCase.getSeriesAwareRecap() with findPreviousBookInSeries() and generatePreviousBookSummary() helpers, SeriesLinkingDialog for UI to link books to series
- [x] UI-003: Character Avatar Bubbles (4h) - CharacterAvatarView custom View with AvatarState enum (IDLE, SPEAKING, LISTENING), pulse animation on border when speaking via ValueAnimator, SpeakerInfo data class and currentSpeaker StateFlow in PlaybackEngine, observer in ReaderFragment to update avatar with character name and state, addDialog/addNarration updated to include speaker name, fragment_reader.xml updated with CharacterAvatarView
- [x] UI-004: Shared Element Transitions (4h) - Book cover morphing animation from Library grid to BookDetail page. BookAdapter updated with ViewCompat.setTransitionName() per book cover, callback signature changed to pass ImageView. LibraryFragment uses FragmentNavigatorExtras with MaterialElevationScale exit/reenter transitions. BookDetailFragment uses MaterialContainerTransform for shared element enter transition, matching transitionName set on book_cover ImageView. fragment_book_detail.xml updated with book cover in header card.
