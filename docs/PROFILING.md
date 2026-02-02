# Profiling slow code

The app logs **performance timings** to logcat so you can see which parts are slow.

## How to view

1. **Android Studio**: Run the app, open **Logcat**, filter by tag or text:
   - Filter by `⏱` to see all performance lines, or
   - Filter by tag: `OnnxQwenModel`, `ChapterCharExtract`, `BookDetailFragment`, `SherpaTtsEngine`, `PlaybackEngine`, `TextAudioSync`, `AudioMixer`, etc.

2. **adb** (Windows):
   ```bash
   adb logcat -s OnnxQwenModel:* ChapterCharExtract:* BookDetailFragment:* SherpaTtsEngine:* | findstr "took"
   ```

3. **Capture to file**: Run `scripts\capture_performance.bat`. Perform the slow action on device (e.g. Analyse Chapters, open chapter), then press Enter. Logs are written to `performance_YYYYMMDD_HHMMSS.txt`.

## What is instrumented

| Location | Log message (tag) | What it measures |
|---------|-------------------|------------------|
| **OnnxQwenModel** | `Load model (ONNX GenAI)` | Time to load the Qwen3-1.7B-Q4-ONNX model (Downloads/Qwen3-1.7B-Q4-ONNX) |
| **OnnxQwenModel** | `LLM generate (maxTokens=N)` | Each ONNX LLM call (prompt + inference) |
| **OnnxQwenModel** | `analyzeChapter (full)` / `extendedAnalysisJson` / etc. | Full chapter analysis, extended analysis, character/trait extraction, voice profiles, story generation |
| **ChapterCharacterExtractionUseCase** | `Phase 1: extract characters and traits (N segments, one call per segment)` | Time for all segment LLM calls (characters + traits) |
| **ChapterCharacterExtractionUseCase** | `Phase 3: suggestVoiceProfilesJson (N chars)` | Time for voice profile LLM call |
| **ChapterCharacterExtractionUseCase** | `extractAndSave total (...)` | Total extraction for one chapter |
| **BookDetailFragment** | `Analyse first chapter (end-to-end)` | Full "Analyse Chapters" flow (when used) |
| **SherpaTtsEngine** | `SherpaTTS ... initialization` | TTS engine init |
| **SherpaTtsEngine** | `TTS synthesis (vits-piper)` | Single TTS synthesis |
| **PlaybackEngine** | `Pre-synthesis segment #N` / `On-demand TTS synthesis segment #N` | Per-segment TTS |
| **PlaybackEngine** | `Pre-synthesis all segments (parallel)` | Total pre-synthesis |
| **TextAudioSync** | `Build text segments` | Building segments from chapter text |
| **AudioMixer** | `Parallel audio file loading` / `Audio mixing` | Mixing audio for playback |
| **ReaderFragment** | `Chapter load and setup (parallel)` | Chapter load + lazy analysis + UI setup |
| **ImportBookUseCase** | `Import book '...'` | PDF/text import |

## Typical slow spots

- **LLM generate** – Usually the biggest cost; each `⏱ LLM generate` line is one inference.
- **extractCharactersAndTraitsInSegment** – One call per segment; total time grows with chapter length (number of segments). Each call returns both character names and traits.
- **analyzeChapter (full)** / **extendedAnalysisJson** – One long inference per chapter; cost grows with chapter length (prompt size) and max tokens.
- **Load model** – One-time at app start; can be several seconds.
- **TTS synthesis** – Per utterance; relevant when playing or pre-generating audio.

Use these logs to see which operation dominates and then optimize (e.g. fewer segments, larger segment size, smaller prompts, or lighter model).

## Current design (character extraction)

Character extraction uses **one LLM call per segment**: `extractCharactersAndTraitsInSegment`. Each call returns both character names and traits for that segment. Already-extracted characters (with traits) are skipped in later segments; characters without traits can get traits filled in later segments. This replaces the older flow of separate "detect characters" then "infer traits" phases.
