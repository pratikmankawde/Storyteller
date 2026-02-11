# TTS Implementation - Key Findings & Considerations

## Summary

The Storyteller app uses **Sherpa-ONNX with VITS-Piper en_US-libritts-high model** for high-quality, character-specific text-to-speech synthesis. The implementation is production-ready with sophisticated speaker matching, voice profiling, and audio caching.

## Key Strengths

1. **Multi-Speaker Support** - 904 speakers (LibriTTS) with gender, age, accent, pitch metadata
2. **Smart Speaker Matching** - Trait-based scoring algorithm matches characters to voices
3. **Voice Profiling** - LLM-extracted voice parameters (pitch, speed, energy, emotion)
4. **Audio Caching** - LRU cache with 100MB limit reduces synthesis latency
5. **Emotion Modifiers** - Dynamic prosody adjustment based on dialog emotion
6. **Graceful Degradation** - GPU/CPU fallback, OOM handling, DegradedModeManager
7. **Character Persistence** - Speaker IDs stored in database for consistency
8. **Segment-Level Control** - Per-character voices in multi-character narration

## Architecture Highlights

- **Modular Design** - Separate concerns: engine, speaker matching, voice profiles, caching
- **Coroutine-Based** - Async synthesis with proper thread management (Dispatchers.IO)
- **Error Resilience** - Comprehensive error handling with retry mechanisms
- **Performance Optimized** - Cache hits <100ms, typical synthesis 1-3 seconds
- **Memory Conscious** - LRU eviction, lazy initialization, resource cleanup

## Important Limitations

1. **No Runtime Pitch Control** - VITS-Piper cannot adjust pitch at runtime; use different speakers
2. **English Only** - Model trained on English (LibriTTS); no multilingual support
3. **Fixed Sample Rate** - 22050 Hz hardcoded; no configurable audio quality
4. **Single Model** - Only VITS-Piper en_US-libritts-high; no model switching
5. **Memory Intensive** - ~500MB-1GB model load; may fail on low-RAM devices
6. **Synthesis Latency** - 1-3 seconds per sentence; not suitable for real-time streaming

## Integration Points

**Initialization:**
- `DramebazApplication.kt` - Lazy singleton creation
- `SplashActivity.kt` - Initialization during app startup

**Usage:**
- `AudioDirector.kt` - Playback pipeline synthesis
- `PlaybackEngine.kt` - On-demand segment synthesis
- `SegmentAudioGenerator.kt` - Per-segment audio generation
- `ReaderFragment.kt` - Recap audio synthesis
- `VoiceSelectorDialog.kt` - Voice preview

**Data:**
- `Character.speakerId` - Selected speaker ID (0-903)
- `Character.voiceProfileJson` - Voice parameters
- `CharacterPageMapping` - Audio generation tracking

## Configuration & Customization

**Modifiable Parameters:**
- `maxCacheSizeBytes` - Cache size limit (currently 100MB)
- `sampleRate` - Audio sample rate (currently 22050 Hz)
- `defaultSpeakerId` - Fallback speaker (currently 0)
- `numThreads` - CPU threads for inference (auto-detected)

**Not Easily Modifiable:**
- Model file (requires asset replacement)
- Speaker catalog (hardcoded in LibrittsSpeakerCatalog.kt)
- Emotion modifiers (hardcoded in EmotionModifier.kt)

## Testing

**Instrumented Tests:**
- `TtsGenerationTest.kt` - Engine initialization and synthesis tests
- Handles OOM gracefully on low-memory devices
- Skips tests if initialization fails

**Manual Testing:**
- `TestActivity.kt` - Interactive TTS testing UI
- Allows custom text input and speaker selection
- Saves generated audio to Downloads folder

## Performance Metrics

**Initialization:**
- First run: 5-10 seconds (model copy + load)
- Subsequent: 2-3 seconds (load from storage)

**Synthesis:**
- Cache hit: <100ms
- Typical: 1-3 seconds per sentence
- Depends on text length and device specs

**Storage:**
- Model files: ~260MB in assets
- Cache: up to 100MB on device
- Per audio file: 50-500KB depending on length

## Known Issues & Workarounds

1. **OOM on Low-RAM Devices**
   - Workaround: Skip TTS init, use system TTS fallback
   - Handled in SplashActivity with try-catch

2. **GPU Unavailable**
   - Automatic fallback to CPU (no user action needed)
   - Logged in initialization

3. **Invalid Speaker IDs**
   - VoiceConsistencyChecker detects and suggests fallback
   - Default to speaker 0 if invalid

4. **Cache Corruption**
   - LRU eviction prevents unbounded growth
   - Manual `clearCache()` available for recovery

## Future Enhancement Opportunities

1. **Model Switching** - Support multiple TTS models (VCTK, Kokoro, etc.)
2. **Streaming Synthesis** - Real-time audio generation for live playback
3. **Pitch Control** - Implement pitch shifting post-processing
4. **Multilingual** - Add support for other languages
5. **Voice Cloning** - Fine-tune model for custom voices
6. **Prosody Control** - More granular emotion/intonation control
7. **Audio Effects** - Add reverb, EQ, compression
8. **Batch Synthesis** - Pre-generate all chapter audio offline

## Dependencies

```gradle
implementation("com.github.k2-fsa:sherpa-onnx:1.12.23")
```

**Transitive Dependencies:**
- ONNX Runtime (native library)
- Android Framework (Context, File I/O)
- Kotlin Coroutines
- Gson (for voice profile JSON)

## Conclusion

The TTS implementation is well-architected, production-ready, and provides excellent speaker variety with intelligent matching. The main constraints are English-only, fixed model, and memory requirements. The system gracefully handles failures and provides good performance through caching and optimization.

