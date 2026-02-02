# Plan: Better Performance Without Compromising Accuracy

This plan applies patterns from the [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) to the Storyteller app: hardware optimization, centralized config, and extraction-tuned sampling so we get better speed while keeping character/dialog extraction quality high.

---

## Reference: Gallery optimization techniques

Summary of Gallery/LiteRT-LM techniques (for alignment and future work):

| Technique | Gallery usage | Storyteller relevance |
|-----------|---------------|------------------------|
| **Backend selection** | Dynamic CPU/GPU (and NPU) via `Accelerator` → `Backend`; vision→GPU, audio→CPU for multimodal | We use GPU→CPU today; Phase 2 adds configurable order + NPU when SDK supports it. Text-only extraction: no separate vision/audio backends. |
| **.litertlm format** | 4-bit per-channel quantization; first-load weight caching for device; smaller size, faster memory | We already use Gemma 3n E2B int4 `.litertlm`; `EngineConfig.cacheDir` enables caching. No change. |
| **Async streaming** | `sendMessageAsync(contents, MessageCallback)` for partial results + `onDone()`; responsive UX | **Implemented**: `LiteRtLmEngine.generate()` uses `sendMessageAsync(String, MessageCallback)` and collects full response via `suspendCancellableCoroutine` (API verified in litertlm-android). |
| **Configurable sampling** | TopK (5–100, default 64), TopP (0–1, 0.95), Temperature (0–2, 1.0), Max Tokens | Phase 1 + 3: config-driven topK/topP/temperature/maxTokens; extraction-friendly defaults. |
| **Context / reset** | 4096 context; `resetConversation()` to avoid overflow | We create a new conversation per `generate()` (`.use { }`), so no accumulation; no reset needed. |
| **NPU** | Qualcomm/MediaTek NPU: e.g. 5836 tok/s prefill (S25 Ultra) vs 1876 GPU | Phase 2: try NPU when config lists it and SDK supports `Backend.NPU`. |

**LiteRT-LM benchmark reference (Samsung S24 Ultra):**

| Model | Backend | Prefill (tok/s) | Decode (tok/s) |
|-------|---------|-----------------|----------------|
| Gemma3-1B | GPU | 1876.5 | 44.57 |
| Gemma3-1B | CPU | 243.24 | 43.56 |
| Gemma-3n-E2B | GPU | 816.4 | 15.6 |
| Gemma-3n-E2B | CPU | 110.5 | 16.1 |

Use these as targets when adding Phase 5 metrics (e.g. log time-per-call or tok/s if available).

---

## Current State (Summary)

| Area | Current | Gap vs gallery-style |
|------|--------|----------------------|
| **Backend** | GPU first, CPU fallback in `LiteRtLmEngine` | No NPU try; backend order not configurable |
| **Sampling** | Hardcoded `topK=40`, `topP=0.95`; Pass1 temp 0.1, Pass2 0.15 | No per-pass/per-task config; not tuned for extraction |
| **Model** | Single hardcoded `gemma-3n-E2B-it-int4.litertlm` | No allowlist; no memory check before load |
| **Metrics** | None in app | No TTFT/decode visibility for tuning or UX |
| **Config** | `SPEED_AND_CONFIG.md` suggests values but app doesn’t read them | App doesn’t use a single source of truth for “best” config |

---

## Goals

1. **Performance**: Prefer best accelerator (GPU/NPU when available), optional metrics for tuning.
2. **Accuracy**: Keep extraction quality by using extraction-optimized sampling (lower temp, tuned topK/topP) from a single config.
3. **Maintainability**: One place (model/config) to tune defaults and add models later.

---

## Phase 1: Centralized LLM config (accuracy + speed defaults)

**Goal**: Single source of truth for sampling and backend order; extraction-optimized defaults.

### 1.1 Add `LlmModelConfig` and asset JSON

- **New**: `app/src/main/assets/models/llm_model_config.json`  
  - One entry for the current Gemma 3n E2B model: `modelFileName`, `displayName`, `defaultConfig` (topK, topP, temperature, maxTokens, accelerators).
  - Use extraction-friendly defaults: e.g. `topK: 48`, `topP: 0.9`, `temperature: 0.6` for general generation; lower temps for Pass1/Pass2 (e.g. 0.1 / 0.15) can stay as pass-specific overrides.
- **New**: Data class `LlmModelConfig` (and optional `LlmDefaultConfig`) in a small `config` package (e.g. under `com.dramebaz.app.ai.llm` or `com.dramebaz.app.config`), parsed from JSON (Gson/Moshi).
- **Behavior**: Engine or use case reads this config at startup and uses it for `SamplerConfig` and for choosing backend order (see Phase 2). Pass-specific temperatures (0.1 / 0.15) can remain in code but general defaults (topK, topP) come from config so you can tune without code changes.

**Files to add/change**  
- Add: `app/src/main/assets/models/llm_model_config.json`  
- Add: `app/src/main/java/.../config/LlmModelConfig.kt` (or under `ai.llm`)  
- Change: `LiteRtLmEngine.kt` — accept or load `LlmModelConfig` / `LlmDefaultConfig`, use for `SamplerConfig` (topK, topP, temperature) and for backend order (Phase 2).

**Accuracy**: Same or better (tuned topK/topP/temperature for extraction). **Speed**: Slight gain from slightly lower topK/topP if you reduce tokens per response.

---

## Phase 2: Configurable accelerator order (GPU → NPU → CPU)

**Goal**: Use best available accelerator; match gallery’s “accelerators” idea.

### 2.1 Backend order from config

- In `llm_model_config.json`, `defaultConfig.accelerators` is a string like `"gpu,cpu"` or `"gpu,npu,cpu"`.
- **LiteRtLmEngine**: Parse this and try backends in that order (e.g. GPU → NPU → CPU). Use existing `Backend.GPU` and `Backend.CPU`. If the LiteRT-LM Android SDK exposes an NPU backend (e.g. `Backend.NPU`), add it when present in config; otherwise skip NPU and keep GPU → CPU.
- **Behavior**: Same as today (GPU first, then CPU), but order and presence of NPU become config-driven so you can switch to `gpu,npu,cpu` when the SDK supports NPU.

**Files to change**  
- `LiteRtLmEngine.kt`: In `initialize()`, loop over backends from config (e.g. `listOf(Backend.GPU, Backend.CPU)` or GPU, NPU, CPU), try each until one succeeds. Set `usingGpu` (or a more generic “accelerator” flag) from the chosen backend.

**Accuracy**: Unchanged. **Speed**: Better on devices with NPU once supported; no regression on others.

---

## Phase 3: Per-pass sampling from config (keep accuracy, optional speed)

**Goal**: Pass-specific sampling (Pass1/Pass2) still optimized for extraction, but driven by config so you can A/B test.

### 3.1 Pass-specific overrides in config

- Extend `llm_model_config.json` with optional `pass1` and `pass2` overrides: e.g. `temperature`, `maxTokens`, optionally `topK`/`topP`.
- **LiteRtLmEngine**: For `pass1ExtractCharactersAndVoiceProfiles` and `pass2ExtractDialogs`, build `SamplerConfig` from default config plus pass override (e.g. Pass1 temp 0.1, Pass2 temp 0.15). If a value is missing in override, use default.
- **Behavior**: Same quality as today by default (0.1 / 0.15), but you can later try e.g. 0.12 / 0.18 from config without a release.

**Files to change**  
- `llm_model_config.json`: Add optional `pass1` / `pass2` blocks.  
- `LiteRtLmEngine.kt`: Add helpers that merge default config + pass overrides and build `SamplerConfig`; use in `pass1Extract...` and `pass2Extract...`.

**Accuracy**: Preserved (same defaults); tunable via config. **Speed**: Minor if you ever lower maxTokens per pass from config.

---

## Phase 4: Optional memory check before load (stability + UX)

**Goal**: Avoid loading a model on low-memory devices and failing mid-run; match gallery’s `estimatedPeakMemoryInBytes`.

### 4.1 Add memory requirement and check

- In `llm_model_config.json`, add `estimatedPeakMemoryInBytes` for the Gemma 3n E2B model (e.g. ~5.9 GB from gallery’s allowlist).
- **New**: Small helper (e.g. in `LiteRtLmEngine` or a `DeviceCapability` util) that checks `Runtime.getRuntime().maxMemory()` or `ActivityManager.getMemoryClass()` (and optionally `largeHeap` if you use it). If available memory is below a threshold (e.g. 80% of `estimatedPeakMemoryInBytes`), do not load the model and surface a clear message (“Not enough memory for this model”).
- **Behavior**: On low-end devices, user gets a clear message instead of an OOM or hang during analysis.

**Files to add/change**  
- `llm_model_config.json`: Add `estimatedPeakMemoryInBytes`.  
- `LiteRtLmEngine.kt` (or a util): Before `Engine(gpuConfig)`, check memory; if insufficient, return false from `initialize()` and log/surface message.  
- Optional: `SplashActivity` or analysis entry point: if init fails, show “Insufficient memory” or “Device not supported” instead of generic error.

**Accuracy**: Unchanged. **Speed**: Avoids slow OOMs; better UX.

---

## Phase 5: Simple performance metrics (TTFT / decode time)

**Goal**: Visibility into first-token and decode time for tuning and optional UI (e.g. “Analysis speed” in settings or debug).

### 5.1 Log (and optionally expose) timings

- In `LiteRtLmEngine.generate()`:  
  - Record `t0` before calling the engine; after first token or first chunk (if the API exposes streaming), record TTFT; when generation completes, record total time and approximate “decode time” (total − TTFT) or tokens-per-second if you have token count.
- If the LiteRT-LM Android API does not expose streaming/callbacks, measure only **total time per call** and log it (e.g. “Pass1 segment N: 3.2s”). That still helps compare backend (GPU vs CPU) and segment sizes.
- **Optional**: Store last run’s “avg time per segment” or “last TTFT” in a small holder or in preferences, and show in Settings or in the analysis progress notification (e.g. “~12 s per segment on GPU”).

**Files to change**  
- `LiteRtLmEngine.kt`: In `generate()`, wrap engine call in timing; log result. Optionally in `GemmaCharacterAnalysisUseCase` or `CharacterAnalysisForegroundService`, aggregate per-chapter timings and log or persist.

**Accuracy**: Unchanged. **Speed**: No direct change; enables data-driven tuning (segment size, backend, sampling).

### 5.2 Phase 5b: Async streaming for extraction (implemented)

**Goal**: Match Gallery’s responsive UX by streaming tokens; avoid blocking until full response.

- **API verified**: `litertlm-android`’s `Conversation` exposes `sendMessageAsync(String, MessageCallback)` with `onMessage(Message)`, `onDone()`, and `onError(Throwable)` (see [LiteRT-LM kotlin/Conversation.kt](https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/java/com/google/ai/edge/litertlm/Conversation.kt)).
- **Implementation**: In `LiteRtLmEngine.generate()`, we call `sendMessageAsync(userPrompt, MessageCallback)` and collect chunks in a `StringBuilder` in `onMessage`; in `onDone()` we resume a `suspendCancellableCoroutine` with the full string. Same contract: `generate()` returns the full response. Timing (Phase 5) logs total ms and response length.
- **Requirements**: Kotlin coroutines (`suspendCancellableCoroutine`); no extra dependencies.

**Accuracy**: Unchanged. **Speed**: Same wall-clock; better perceived responsiveness during long Pass1/Pass2 segments.

---

## Phase 6: Model path from config (future-proofing)

**Goal**: Support multiple models later (e.g. E2B vs E4B) without code changes; match gallery’s allowlist idea.

### 6.1 Model file name and paths from config

- In `llm_model_config.json`, the model entry already has `modelFileName` (e.g. `gemma-3n-E2B-it-int4.litertlm`). Extend `findModelFile()` to use this name from config (and optionally a list of search paths from config).
- **Behavior**: Today still one model; later you can add another entry (e.g. E4B) and a “model picker” in settings that sets which config entry to use. No change to analysis quality until you add a second model.

**Files to change**  
- `LiteRtLmEngine.kt`: Load config first; `findModelFile()` uses `config.modelFileName` and optional paths. Keep fallback paths (Download, filesDir, etc.) if not in config.

**Accuracy**: Unchanged. **Speed**: Unchanged; enables future model choice (e.g. smaller vs larger).

---

## Implementation order (recommended)

| Order | Phase | Rationale |
|-------|--------|-----------|
| 1 | Phase 1 – Centralized config | Foundation; no behavior change if defaults match current. |
| 2 | Phase 2 – Accelerator order | Small code change; enables NPU when SDK supports it. |
| 3 | Phase 3 – Per-pass config | Keeps extraction quality, makes tuning config-driven. |
| 4 | Phase 4 – Memory check | Improves stability and UX on low-memory devices. |
| 5 | Phase 5 – Metrics | Logging only first; UI later if needed. |
| 5b | Phase 5b – Async streaming | **Done**: sendMessageAsync + MessageCallback; API verified and implemented. |
| 6 | Phase 6 – Model path from config | Prep for multiple models; low risk. |

---

## What we’re not doing (to avoid scope creep)

- **Runtime internals (zero-copy, async execution inside native)**: We use the existing Android API only; no changes to LiteRT/LiteRT-LM native stack. **sendMessageAsync** from the Android API is used in Phase 5b (implemented).
- **Quantization changes**: Keep current 4-bit per-channel model; no conversion or new quantization in this plan.
- **Multimodal (vision/audio)**: Storyteller’s LLM path is text-only for character/dialog extraction; no separate vision/audio backends.
- **Multiple models in UI**: Phase 6 only makes path/config ready; adding a model picker and testing E4B is a follow-up.

---

## Success criteria

- **Performance**: Same or better time per chapter (GPU/NPU when available; optional metrics to prove it).
- **Accuracy**: No regression in character/dialog extraction; same or better with tuned topK/topP/temperature from config.
- **Stability**: No OOM on devices that meet the memory requirement; clear message when they don’t.
- **Maintainability**: One JSON config for model and sampling defaults; backend order and per-pass overrides config-driven.

---

## Config example (Phase 1 + 3 + 4 + 6)

```json
{
  "models": [
    {
      "modelFileName": "gemma-3n-E2B-it-int4.litertlm",
      "displayName": "Gemma 3n E2B (int4)",
      "estimatedPeakMemoryInBytes": 5905580032,
      "defaultConfig": {
        "topK": 48,
        "topP": 0.9,
        "temperature": 0.6,
        "maxTokens": 2048,
        "accelerators": "gpu,cpu"
      },
      "pass1": {
        "temperature": 0.1,
        "maxTokens": 1024
      },
      "pass2": {
        "temperature": 0.15,
        "maxTokens": 1024
      }
    }
  ],
  "selectedModelId": 0
}
```

This plan gives you gallery-style benefits (config-driven accelerators and sampling, optional memory check and metrics) while keeping extraction accuracy and a clear path to future models.
