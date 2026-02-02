#!/usr/bin/env python3
"""
Test script to run the same LLM inference locally that the Android app uses.
Uses llama-cpp-python to load the Qwen3-1.7B-Q8_0.gguf model and run Pass-1 character extraction.
"""

import sys
import time
from pathlib import Path

# Try to import required libraries
try:
    from llama_cpp import Llama
except ImportError:
    print("ERROR: llama-cpp-python not installed. Run: pip install llama-cpp-python")
    sys.exit(1)

try:
    import fitz  # PyMuPDF
except ImportError:
    print("ERROR: PyMuPDF not installed. Run: pip install pymupdf")
    sys.exit(1)

# Configuration
MODEL_PATH = r"D:\Learning\Ai\Models\Qwen3-1.7B-Q8_0 gguf\Qwen3-1.7B-Q8_0.gguf"
PDF_PATH = r"C:\Users\Pratik\source\Storyteller\Space story.pdf"
OUTPUT_FILE = r"C:\Users\Pratik\source\Storyteller\scripts\llm_test_output.txt"

# Same parameters as the Android app
MAX_TOKENS = 512
TEMPERATURE = 0.1
MAX_INPUT_CHARS = 10000
JSON_VALIDITY_REMINDER = "\nEnsure the JSON is valid and contains no trailing commas."

def extract_text_from_pdf(pdf_path: str) -> str:
    """Extract all text from a PDF file."""
    doc = fitz.open(pdf_path)
    text = ""
    for page in doc:
        text += page.get_text()
    doc.close()
    return text

def build_pass1_prompt(chapter_text: str) -> str:
    """Build the exact same prompt that the Android app uses for Pass-1 character extraction."""
    text = chapter_text[:MAX_INPUT_CHARS]
    if len(chapter_text) > MAX_INPUT_CHARS:
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
{JSON_VALIDITY_REMINDER}"""
    
    # ChatML format with /no_think directive (same as Android app)
    prompt = f"<|im_start|>system\n{system_prompt} /no_think<|im_end|>\n<|im_start|>user\n{user_prompt}<|im_end|>\n<|im_start|>assistant\n"
    return prompt

def main():
    print("=" * 60)
    print("LLM Local Test Script")
    print("=" * 60)
    
    # Check model exists
    if not Path(MODEL_PATH).exists():
        print(f"ERROR: Model not found at {MODEL_PATH}")
        sys.exit(1)
    
    # Extract text from PDF
    print(f"\n1. Extracting text from: {PDF_PATH}")
    pdf_text = extract_text_from_pdf(PDF_PATH)
    print(f"   Extracted {len(pdf_text)} characters")
    
    # Build prompt
    print("\n2. Building Pass-1 prompt...")
    prompt = build_pass1_prompt(pdf_text)
    print(f"   Prompt length: {len(prompt)} characters")
    
    # Load model
    print(f"\n3. Loading model from: {MODEL_PATH}")
    print("   This may take a minute...")
    start_load = time.time()
    llm = Llama(
        model_path=MODEL_PATH,
        n_ctx=4096,  # Context size
        n_gpu_layers=-1,  # Use GPU for all layers
        verbose=True
    )
    load_time = time.time() - start_load
    print(f"   Model loaded in {load_time:.2f} seconds")
    
    # Generate response
    print(f"\n4. Generating response (max_tokens={MAX_TOKENS}, temp={TEMPERATURE})...")
    start_gen = time.time()
    output = llm(
        prompt,
        max_tokens=MAX_TOKENS,
        temperature=TEMPERATURE,
        stop=["<|im_end|>", "<|im_start|>"],
        echo=False
    )
    gen_time = time.time() - start_gen
    
    response_text = output["choices"][0]["text"]
    print(f"   Generation completed in {gen_time:.2f} seconds")
    print(f"   Response length: {len(response_text)} characters")
    
    # Save results
    print(f"\n5. Saving results to: {OUTPUT_FILE}")
    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        f.write("=" * 60 + "\n")
        f.write("LLM Local Test Results\n")
        f.write("=" * 60 + "\n\n")
        f.write(f"Model: {MODEL_PATH}\n")
        f.write(f"PDF: {PDF_PATH}\n")
        f.write(f"Load time: {load_time:.2f}s\n")
        f.write(f"Generation time: {gen_time:.2f}s\n")
        f.write(f"Input chars: {len(pdf_text)}\n")
        f.write(f"Prompt chars: {len(prompt)}\n\n")
        f.write("=" * 60 + "\n")
        f.write("INPUT TEXT (first 2000 chars):\n")
        f.write("=" * 60 + "\n")
        f.write(pdf_text[:2000] + "\n\n")
        f.write("=" * 60 + "\n")
        f.write("LLM RESPONSE:\n")
        f.write("=" * 60 + "\n")
        f.write(response_text + "\n")
    
    print("\n" + "=" * 60)
    print("LLM RESPONSE:")
    print("=" * 60)
    print(response_text)
    print("=" * 60)
    print(f"\nResults saved to: {OUTPUT_FILE}")

if __name__ == "__main__":
    main()

