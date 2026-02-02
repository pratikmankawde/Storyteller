# Task list: Replace GGUF with Qwen3-1.7B-Q4-ONNX

**Goal:** Use [Qwen3-1.7B-Q4-ONNX](https://huggingface.co/Edison2ST/Qwen3-1.7B-Q4-ONNX) for the "Analyse Chapters" workflow. Model is on device in **Downloads/Qwen3-1.7B-Q4-ONNX**.

**Model folder contents:** `genai_config.json`, `model.onnx`, `model.weights.bin`, `tokenizer.json`, `tokenizer_config.json`, `README.md`, `LICENSE`, `NOTICE`.

**Reference:** [Edison2ST/Qwen3-1.7B-Q4-ONNX](https://huggingface.co/Edison2ST/Qwen3-1.7B-Q4-ONNX) – 4-bit ONNX for mobile, ONNX Runtime (GenAI API), ~10.89 tokens/sec on Dimensity 6300, ~1.31 GB RAM.

---

## Task list

| # | Task | Status | File(s) |
|---|------|--------|--------|
| 1 | Add onnxruntime-genai Android AAR dependency | Done | app/build.gradle.kts, app/libs/ |
| 2 | Create OnnxQwenModel: load from folder, tokenize, generate, detokenize | Done | OnnxQwenModel.kt |
| 3 | Wire QwenStub: try ONNX on ARM, then stub (no GGUF) | Done | QwenStub.kt |
| 4 | Update SplashActivity to load ONNX model from Downloads/Qwen3-1.7B-Q4-ONNX | Done | SplashActivity.kt |
| 5 | Update comments, logs, and docs (PROFILING, QWEN_MODEL_INTEGRATION, usertask) | Done | Multiple |
| 6 | Build, test, install | Done | — |

---

## 1. Dependency

- **onnxruntime-genai** Android AAR: [v0.11.4](https://github.com/microsoft/onnxruntime-genai/releases/tag/v0.11.4) – [onnxruntime-genai-android-0.11.4.aar](https://github.com/microsoft/onnxruntime-genai/releases/download/v0.11.4/onnxruntime-genai-android-0.11.4.aar)
- Place the AAR in `app/libs/` and add `implementation(files("libs/onnxruntime-genai-android-0.11.4.aar"))` (or equivalent) in `app/build.gradle.kts`.

## 2. Model path on device

- **Folder:** `Downloads/Qwen3-1.7B-Q4-ONNX`
- Resolved paths (same as current GGUF): `/sdcard/Download/Qwen3-1.7B-Q4-ONNX`, `/storage/emulated/0/Download/Qwen3-1.7B-Q4-ONNX`, or `Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)/Qwen3-1.7B-Q4-ONNX`.
- The ONNX Runtime GenAI API loads a **directory** containing `model.onnx`, `tokenizer.json`, etc.; pass this folder path as the model path.

## 3. API usage (ai.onnxruntime.genai)

- `Model(modelPath)` – path = folder path
- `model.createTokenizer()`
- `model.createGeneratorParams()` then `params.setInput(tokenizer.encode(prompt))`, `params.setSearchOption("max_length", maxTokens)`, `params.setSearchOption("temperature", temperature)`
- `model.generate(params)` → `Sequences`
- `tokenizer.decode(sequences.getSequence(0))` → output text

## 4. Fallback order

1. On ARM only: try load ONNX from `Downloads/Qwen3-1.7B-Q4-ONNX`.
2. If that fails or device is x86/emulator, use in-memory stub (no model file). No GGUF/llama.cpp.

---

## 5. Files to touch

| File | Change |
|------|--------|
| app/build.gradle.kts | Add flatDir + implementation for onnxruntime-genai AAR |
| app/libs/ | Add README + placeholder for AAR (user downloads AAR) |
| app/.../ai/llm/OnnxQwenModel.kt | New: load ONNX folder, generateResponse via GenAI API |
| app/.../ai/llm/QwenStub.kt | Prefer OnnxQwenModel on ARM, then stub (QwenModel/GGUF removed) |
| app/.../ui/splash/SplashActivity.kt | Initialize ONNX model (folder path) first |
| PROFILING.md, QWEN_MODEL_INTEGRATION.md, usertask.md | Docs: ONNX path, model name, fallback order |
