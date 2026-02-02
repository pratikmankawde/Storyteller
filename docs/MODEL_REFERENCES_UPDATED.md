# Model References Update Summary
**Date:** 2026-01-30

## ✅ Changes Made

All documentation has been updated to remove references to old/incorrect models and ensure consistency with the actual models being used in the codebase.

---

## 🔄 Model Corrections

### LLM Model

**❌ OLD (Removed):**
- Qwen2.5-3B-Instruct
- Qwen3-4B-Q4_K_M
- qwen2.5-3b-instruct-q4_k_m.gguf
- References to llama.cpp/GGUF integration

**✅ CURRENT (Correct):**
- **Qwen3-1.7B-Q4-ONNX**
- Integration: ONNX Runtime GenAI
- Location: Downloads/Qwen3-1.7B-Q4-ONNX/
- Context: 32K tokens (using 10K chars for mobile)
- Quantization: 4-bit (Q4)

### TTS Model

**❌ OLD (Removed):**
- SherpaTTS VITS-VCTK (generic reference)
- References to "SherpaTTS Android engine"
- vits-piper-en_GB-vctk-medium (replaced)

**✅ CURRENT (Correct):**
- **vits-piper-en_US-libritts-high**
- Integration: Sherpa-ONNX
- Location: app/src/main/assets/models/tts/sherpa/
- Speakers: 904 (LibriTTS dataset)
- Sample Rate: 22050 Hz

---

## 📝 Files Updated

### 1. augTaskList.json
**Line 9:** Updated context length description
- Before: `"32K tokens (Qwen2.5 family supports 128K, but using 10K chars for mobile performance)"`
- After: `"32K tokens (Qwen3 supports up to 128K, but using 10K chars for mobile performance)"`

### 2. tasklist.json
**Lines 832-833:** Updated model integration notes
- Before: `"Qwen3-4B-Q4_K_M model integrated with llama.cpp JNI"`
- After: `"Qwen3-1.7B-Q4-ONNX model integrated with ONNX Runtime GenAI"`

- Before: `"SherpaTTS VITS-VCTK model integrated with ONNX Runtime"`
- After: `"vits-piper-en_US-libritts-high model integrated with Sherpa-ONNX"`

**Line 834:** Updated prompt format
- Before: `"<|im_start|>system<|im_sep|>...<|im_end|>"`
- After: `"<|im_start|>system<|im_end|><|im_start|>user<|im_end|><|im_start|>assistant"`

### 3. FEATURE_COMPLIANCE_ANALYSIS.md
**Line 33:** Removed incorrect GGUF model reference
- Removed: `"✅ GGUF model support (qwen2.5-3b-instruct-q4_k_m.gguf)"`
- Kept: `"✅ ONNX model support (Qwen3-1.7B-Q4-ONNX)"`

---

## ✅ Verified Clean Files

The following files were checked and confirmed to have NO references to old models:

- ✅ IMPLEMENTATION_GUIDE.md
- ✅ IMPLEMENTATION_PLAN_SUMMARY.md
- ✅ TECHNICAL_SPECIFICATIONS.md
- ✅ QUICK_REFERENCE.md
- ✅ COMPREHENSIVE_ISSUES_SUMMARY.md
- ✅ TASKLIST_VERIFICATION.md
- ✅ CODEBASE_ISSUES.md
- ✅ ACTION_ITEMS.md

All these files already used the correct model names:
- Qwen3-1.7B-Q4-ONNX
- vits-piper-en_US-libritts-high (Sherpa-ONNX)

---

## 📋 Current Model Configuration (Verified)

### LLM: Qwen3-1.7B-Q4-ONNX

```json
{
  "model": "Qwen3-1.7B-Q4-ONNX",
  "framework": "ONNX Runtime GenAI",
  "location": "Downloads/Qwen3-1.7B-Q4-ONNX/",
  "context_length": "32K tokens",
  "practical_limit": "10K chars (mobile performance)",
  "quantization": "4-bit (Q4)",
  "parameters": "1.7 billion",
  "capabilities": [
    "JSON extraction",
    "Character analysis",
    "Dialog extraction",
    "Emotion detection",
    "Story generation"
  ]
}
```

### TTS: vits-piper-en_US-libritts-high

```json
{
  "model": "vits-piper-en_US-libritts-high",
  "framework": "Sherpa-ONNX",
  "location": "app/src/main/assets/models/tts/sherpa/",
  "speakers": 904,
  "sample_rate": 22050,
  "architecture": "VITS (Piper variant)",
  "phoneme_backend": "espeak-ng",
  "capabilities": [
    "Multi-speaker synthesis (904 voices)",
    "Speed control (0.5x - 2.0x)"
  ],
  "limitations": [
    "NO runtime pitch control",
    "NO runtime energy control",
    "NO emotion presets"
  ],
  "workarounds": [
    "Pitch: Use different speaker IDs",
    "Energy: Post-process audio samples",
    "Emotion: Combine speed + speaker selection"
  ]
}
```

---

## 🔍 External References (Not Updated)

The following files contain references to old models but are **external dependencies** and should NOT be modified:

1. **FullFeatured app Instructions.md**
   - Original specification document
   - References Qwen3-4B-Q4_K_M and SherpaTTS
   - Keep as-is (historical reference)

2. **app/.cxx/Debug/.../llama.cpp-src/docs/**
   - llama.cpp library documentation
   - Contains Qwen2.5 benchmark examples
   - External dependency, not part of our codebase

---

## ✅ Verification Complete

All documentation now correctly references:
- ✅ **Qwen3-1.7B-Q4-ONNX** (LLM)
- ✅ **vits-piper-en_US-libritts-high** (TTS)
- ✅ **ONNX Runtime GenAI** (LLM framework)
- ✅ **Sherpa-ONNX** (TTS framework)

No references to:
- ❌ Qwen2.5-3B-Instruct
- ❌ Qwen3-4B-Q4_K_M
- ❌ llama.cpp/GGUF (for LLM)
- ❌ Generic "SherpaTTS VITS-VCTK"
- ❌ vits-piper-en_GB-vctk-medium (replaced with libritts-high)

---

## 📊 Summary

- **Files Updated:** 3
- **Files Verified Clean:** 8
- **External Files (Not Modified):** 2
- **Total Documentation Files:** 13

**Status:** ✅ All model references corrected and verified
