# TTS Implementation - Visual Summary

## System Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                    STORYTELLER APP                              │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  UI Layer                                                │  │
│  │  ReaderFragment, VoiceSelectorDialog, TestActivity      │  │
│  └──────────────────────┬───────────────────────────────────┘  │
│                         │                                       │
│  ┌──────────────────────▼───────────────────────────────────┐  │
│  │  Audio Generation                                        │  │
│  │  AudioDirector, PlaybackEngine, SegmentAudioGenerator   │  │
│  └──────────────────────┬───────────────────────────────────┘  │
│                         │                                       │
│  ┌──────────────────────▼───────────────────────────────────┐  │
│  │  Voice Processing                                        │  │
│  │  SpeakerMatcher, VoiceProfileMapper, VoiceConsistency   │  │
│  └──────────────────────┬───────────────────────────────────┘  │
│                         │                                       │
│  ┌──────────────────────▼───────────────────────────────────┐  │
│  │  SherpaTtsEngine (Core)                                  │  │
│  │  ✓ Model loading & initialization                       │  │
│  │  ✓ Audio synthesis (Sherpa-ONNX SDK)                    │  │
│  │  ✓ Audio caching (LRU, 100MB)                           │  │
│  │  ✓ Energy scaling & WAV generation                      │  │
│  │  ✓ GPU/CPU fallback                                     │  │
│  └──────────────────────┬───────────────────────────────────┘  │
│                         │                                       │
│  ┌──────────────────────▼───────────────────────────────────┐  │
│  │  Sherpa-ONNX SDK (Native)                               │  │
│  │  VITS-Piper en_US-libritts-high (904 speakers)          │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  Data Layer                                              │  │
│  │  Character (speakerId, voiceProfileJson)                │  │
│  │  CharacterPageMapping (audio tracking)                  │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

## Text-to-Audio Pipeline

```
Input Text
    │
    ▼
┌─────────────────────────────────────────┐
│ VoiceProfileMapper                      │
│ Convert VoiceProfile → TtsParams        │
│ Extract: speakerId, speed, energy       │
└─────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────┐
│ Cache Lookup                            │
│ Key: MD5(text|speakerId|speed|energy)   │
└─────────────────────────────────────────┘
    │
    ├─ HIT ──────────────────────────────┐
    │                                     │
    │                                     ▼
    │                            Return Cached File
    │                                     │
    │                                     ▼
    │                            ┌──────────────────┐
    │                            │ Audio Playback   │
    │                            │ (AudioTrack/MP)  │
    │                            └──────────────────┘
    │
    └─ MISS ──────────────────────────────┐
                                          │
                                          ▼
                            ┌──────────────────────────────┐
                            │ Sherpa-ONNX Synthesis        │
                            │ generate(text, sid, speed)   │
                            │ Returns: FloatArray samples  │
                            └──────────────────────────────┘
                                          │
                                          ▼
                            ┌──────────────────────────────┐
                            │ Energy Scaling               │
                            │ Scale samples by energy      │
                            │ Clamp to [-1.0, 1.0]        │
                            └──────────────────────────────┘
                                          │
                                          ▼
                            ┌──────────────────────────────┐
                            │ WAV Encoding                 │
                            │ 16-bit PCM, 22050 Hz, mono   │
                            │ Write WAV header + samples   │
                            └──────────────────────────────┘
                                          │
                                          ▼
                            ┌──────────────────────────────┐
                            │ Cache Storage                │
                            │ LRU eviction if >100MB       │
                            └──────────────────────────────┘
                                          │
                                          ▼
                            ┌──────────────────────────────┐
                            │ Return Audio File            │
                            │ (WAV format)                 │
                            └──────────────────────────────┘
                                          │
                                          ▼
                            ┌──────────────────────────────┐
                            │ Audio Playback               │
                            │ (AudioTrack/MediaPlayer)     │
                            └──────────────────────────────┘
```

## Speaker Matching Flow

```
Character Traits
(gender, age, accent, personality)
    │
    ▼
┌──────────────────────────────────────┐
│ SpeakerMatcher.suggestSpeakerId()    │
│ Parse traits → token list            │
│ Score each speaker (0-903)           │
│ Gender match: +10 / -8               │
│ Age match: +5                        │
│ Accent match: +3                     │
│ Deterministic tie-breaking           │
└──────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────┐
│ LibrittsSpeakerCatalog               │
│ 904 speakers with metadata:          │
│ - Gender (M/F)                       │
│ - Age (years)                        │
│ - Accent (American)                  │
│ - Region                             │
│ - Pitch Level (HIGH/MEDIUM/LOW)      │
└──────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────┐
│ Top Matching Speaker ID              │
│ (0-903)                              │
└──────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────┐
│ VoiceConsistencyChecker              │
│ Validate speaker ID in range         │
│ Suggest fallback if invalid          │
└──────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────┐
│ Character.speakerId (Persisted)      │
│ Stored in database for consistency   │
└──────────────────────────────────────┘
```

## Key Statistics

```
┌─────────────────────────────────────────────────────────────┐
│ TTS Engine Specifications                                   │
├─────────────────────────────────────────────────────────────┤
│ Library:              Sherpa-ONNX 1.12.23                   │
│ Model:                VITS-Piper en_US-libritts-high        │
│ Speakers:             904 (IDs 0-903)                       │
│ Sample Rate:          22050 Hz                              │
│ Audio Format:         16-bit PCM, mono, WAV                 │
│ Phoneme Engine:       espeak-ng                             │
│ Execution:            GPU (Vulkan) or CPU                   │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ Performance Metrics                                         │
├─────────────────────────────────────────────────────────────┤
│ Initialization (first):    5-10 seconds                     │
│ Initialization (cached):   2-3 seconds                      │
│ Synthesis (typical):       1-3 seconds per sentence         │
│ Cache hit:                 <100ms                           │
│ Memory (model):            ~500MB-1GB                       │
│ Cache Size:                100MB (LRU eviction)             │
│ Model Files:               ~260MB in assets                 │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ Parameter Ranges                                            │
├─────────────────────────────────────────────────────────────┤
│ Speed:                 0.5 - 2.0 (1.0 = normal)             │
│ Energy:                0.5 - 1.5 (1.0 = normal)             │
│ Pitch:                 0.5 - 1.5 (1.0 = normal)             │
│ Speaker ID:            0 - 903 (LibriTTS)                   │
│ Sample Rate:           22050 Hz (fixed)                     │
└─────────────────────────────────────────────────────────────┘
```

## File Organization

```
TTS Implementation Files:
├── Core Engine
│   └── SherpaTtsEngine.kt (619 lines)
├── Speaker Management
│   ├── LibrittsSpeakerCatalog.kt
│   ├── SpeakerMatcher.kt
│   └── VoiceConsistencyChecker.kt
├── Voice Processing
│   ├── VoiceProfile.kt
│   └── VoiceProfileMapper.kt
├── Audio Generation
│   ├── SegmentAudioGenerator.kt
│   ├── AudioDirector.kt
│   └── PlaybackEngine.kt
├── UI Components
│   ├── VoiceSelectorDialog.kt
│   ├── SpeakerSelectionFragment.kt
│   └── TestActivity.kt
└── Data Models
    ├── Character.kt
    └── CharacterPageMapping.kt

Model Assets:
├── en_US-libritts-high.onnx (~200MB)
├── tokens.txt (~50KB)
└── espeak-ng-data/ (~10MB)
```

## Integration Points

```
DramebazApplication
    └── ttsEngine: SherpaTtsEngine (singleton)
        ├── Used by: AudioDirector
        ├── Used by: PlaybackEngine
        ├── Used by: SegmentAudioGenerator
        ├── Used by: ReaderFragment
        ├── Used by: VoiceSelectorDialog
        ├── Used by: SpeakerSelectionFragment
        └── Used by: TestActivity

Character Database
    ├── speakerId (0-903)
    ├── voiceProfileJson
    └── Used by: SpeakerMatcher, VoiceConsistencyChecker

LLM Analysis Output
    ├── Character traits
    ├── Voice profile (pitch, speed, energy, emotion)
    └── Dialog emotions
        └── Used by: EmotionModifier for prosody
```

## Strengths & Limitations

```
✓ STRENGTHS                          ✗ LIMITATIONS
├─ 904 speaker voices                ├─ English only
├─ Smart trait matching              ├─ No runtime pitch control
├─ Voice profiling                   ├─ Fixed sample rate (22050)
├─ Audio caching (LRU)               ├─ Single model (VITS-Piper)
├─ Emotion modifiers                 ├─ Memory intensive (~500MB)
├─ GPU/CPU fallback                  ├─ Synthesis latency (1-3s)
├─ Character persistence             ├─ No real-time streaming
├─ Segment-level control             └─ No voice cloning
├─ Graceful degradation
└─ Production-ready
```

---

**Generated:** 2026-02-06 | **Engine:** Sherpa-ONNX 1.12.23 | **Model:** VITS-Piper en_US-libritts-high

