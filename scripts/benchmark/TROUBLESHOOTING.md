# LiteRT-LM Troubleshooting Guide

This guide covers common issues when using `lit.exe` with the benchmark scripts.

## Quick Reference

| Issue | Solution |
|-------|----------|
| "model not found in local cache" | Use correct `--model-alias` matching registry |
| lit.exe not found | Download from LiteRT-LM releases |
| Model file not copied | Check disk space, file permissions |
| Slow generation | Use `--backend gpu` if available |

---

## Common Issues

### 1. "model 'X' not found in local cache"

**Error message:**
```
Error: model 'gemma-3n-E2B' not found in local cache. Please run 'lit pull gemma-3n-E2B' first
```

**Root Cause:**
`lit.exe` has a model registry that maps aliases to specific filenames. When you run `lit.exe run <alias>`, it looks for a file with a **specific expected filename** in `~/.litert-lm/models/`.

**The Problem:**
- `lit.exe list` shows models from `~/.litert-lm/models/`
- `lit.exe run <alias>` expects the file to have the **exact registry filename**
- If you copy a model with a different filename, `lit.exe list` shows it but `lit.exe run` can't find it

**Example:**
- Registry alias: `gemma-3n-E2B`
- Expected filename: `gemma-3n-E2B-it-int4.litertlm`
- If you save as `gemma-3n-E2B.litertlm` → **FAILS**
- If you save as `gemma-3n-E2B-it-int4.litertlm` → **WORKS**

**Solution:**

1. Check the registry to find the expected filename:
   ```powershell
   lit.exe list --show_all
   ```

2. Use the correct `--model-alias` parameter:
   ```powershell
   python -m benchmark.run_benchmark --pdf book.pdf --model litert \
       --model-path D:\Models\gemma-3n-E2B-it-int4.litertlm \
       --model-alias gemma-3n-E2B \
       --model-type gemma
   ```

3. Or list known aliases in the benchmark script:
   ```powershell
   python -m benchmark.run_benchmark --list-aliases
   ```

### 2. Known Model Aliases

The benchmark scripts know about these model aliases:

| Alias | Expected Filename | Model Type |
|-------|-------------------|------------|
| `gemma-3n-E2B` | `gemma-3n-E2B-it-int4.litertlm` | gemma |
| `gemma-3n-E4B` | `gemma-3n-E4B-it-int4.litertlm` | gemma |
| `gemma3-1b` | `gemma3-1b-it-int4.litertlm` | gemma |
| `phi-4-mini` | `Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm` | chatml |
| `qwen2.5-1.5b` | `Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm` | chatml |

### 3. lit.exe Not Found

**Error:**
```
[LiteRTModel] WARNING: lit.exe not found
```

**Solution:**
1. Download `lit.exe` from [LiteRT-LM Releases](https://github.com/google-ai-edge/LiteRT-LM/releases)
2. Place it in the `scripts/` directory
3. Or specify the path with `--lit-exe`:
   ```powershell
   python -m benchmark.run_benchmark --pdf book.pdf --model litert \
       --lit-exe "C:\path\to\lit.exe" \
       --model-path model.litertlm
   ```

### 4. Model Cache Location

`lit.exe` stores models in the user's home directory:
```
Windows: C:\Users\<username>\.litert-lm\models\
Linux:   ~/.litert-lm/models/
```

To check cached models:
```powershell
lit.exe list
```

To clear the cache:
```powershell
# Windows
Remove-Item -Recurse -Force "$env:USERPROFILE\.litert-lm"

# Linux/Mac
rm -rf ~/.litert-lm
```

### 5. GPU vs CPU Backend

Use `--backend gpu` for faster inference (if GPU is available):
```powershell
python -m benchmark.run_benchmark --pdf book.pdf --model litert \
    --model-path model.litertlm \
    --model-alias gemma-3n-E2B \
    --backend gpu
```

If GPU fails, fall back to CPU:
```powershell
--backend cpu
```

---

## Debugging Tips

### Verify Model is Cached Correctly

```powershell
# List cached models
lit.exe list

# Check expected filename for an alias
lit.exe list --show_all | Select-String "gemma"
```

### Test lit.exe Directly

```powershell
# Create a test prompt file
echo "<start_of_turn>user`nSay hello`n<end_of_turn>`n<start_of_turn>model" > test.txt

# Run lit.exe directly
lit.exe run gemma-3n-E2B --backend gpu --input_prompt_file test.txt
```

### Check Model File Size

Ensure the cached model has the correct size:
```powershell
Get-ChildItem "$env:USERPROFILE\.litert-lm\models" | Format-Table Name, Length
```

---

## Model Type and Stop Tokens

Different models use different prompt formats and stop tokens:

| Model Type | Stop Token | Prompt Format |
|------------|------------|---------------|
| `gemma` | `<end_of_turn>` | `<start_of_turn>user\n...\n<end_of_turn>\n<start_of_turn>model\n` |
| `chatml` | `<\|im_end\|>` | `<\|im_start\|>user\n...\n<\|im_end\|>\n<\|im_start\|>assistant\n` |
| `qwen3` | `<\|im_end\|>` | Same as chatml |
| `qwen2` | `<\|im_end\|>` | Same as chatml |

Always specify `--model-type` to ensure correct prompt formatting:
```powershell
python -m benchmark.run_benchmark --pdf book.pdf --model litert \
    --model-path model.litertlm \
    --model-alias gemma-3n-E2B \
    --model-type gemma
```

