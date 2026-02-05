# LiteRT-LM Benchmark Guide

This guide explains how to run the LLM benchmarks for character extraction and dialog attribution using LiteRT-LM models.

## Overview

The benchmark scripts analyze a PDF document (default: `Space story.pdf`) in two passes:
1. **Pass 1**: Extract characters, traits, and generate TTS voice profiles (~3000 tokens input)
2. **Pass 2**: Map dialogs to characters (~1500 token segments)

## Available Benchmark Scripts

| Script                           | Model         | Size   | Context     |
| -------------------------------- | ------------- | ------ | ----------- |
| `benchmark_gemma3n_litertlm.py`  | Gemma 3n E2B  | 3.2 GB | 4069 tokens |
| `benchmark_phi_mini_litertlm.py` | Phi-4-mini    | 3.6 GB | 4069 tokens |
| `benchmark_qwen_litertlm.py`     | Qwen 2.5 1.5B | 1.5 GB | 4096 tokens |

---

## Prerequisites

### 1. Python Dependencies

```powershell
# Required: PyMuPDF for PDF text extraction
pip install pymupdf
```

### 2. LiteRT-LM CLI (lit.exe)

The `lit.exe` CLI is already included in the `scripts/` folder. If you need to update it:

```powershell
# Download from LiteRT-LM releases
# https://github.com/google-ai-edge/LiteRT-LM/releases

# Or build from source:
# bazel build //runtime/engine:litert_lm_main --config=windows
```

### 3. Model Files

Download LiteRT-LM format models (.litertlm files):

| Model         | Download Location                                             | Expected Path                                                                                |
| ------------- | ------------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| Gemma 3n E2B  | [Google AI Edge](https://github.com/google-ai-edge/LiteRT-LM) | `D:\Learning\Ai\Models\LLM\gemma-3n-E2B-it-int4.litertlm`                                    |
| Phi-4-mini    | LiteRT-LM registry                                            | `D:\Learning\Ai\Models\LLM\Phi\Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm`    |
| Qwen 2.5 1.5B | LiteRT-LM registry                                            | `D:\Learning\Ai\Models\LLM\Qwen\Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm` |

Check available models in the registry:
```powershell
scripts\lit.exe list --show_all
```

---

## Running Benchmarks

### Basic Usage

```powershell
# Navigate to project root
cd C:\Users\Pratik\source\Storyteller

# Run Phi-4-mini benchmark
python scripts/benchmark_phi_mini_litertlm.py

# Run Qwen benchmark
python scripts/benchmark_qwen_litertlm.py

# Run Gemma 3n benchmark
python scripts/benchmark_gemma3n_litertlm.py
```

### GPU Acceleration

For faster inference, set the backend to GPU:

```powershell
# PowerShell - set environment variable before running
$env:LITERT_BACKEND = "gpu"
python scripts/benchmark_phi_mini_litertlm.py
```

```cmd
# Command Prompt
set LITERT_BACKEND=gpu
python scripts/benchmark_phi_mini_litertlm.py
```

### Custom PDF Input

```powershell
# Use a different PDF file
$env:BENCHMARK_PDF_PATH = "C:\path\to\your\book.pdf"
python scripts/benchmark_phi_mini_litertlm.py
```

### Custom Model Path

```powershell
# Override model location
$env:PHI_MINI_MODEL_PATH = "D:\Models\phi-4-mini.litertlm"
python scripts/benchmark_phi_mini_litertlm.py

$env:QWEN_MODEL_PATH = "D:\Models\qwen2.5-1.5b.litertlm"
python scripts/benchmark_qwen_litertlm.py
```

---

## Command-Line Options

```powershell
python scripts/benchmark_phi_mini_litertlm.py --help
```

| Option               | Description                                    |
| -------------------- | ---------------------------------------------- |
| `--pdf PATH`         | Path to input PDF (default: `Space story.pdf`) |
| `--output PATH`      | Output file path                               |
| `--max-chapters N`   | Process only first N chapters                  |
| `--session`          | Keep model loaded between prompts (faster)     |
| `--backend cpu\|gpu` | Inference backend                              |

---

## Output

Results are saved to `scripts/benchmarkResults/`:

| Model      | Output File                              |
| ---------- | ---------------------------------------- |
| Gemma 3n   | `gemma3n_litertlm_benchmark_output.txt`  |
| Phi-4-mini | `phi_mini_litertlm_benchmark_output.txt` |
| Qwen 2.5   | `qwen_litertlm_benchmark_output.txt`     |

### Output Format

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

## Performance Tuning

See `scripts/SPEED_AND_CONFIG.md` for detailed tuning options.

### Quick Tips

| Goal               | Action                                 |
| ------------------ | -------------------------------------- |
| **Faster runs**    | Use `--session` + `LITERT_BACKEND=gpu` |
| **Less memory**    | Use Qwen 2.5 1.5B (1.5 GB vs 3.6 GB)   |
| **Better quality** | Use Phi-4-mini (cleaner JSON output)   |
| **Process less**   | Use `--max-chapters 1` for testing     |

---

## Troubleshooting

### "Model not found in local cache"

The lit CLI caches models in `C:\Users\<username>\.litert-lm\models\`. The benchmark script automatically copies your model there with the correct filename.

```powershell
# Check cached models
scripts\lit.exe list

# Manual cache location
dir $env:USERPROFILE\.litert-lm\models\
```

### PowerShell Environment Variable Issues

If `$env:LITERT_BACKEND = "gpu"` doesn't work, try:

```powershell
# Run in same command
$env:LITERT_BACKEND="gpu"; python scripts/benchmark_phi_mini_litertlm.py
```

### Slow Performance

1. Ensure GPU backend is active: `$env:LITERT_BACKEND = "gpu"`
2. Check GPU drivers are up to date
3. Close other GPU-intensive applications
4. Use `--session` mode to avoid reloading the model

---

## Benchmark Results Comparison

Results from running benchmarks on `Space story.pdf` (6858 characters):

### Performance

| Model         | Size   | Pass 1  | Pass 2  | Total Time              |
| ------------- | ------ | ------- | ------- | ----------------------- |
| Phi-4-mini    | 3.6 GB | 533.38s | 318.89s | **852.27s** (~14.2 min) |
| Qwen 2.5 1.5B | 1.5 GB | 182.18s | 172.26s | **354.44s** (~5.9 min)  |

### Quality

| Aspect                | Phi-4-mini                  | Qwen 2.5 1.5B            |
| --------------------- | --------------------------- | ------------------------ |
| Characters Found      | ✅ 5/5                       | ✅ 5/5                    |
| JSON Format           | ✅ Clean single array        | ⚠️ Separate per character |
| Voice Profile Variety | ✅ Varied pitch/speed/energy | ⚠️ All values at 1.0      |
| Dialog Extraction     | ✅ Clean format              | ⚠️ Includes artifacts     |

### Recommendations

| Use Case                           | Recommended Model               |
| ---------------------------------- | ------------------------------- |
| Speed-critical / Resource-limited  | **Qwen 2.5 1.5B** (2.4x faster) |
| Quality-focused TTS voice profiles | **Phi-4-mini**                  |
| Production dialog extraction       | **Phi-4-mini**                  |
| Quick prototyping / testing        | **Qwen 2.5 1.5B**               |
