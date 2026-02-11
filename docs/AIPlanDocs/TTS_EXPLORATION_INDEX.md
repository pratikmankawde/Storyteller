# TTS Implementation Exploration - Complete Index

## Overview

This exploration documents the complete Text-to-Speech (TTS) implementation in the Storyteller app, including architecture, APIs, file structure, and technical details.

## Documents Generated

### 1. **TTS_IMPLEMENTATION_EXPLORATION.md** (Main Summary)
   - TTS engine & library details
   - Core classes and files
   - Initialization & model loading
   - Usage patterns across the app
   - Audio caching system
   - Configuration & settings
   - Abstraction layers
   - Error handling & degraded mode
   - Related data models
   - Testing approach

### 2. **TTS_ARCHITECTURE_DETAILS.md** (Technical Deep Dive)
   - System architecture diagram
   - Data flow: Text to Audio
   - Model files & assets structure
   - Speaker matching algorithm
   - Voice profile parameters
   - Cache management strategy
   - Error handling & degradation
   - Performance characteristics
   - Integration points

### 3. **TTS_API_REFERENCE.md** (Developer Guide)
   - SherpaTtsEngine API methods
   - Usage examples (basic, with profiles, callbacks)
   - Speaker selection APIs
   - Voice profile APIs
   - Data models & structures
   - Constants & ranges
   - Error handling patterns

### 4. **TTS_KEY_FINDINGS.md** (Analysis & Insights)
   - Implementation summary
   - Key strengths
   - Architecture highlights
   - Important limitations
   - Integration points
   - Configuration & customization
   - Testing approach
   - Performance metrics
   - Known issues & workarounds
   - Future enhancement opportunities
   - Dependencies

### 5. **TTS_FILE_STRUCTURE.md** (Code Organization)
   - Source code directory structure
   - Class dependency graph
   - Data flow dependencies
   - External dependencies
   - Key classes & responsibilities
   - Configuration files
   - Runtime storage locations
   - Testing structure

## Quick Reference

### TTS Engine
- **Library:** Sherpa-ONNX 1.12.23
- **Model:** VITS-Piper en_US-libritts-high
- **Speakers:** 904 (LibriTTS, IDs 0-903)
- **Sample Rate:** 22050 Hz
- **Main Class:** `SherpaTtsEngine.kt` (619 lines)

### Core API
```kotlin
suspend fun speak(
    text: String,
    voiceProfile: VoiceProfile?,
    onComplete: (() -> Unit)? = null,
    speakerId: Int? = null
): Result<File?>
```

### Key Classes
| Class | Purpose |
|-------|---------|
| SherpaTtsEngine | Core TTS synthesis & caching |
| LibrittsSpeakerCatalog | 904 speaker metadata |
| SpeakerMatcher | Trait-based speaker matching |
| VoiceProfileMapper | Profile to TTS parameters |
| VoiceConsistencyChecker | Speaker ID validation |
| SegmentAudioGenerator | Per-segment audio generation |
| AudioDirector | Playback pipeline |

### Usage Locations
- **AudioDirector.kt** - Playback pipeline synthesis
- **PlaybackEngine.kt** - On-demand synthesis
- **SegmentAudioGenerator.kt** - Per-segment generation
- **ReaderFragment.kt** - Recap audio
- **VoiceSelectorDialog.kt** - Voice preview
- **SpeakerSelectionFragment.kt** - Speaker preview
- **TestActivity.kt** - Testing UI

### Model Files (Assets)
- `models/tts/sherpa/en_US-libritts-high.onnx` (~200MB)
- `models/tts/sherpa/tokens.txt` (~50KB)
- `models/tts/sherpa/espeak-ng-data/` (~10MB)

### Configuration
- **Cache Size:** 100MB with LRU eviction
- **Speed Range:** 0.5-2.0 (1.0 = normal)
- **Energy Range:** 0.5-1.5 (1.0 = normal)
- **Pitch Range:** 0.5-1.5 (1.0 = normal)
- **Default Speaker:** 0 (LibriTTS)

### Performance
- **Init (first run):** 5-10 seconds
- **Init (subsequent):** 2-3 seconds
- **Synthesis (typical):** 1-3 seconds per sentence
- **Cache hit:** <100ms
- **Memory:** ~500MB-1GB model load

### Key Features
✓ Multi-speaker support (904 voices)
✓ Smart speaker matching (trait-based)
✓ Voice profiling (pitch, speed, energy, emotion)
✓ Audio caching (LRU eviction)
✓ Emotion modifiers (dynamic prosody)
✓ Graceful degradation (GPU/CPU fallback)
✓ Character persistence (speaker IDs in DB)
✓ Segment-level control (per-character voices)

### Limitations
✗ No runtime pitch control (use different speakers)
✗ English only (LibriTTS model)
✗ Fixed sample rate (22050 Hz)
✗ Single model (VITS-Piper only)
✗ Memory intensive (~500MB-1GB)
✗ Synthesis latency (1-3 seconds)

## How to Use These Documents

1. **Start Here:** Read `TTS_IMPLEMENTATION_EXPLORATION.md` for overview
2. **Understand Architecture:** Review `TTS_ARCHITECTURE_DETAILS.md`
3. **Implement Features:** Use `TTS_API_REFERENCE.md` for code examples
4. **Plan Changes:** Check `TTS_KEY_FINDINGS.md` for constraints
5. **Navigate Code:** Use `TTS_FILE_STRUCTURE.md` to find files

## Key Findings Summary

The TTS implementation is **production-ready** with:
- Well-architected modular design
- Sophisticated speaker matching algorithm
- Comprehensive error handling
- Performance optimization through caching
- Graceful degradation on failures
- Character-specific voice persistence

Main constraints are English-only, fixed model, and memory requirements. The system is suitable for offline audiobook narration but not real-time streaming.

## Related Code Locations

**Initialization:**
- `DramebazApplication.kt` - Singleton creation
- `SplashActivity.kt` - Startup initialization

**Data Models:**
- `Character.kt` - speakerId, voiceProfileJson
- `VoiceProfile.kt` - pitch, speed, energy, emotion_bias
- `Dialog.kt` - emotion, intensity for prosody

**Database:**
- `CharacterDao` - Character queries
- `CharacterPageMapping` - Audio tracking
- `CharacterPageMappingDao` - Mapping queries

**Utilities:**
- `AppLogger.kt` - Logging
- `DegradedModeManager.kt` - Failure handling
- `EmotionModifier.kt` - Emotion-based adjustments

## Next Steps for Development

1. **Understand Current Implementation** - Read all documents
2. **Review Code** - Study SherpaTtsEngine.kt and related classes
3. **Test Locally** - Use TestActivity.kt for manual testing
4. **Plan Changes** - Consider limitations and constraints
5. **Implement** - Use API reference for correct usage
6. **Test** - Run TtsGenerationTest.kt for validation

## Questions to Consider

- What TTS features need enhancement?
- Should we support multiple models?
- Can we implement real-time streaming?
- How to add multilingual support?
- Should we implement voice cloning?
- Can we improve speaker matching?
- How to optimize memory usage?
- Should we add more emotion control?

---

**Exploration Date:** 2026-02-06
**Codebase:** Storyteller (Android)
**TTS Engine:** Sherpa-ONNX 1.12.23
**Model:** VITS-Piper en_US-libritts-high

