# NovelReaderWeb Feature Implementation Plan for Storyteller

> **Reference Project:** `C:\Users\Pratik\source\NovelReaderWeb`  
> **Documentation Files:** See [Source Reference Index](#source-reference-index)

---

## Executive Summary

This document analyzes features from NovelReaderWeb's documentation and compares them to the current Storyteller (Dramebaz) implementation, identifying gaps and creating a detailed implementation plan.

---

## Feature Comparison Matrix

| Feature | NovelReaderWeb Spec | Storyteller Status | Priority | Effort |
|---------|---------------------|-------------------|----------|--------|
| **1. Smart Import & Text Extraction** | PDF/TXT import, cover gen, chapter detection | ✅ Implemented | - | - |
| **2. Chapter Boundary Detection** | Regex + LLM verification hybrid | ✅ Implemented | - | - |
| **3. The "Casting Director"** | Background character analysis, voice assignment | ✅ Implemented | - | - |
| **4. The "Director" Pipeline** | Producer-Consumer audio pipeline | ⚠️ Partial | HIGH | 8h |
| **5. Karaoke Text Highlighting** | Word-by-word text sync with audio | ❌ Missing | HIGH | 12h |
| **6. Automatic Summaries & Recaps** | "Previously on..." overlay after 24h+ | ✅ Implemented | - | - |
| **7. Generative UI (Theme Analysis)** | LLM-based dynamic UI theming | ❌ Missing | MEDIUM | 6h |
| **8. TTS Emotion Modifiers** | Whisper/Angry/Sad speed/pitch modulation | ⚠️ Partial | HIGH | 4h |
| **9. Shared Element Transitions** | Library → Reader cover morphing | ❌ Missing | LOW | 4h |
| **10. Fluid Page Turning** | Book-fold rotation effects | ❌ Missing | LOW | 3h |
| **11. VoiceWaveform Visualizer** | PCM buffer visualization | ❌ Missing | MEDIUM | 6h |
| **12. Character Avatar Bubbles** | Pulse animation on speaking | ❌ Missing | MEDIUM | 4h |
| **13. Settings Model Switching** | Runtime LLM/TTS strategy swap | ⚠️ Partial | MEDIUM | 5h |
| **14. Play Asset Delivery** | On-demand model downloading | ❌ Missing | LOW | 8h |
| **15. Emotional Arc Detection** | Intensity graph visualization | ⚠️ Partial | MEDIUM | 6h |
| **16. Foreshadowing Detection** | Trigger phrase analysis | ❌ Missing | LOW | 4h |
| **17. Themes/Motifs Extraction** | Recursive summarization for themes | ⚠️ Partial | LOW | 4h |

---

## Missing Features Detail

### HIGH PRIORITY

#### 1. Karaoke Text Highlighting (HIGH - 12h)
**NovelReaderWeb Reference:**
- `C:\Users\Pratik\source\NovelReaderWeb\docs\UI.md` - Section "Karaoke Flow"
- `C:\Users\Pratik\source\NovelReaderWeb\docs\UIFlow.md` - Section "Karaoke Sync"

**Implementation Strategy:**
- Use `SpanStyle` animations in Compose (or equivalent in Android View system)
- Track `currentSegmentId` from PlaybackEngine
- Animate background color for active text range
- Sync with `AudioTrack` markers or estimated duration

**Source Code Reference:**
```kotlin
// From NovelReaderWeb docs/UI.md - KaraokeText pattern
Text(buildAnnotatedString {
    append(fullText)
    if (activeSegmentRange != null) {
        addStyle(SpanStyle(background = animatedColor),
                 start = activeSegmentRange.start,
                 end = activeSegmentRange.end)
    }
})
```

#### 2. Enhanced Director Pipeline (HIGH - 8h)
**NovelReaderWeb Reference:**
- `C:\Users\Pratik\source\NovelReaderWeb\docs\ARCHITECTURE.md` - "The Director Pipeline"
- `C:\Users\Pratik\source\NovelReaderWeb\docs\FEATURES.md` - Feature 4: AudioDirector

**Implementation Strategy:**
- Implement true Producer-Consumer pattern with Kotlin Channels
- Pre-process next sentence while playing current one
- Add buffered Channel for script segments and audio buffers

#### 3. Full TTS Emotion Modifiers (HIGH - 4h)
**NovelReaderWeb Reference:**
- `C:\Users\Pratik\source\NovelReaderWeb\docs\AI.md` - "TTS Emotion Modifiers"

**Emotion Table:**
| Emotion | Speed | Pitch/Volume |
|---------|-------|--------------|
| neutral | 1.0 | Standard |
| sad | 0.8 | Pitch -0.1 |
| angry | 1.2 | Pitch +0.2, Volume +20% |
| fear | 1.1 | Pitch +0.3 |
| whisper | 0.9 | Volume -50% |
| happy | 1.1 | Pitch +0.1 |

---

### MEDIUM PRIORITY

#### 4. Generative UI Theme Analysis (MEDIUM - 6h)
**NovelReaderWeb Reference:**
- `C:\Users\Pratik\source\NovelReaderWeb\docs\FEATURES.md` - Feature 5: Generative UI
- `C:\Users\Pratik\source\NovelReaderWeb\docs\AI.md` - "Theme Analysis"

**Implementation Strategy:**
- On book import, analyze first chapter text with LLM
- Extract mood (Horror, Romance, Sci-Fi, etc.)
- Map mood to color scheme and font family
- Store in BookEntity.themeJson
- Apply theme dynamically in ReaderFragment

**AI Prompt:**
```json
{"system": "You are a graphic designer. Analyze the mood of the story.",
 "output": {"mood": "string", "color": "#RRGGBB", "font": "Serif|Sans"}}
```

#### 5. VoiceWaveform Visualizer (MEDIUM - 6h)
**NovelReaderWeb Reference:**
- `C:\Users\Pratik\source\NovelReaderWeb\docs\UI.md` - "VoiceWaveform"

**Implementation Strategy:**
- Create custom View/Canvas for audio visualization
- Draw cubic bezier curves based on PCM amplitude (RMS)
- Connect to AudioTrack buffer during playback
- Use `infiniteTransition` for smooth animation

#### 6. Character Avatar Bubbles (MEDIUM - 4h)
**NovelReaderWeb Reference:**
- `C:\Users\Pratik\source\NovelReaderWeb\docs\UI.md` - "CharacterAvatarBubble"

**Implementation Strategy:**
- Show active speaker avatar in Reader UI
- Pulse animation on border when speaking
- States: Idle, Speaking, Listening
- Display character name and assigned voice

#### 7. Runtime Model Switching (MEDIUM - 5h)
**NovelReaderWeb Reference:**
- `C:\Users\Pratik\source\NovelReaderWeb\docs\UI.md` - "AI Model Abstraction"
- `C:\Users\Pratik\source\NovelReaderWeb\docs\ARCHITECTURE.md` - "LlmInferenceStrategy"

**Implementation Strategy:**
- Create `LlmInferenceStrategy` interface
- Implement `GemmaStrategy`, `QwenStrategy`
- Add `LlmProvider` factory for runtime switching
- Add Settings UI to select active model
- Apply Strategy pattern to TtsEngine as well

#### 8. Emotional Arc Visualization (MEDIUM - 6h)
**NovelReaderWeb Reference:**
- `C:\Users\Pratik\source\NovelReaderWeb\docs\HEURISTICS.md` - "Emotional Arc Detection"

**Implementation Strategy:**
- Sample first 1000 chars of each chapter
- Batch analyze via WorkManager
- Store intensity scores (1-10) per chapter
- Visualize as line graph in InsightsFragment

---

### LOW PRIORITY

#### 9. Shared Element Transitions (LOW - 4h)
**NovelReaderWeb Reference:**
- `C:\Users\Pratik\source\NovelReaderWeb\docs\UI.md` - "Shared Element Transitions"

**Implementation Strategy:**
- Use SharedElementTransition for book cover morphing
- Library grid → Reader header animation
- Apply `sharedElement` modifier with matching keys

#### 10. Fluid Page Turning Effects (LOW - 3h)
**NovelReaderWeb Reference:**
- `C:\Users\Pratik\source\NovelReaderWeb\docs\UI.md` - "Fluid Page Turning"

**Implementation Strategy:**
- Use `HorizontalPager` or ViewPager2
- Apply `graphicsLayer` with rotation effects
- Simulate book-spine fold rotation on swipe

#### 11. Play Asset Delivery for Models (LOW - 8h)
**NovelReaderWeb Reference:**
- `C:\Users\Pratik\source\NovelReaderWeb\docs\ARCHITECTURE.md` - "Deployment Strategy"

**Implementation Strategy:**
- Configure Play Asset Delivery for large model files
- Move LLM (.bin) and TTS (.onnx) models to on-demand packs
- Show "Downloading AI Brain..." progress on first launch
- Use AssetPackManager API

#### 12. Foreshadowing Detection (LOW - 4h)
**NovelReaderWeb Reference:**
- `C:\Users\Pratik\source\NovelReaderWeb\docs\HEURISTICS.md` - "Foreshadowing Detection"

**Implementation Strategy:**
- Scan for trigger phrases: "little did he know", "years later"
- Extract surrounding paragraph context
- Use LLM to speculate on foreshadowed events
- Display in InsightsFragment

#### 13. Loading Shimmer Effect (LOW - 2h)
**NovelReaderWeb Reference:**
- `C:\Users\Pratik\source\NovelReaderWeb\docs\UI.md` - "LoadingShimmer"

**Implementation Strategy:**
- Replace circular spinners with text-line shimmer
- Use LinearGradient brush with infiniteTransition
- Apply during LLM analysis loading states

---

## Implementation Task List

### Phase 1: Core Audio Experience (24h total)

| Task ID | Task | Hours | Reference Files |
|---------|------|-------|-----------------|
| NRW-001 | Implement Karaoke Text Highlighting | 12h | `docs/UI.md`, `docs/UIFlow.md` |
| NRW-002 | Enhance Director Pipeline with Channels | 8h | `docs/ARCHITECTURE.md`, `docs/FEATURES.md` |
| NRW-003 | Complete TTS Emotion Modifiers | 4h | `docs/AI.md` |

### Phase 2: Visual Enhancements (16h total)

| Task ID | Task | Hours | Reference Files |
|---------|------|-------|-----------------|
| NRW-004 | Implement Generative UI Theme Analysis | 6h | `docs/FEATURES.md`, `docs/AI.md` |
| NRW-005 | Create VoiceWaveform Visualizer | 6h | `docs/UI.md` |
| NRW-006 | Add Character Avatar Bubbles | 4h | `docs/UI.md` |

### Phase 3: Architecture Improvements (11h total)

| Task ID | Task | Hours | Reference Files |
|---------|------|-------|-----------------|
| NRW-007 | Implement LLM Strategy Pattern | 5h | `docs/UI.md`, `docs/ARCHITECTURE.md` |
| NRW-008 | Add Emotional Arc Visualization | 6h | `docs/HEURISTICS.md` |

### Phase 4: Polish & Nice-to-Have (21h total)

| Task ID | Task | Hours | Reference Files |
|---------|------|-------|-----------------|
| NRW-009 | Add Shared Element Transitions | 4h | `docs/UI.md` |
| NRW-010 | Implement Fluid Page Turning | 3h | `docs/UI.md` |
| NRW-011 | Setup Play Asset Delivery | 8h | `docs/ARCHITECTURE.md` |
| NRW-012 | Add Foreshadowing Detection | 4h | `docs/HEURISTICS.md` |
| NRW-013 | Implement Loading Shimmer Effect | 2h | `docs/UI.md` |

---

## Source Reference Index

All reference documentation is located at: `C:\Users\Pratik\source\NovelReaderWeb\docs\`

| Document | Path | Key Sections |
|----------|------|--------------|
| **AgentInstruction.md** | `docs/AgentInstruction.md` | Master guide, feature overview |
| **AI.md** | `docs/AI.md` | LLM prompts, TTS integration, emotion modifiers |
| **ARCHITECTURE.md** | `docs/ARCHITECTURE.md` | Clean Architecture, Director Pipeline, data flow |
| **FEATURES.md** | `docs/FEATURES.md` | Feature implementations with code samples |
| **HEURISTICS.md** | `docs/HEURISTICS.md` | Chapter detection, dialog attribution, voice mapping |
| **INSTRUCTIONS.md** | `docs/INSTRUCTIONS.md` | Master blueprint, data schema, JNI setup |
| **SUMMARIES.md** | `docs/SUMMARIES.md` | Auto-summarization, smart recaps |
| **UI.md** | `docs/UI.md` | UI/UX patterns, animations, components |
| **UIFlow.md** | `docs/UIFlow.md` | User flows, event handling, state management |

---

## Detailed Implementation Instructions

### NRW-001: Karaoke Text Highlighting

**Files to Modify:**
- `app/src/main/java/com/dramebaz/app/ui/reader/ReaderFragment.kt`
- `app/src/main/java/com/dramebaz/app/playback/engine/PlaybackEngine.kt`

**Reference Implementation from NovelReaderWeb:**
See `C:\Users\Pratik\source\NovelReaderWeb\docs\UI.md` - "Karaoke Flow" section

**Steps:**
1. Add `activeSegmentRange` StateFlow to PlaybackEngine
2. Emit segment text range when playback starts for each segment
3. In ReaderFragment, observe the range and update text styling
4. Use SpannableString with BackgroundColorSpan for highlighting
5. Animate color transition using ValueAnimator

**Code Pattern (Kotlin equivalent):**
```kotlin
// In PlaybackEngine
private val _activeSegment = MutableStateFlow<TextRange?>(null)
val activeSegment: StateFlow<TextRange?> = _activeSegment

private suspend fun playSegment(segment: AudioSegment) {
    // Calculate text range for this segment
    val range = TextRange(segment.startOffset, segment.endOffset)
    _activeSegment.value = range
    // ... play audio ...
}

// In ReaderFragment
lifecycleScope.launch {
    playbackEngine.activeSegment.collect { range ->
        updateTextHighlight(range)
    }
}
```

### NRW-002: Enhanced Director Pipeline

**Files to Modify:**
- `app/src/main/java/com/dramebaz/app/playback/engine/PlaybackEngine.kt`

**Reference Implementation:**
See `C:\Users\Pratik\source\NovelReaderWeb\docs\ARCHITECTURE.md` - "The Director Pipeline"
See `C:\Users\Pratik\source\NovelReaderWeb\docs\FEATURES.md` - "AudioDirector" class

**Steps:**
1. Create `Channel<SpeechSegment>` for script segments (capacity 10)
2. Create `Channel<AudioBuffer>` for synthesized audio (capacity 5)
3. Implement three coroutine jobs:
   - Producer: LLM script generation
   - Transformer: TTS synthesis
   - Consumer: AudioTrack playback
4. Use `launch(Dispatchers.Default)` for CPU-bound work
5. Use `launch(Dispatchers.IO)` for audio playback

**Code Pattern:**
```kotlin
class AudioDirector(
    private val llmService: QwenStub,
    private val ttsService: SherpaTtsEngine
) {
    private val segmentChannel = Channel<SpeechSegment>(capacity = 10)
    private val audioBufferChannel = Channel<AudioBuffer>(capacity = 5)

    suspend fun startReading(pageText: String) = coroutineScope {
        // Producer: Generate script
        launch(Dispatchers.Default) {
            val scriptJson = llmService.analyzeChapter(pageText)
            val segments = parseSegments(scriptJson)
            segments.forEach { segmentChannel.send(it) }
            segmentChannel.close()
        }

        // Transformer: Synthesize audio
        launch(Dispatchers.Default) {
            for (seg in segmentChannel) {
                val audio = ttsService.synthesize(seg.text, seg.speakerId, seg.speed)
                audioBufferChannel.send(AudioBuffer(audio, seg))
            }
            audioBufferChannel.close()
        }

        // Consumer: Play audio
        launch(Dispatchers.IO) {
            for (buffer in audioBufferChannel) {
                playAudio(buffer)
                emitActiveSegment(buffer.segment)
            }
        }
    }
}
```

### NRW-003: Complete TTS Emotion Modifiers

**Files to Modify:**
- `app/src/main/java/com/dramebaz/app/ai/tts/SherpaTtsEngine.kt`
- `app/src/main/java/com/dramebaz/app/data/models/VoiceProfile.kt`

**Reference Implementation:**
See `C:\Users\Pratik\source\NovelReaderWeb\docs\AI.md` - "TTS Input Formatting" section

**Steps:**
1. Create EmotionModifier data class
2. Map emotion tags to speed/pitch/volume multipliers
3. Apply modifiers in SherpaTtsEngine.synthesize()
4. Add volume post-processing for whisper/angry effects

**Code Pattern:**
```kotlin
data class EmotionModifier(
    val speedMultiplier: Float,
    val pitchMultiplier: Float,
    val volumeMultiplier: Float
)

val EMOTION_MODIFIERS = mapOf(
    "neutral" to EmotionModifier(1.0f, 1.0f, 1.0f),
    "sad" to EmotionModifier(0.8f, 0.9f, 1.0f),
    "angry" to EmotionModifier(1.2f, 1.2f, 1.2f),
    "fear" to EmotionModifier(1.1f, 1.3f, 1.0f),
    "whisper" to EmotionModifier(0.9f, 1.0f, 0.5f),
    "happy" to EmotionModifier(1.1f, 1.1f, 1.0f)
)
```

---

## Total Estimated Effort

| Phase | Hours | Priority |
|-------|-------|----------|
| Phase 1: Core Audio | 24h | HIGH |
| Phase 2: Visual | 16h | MEDIUM |
| Phase 3: Architecture | 11h | MEDIUM |
| Phase 4: Polish | 21h | LOW |
| **Total** | **72h** | - |

---

## Quick Start: Implementing the Top 3 Features

1. **Start with NRW-003 (TTS Emotion Modifiers)** - Lowest effort, highest impact
   - Reference: `NovelReaderWeb/docs/AI.md`

2. **Then NRW-001 (Karaoke Highlighting)** - Most visible feature
   - Reference: `NovelReaderWeb/docs/UI.md`, `NovelReaderWeb/docs/UIFlow.md`

3. **Finally NRW-002 (Director Pipeline)** - Performance improvement
   - Reference: `NovelReaderWeb/docs/ARCHITECTURE.md`, `NovelReaderWeb/docs/FEATURES.md`


