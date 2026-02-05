# 📊 Benchmark Comparison: Phi-4-mini vs Qwen 2.5 1.5B vs Gemma 3n E2B

## Performance Summary

| Metric           | Phi-4-mini          | Qwen 2.5 1.5B      | Gemma 3n E2B       | Winner               |
| ---------------- | ------------------- | ------------------ | ------------------ | -------------------- |
| Model Size       | 3.6 GB              | 1.5 GB             | 3.2 GB             | 🏆 Qwen (58% smaller) |
| Total Time       | 852.27s (~14.2 min) | 354.44s (~5.9 min) | ❌ CRASHED*        | 🏆 Qwen (2.4x faster) |
| Pass 1 Time      | 533.38s             | 182.18s            | ❌ CRASHED*        | 🏆 Qwen               |
| Pass 2 Time      | 318.89s             | 172.26s            | ❌ CRASHED*        | 🏆 Qwen               |
| Characters Found | ✅ 5/5               | ✅ 5/5              | ❌ CRASHED*        | Tie                  |

*Note: Gemma 3n E2B benchmark on "Space story.pdf" **FAILED** (2026-02-04). The LiteRT-LM engine crashed with exit code 3221225477 (access violation) during `litert_lm_engine_create`. Previous successful results exist for "HP First3Chapters.pdf" only.

## Quality Comparison

### Pass 1: Character Analysis & Voice Profiles

| Aspect                        | Phi-4-mini                                  | Qwen 2.5 1.5B                                      | Gemma 3n E2B (HP test)                              |
| ----------------------------- | ------------------------------------------- | -------------------------------------------------- | --------------------------------------------------- |
| Output Format                 | ✅ Clean JSON array with all profiles        | ⚠️ Separate JSON per character (not a single array) | ✅ Clean JSON array with all profiles                |
| Character Identification      | ✅ Jax, Lyra, Kael, Zane, Mina               | ✅ Jax, Lyra, Kael, Zane, Mina                      | ✅ Mr. Dursley, Mrs. Dursley, Dudley, etc.           |
| Voice Profile Differentiation | ✅ Varied pitch/speed/energy per character   | ⚠️ All profiles use pitch=1.0, speed=1.0, energy=1.0 | ✅ Varied (pitch 0.7-0.9, speed 1.0-1.2, energy 0.5-0.8) |
| Tone Descriptions             | ✅ Detailed (e.g., "smirking and energetic") | ✅ Detailed (e.g., "confident and assertive")       | ✅ Detailed (e.g., "gruff, slightly condescending")  |
| Age Assignments               | All "adult"                                 | ✅ Varied (teen, adult, middle-aged, elderly)       | ✅ Varied (kid, adult)                               |
| Emotion Bias                  | ✅ Varied per character                      | ⚠️ Same values for all characters                   | ✅ Varied per character                              |

### Pass 2: Dialog Extraction

| Aspect              | Phi-4-mini                         | Qwen 2.5 1.5B                                    | Gemma 3n E2B (HP test)                           |
| ------------------- | ---------------------------------- | ------------------------------------------------ | ------------------------------------------------ |
| Dialog Extraction   | ✅ Clean Character: "Dialog" format | ⚠️ Mixed - some proper format, some narrative     | ⚠️ Heavy Narrator attribution, some dialog mixed  |
| Speaker Attribution | ✅ Accurate                         | ⚠️ Includes "SPEAKER ATTRIBUTION RULES" artifacts | ⚠️ Many lines attributed to "Narrator"            |
| Narrative Handling  | ✅ Mostly excluded narrative        | ⚠️ Included narrative passages                    | ⚠️ Narrative often labeled as Narrator dialog     |

## Key Observations

### Phi-4-mini Strengths:
- ✅ Cleaner, more structured output (single valid JSON array)
- ✅ Better voice profile differentiation with varied pitch/speed/energy values
- ✅ More accurate dialog-only extraction in Pass 2

### Qwen 2.5 1.5B Strengths:
- ✅ 2.4x faster processing time (354s vs 852s)
- ✅ 58% smaller model size (1.5 GB vs 3.6 GB)
- ✅ More varied age assignments for characters
- ✅ Included chapter structure in output

### Qwen 2.5 1.5B Weaknesses:
- ⚠️ Voice profiles lack differentiation (all values at 1.0)
- ⚠️ Pass 2 includes raw artifacts ("SPEAKER ATTRIBUTION RULES: QUOTED SPEECH")
- ⚠️ Mixed narrative with dialog instead of clean separation

### Gemma 3n E2B Strengths (based on HP test):
- ✅ Good voice profile differentiation (varied pitch/speed/energy)
- ✅ Clean JSON array format for voice profiles
- ✅ Varied emotion_bias per character
- ✅ Appropriate age assignments (including "kid" for Dudley)

### Gemma 3n E2B Weaknesses (based on HP test):
- ⚠️ Heavy use of "Narrator" attribution in Pass 2
- ⚠️ Dialog extraction mixes narrative with actual dialog
- ⚠️ Some model cache messages appear in output ("Assuming you meant...")
- ⚠️ No timing data available for direct comparison

## Recommendation

| Use Case                           | Recommended Model |
| ---------------------------------- | ----------------- |
| Speed-critical/Resource-limited    | 🏆 Qwen 2.5 1.5B   |
| Quality-focused TTS voice profiles | 🏆 Phi-4-mini or Gemma 3n |
| Production dialog extraction       | 🏆 Phi-4-mini      |
| Quick prototyping/testing          | 🏆 Qwen 2.5 1.5B   |
| Balanced quality + size            | 🏆 Gemma 3n E2B (3.2 GB) |

## Overall Summary

**Phi-4-mini** produces the highest quality, most usable output for TTS voice synthesis with clean dialog extraction.

**Qwen 2.5 1.5B** is significantly faster (2.4x) and smaller (58%) but requires more post-processing to clean up artifacts and differentiate voice profiles.

**Gemma 3n E2B** shows promise for voice profile generation with good differentiation, but needs improvement in dialog extraction (too much Narrator attribution). Direct timing comparison pending - needs to be run on "Space story.pdf".

---

## Gemma 3n Benchmark Status

### ❌ FAILED (2026-02-04)

The Gemma 3n E2B benchmark on "Space story.pdf" crashed with the following error:

```
[Process exited with code 3221225477]
Exception 0xe06d7363 - Access Violation
Crash location: litert_lm_engine_create (engine.go:97)
```

**Possible causes:**
1. Model file corruption or incompatibility
2. LiteRT-LM runtime version mismatch with model format
3. Insufficient memory to load the model
4. CPU backend compatibility issues with this specific model

### Previous Successful Run (HP First3Chapters.pdf)

Gemma 3n E2B was successfully tested on "HP First3Chapters.pdf" with good results:
- ✅ Clean JSON voice profiles with varied pitch/speed/energy
- ✅ Appropriate age assignments (including "kid" for Dudley)
- ⚠️ Heavy "Narrator" attribution in Pass 2

### To Retry

```powershell
# Try with GPU backend
$env:LITERT_BACKEND = "gpu"
python scripts/benchmark_gemma3n_litertlm.py

# Or verify model file integrity
Get-FileHash "D:\Learning\Ai\Models\LLM\gemma-3n-E2B-it-int4.litertlm"
```
