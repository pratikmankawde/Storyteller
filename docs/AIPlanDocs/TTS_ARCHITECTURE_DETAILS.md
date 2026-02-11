# TTS Architecture & Technical Details

## System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    UI Layer                                  │
│  ReaderFragment, VoiceSelectorDialog, SpeakerSelectionUI    │
└────────────────────┬────────────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────────────┐
│              Playback & Audio Generation                     │
│  AudioDirector, PlaybackEngine, SegmentAudioGenerator       │
└────────────────────┬────────────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────────────┐
│           Voice Profile & Speaker Matching                   │
│  VoiceProfileMapper, SpeakerMatcher, VoiceConsistencyChecker│
└────────────────────┬────────────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────────────┐
│              SherpaTtsEngine (Core)                          │
│  - Model initialization & loading                           │
│  - Audio synthesis via Sherpa-ONNX SDK                      │
│  - Audio caching with LRU eviction                          │
│  - Energy scaling & WAV file generation                     │
└────────────────────┬────────────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────────────┐
│         Sherpa-ONNX SDK (Native Library)                    │
│  - VITS-Piper model inference                              │
│  - espeak-ng phoneme generation                            │
│  - GPU/CPU execution                                        │
└─────────────────────────────────────────────────────────────┘
```

## Data Flow: Text to Audio

```
1. UI/Service calls: ttsEngine.speak(text, voiceProfile, speakerId)
2. VoiceProfileMapper converts VoiceProfile → TtsParams
3. Extract: speakerId (0-903), speed (0.5-2.0), energy (0.5-1.5)
4. Check cache: MD5(text|speakerId|speed|energy)
5. If cached: return cached File
6. If not cached:
   a. Call OfflineTts.generate(text, sid, speed)
   b. Receive FloatArray samples at 22050 Hz
   c. Apply energy scaling if energy ≠ 1.0
   d. Convert float samples → 16-bit PCM WAV
   e. Save to temp file
   f. Cache with LRU eviction
   g. Return cached File
7. Playback: AudioTrack or MediaPlayer plays WAV file
```

## Model Files & Assets

**Location:** `app/src/main/assets/models/tts/sherpa/`

```
en_US-libritts-high.onnx    (~200MB) - VITS-Piper model
tokens.txt                   (~50KB)  - Token vocabulary
espeak-ng-data/              (~10MB)  - Phoneme data
  ├── en_dict
  ├── lang/
  ├── voices/
  └── ...
```

**Runtime Location:** `context.filesDir/models/tts/sherpa/`

## Speaker Matching Algorithm

**SpeakerMatcher.suggestSpeakerId()** scoring:
- Gender match: +10 (female/male keywords)
- Gender mismatch: -8
- Age bucket match: +5 (toddler, child, teen, adult, senior)
- Accent/region match: +3
- Deterministic tie-breaking: character name hash

**getSimilarSpeakers()** returns top N speakers by score for UI display.

## Voice Profile Parameters

**From LLM Analysis:**
```json
{
  "pitch": 1.0,           // 0.5-1.5 (1.0 = normal)
  "speed": 1.0,           // 0.5-1.5 (1.0 = normal)
  "energy": 1.0,          // 0.5-1.5 (1.0 = normal)
  "emotion_bias": {
    "neutral": 0.5,
    "happy": 0.2,
    "sad": 0.1
  }
}
```

**Emotion Modifiers (EmotionModifier.kt):**
- Happy: speed +0.2, energy +0.2
- Sad: speed -0.2, energy -0.2
- Angry: speed +0.1, energy +0.3
- Etc.

## Cache Management

**LRU Eviction Strategy:**
1. Monitor cache size in `tts_audio_cache/`
2. When exceeding 100MB limit:
   - Sort files by last modified time
   - Delete oldest files until 80% of limit
   - Log eviction count and freed space

**Cache Key Format:**
```
MD5(text|speakerId|speed|energy).wav
```

## Error Handling & Degradation

**DegradedModeManager States:**
- `FULL` - TTS operational
- `DISABLED` - TTS failed, fallback to system TTS or text-only
- `NOT_INITIALIZED` - TTS not yet loaded

**Failure Scenarios:**
1. Model files missing → init() returns false
2. GPU unavailable → fallback to CPU
3. CPU also fails → set DISABLED mode
4. OutOfMemoryError → caught, logged, continue with fallback
5. Invalid speaker ID → use default (0)

## Performance Characteristics

**Initialization:**
- First run: ~5-10 seconds (copy model files + load)
- Subsequent runs: ~2-3 seconds (load from internal storage)

**Synthesis:**
- Typical: 1-3 seconds per sentence
- Cached: <100ms
- Depends on text length and device CPU/GPU

**Memory:**
- Model loaded: ~500MB-1GB
- Per synthesis: ~100-200MB temporary
- Cache: up to 100MB on disk

## Integration Points

**DramebazApplication.kt:**
```kotlin
val ttsEngine: SherpaTtsEngine by lazy { SherpaTtsEngine(this) }
val segmentAudioGenerator: SegmentAudioGenerator by lazy { ... }
val pageAudioStorage: PageAudioStorage by lazy { ... }
```

**SplashActivity.kt:**
```kotlin
app.ttsEngine.init()  // Called during app startup
```

**Character Database:**
- `Character.speakerId` - Selected speaker (0-903)
- `Character.voiceProfileJson` - Voice parameters
- `CharacterPageMapping` - Audio generation tracking

