# Qwen Model Integration Guide

## Current Status

The app uses the **Qwen3-1.7B-Q4_K_M.gguf** model for chapter analysis (e.g. "Analyse Chapters" workflow). The GGUF model is loaded from the device **Downloads** folder via llama.cpp JNI. If the model is not found (or on x86/emulator), the app uses stub behavior.

### Model Choice: Q4_K_M vs Q8_0

The app switched from **Q8_0** to **Q4_K_M** quantization based on benchmark results:

| Model  | 5-Pass Analysis Time (PC) | Valid JSON Output | Notes                                |
| ------ | ------------------------- | ----------------- | ------------------------------------ |
| Q4_K_M | ~87s (~17s/pass)          | ✓ Yes             | **Recommended** - 2x faster          |
| Q8_0   | ~163s (~32s/pass)         | ✓ Yes             | Hangs on Android (GPU/memory issues) |

**Why Q4_K_M:**
- ~2x faster inference than Q8_0
- Still produces valid JSON output for all analysis passes
- Q8_0 was observed to hang on Android devices, likely due to Vulkan GPU backend or memory constraints

## What's Implemented

1. **Qwen3Model.kt** - llama.cpp model class that loads **Qwen3-1.7B-Q4_K_M.gguf** from device **Downloads** folder. Uses llama.cpp JNI for inference.
2. **QwenStub.kt** - Tries **Qwen3Model** (llama.cpp) on ARM devices; otherwise uses stub. All analysis calls use the active model or stub.
3. **AAR** - llama.cpp AAR in `app/libs/` provides native JNI bindings.
4. **Initialization** - Model loading is attempted at app startup in SplashActivity.

## Model: Qwen3-1.7B-Q4_K_M.gguf (llama.cpp)

- **File name:** `Qwen3-1.7B-Q4_K_M.gguf`
- **Location:** Device **Downloads** folder (e.g. `/storage/emulated/0/Download/Qwen3-1.7B-Q4_K_M.gguf`)
- The app checks multiple paths for this file:
  - `/storage/emulated/0/Download/Qwen3-1.7B-Q4_K_M.gguf` (preferred)
  - `/sdcard/Download/Qwen3-1.7B-Q4_K_M.gguf`
  - Also searches for pattern `qwen*1.7b*(q8|q4_k_m)*.gguf` in Downloads folder
- **Note:** llama.cpp is only attempted on ARM (arm64-v8a, armeabi-v7a). On x86/x86_64 (emulator) the app uses stub only.

## Current Behavior

- On startup (SplashActivity), on ARM the app tries to load **llama.cpp GGUF** model.
- If model loads successfully, all analysis uses **Qwen3Model**.
- If model is not found, fails, or the device is x86/emulator, the app uses stub behavior.
- All analysis calls (`analyzeChapter`, `extendedAnalysisJson`, `extractCharactersAndTraitsInSegment`, etc.) use the active model or stub.
- **Timeout Protection:** All LLM calls have a 180-second timeout to prevent hangs. If a call times out, the app falls back to stub behavior.

## Chat Template

The app uses ChatML-style format compatible with Qwen3:

- `<|im_start|>system\n...<|im_end|>\n<|im_start|>user\n...<|im_end|>\n<|im_start|>assistant\n`
- The `/no_think` directive is added to system prompts to disable chain-of-thought reasoning for faster inference.

## Android Hang Investigation

The Q8_0 model was observed to complete successfully on PC but hang on Android. Potential root causes:

1. **Vulkan GPU Backend Issues:** Mobile GPU drivers may have compatibility issues with llama.cpp's Vulkan implementation
2. **Memory Constraints:** Q8_0 requires more memory than Q4_K_M; Android may hit OOM
3. **JNI Threading Issues:** Native code may not handle Android's threading model correctly
4. **Build Configuration Differences:** The Android AAR may be built with different optimizations than PC

**Current Workaround:** Use Q4_K_M model which is faster and more stable on Android.

## Testing

1. **GGUF:** On an ARM device, place the **Qwen3-1.7B-Q4_K_M.gguf** file in the device's **Downloads** folder. Launch the app and check logcat (tag `Qwen3Model` or `QwenStub`): "Found GGUF model: ..." and "Qwen3-1.7B-Q4_K_M GGUF loaded successfully via llama.cpp" → llama.cpp in use.
2. On emulator (x86_64) or without the file, the app uses stub; logcat will show "Skipping llama.cpp..." or "GGUF model not found or skipped, using stub".
3. Use "Analyse Chapters" or any feature that calls the LLM; it will use the real model if loading succeeded.

## Files

### Core Files
- `app/src/main/java/com/dramebaz/app/ai/llm/Qwen3Model.kt` - llama.cpp model loading and inference
- `app/src/main/java/com/dramebaz/app/ai/llm/QwenStub.kt` - Entry point, llama.cpp on ARM then stub
- `app/src/main/java/com/dramebaz/app/ai/llm/LlamaNative.kt` - JNI bindings for llama.cpp
- `app/src/main/java/com/dramebaz/app/ai/llm/LlmService.kt` - Facade pattern entry point for LLM operations
- `app/src/main/java/com/dramebaz/app/ai/llm/LlmTypes.kt` - Common type definitions (ChapterAnalysisResponse, CharacterStub, etc.)
- `app/libs/llama-*.aar` - llama.cpp Android AAR

### Model Abstractions (models/ subfolder)
- `app/src/main/java/com/dramebaz/app/ai/llm/models/LlmModel.kt` - Strategy interface for all LLM models
- `app/src/main/java/com/dramebaz/app/ai/llm/models/LlmModelFactory.kt` - Factory for creating model instances
- `app/src/main/java/com/dramebaz/app/ai/llm/models/Qwen3ModelImpl.kt` - Adapter wrapping Qwen3Model
- `app/src/main/java/com/dramebaz/app/ai/llm/models/LiteRtLmEngineImpl.kt` - Adapter wrapping LiteRtLmEngine

### Prompts (prompts/ subfolder)
- `app/src/main/java/com/dramebaz/app/ai/llm/prompts/ExtractionPrompts.kt` - Pass 1-3 character extraction prompts
- `app/src/main/java/com/dramebaz/app/ai/llm/prompts/AnalysisPrompts.kt` - Chapter/extended analysis prompts
- `app/src/main/java/com/dramebaz/app/ai/llm/prompts/StoryPrompts.kt` - Story generation, key moments, relationships prompts

### Design Patterns Applied
- **Strategy Pattern**: `LlmModel` interface allows different model implementations to be used interchangeably
- **Factory Pattern**: `LlmModelFactory` creates appropriate model instances based on device capabilities
- **Adapter Pattern**: `Qwen3ModelImpl` and `LiteRtLmEngineImpl` wrap existing implementations
- **Facade Pattern**: `LlmService` provides a simplified interface to the LLM subsystem
