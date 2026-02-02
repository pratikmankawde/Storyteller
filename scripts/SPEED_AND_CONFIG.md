# Making the benchmark faster & config tuning

## 1. Benchmark speed (Python + lit.exe)

### Already optimal
- **Session mode** (`--session`): One model load; all prompts sent via stdin. Use it.
- **GPU backend**: `LITERT_BACKEND=gpu` (default in script is `cpu`; set env or use a wrapper that sets it).
- **Session + GPU** gives the best throughput for multi-pass runs.

### Things you can change

| What | Effect |
|------|--------|
| **Backend** | `gpu` is fastest on most PCs; on devices with NPU (e.g. Google Tensor, some Qualcomm), try `LITERT_BACKEND=npu` for Gemma 3n. |
| **Segment size** | In `benchmark_gemma3n_litertlm.py`, `PASS1_TARGET_TOKENS=3000` and `PASS2_TARGET_TOKENS=1500`. Smaller values → shorter responses per call → faster per call, but more calls. Larger → fewer calls, longer wait per call. Decode speed is ~15–27 tok/s (GPU), so fewer output tokens per turn helps. |
| **Run less** | Use `--max-chapters 1` to process only the first chapter. |

### What the script does *not* control
- The **lit** CLI (lit.exe) does not expose `max_tokens`, `temperature`, or `topK`/`topP`. Those are fixed inside the runtime. So we cannot cap output length or change sampling from the benchmark script.

---

## 2. Your JSON config (app / model card)

The config you have is for the **app** (or a model card that an app reads), not for the **lit** CLI. So:

- **lit.exe** used by the benchmark does **not** read this JSON.
- When your **Android app** (or another runtime) uses LiteRT-LM with this model, it can use these values if the app passes them into the engine.

### Suggested “best” config for extraction quality + speed

For **structured extraction** (characters, dialogs), you want more deterministic, less rambling output. That usually means lower temperature and slightly tighter sampling:

```json
"defaultConfig": {
  "topK": 48,
  "topP": 0.9,
  "temperature": 0.6,
  "maxTokens": 4096,
  "accelerators": "gpu,cpu"
}
```

| Parameter | Your value | Suggested | Why |
|-----------|------------|-----------|-----|
| **temperature** | 1.0 | **0.5–0.7** | Lower = more deterministic, less wandering, often faster convergence. Good for extraction. |
| **topP** | 0.95 | **0.9** | Slightly tighter distribution; can reduce tokens per response. |
| **topK** | 64 | **40–48** | Fewer candidates per step; small speed gain, usually similar quality for structured tasks. |
| **maxTokens** | 4096 | **4096** (or 2048 if app supports “max output”) | 4096 is context size. If the app interprets this as “max output tokens”, lowering to 2048 can stop generation earlier and speed up. |
| **accelerators** | "cpu,gpu" | **"gpu,cpu"** | Prefer GPU first so the engine uses GPU when available. On devices with NPU: **"npu,gpu,cpu"** for Gemma 3n. |

So: **yes, you can fine-tune further** — mainly by lowering `temperature` and optionally `topP`/`topK`, and by preferring GPU (or NPU where supported).

### Is this the “best” config?

- **For the benchmark (lit.exe)**: The script cannot apply this JSON; “best” there = session + GPU + optional segment-size tweaks.
- **For the app**: The above is a better default for extraction than `temperature: 1.0` and `topP: 0.95`; you can tune in 0.1 steps (e.g. try 0.5, 0.6, 0.7 for temperature) and compare quality vs speed.

---

## 3. Summary

| Goal | Action |
|------|--------|
| **Faster benchmark** | Use `--session`, `LITERT_BACKEND=gpu`, and optionally reduce `PASS1_TARGET_TOKENS` / `PASS2_TARGET_TOKENS` in the script. |
| **Faster / better app runs** | In the JSON config: lower `temperature` (e.g. 0.6), `topP` 0.9, `topK` 40–48; use `accelerators`: `"gpu,cpu"` or `"npu,gpu,cpu"`. |
| **Best config possible?** | No single “best” for every device; the suggested config is a better default for extraction and can be fine-tuned (e.g. temperature 0.5–0.7, topK 40–64) per use case. |
