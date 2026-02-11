# TTS Implementation Exploration Report

## 1. TTS Engine & Library

**Current Implementation:** Sherpa-ONNX with VITS-Piper model
- **Library:** `com.github.k2-fsa:sherpa-onnx:1.12.23` (Gradle dependency)
- **Model:** `vits-piper-en_US-libritts-high` (VITS-Piper model)
- **Speakers:** 904 speakers (IDs 0-903) from LibriTTS dataset
- **Sample Rate:** 22050 Hz
- **Phoneme Generation:** espeak-ng for phoneme synthesis

## 2. Core TTS Classes & Files

### Main Implementation
- **SherpaTtsEngine.kt** - Primary TTS engine (619 lines)
  - Initialization, model loading, audio synthesis
  - Audio caching with LRU eviction (AUG-034)
  - Energy scaling for volume control
  - GPU/CPU provider fallback

- **SherpaTtsStub.kt** - Deprecated stub (backward compatibility)

### Voice Selection & Matching
- **LibrittsSpeakerCatalog.kt** - 904 speaker metadata (gender, age, accent, pitch level)
- **VctkSpeakerCatalog.kt** - Alternative VCTK model speakers (109 speakers, IDs 0-108)
- **SpeakerMatcher.kt** - Matches character traits to speaker IDs using scoring algorithm
- **VoiceConsistencyChecker.kt** - Validates speaker IDs, detects invalid assignments

### Voice Profile & Parameters
- **VoiceProfile.kt** - Data model (pitch, speed, energy, emotion_bias)
- **VoiceProfileMapper.kt** - Maps VoiceProfile to TTS parameters with emotion modifiers

## 3. Initialization & Model Loading

**Location:** `DramebazApplication.kt`
```kotlin
val ttsEngine: SherpaTtsEngine by lazy { SherpaTtsEngine(this) }
```

**Initialization Flow:**
1. **SplashActivity** - Calls `app.ttsEngine.init()` during app startup
2. **Model Files** - Copied from assets to internal storage:
   - `models/tts/sherpa/en_US-libritts-high.onnx` (ONNX model)
   - `models/tts/sherpa/tokens.txt` (token mapping)
   - `models/tts/sherpa/espeak-ng-data/` (phoneme data)
3. **Provider Selection** - Tries GPU first, falls back to CPU
4. **Error Handling** - Reports failures to DegradedModeManager

## 4. TTS Usage Patterns

### Main Entry Point
```kotlin
suspend fun speak(
    text: String,
    voiceProfile: VoiceProfile?,
    onComplete: (() -> Unit)? = null,
    speakerId: Int? = null
): Result<File?>
```

### Call Sites
- **AudioDirector.kt** - Synthesizes segments in playback pipeline
- **PlaybackEngine.kt** - On-demand synthesis for segments
- **SegmentAudioGenerator.kt** - Generates per-segment audio with character voices
- **ReaderFragment.kt** - Recap audio synthesis
- **VoiceSelectorDialog.kt** - Voice preview playback
- **SpeakerSelectionFragment.kt** - Speaker preview testing
- **TestActivity.kt** - TTS testing/debugging

## 5. Audio Caching (AUG-034)

- **Cache Location:** `context.cacheDir/tts_audio_cache/`
- **Cache Key:** MD5 hash of (text + speakerId + speed + energy)
- **Max Size:** 100MB with LRU eviction
- **Eviction:** Removes oldest files when cache exceeds 80% of limit

## 6. Configuration & Settings

- **Sample Rate:** 22050 Hz (hardcoded)
- **Default Speaker ID:** 0 (LibriTTS)
- **Speed Range:** 0.5-2.0 (1.0 = normal)
- **Energy Range:** 0.5-1.5 (1.0 = normal)
- **Pitch Range:** 0.5-1.5 (1.0 = normal)
- **Note:** VITS-Piper has NO runtime pitch parameter; pitch variation uses different speakers

## 7. Abstraction Layers

- **VoiceProfileMapper** - Abstracts voice profile to TTS parameters
- **SpeakerMatcher** - Abstracts character trait matching
- **DegradedModeManager** - Handles TTS failure states
- **SegmentAudioGenerator** - Abstracts segment-level audio generation

## 8. Error Handling & Degraded Mode

- **DegradedModeManager.TtsMode** - Tracks TTS state (FULL, DISABLED, NOT_INITIALIZED)
- **Retry Mechanism** - `retryInit()` for user-triggered recovery
- **OutOfMemoryError** - Gracefully handled with fallback to stub
- **Provider Fallback** - GPU → CPU automatic fallback

## 9. Related Data Models

- **Character.kt** - Stores `speakerId` (0-903) and `voiceProfileJson`
- **VoiceProfile** - Extracted by LLM analysis (pitch, speed, energy, emotion_bias)
- **Dialog** - Contains emotion and intensity for prosody control
- **CharacterPageMapping** - Tracks audio generation status per segment

## 10. Testing

- **TtsGenerationTest.kt** - Android instrumented tests
  - Engine initialization test
  - Basic synthesis test
  - Handles OOM gracefully on low-memory devices

