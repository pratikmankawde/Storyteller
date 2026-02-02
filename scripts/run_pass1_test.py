#!/usr/bin/env python3
"""Run Pass-1 character extraction test with full chapter text."""
import fitz
import subprocess
import time
import sys

# Force UTF-8 output
sys.stdout.reconfigure(encoding='utf-8')

MODEL = r"D:\Learning\Ai\Models\LLM\Qwen3-1.7B-Q8_0 gguf\Qwen3-1.7B-Q8_0.gguf"
LLAMA_CLI = r"C:\Users\Pratik\source\Storyteller\scripts\llama-cpp\llama-cli.exe"

# Extract text from PDF
doc = fitz.open("Space story.pdf")
full_text = "".join([p.get_text() for p in doc])
doc.close()

# Clean text - remove special unicode chars that might cause issues
text = full_text[:2000].encode('ascii', 'ignore').decode('ascii')

print(f"Text length: {len(text)} chars")

# Build prompt (same format as Android app)
prompt = f"""<|im_start|>system
You are a character name extraction engine. Extract ONLY character names. /no_think<|im_end|>
<|im_start|>user
STRICT RULES:
- Extract ONLY proper names explicitly written in the text
- Do NOT include pronouns or generic descriptions
- Output valid JSON only

Format: {{"characters": ["Name1", "Name2"]}}

TEXT:
{text}
<|im_end|>
<|im_start|>assistant
"""

# Save prompt
with open("scripts/pass1_test.txt", "w", encoding="utf-8") as f:
    f.write(prompt)

print(f"Prompt length: {len(prompt)} chars")
print("Running llama-cli...")
print("-" * 50)

start = time.time()
result = subprocess.run([
    LLAMA_CLI,
    "-m", MODEL,
    "-f", "scripts/pass1_test.txt",
    "-n", "256",
    "--temp", "0.1",
    "-c", "4096",
    "--no-display-prompt"
], capture_output=True, text=True, encoding="utf-8", errors="replace")
elapsed = time.time() - start

print(result.stdout)
print("-" * 50)
print(f"Elapsed: {elapsed:.1f} seconds")

if result.returncode != 0:
    print(f"Return code: {result.returncode}")
    
# Save output
with open("scripts/pass1_output.txt", "w", encoding="utf-8") as f:
    f.write(f"Elapsed: {elapsed:.1f}s\n\n")
    f.write(result.stdout)
print(f"\nOutput saved to scripts/pass1_output.txt")

