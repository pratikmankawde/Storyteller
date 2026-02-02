#!/usr/bin/env python3
"""
Test the Pass-1 character extraction prompt using llama-cli.
Uses the exact same prompt format as the Android app.
"""

import subprocess
import time
import fitz  # PyMuPDF

# Paths
MODEL_PATH = r"D:\Learning\Ai\Models\LLM\Qwen3-1.7B-Q8_0 gguf\Qwen3-1.7B-Q8_0.gguf"
PDF_PATH = r"C:\Users\Pratik\source\Storyteller\Space story.pdf"
LLAMA_CLI = r"C:\Users\Pratik\source\Storyteller\scripts\llama-cpp\llama-cli.exe"
OUTPUT_FILE = r"C:\Users\Pratik\source\Storyteller\scripts\llm_test_output.txt"
PROMPT_FILE = r"C:\Users\Pratik\source\Storyteller\scripts\pass1_prompt.txt"

# Extract text from PDF
print("Extracting text from PDF...")
doc = fitz.open(PDF_PATH)
chapter_text = ""
for page in doc:
    chapter_text += page.get_text()
doc.close()
print(f"Extracted {len(chapter_text)} characters from PDF")

# Build the exact Pass-1 prompt (same as Qwen3Model.kt)
max_input_chars = 10000
json_validity_reminder = "\nEnsure the JSON is valid and contains no trailing commas."

text = chapter_text[:max_input_chars]
if len(chapter_text) > max_input_chars:
    text += "\n[...truncated]"

system_prompt = "You are a character name extraction engine. Extract ONLY character names that appear in the provided text."

user_prompt = f"""STRICT RULES:
- Extract ONLY proper names explicitly written in the text (e.g., "Harry Potter", "Hermione", "Mr. Dursley")
- Do NOT include pronouns (he, she, they, etc.)
- Do NOT include generic descriptions (the boy, the woman, the teacher)
- Do NOT include group references (the family, the crowd, the students)
- Do NOT include titles alone (Professor, Sir, Madam) unless used as the character's actual name
- Do NOT infer or guess names not explicitly mentioned
- Do NOT split full names: if "Harry Potter" appears, do NOT list "Potter" separately
- Do NOT include names of characters who are only mentioned but not present/acting in the scene
- Include a name only if the character speaks, acts, or is directly described in this specific page

OUTPUT FORMAT (valid JSON only):
{{"characters": ["Name1", "Name2", "Name3"]}}

TEXT:
{text}
{json_validity_reminder}"""

# Build ChatML format with /no_think directive (same as app)
full_prompt = f"<|im_start|>system\n{system_prompt} /no_think<|im_end|>\n<|im_start|>user\n{user_prompt}<|im_end|>\n<|im_start|>assistant\n"

print(f"Prompt length: {len(full_prompt)} characters")

# Save prompt to file for llama-cli
with open(PROMPT_FILE, "w", encoding="utf-8") as f:
    f.write(full_prompt)
print(f"Prompt saved to: {PROMPT_FILE}")

# Run llama-cli
print("\n" + "="*60)
print("Running llama-cli with Pass-1 prompt...")
print("="*60)

start_time = time.time()

cmd = [
    LLAMA_CLI,
    "-m", MODEL_PATH,
    "-f", PROMPT_FILE,
    "-n", "512",           # max tokens (same as app)
    "--temp", "0.1",       # temperature (same as app)
    "-c", "4096",          # context size
    "--no-display-prompt", # don't echo the prompt
]

print(f"Command: {' '.join(cmd)}")
print("\nLLM Output:")
print("-"*60)

result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")

elapsed = time.time() - start_time

output = result.stdout
stderr = result.stderr

print(output)
print("-"*60)
print(f"\nElapsed time: {elapsed:.2f} seconds")

if result.returncode != 0:
    print(f"\nError (returncode={result.returncode}):")
    print(stderr)

# Save results
with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
    f.write(f"=== Pass-1 Character Extraction Test ===\n")
    f.write(f"Model: {MODEL_PATH}\n")
    f.write(f"PDF: {PDF_PATH}\n")
    f.write(f"Prompt length: {len(full_prompt)} chars\n")
    f.write(f"Elapsed time: {elapsed:.2f} seconds\n")
    f.write(f"\n=== LLM Output ===\n")
    f.write(output)
    if stderr:
        f.write(f"\n=== Stderr ===\n")
        f.write(stderr)

print(f"\nResults saved to: {OUTPUT_FILE}")

