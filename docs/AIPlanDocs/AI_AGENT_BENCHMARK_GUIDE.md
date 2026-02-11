# AI Agent Benchmark Guide

This guide provides step-by-step instructions for creating, setting up, and running benchmark tests for AI agents in the Storyteller project.

## Overview

The Storyteller project contains two categories of benchmarks:

| Category | Location | Purpose |
|----------|----------|---------|
| **LLM Benchmarks** | `scripts/` | Test LLM models for character extraction and dialog attribution |
| **Android Performance Benchmarks** | `app/src/androidTest/.../benchmarks/` | Measure app component performance (TTS, database, etc.) |

---

## Part 1: LLM Benchmark Tests

### 1.1 Prerequisites

#### Install Python Dependencies

```powershell
pip install pymupdf
```

#### Obtain LiteRT-LM CLI

The `lit.exe` CLI is included in `scripts/`. To update:

```powershell
# Download from: https://github.com/google-ai-edge/LiteRT-LM/releases
# Or build from source:
bazel build //runtime/engine:litert_lm_main --config=windows
```

#### Download Model Files

| Model | Size | Download Location |
|-------|------|-------------------|
| Gemma 3n E2B | 3.2 GB | [Google AI Edge](https://github.com/google-ai-edge/LiteRT-LM) |
| Phi-4-mini | 3.6 GB | LiteRT-LM registry |
| Qwen 2.5 1.5B | 1.5 GB | LiteRT-LM registry |

Check available models:
```powershell
scripts\lit.exe list --show_all
```

---

### 1.2 LiteRT-LM CLI (lit.exe) Reference

The `lit.exe` CLI is the primary tool for running LLM inference. Here's a complete reference:

#### Available Commands

| Command | Description |
|---------|-------------|
| `lit run <model>` | Run a model and start an interactive session |
| `lit list` | List locally downloaded models |
| `lit list --show_all` | List all models available in the registry |
| `lit pull <model>` | Download a model from registry or URL |
| `lit rm <model>` | Remove a locally downloaded model |

#### Running Models

**Basic interactive session:**
```powershell
scripts\lit.exe run phi-4-mini
```

**With GPU acceleration:**
```powershell
scripts\lit.exe run phi-4-mini --backend gpu
```

**Single-turn inference from file:**
```powershell
scripts\lit.exe run phi-4-mini --input_prompt_file prompt.txt --backend gpu
```

**Benchmark mode (performance metrics):**
```powershell
scripts\lit.exe run phi-4-mini --benchmark --backend gpu
```

**Verbose logging for debugging:**
```powershell
scripts\lit.exe run phi-4-mini --verbose --min_log_level 0
```

#### Run Command Flags

| Flag | Description |
|------|-------------|
| `--backend cpu\|gpu` | Inference backend (default: cpu) |
| `--benchmark` | Run in benchmark mode with performance metrics |
| `-f, --input_prompt_file PATH` | Path to file containing input prompt |
| `-v, --verbose` | Enable verbose logging |
| `--min_log_level N` | Log level: 0=VERBOSE, 1=INFO, 2=WARNING, 3=ERROR, 4=SILENT |

#### Managing Models

**List local models:**
```powershell
scripts\lit.exe list
```

**List all available models in registry:**
```powershell
scripts\lit.exe list --show_all
```

**Download a model from registry:**
```powershell
scripts\lit.exe pull phi-4-mini
scripts\lit.exe pull qwen2.5-1.5b
scripts\lit.exe pull gemma-3n-e2b
```

**Download from Hugging Face URL:**
```powershell
scripts\lit.exe pull https://huggingface.co/user/model/resolve/main/model.litertlm --alias my-model
```

**Download with authentication (for gated models):**
```powershell
$env:HUGGING_FACE_HUB_TOKEN = "hf_your_token_here"
scripts\lit.exe pull https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm --alias gemma3n
```

**Remove a model:**
```powershell
scripts\lit.exe rm phi-4-mini
```

#### Model Cache Location

Models are cached in:
```
C:\Users\<username>\.litert-lm\models\
```

The benchmark scripts automatically copy models to this cache with the correct alias.

#### Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `LITERT_BACKEND` | Default backend for inference | `gpu`, `cpu`, `npu` |
| `LITERT_LM_MAIN` | Path to lit.exe or litert_lm_main | `C:\path\to\lit.exe` |
| `HUGGING_FACE_HUB_TOKEN` | HF token for gated model downloads | `hf_xxxxx` |

**Set environment variables in PowerShell:**
```powershell
$env:LITERT_BACKEND = "gpu"
$env:LITERT_LM_MAIN = "C:\Users\Pratik\source\Storyteller\scripts\lit.exe"
```

---

### 1.3 Creating a New LLM Benchmark Script

1. **Copy an existing benchmark template:**

```powershell
cp scripts/benchmark_phi_mini_litertlm.py scripts/benchmark_my_model_litertlm.py
```

2. **Update the configuration section:**

```python
# Configuration
MODEL_PATH = os.environ.get(
    "MY_MODEL_PATH",
    r"D:\path\to\your-model.litertlm"
)
PDF_PATH = os.environ.get(
    "BENCHMARK_PDF_PATH",
    str(Path(__file__).resolve().parent.parent / "SpaceStory.pdf")
)
OUTPUT_DIR = Path(__file__).resolve().parent / "benchmarkResults"

# Token sizing (adjust based on model context window)
PASS1_TARGET_TOKENS = 3000  # For character extraction
PASS2_TARGET_TOKENS = 1500  # For dialog mapping
```

3. **Update the model alias and filename:**

```python
LIT_MODEL_ALIAS = "your-model-name"
LIT_MODEL_FILENAME = "your-model-file.litertlm"
```

4. **Customize prompts if needed** (see `build_prompt_pass1()` and `build_prompt_pass2()` functions).

---

### 1.4 Running LLM Benchmarks

#### Basic Usage

```powershell
cd C:\Users\Pratik\source\Storyteller

# Run individual benchmarks
python scripts/benchmark_phi_mini_litertlm.py
python scripts/benchmark_qwen_litertlm.py
python scripts/benchmark_gemma3n_litertlm.py
```

#### With GPU Acceleration

```powershell
$env:LITERT_BACKEND = "gpu"
python scripts/benchmark_phi_mini_litertlm.py
```

#### With Custom Input

```powershell
$env:BENCHMARK_PDF_PATH = "C:\path\to\your\book.pdf"
python scripts/benchmark_phi_mini_litertlm.py
```

#### Command-Line Options

| Option | Description |
|--------|-------------|
| `--pdf PATH` | Path to input PDF |
| `--output PATH` | Output file path |
| `--max-chapters N` | Process only first N chapters |
| `--session` | Keep model loaded between prompts (faster) |
| `--backend cpu\|gpu` | Inference backend |

---

### 1.5 Running Multi-Model Comparison

Run benchmarks across multiple GGUF models:

```powershell
# Using llama.cpp server benchmarks
.\scripts\run_3pass_benchmark_all_models.ps1

# Using llama-bench for raw performance
.\scripts\benchmark_models.ps1
```

---

### 1.6 Benchmark Output

Results are saved to `scripts/benchmarkResults/`:

```
Model LiteRT-LM benchmark
============================================================
Model: <path>
PDF: <path> (<chars> chars)
Wall time: Pass1=XXXs, Pass2=XXXs, Total=XXXs
============================================================

=== PASS 1: Characters + Traits + Voice profile ===
<character analysis and JSON voice profiles>

=== PASS 2: Character:Dialog mapping ===
<dialog attributions>
```

---

## Part 2: Android Performance Benchmarks

### 2.1 Location

```
app/src/androidTest/java/com/dramebaz/app/benchmarks/PerformanceBenchmarks.kt
```

### 2.2 What's Measured

| Benchmark | Threshold | Description |
|-----------|-----------|-------------|
| TTS Synthesis | < 5000ms | Text-to-speech generation time |
| Voice Mapping | < 50ms | Voice profile to TTS params mapping |
| Prosody Calculation | < 10ms | Dialog prosody calculation |
| Database Query | < 500ms | Book query performance |
| Input Validation | < 50ms | Text sanitization performance |

### 2.3 Running Android Benchmarks

```powershell
# From project root
./gradlew connectedAndroidTest --tests "*PerformanceBenchmarks*"

# Or run specific benchmark
./gradlew connectedAndroidTest --tests "*benchmarkVoiceProfileMapping*"
```

### 2.4 Creating New Android Benchmarks

Add a new test method to `PerformanceBenchmarks.kt`:

```kotlin
@Test
fun benchmarkMyOperation() {
    // Warmup
    repeat(WARMUP_ITERATIONS) {
        myOperation()
    }

    // Benchmark
    val times = mutableListOf<Long>()
    repeat(BENCHMARK_ITERATIONS) {
        val start = System.nanoTime()
        myOperation()
        val elapsed = (System.nanoTime() - start) / 1_000_000
        times.add(elapsed)
    }

    val avgTime = times.average()
    android.util.Log.i("Benchmark", "MyOperation - Avg: ${avgTime}ms")

    assertTrue("Should complete in <50ms", avgTime < 50)
}
```

---

## Part 3: Performance Tuning

### 3.1 LLM Benchmark Optimization

| Goal | Action |
|------|--------|
| **Faster runs** | Use `--session` + `LITERT_BACKEND=gpu` |
| **Less memory** | Use Qwen 2.5 1.5B (1.5 GB vs 3.6 GB) |
| **Better quality** | Use Phi-4-mini (cleaner JSON output) |
| **Quick testing** | Use `--max-chapters 1` |

### 3.2 Model Configuration for Extraction

For structured extraction tasks, use these app/model config values:

```json
{
  "topK": 48,
  "topP": 0.9,
  "temperature": 0.6,
  "maxTokens": 4096,
  "accelerators": "gpu,cpu"
}
```

| Parameter | Default | Recommended | Why |
|-----------|---------|-------------|-----|
| temperature | 1.0 | 0.5-0.7 | More deterministic output |
| topP | 0.95 | 0.9 | Tighter distribution |
| topK | 64 | 40-48 | Fewer candidates per step |
| accelerators | cpu,gpu | gpu,cpu | Prefer GPU first |

---

## Part 4: Troubleshooting

### Model Not Found

```powershell
# Check cached models
scripts\lit.exe list

# Manual cache location
dir $env:USERPROFILE\.litert-lm\models\
```

### Slow Performance

1. Ensure GPU backend: `$env:LITERT_BACKEND = "gpu"`
2. Update GPU drivers
3. Close other GPU applications
4. Use `--session` mode to avoid reloading

### PowerShell Environment Issues

```powershell
# Run in same command
$env:LITERT_BACKEND="gpu"; python scripts/benchmark_phi_mini_litertlm.py
```

---

## Part 5: Benchmark Workflow Summary

### For AI Agents Following This Guide

1. **Setup Environment:**
   ```powershell
   pip install pymupdf
   cd C:\Users\Pratik\source\Storyteller
   ```

2. **Choose Benchmark Type:**
   - LLM benchmarks → Python scripts in `scripts/`
   - Android benchmarks → Kotlin tests via Gradle

3. **Run LLM Benchmark:**
   ```powershell
   $env:LITERT_BACKEND = "gpu"
   python scripts/benchmark_phi_mini_litertlm.py --session
   ```

4. **Run Android Benchmark:**
   ```powershell
   ./gradlew connectedAndroidTest --tests "*PerformanceBenchmarks*"
   ```

5. **Check Results:**
   - LLM: `scripts/benchmarkResults/*.txt`
   - Android: Logcat with tag "Benchmark"

---

## Related Documentation

- `scripts/BENCHMARK_GUIDE.md` - LiteRT-LM specific guide
- `scripts/SPEED_AND_CONFIG.md` - Performance tuning details
- `scripts/benchmarkResults/` - Historical benchmark results

