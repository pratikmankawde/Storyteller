# Qwen Model Integration Guide

## Current Status

The app uses the **qwen2.5-3b-instruct-q4_k_m.gguf** model. The model file is loaded from the device **Downloads** folder. The native llama.cpp library is integrated via JNI.

## What's Implemented

1. **QwenModel.kt** - Main model class that loads the GGUF file from device Downloads (e.g. `/storage/emulated/0/Download/qwen2.5-3b-instruct-q4_k_m.gguf`)
2. **QwenStub.kt** - Uses QwenModel when available, falls back to stub if model not loaded
3. **JNI Wrapper** - `llama_jni.cpp` loads the model and runs inference via llama.cpp
4. **Initialization** - Model loading is attempted at app startup in SplashActivity

## Model File

- **Filename:** `qwen2.5-3b-instruct-q4_k_m.gguf`
- **Location:** Device Downloads folder (e.g. `/storage/emulated/0/Download/` or `Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)`)
- The app checks multiple paths (sdcard/Download, storage/emulated/0/Download, context external files dir)

## Current Behavior

- The app attempts to load the Qwen model on startup (SplashActivity).
- If the model file is found and llama.cpp loads it, analysis uses the real model.
- If the model file is not found or native library fails, the app falls back to stub behavior.
- All analysis calls (`analyzeChapter`, `extendedAnalysisJson`, `extractCharactersAndTraitsInSegment`, `detectCharactersOnPage`, `inferTraitsForCharacter`, `suggestVoiceProfilesJson`, etc.) use the real model if loaded, otherwise stub.

## Chat Template

The app uses ChatML-style format compatible with Qwen2.5:

- `<|im_start|>system\n...<|im_end|>\n<|im_start|>user\n...<|im_end|>\n<|im_start|>assistant\n`

## Testing

1. Copy **qwen2.5-3b-instruct-q4_k_m.gguf** to the device's Downloads folder.
2. Launch the app and check logcat (tag `QwenModel` or `QwenStub`):
   - "Found model at: ... (size: ... bytes)" and "Qwen model loaded successfully" → model in use.
   - "Model file 'qwen2.5-3b-instruct-q4_k_m.gguf' not found" → check filename and location.
3. Use a feature that calls the LLM (e.g. Analyse chapters); it should use the real model if loading succeeded.

## Files

- `app/src/main/java/com/dramebaz/app/ai/llm/QwenModel.kt` - Model loading and inference
- `app/src/main/java/com/dramebaz/app/ai/llm/QwenStub.kt` - Entry point, fallback to stub
- `app/src/main/cpp/llama_jni.cpp` - JNI bridge to llama.cpp
