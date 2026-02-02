# User Tasks – What We Need From You

This document lists everything the Dramebaz app implementation needs from you (the user). Tasks that require these items are left **skippable** in the agent flow until you provide them.

---

## 1. Model Files & Binaries (Offline AI)

The app is designed to run **fully offline** with on-device models. The following require you to obtain and (optionally) place files:

| Item | Purpose | Where to get | Where to put (suggested) |
|------|--------|---------------|---------------------------|
| **Qwen3-1.7B-Q4-ONNX** | Chapter analysis (characters, dialogs, summaries, sound cues) | [Edison2ST/Qwen3-1.7B-Q4-ONNX](https://huggingface.co/Edison2ST/Qwen3-1.7B-Q4-ONNX) | Device **Downloads** folder: put folder `Qwen3-1.7B-Q4-ONNX` (with model.onnx, tokenizer.json, model.weights.bin, etc.) there. On ARM only; emulator uses stub. |
| **SherpaTTS Android engine / model** | Character-aware TTS | SherpaTTS Android builds | Per SherpaTTS Android docs; paths configured in app |
| **SFX/Audio model** (optional) | Stable Audio Open / AudioGen or similar for generating SFX/ambience | Or use bundled SFX library only | Assets or sideload path if using generation |

**What we did without you:**  
- Placeholder **stubs** for LLM, TTS, and audio model loaders that simulate success and return mock data.
- You can replace stubs with real implementations once you have the model files and native libs.

---

## 2. Configuration & Secrets

| Item | Purpose | When needed |
|------|--------|-------------|
| **Model paths** | Where Qwen ONNX folder, SherpaTTS assets, and (if any) SFX model live on device | When moving from stubs to real inference |
| **Device-specific paths** | Sideload directories for large models (if not in assets) | When bundling in assets is not possible |

No API keys or network secrets are required for the core offline design.

---

## 3. Optional: Real Integrations

Until you add these, the app uses **stubs**:

- **LLM (Qwen)**  
  - Stub returns fixed or random JSON that matches the chapter-analysis schema.  
  - Add Qwen3-1.7B-Q4-ONNX folder in Downloads (see table above) for real chapter analysis on ARM devices.

- **SherpaTTS**  
  - Stub returns success and optional mock audio.  
  - You need: SherpaTTS Android SDK/model and wiring in `ai/tts/`.

- **SFX generation**  
  - Stub picks from a small set of bundled files or no-op.  
  - You need: Either a bundled SFX library with tags, or an audio-generation model + integration.

---

## 4. Tasks That May Wait on You

These are the tasks that **cannot be fully finished** without the above (or are left as stubs until you provide assets):

1. **T1.1** – Real SherpaTTS integration (engine init, speak, stop) – needs SherpaTTS binary/model.
2. **T3.2** – Real SFX generation or a full SFX library with tags – needs model or curated asset pack.
3. **T2.4 / T3.4** – Full playback with real TTS and real SFX – depends on T1.1 and T3.2.
4. **M2 (Qwen)** – Real chapter analysis – needs Qwen GGUF + Android inference runtime.
5. **T5.3** – Real global character merging – needs LLM (Qwen) or a deterministic merge rule you define.
6. **T9.1** – Extended analysis (themes, symbols, vocabulary) – needs LLM (Qwen) or other model.

Everything else (UI, DB, parsing, session, bookmarks, themes, recap, insights layout, and all stubs) can be implemented and tested without your input.

---

## 5. What You Can Do Next

1. **Run the app as-is**  
   Use stubs: import (TXT or stub), browse library, open reader, use mode toggle, bookmarks, settings, themes, character/insights screens. Playback will use stub TTS/SFX.

2. **Add real models**  
   - Add the **Qwen3-1.7B-Q4-ONNX** folder to device Downloads on an ARM device (app loads it automatically).  
   - Add SherpaTTS per its Android instructions and replace the TTS stub.  
   - Add SFX assets or an audio model and replace the SFX stub.

3. **Tune UX**  
   - Choose default theme, recap length, and which analysis fields to show in Insights.  
   - Adjust theme parameters (SFX/ambience volume, prosody scaling) in `T8.2`.

---

## 6. Build & run

- **Gradle wrapper:** If `gradlew.bat` says "Gradle wrapper JAR not found", run: `gradle wrapper --gradle-version 8.2`. Or use **Android Studio**.
- **Gradle cache errors:** Run `gradle --stop`, kill OpenJDK/Java, then retry or use `-g .gradle-clean` for a clean home.
- **Install:** `.\gradlew.bat installDebug`. Test: `adb shell am start -n com.dramebaz.app/.ui.test.TestActivity`

## 7. Summary

- **Required from you for “full” experience:**  
  Model files (or approved sideload paths) for Qwen, SherpaTTS, and optionally SFX generation or a tagged SFX library.
- **Not required for a working, testable app:**  
  API keys, server URLs, or any network access; the app is built to run offline with stubs until you plug in the real models.

---

## 8. When you’re done

**Hibernate when:**  
All implementable tasks are finished, or only tasks that require your input (model files, API keys) or unfixable build/connection errors remain.

**How to test:**  
- Open Dramebaz on the emulator → Library overflow (⋮) → **Test activities** to open TestActivity.  
- Or run: `adb shell am start -n com.dramebaz.app/.ui.test.TestActivity`  
- Each test button opens Main; use Library → Import (FAB) → pick a TXT → open book → Start reading / Characters / Settings to verify flows.
