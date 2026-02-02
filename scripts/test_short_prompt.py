#!/usr/bin/env python3
import fitz
import subprocess
import time

# Extract first 1500 chars from PDF
doc = fitz.open("Space story.pdf")
text = "".join([page.get_text() for page in doc])[:1500]
doc.close()

system_prompt = "You are a character name extraction engine. Extract ONLY character names."
user_prompt = f"""Extract character names from this text. Output JSON only.
{{"characters": ["Name1", "Name2"]}}

TEXT:
{text}"""

full_prompt = f"<|im_start|>system\n{system_prompt} /no_think<|im_end|>\n<|im_start|>user\n{user_prompt}<|im_end|>\n<|im_start|>assistant\n"

with open("scripts/short_prompt.txt", "w", encoding="utf-8") as f:
    f.write(full_prompt)

print(f"Prompt length: {len(full_prompt)} chars")
print("Running llama-cli...")

start = time.time()
result = subprocess.run([
    r"C:\Users\Pratik\source\Storyteller\scripts\llama-cpp\llama-cli.exe",
    "-m", r"D:\Learning\Ai\Models\LLM\Qwen3-1.7B-Q8_0 gguf\Qwen3-1.7B-Q8_0.gguf",
    "-f", "scripts/short_prompt.txt",
    "-n", "256",
    "--temp", "0.1",
    "-c", "4096",
    "--no-display-prompt"
], capture_output=True, text=True, encoding="utf-8")
elapsed = time.time() - start

print(f"\nOutput ({elapsed:.1f}s):")
print("-" * 40)
print(result.stdout)
if result.stderr:
    print("Stderr:", result.stderr[:500])

