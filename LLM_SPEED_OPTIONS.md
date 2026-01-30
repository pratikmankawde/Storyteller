# LLM Speed Options

Ways to increase the speed of LLM operations in Storyteller (Qwen via llama.cpp).

## Already in place

- **GPU offload**: `n_gpu_layers` is set from device config (API 24+, sufficient heap → -1 = all layers on GPU when backend supports it).
- **Batch size**: `n_batch = 2048` for faster prompt decode (processes more tokens per batch).
- **CPU threads**: Uses up to `n_cores - 1` (or all cores on small devices) for inference, up to 8.
- **Quantized model**: `qwen2.5-3b-instruct-q4_k_m.gguf` (Q4_K_M) for smaller size and faster inference.
- **Context size**: 2048 tokens (balanced; larger = slower and more memory).

## Further options (no code change)

1. **Lighter quantization**  
   Use Q4_K_S or Q3_K_S instead of Q4_K_M: faster and smaller, small quality drop.

2. **Shorter prompts**  
   Trim system prompts and chapter excerpts where possible; less input = faster.

3. **Lower max_tokens where acceptable**  
   Tasks already use 256–2048; reduce only if output length allows (e.g. short outputs like name lists use 256; character+traits per segment use 512).

4. **Smaller model**  
   e.g. Qwen2.5-1.5B instead of 3B: faster and less memory, lower quality.

## Optional code/build changes

5. **Flash attention (GPU)**  
   If your llama.cpp build and `llama.h` expose `flash_attn_type`, set it to enabled when using GPU for faster attention on supported backends.

6. **Increase `n_ubatch`**  
   Try 1024 instead of 512 in `llama_jni.cpp` for more throughput on capable devices (watch memory).

7. **`use_mlock` on high-memory devices**  
   Enable only when heap is large (e.g. > 512 MB); can reduce swap and improve latency, but risks OOM on low-memory devices.

8. **Vulkan/OpenCL build**  
   Build llama.cpp with Vulkan or OpenCL so GPU offload is actually used; otherwise `n_gpu_layers` has no effect.

9. **Speculative decoding**  
   Use a small draft model + Qwen for faster generation if your llama.cpp version and build support it (more involved).
