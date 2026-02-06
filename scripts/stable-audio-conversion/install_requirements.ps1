# Stable Audio Open Small - LiteRT Conversion Dependencies
# PowerShell script for Windows

Write-Host "Installing required packages for Stable Audio model conversion..." -ForegroundColor Green

# AI Edge Torch packages (for PyTorch -> LiteRT conversion)
Write-Host "`nInstalling AI Edge Torch packages..." -ForegroundColor Cyan
pip install ai-edge-torch==0.4.0 `
    "tf-nightly>=2.19.0.dev20250208" `
    "ai-edge-litert-nightly>=1.1.2.dev20250305" `
    "ai-edge-quantizer-nightly>=0.0.1.dev20250208"

# Stable Audio Tools (for loading the model)
Write-Host "`nInstalling Stable Audio Tools..." -ForegroundColor Cyan
pip install "stable_audio_tools==0.0.19"

# PyTorch and core packages (specific versions for compatibility)
Write-Host "`nInstalling PyTorch and core packages..." -ForegroundColor Cyan
pip install --no-deps "torch==2.6.0" `
                      "torchaudio==2.6.0" `
                      "torchvision==0.21.0" `
                      "protobuf==5.29.4" `
                      "numpy==1.26.4"

# ONNX conversion packages (for Conditioners via ONNX -> LiteRT path)
Write-Host "`nInstalling ONNX conversion packages..." -ForegroundColor Cyan
pip install --no-deps "onnx==1.18.0" `
                      "onnxsim==0.4.36" `
                      "onnx2tf==1.27.10" `
                      "tensorflow==2.19.0" `
                      "tf_keras==2.19.0" `
                      "onnx-graphsurgeon==0.5.8" `
                      "ai_edge_litert" `
                      "sng4onnx==1.0.4"

# Transformers for T5 tokenizer
Write-Host "`nInstalling Transformers for T5 tokenizer..." -ForegroundColor Cyan
pip install transformers sentencepiece

# Einops for tensor operations
pip install einops

Write-Host "`n" -NoNewline
Write-Host "=".PadRight(60, "=") -ForegroundColor Green
Write-Host "Installation complete!" -ForegroundColor Green
Write-Host "=".PadRight(60, "=") -ForegroundColor Green
Write-Host "`nNext steps:" -ForegroundColor Yellow
Write-Host "1. Convert Conditioners: python export_conditioners.py --model_config <path> --ckpt_path <path>"
Write-Host "2. Convert DiT/AutoEncoder: python export_dit_autoencoder.py --model_config <path> --ckpt_path <path>"
Write-Host "3. Download tokenizer: curl -L https://huggingface.co/google-t5/t5-base/resolve/main/spiece.model -o spiece.model"

