 Benchmark Comparison: 0.6B CustomVoice vs 0.6B Base Model

   Summary Table

   | Metric                      | 0.6B Base Model     | 0.6B CustomVoice    | Difference                     |
   | --------------------------- | ------------------- | ------------------- | ------------------------------ |
   | Model File Size             | ~2.52 GB            | ~2.52 GB            | Same                           |
   | Prompts Tested              | 43                  | 59                  | Different test sets            |
   | Success Rate                | 100%                | 100%                | Equal                          |
   | Average RTF                 | 11.32               | 7.39                | CustomVoice 35% faster         |
   | Avg Generation Time         | 102.63s             | 109.73s             | +7% longer                     |
   | Total Audio Generated       | 402.96s             | 885.84s             | -                              |
   | Audio per Gen Time          | 0.091s/s            | 0.137s/s            | CustomVoice 50% more efficient |
   | GPU VRAM                    | 6.0 GB (fully used) | 6.0 GB (fully used) | Same                           |
   | Memory Delta (first prompt) | 0.0 MB              | 8.1 MB              | Minimal                        |

   Detailed Analysis

   1. Generation Speed (RTF - Real-Time Factor)

   | Model       | Average RTF | Interpretation                       |
   | ----------- | ----------- | ------------------------------------ |
   | Base        | 11.32       | Takes ~11.3s to generate 1s of audio |
   | CustomVoice | 7.39        | Takes ~7.4s to generate 1s of audio  |

   Finding: The CustomVoice model is ~35% faster in terms of RTF. This means:
     • To generate 1 minute of audio: Base = ~11 min, CustomVoice = ~7.4 min
     • The instruction processing in CustomVoice appears to be more efficient than the voice cloning process in Base

   2. Memory Usage (GPU VRAM)

   Both models fully utilize the 6GB GTX 1060 VRAM. The memory delta during generation is minimal (~8 MB for CustomVoice), indicating:
     • Both models have similar memory footprints (~2.5GB model weights)
     • Working memory for inference is similar
     • Both fit comfortably in 6GB VRAM

   3. Generation Time Analysis

   | Metric                        | Base    | CustomVoice |
   | ----------------------------- | ------- | ----------- |
   | Avg Gen Time per prompt       | 102.63s | 109.73s     |
   | Avg Audio Duration per prompt | 9.37s   | 15.01s      |
   | Throughput (audio/gen time)   | 0.091   | 0.137       |

   Finding: CustomVoice generates 50% more audio per second of computation despite the prompts being longer. This correlates with the better RTF.


   ─────────────────────────────────────────────────────────────────────

   Mobile Deployment Feasibility Assessment

   Hardware Requirements for Qwen3-TTS 0.6B

   | Requirement       | Desktop (Current) | Mobile Minimum         | Recommendation                    |
   | ----------------- | ----------------- | ---------------------- | --------------------------------- |
   | Model Size (disk) | ~2.52 GB          | 1.3-2.5 GB (quantized) | 1.0-1.5 GB (INT8)                 |
   | RAM               | 6 GB GPU VRAM     | 4-6 GB available RAM   | 8+ GB device RAM                  |
   | Compute           | GTX 1060 GPU      | NPU/GPU accelerator    | Snapdragon 8 Gen 2+ or Apple A15+ |
   | Inference Time    | 7.4× real-time    | 1× real-time target    | Challenging                       |

   Available Optimized Versions

   | Format             | Status          | Platform           | Notes                      |
   | ------------------ | --------------- | ------------------ | -------------------------- |
   | MLX (4-bit, 8-bit) | ✅ Available     | Apple Silicon only | NOT for Android            |
   | GGUF (Q5_K_M)      | ✅ Available     | CPU (llama.cpp)    | Experimental, ~1.5GB       |
   | ONNX               | ❌ Not Official  | Cross-platform     | Needs manual export        |
   | TensorFlow Lite    | ❌ Not Available | Android/iOS        | Not supported              |
   | Core ML            | ❌ Not Available | iOS                | Not supported              |
   | MNN (Alibaba)      | ⚠️ Possible      | Android/iOS        | Manual conversion needed   |
   | Sherpa-ONNX        | ⚠️ Not Yet       | Cross-platform     | Other TTS models supported |

   Expected Inference Latency on Mobile

   Based on the 7.4× RTF on GTX 1060 and mobile hardware comparisons:

   | Device             | Processor               | Estimated RTF        | 10s Audio Gen Time |
   | ------------------ | ----------------------- | -------------------- | ------------------ |
   | Desktop (GTX 1060) | CUDA GPU                | 7.4×                 | 74s                |
   | iPhone 15 Pro      | A17 Pro (Neural Engine) | 15-25× (estimated)   | 2.5-4 min          |
   | Samsung S24 Ultra  | Snapdragon 8 Gen 3      | 20-35× (estimated)   | 3.5-6 min          |
   | Mid-range Android  | Snapdragon 778G         | 50-80× (estimated)   | 8-13 min           |
   | Budget Android     | Helio G99               | 100-150× (estimated) | 17-25 min          |

   Practicality Assessment

   | Use Case                      | On-Device 0.6B            | Server-Side      | Recommendation      |
   | ----------------------------- | ------------------------- | ---------------- | ------------------- |
   | Real-time audiobook narration | ❌ Not practical           | ✅ Viable         | Server-side         |
   | Pre-generate chapters         | ⚠️ Possible (minutes wait) | ✅ Better         | Server with caching |
   | Short phrases (<5s)           | ⚠️ 30-60s wait             | ✅ <10s           | Server or hybrid    |
   | Offline capability            | ✅ Primary benefit         | ❌ Needs internet | Trade-off           |

   Recommendation for Novel Reader Application

   Server-side inference is strongly recommended for the following reasons:

     1. Latency: Current 0.6B model takes ~7.4× real-time even on a desktop GPU. Mobile would be 15-80× slower.

     2. Battery: Continuous neural network inference drains battery rapidly.

     3. User Experience: Waiting 3-10 minutes to generate 1 minute of audio is not acceptable for real-time narration.

     4. No optimized mobile format: Unlike models like Whisper (which have Sherpa-ONNX support), Qwen3-TTS doesn't have official ONNX/mobile exports yet.

   Suggested Architecture


   | Novel Reader App                               |
   | ---------------------------------------------- |
   | 1. User selects chapter                        |
   | 2. App sends text + voice style to server      |
   | 3. Server (GPU) generates audio with Qwen3-TTS |
   | 4. Audio streamed/cached to device             |
   | 5. User plays pre-generated or streaming audio |

   Server Options:
   - Cloud GPU (AWS/GCP/Azure with T4/A10G)
   - Self-hosted with NVIDIA GPU
   - Serverless GPU (Modal, Replicate, RunPod)

   Future Possibilities

   | Development            | Timeline     | Impact                                      |
   | ---------------------- | ------------ | ------------------------------------------- |
   | Official ONNX export   | Unknown      | Would enable Sherpa-ONNX on mobile          |
   | INT4/INT8 quantization | Likely 2026  | 50-70% size reduction                       |
   | Streaming synthesis    | If supported | Could start playback before full generation |
   | NPU-optimized version  | Unknown      | Qualcomm/Apple NPU acceleration             |

  ---------

   Summary

   | Question                 | Answer                                      |
   | ------------------------ | ------------------------------------------- |
   | Which model is faster?   | CustomVoice (35% better RTF)                |
   | Memory difference?       | Negligible (both use ~6GB)                  |
   | Can 0.6B run on mobile?  | Technically yes, but too slow for real-time |
   | Best mobile strategy?    | Server-side inference with caching          |
   | Official mobile formats? | Not yet available                           |
   | Expected mobile latency? | 3-10+ minutes per minute of audio           |


 Research Results Summary

   1. Cloud GPU Provider Pricing Comparison

   | Provider  | GPU                  | Price/Hour | Price/Second | Notes                                |
   | --------- | -------------------- | ---------- | ------------ | ------------------------------------ |
   | Modal     | T4                   | $0.81      | $0.000225    | Per-second billing, $30/mo free tier |
   | Modal     | A10G                 | $1.10      | $0.000306    | Per-second billing                   |
   | Modal     | A100 (80GB)          | $5.04      | $0.001400    | Per-second billing                   |
   | Replicate | T4                   | $0.81      | $0.000225    | Billed per-second, pay per use       |
   | Replicate | L40S                 | $3.51      | $0.000975    | 48GB VRAM                            |
   | Replicate | H100                 | $5.49      | $0.001525    | 80GB VRAM                            |
   | RunPod    | T4                   | ~$0.16     | -            | Community Cloud (lowest)             |
   | RunPod    | A100 (80GB)          | $1.75      | -            | Secure Cloud                         |
   | RunPod    | H100                 | ~$4.31     | -            | Enterprise pricing                   |
   | Beam      | T4, A10G, A100, H100 | Varies     | -            | $30 free tier, ~2-3s cold start      |

   2. Estimated Costs for Your Audiobook TTS Workload

   Based on your benchmark results (CustomVoice model, RTF ~7.39):
     • 1 minute of audio takes ~7.4 minutes to generate

   | Provider       | GPU Needed  | Cost per 1 min audio | Cost per 1 hour audiobook |
   | -------------- | ----------- | -------------------- | ------------------------- |
   | Modal (T4)     | T4 (16GB)   | $0.10                | $6.00                     |
   | Replicate (T4) | T4          | $0.10                | $6.00                     |
   | RunPod (T4)    | T4          | $0.02                | $1.20                     |
   | Modal (A10G)   | A10G (24GB) | $0.14                | $8.10                     |

   Recommendation: For your 0.6B model (~6GB VRAM), a T4 GPU is sufficient. RunPod Community Cloud offers the best value at ~$0.02 per minute of audio generated.

   3. ONNX Export Feasibility

   | Aspect           | Status            | Details                                               |
   | ---------------- | ----------------- | ----------------------------------------------------- |
   | ONNX Library     | ✅ Available       | v1.20.1 installed                                     |
   | torch.onnx       | ✅ Available       | PyTorch 2.6.0+cu124                                   |
   | torch.export     | ✅ Available       | For ExecuTorch mobile                                 |
   | Model Structure  | ⚠️ Complex         | Qwen3TTSForConditionalGeneration with 905M parameters |
   | Inner Model      | ✅ torch.nn.Module | Potentially exportable                                |
   | Optimum Library  | ❌ Not installed   | Would need manual setup                               |
   | Official Support | ❌ None            | No official ONNX export from Qwen team                |

   Key Findings:
     • The model IS a torch.nn.Module with 905M parameters
     • It consists of a Qwen3TTSTalkerForConditionalGeneration as the core component
     • ONNX export is technically possible but requires significant effort:
        1. Complex multi-component architecture (text encoder + tokenizer + vocoder)
        2. Custom audio processing layers
        3. Dynamic sequence lengths
        4. No official support or examples

   4. Deployment Recommendations for Novel Reader App

   | Strategy                        | Best For         | Latency             | Cost             |
   | ------------------------------- | ---------------- | ------------------- | ---------------- |
   | Server-side (RunPod Serverless) | Production       | ~7-10s per sentence | ~$0.02/min audio |
   | Server-side (Modal)             | Development      | ~7-10s per sentence | ~$0.10/min audio |
   | Pre-generate + Cache            | Static content   | Instant playback    | Storage only     |
   | Hybrid                          | Dynamic + static | Varies              | Optimized        |

   Recommended Architecture:
   Mobile App
       │
       ▼
   [Cache Layer] ──── Pre-generated common content
       │
       ▼
   [API Gateway] ──── Rate limiting, auth
       │
       ▼
   [RunPod Serverless] ──── 0.6B CustomVoice model on T4 GPU
       │
       ▼
   [Audio CDN] ──── Cached generated audio

   Cost Estimate for a 10-hour audiobook:
     • Generation time: ~74 hours GPU time
     • RunPod T4: ~$12-15 total
     • Modal T4: ~$60 total
     • Replicate T4: ~$60 total
