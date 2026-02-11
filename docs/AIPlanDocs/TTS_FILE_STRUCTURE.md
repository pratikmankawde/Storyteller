# TTS Implementation - File Structure & Dependencies

## Source Code Structure

```
app/src/main/java/com/dramebaz/app/
├── ai/tts/
│   ├── SherpaTtsEngine.kt              (619 lines) - Core TTS engine
│   ├── SherpaTtsStub.kt                (39 lines)  - Deprecated stub
│   ├── LibrittsSpeakerCatalog.kt       (904 speakers metadata)
│   ├── VctkSpeakerCatalog.kt           (109 speakers metadata)
│   ├── SpeakerMatcher.kt               (Trait-based speaker matching)
│   ├── VoiceConsistencyChecker.kt      (Voice validation)
│   ├── VoiceProfileMapper.kt           (Profile → TTS params)
│   └── [Related classes]
│
├── audio/
│   ├── SegmentAudioGenerator.kt        (Per-segment audio generation)
│   ├── PageAudioStorage.kt             (Audio file persistence)
│   └── [Audio utilities]
│
├── playback/engine/
│   ├── AudioDirector.kt                (Playback pipeline)
│   ├── PlaybackEngine.kt               (On-demand synthesis)
│   └── [Playback utilities]
│
├── ui/
│   ├── reader/
│   │   ├── ReaderFragment.kt           (Recap audio synthesis)
│   │   ├── VoiceSelectorDialog.kt      (Voice selection UI)
│   │   └── [Reader UI]
│   ├── characters/
│   │   ├── SpeakerSelectionFragment.kt (Speaker preview)
│   │   └── [Character UI]
│   ├── test/
│   │   └── TestActivity.kt             (TTS testing UI)
│   └── [Other UI]
│
├── data/
│   ├── models/
│   │   ├── VoiceProfile.kt             (Voice parameters)
│   │   └── [Other models]
│   ├── db/
│   │   ├── Character.kt                (Character entity with speakerId)
│   │   ├── CharacterPageMapping.kt     (Audio tracking)
│   │   └── [Database entities]
│   └── [Data layer]
│
├── DramebazApplication.kt              (TTS engine singleton)
└── [Other app code]

app/src/main/assets/models/tts/sherpa/
├── en_US-libritts-high.onnx            (~200MB) - VITS-Piper model
├── tokens.txt                          (~50KB)  - Token vocabulary
└── espeak-ng-data/                     (~10MB)  - Phoneme data
    ├── en_dict
    ├── lang/
    ├── voices/
    └── [Phoneme resources]

app/src/androidTest/java/com/dramebaz/app/ai/tts/
└── TtsGenerationTest.kt                (Instrumented tests)

build.gradle.kts
└── implementation("com.github.k2-fsa:sherpa-onnx:1.12.23")
```

## Class Dependency Graph

```
┌─────────────────────────────────────────────────────────────┐
│                    UI Components                             │
│  ReaderFragment, VoiceSelectorDialog, SpeakerSelectionUI    │
└────────────────────┬────────────────────────────────────────┘
                     │ uses
                     ▼
┌─────────────────────────────────────────────────────────────┐
│              Audio Generation Services                       │
│  AudioDirector, PlaybackEngine, SegmentAudioGenerator       │
└────────────────────┬────────────────────────────────────────┘
                     │ uses
                     ▼
┌─────────────────────────────────────────────────────────────┐
│           Voice Profile & Speaker Matching                   │
│  VoiceProfileMapper, SpeakerMatcher, VoiceConsistencyChecker│
└────────────────────┬────────────────────────────────────────┘
                     │ uses
                     ▼
┌─────────────────────────────────────────────────────────────┐
│              SherpaTtsEngine                                 │
│  - Initialization & model loading                           │
│  - Audio synthesis                                          │
│  - Caching & resource management                            │
└────────────────────┬────────────────────────────────────────┘
                     │ uses
                     ▼
┌─────────────────────────────────────────────────────────────┐
│         Sherpa-ONNX SDK (Native Library)                    │
│  - OfflineTts, OfflineTtsConfig, OfflineTtsVitsModelConfig │
│  - VITS-Piper model inference                              │
└─────────────────────────────────────────────────────────────┘
```

## Data Flow Dependencies

```
Character (DB)
├── speakerId (0-903)
├── voiceProfileJson
└── traits, personalitySummary

    ↓ used by

SpeakerMatcher
├── suggestSpeakerId(traits, summary, name)
└── getSimilarSpeakers(traits, summary, name, topN)

    ↓ returns

LibrittsSpeakerCatalog.SpeakerTraits
├── gender, age, accent, region, pitchLevel
└── used for UI display & validation

    ↓ used by

VoiceConsistencyChecker
├── checkAllCharacters()
└── isValidSpeakerId(speakerId)

    ↓ used by

SherpaTtsEngine.speak()
├── speakerId (0-903)
├── voiceProfile (pitch, speed, energy, emotion)
└── returns Result<File?>

    ↓ returns

Audio File (WAV)
├── 16-bit PCM, 22050 Hz, mono
├── Cached with LRU eviction
└── Played by AudioTrack or MediaPlayer
```

## External Dependencies

```
Gradle Dependencies:
├── com.github.k2-fsa:sherpa-onnx:1.12.23
│   ├── ONNX Runtime (native)
│   └── Sherpa-ONNX SDK
├── androidx.coroutines:kotlinx-coroutines-android:1.7.3
├── com.google.code.gson:gson:2.10.1
├── androidx.room:room-runtime:2.7.1
└── [Other Android framework dependencies]

Native Libraries:
├── libsherpa-onnx.so (ARM64)
├── libonnxruntime.so (ARM64)
└── [ONNX Runtime providers]

Asset Files:
├── models/tts/sherpa/en_US-libritts-high.onnx
├── models/tts/sherpa/tokens.txt
└── models/tts/sherpa/espeak-ng-data/
```

## Key Classes & Their Responsibilities

| Class | Lines | Responsibility |
|-------|-------|-----------------|
| SherpaTtsEngine | 619 | Core TTS synthesis, model loading, caching |
| LibrittsSpeakerCatalog | ~2000 | 904 speaker metadata & filtering |
| SpeakerMatcher | ~200 | Trait-based speaker matching algorithm |
| VoiceProfileMapper | ~60 | Voice profile to TTS parameters conversion |
| VoiceConsistencyChecker | ~150 | Speaker ID validation & consistency checking |
| SegmentAudioGenerator | ~400 | Per-segment audio generation with character voices |
| AudioDirector | ~500 | Playback pipeline orchestration |
| PlaybackEngine | ~400 | On-demand synthesis & playback |

## Configuration Files

```
app/src/main/assets/
├── models/
│   ├── tts/sherpa/
│   │   ├── en_US-libritts-high.onnx
│   │   ├── tokens.txt
│   │   └── espeak-ng-data/
│   └── llm_model_config.json
└── [Other assets]

app/build.gradle.kts
├── TTS dependency: sherpa-onnx:1.12.23
├── Kotlin version: 2.2.21
└── [Other build config]

AndroidManifest.xml
├── Permissions: READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE
└── [Other manifest entries]
```

## Runtime Storage Locations

```
Internal Storage (context.filesDir):
└── models/tts/sherpa/
    ├── en_US-libritts-high.onnx (copied from assets)
    ├── tokens.txt (copied from assets)
    └── espeak-ng-data/ (copied from assets)

Cache Directory (context.cacheDir):
├── tts_audio_cache/
│   └── [MD5_hash].wav (LRU cached audio files)
└── tts_audio/
    └── [temp_hash].wav (temporary synthesis files)
```

## Testing Structure

```
app/src/androidTest/java/com/dramebaz/app/ai/tts/
└── TtsGenerationTest.kt
    ├── testTtsEngineInitialization()
    ├── testTtsSynthesisBasic()
    └── [Other tests]

app/src/main/java/com/dramebaz/app/ui/test/
└── TestActivity.kt
    ├── Interactive TTS testing UI
    ├── Custom text input
    ├── Speaker selection
    └── Audio playback & saving
```

