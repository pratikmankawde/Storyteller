# Stable Audio Open Small - LiteRT Conversion

This folder contains scripts to convert the StabilityAI Stable Audio Open Small model to LiteRT format
for on-device SFX generation in the Storyteller Android app.

## ⚠️ Platform Requirements

**IMPORTANT:** The `ai-edge-torch` package required for DiT/AutoEncoder conversion **only works on Linux or macOS**.
Windows is NOT supported for the full conversion pipeline due to missing `ai-edge-tensorflow` wheels.

**Recommended Setup:**
- **Linux** (Ubuntu 20.04+ recommended) or **macOS**
- **Python 3.10** (other versions may have compatibility issues)
- **WSL2** on Windows (run the conversion inside WSL2 Ubuntu)

## Prerequisites

1. **Python 3.10** (required for ai-edge-torch compatibility)
2. **Model Files** downloaded from HuggingFace:
   - `model.ckpt` and `model_config.json` from `stabilityai/stable-audio-open-small`
   - Located at: `D:\Learning\Ai\Models\Sounds\StabilityAI\OpenSmall`

## Option 1: Use Pre-converted Models (Recommended)

If you already have the pre-converted TFLite models, skip to the **Android Integration** section below.

Pre-converted models can be obtained from:
- [Arm ML-examples releases](https://github.com/Arm-Examples/ML-examples/tree/main/kleidiai-examples/audiogen)

## Option 2: Convert Models Yourself

### Setup (Linux/macOS/WSL2)

#### Step 1: Create Virtual Environment

```bash
# On Linux/macOS/WSL2
python3.10 -m venv .venv
source .venv/bin/activate
```

#### Step 2: Install Dependencies

```bash
# Upgrade pip
pip install --upgrade pip

# PyTorch (CPU)
pip install torch==2.6.0 torchaudio==2.6.0 --index-url https://download.pytorch.org/whl/cpu

# Stable Audio Tools (without strict deps)
pip install --no-deps stable_audio_tools==0.0.19

# Core dependencies
pip install einops transformers sentencepiece huggingface_hub

# AI Edge Torch (requires Linux/macOS)
pip install ai-edge-torch==0.4.0 tensorflow==2.19.0

# ONNX conversion packages
pip install onnx onnx2tf onnxsim onnx-graphsurgeon sng4onnx
```

### Model Conversion

#### Step 3: Convert Conditioners (T5 Encoder)

```bash
python export_conditioners.py \
    --model_config "/path/to/model_config.json" \
    --ckpt_path "/path/to/model.ckpt"
```

Output: `conditioners_tflite/conditioners_float32.tflite`

#### Step 4: Convert DiT and AutoEncoder

```bash
python export_dit_autoencoder.py \
    --model_config "/path/to/model_config.json" \
    --ckpt_path "/path/to/model.ckpt"
```

Outputs:
- `dit_model.tflite` - Diffusion Transformer
- `autoencoder_model.tflite` - AutoEncoder Decoder
- `autoencoder_encoder_model.tflite` - AutoEncoder Encoder (for audio-to-audio)

## Output Files

After conversion, you should have these files:
- `conditioners_tflite/conditioners_float32.tflite` (~300MB)
- `dit_model.tflite` (~200MB)
- `autoencoder_model.tflite` (~50MB)
- `autoencoder_encoder_model.tflite` (~50MB, optional)
- `spiece.model` (~800KB) - T5 tokenizer (already downloaded)

## Android Integration

Copy the converted models to:
- App assets: `app/src/main/assets/models/stable-audio/`
- Or external storage: `/sdcard/Download/stable-audio/`

Required files for Android:
1. `conditioners_float32.tflite`
2. `dit_model.tflite`
3. `autoencoder_model.tflite`
4. `spiece.model`

## References

- [Arm Learning Path](https://learn.arm.com/learning-paths/mobile-graphics-and-gaming/run-stable-audio-open-small-with-lite-rt/)
- [Arm ML-examples](https://github.com/Arm-Examples/ML-examples/tree/main/kleidiai-examples/audiogen)
- [HuggingFace Model](https://huggingface.co/stabilityai/stable-audio-open-small)

