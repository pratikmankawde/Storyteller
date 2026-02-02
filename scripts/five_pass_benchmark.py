#!/usr/bin/env python3
"""
Comprehensive 5-Pass Character Analysis Benchmark
Tests all passes using exact prompts from the Android app.
Uses llama-server HTTP API for efficient model reuse.
"""

import requests
import time
import json
import re
import sys
import fitz  # PyMuPDF

# Force UTF-8
sys.stdout.reconfigure(encoding='utf-8')

# Configuration - Use llama-server API
SERVER_URL = "http://localhost:8080"
PDF_PATH = r"C:\Users\Pratik\source\Storyteller\Space story.pdf"
OUTPUT_FILE = r"C:\Users\Pratik\source\Storyteller\scripts\five_pass_benchmark_results.txt"

JSON_REMINDER = "\nEnsure the JSON is valid and contains no trailing commas."

def extract_pdf_text():
    """Extract text from PDF."""
    doc = fitz.open(PDF_PATH)
    text = "".join([p.get_text() for p in doc])
    doc.close()
    # Clean non-ASCII for compatibility
    return text.encode('ascii', 'ignore').decode('ascii')

def build_chat_prompt(system_prompt, user_prompt):
    """Build ChatML format with /no_think directive."""
    return f"<|im_start|>system\n{system_prompt} /no_think<|im_end|>\n<|im_start|>user\n{user_prompt}<|im_end|>\n<|im_start|>assistant\n"

def run_llm(prompt, max_tokens=512, temp=0.1):
    """Run LLM via llama-server HTTP API."""
    start = time.time()
    try:
        response = requests.post(
            f"{SERVER_URL}/completion",
            json={
                "prompt": prompt,
                "n_predict": max_tokens,
                "temperature": temp,
                "cache_prompt": True,  # Enable prompt caching for repeated similar prompts
                "stop": ["<|im_end|>", "<|endoftext|>"]
            },
            timeout=120
        )
        elapsed = time.time() - start
        data = response.json()

        output = data.get("content", "")
        # Get timing info from response
        timings = data.get("timings", {})
        pp_speed = timings.get("prompt_per_second", 0)
        tg_speed = timings.get("predicted_per_second", 0)

        # Extract JSON from output (handle nested JSON)
        json_match = re.search(r'\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}', output, re.DOTALL)
        json_output = json_match.group(0) if json_match else ""

        return {
            "elapsed": elapsed,
            "pp_speed": pp_speed,
            "tg_speed": tg_speed,
            "raw_output": output,
            "json_output": json_output,
            "success": bool(json_match)
        }
    except requests.Timeout:
        elapsed = time.time() - start
        return {
            "elapsed": elapsed, "pp_speed": 0, "tg_speed": 0,
            "raw_output": "TIMEOUT", "json_output": "", "success": False
        }
    except Exception as e:
        elapsed = time.time() - start
        return {
            "elapsed": elapsed, "pp_speed": 0, "tg_speed": 0,
            "raw_output": str(e), "json_output": "", "success": False
        }

def validate_json(json_str, required_keys):
    """Validate JSON and check for required keys."""
    try:
        data = json.loads(json_str)
        missing = [k for k in required_keys if k not in data]
        return {"valid": True, "data": data, "missing_keys": missing}
    except json.JSONDecodeError as e:
        return {"valid": False, "error": str(e), "missing_keys": required_keys}

# Pass prompt builders (matching Android app exactly)
def build_pass1_prompt(text):
    system = "You are a character name extraction engine. Extract ONLY character names that appear in the provided text."
    user = f"""STRICT RULES:
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
{JSON_REMINDER}"""
    return build_chat_prompt(system, user)

def build_pass2_prompt(character_name, text):
    system = f'You are a trait extraction engine. Extract ONLY the explicitly stated traits for the character "{character_name}" from the provided text.'
    user = f"""STRICT RULES:
- Extract ONLY traits directly stated or shown in the text
- Include physical descriptions if explicitly mentioned (e.g., "tall", "red hair", "scarred")
- Include behavioral traits if explicitly shown (e.g., "spoke softly", "slammed the door", "laughed nervously")
- Include speech patterns if demonstrated (e.g., "stutters", "uses formal language", "speaks with accent")
- Include emotional states if explicitly described (e.g., "angry", "frightened", "cheerful")
- Do NOT infer personality from actions
- Do NOT add interpretations or assumptions
- Do NOT include traits of other characters
- If no traits are found for this character on this page, return an empty list

OUTPUT FORMAT (valid JSON only):
{{"character": "{character_name}", "traits": ["trait1", "trait2", "trait3"]}}

TEXT:
{text}
{JSON_REMINDER}"""
    return build_chat_prompt(system, user)

def build_pass2_5_prompt(text, character_names):
    chars_json = json.dumps(character_names)
    system = "You are a dialog extraction engine. Extract quoted speech and attribute it to the correct speaker. Output valid JSON only."
    user = f"""CHARACTERS ON THIS PAGE: {chars_json}

EXTRACTION RULES:
1. DIALOGS - Extract text within quotation marks ("..." or '...'):
   - Attribute each dialog to the nearest character name appearing BEFORE or AFTER the quote
   - Use attribution patterns: "said [Name]", "[Name] said", "[Name]:", etc.
   - If speaker cannot be determined, use "Unknown"

2. EMOTION DETECTION - For each segment:
   - Infer emotion: neutral, happy, sad, angry, surprised, fearful, excited, worried, curious, defiant
   - Estimate intensity: 0.0 (very mild) to 1.0 (very intense)

OUTPUT FORMAT (valid JSON only):
{{"dialogs": [{{"speaker": "Name", "text": "dialog", "emotion": "neutral", "intensity": 0.5}}]}}

TEXT:
{text}
{JSON_REMINDER}"""
    return build_chat_prompt(system, user)

def build_pass3_prompt(character_name, traits):
    traits_text = "\n- ".join(traits) if traits else "No explicit traits found."
    traits_json = json.dumps(traits)
    system = f'You are a personality analysis engine. Infer the personality of "{character_name}" based ONLY on the traits provided below.'
    user = f"""TRAITS:
- {traits_text}

STRICT RULES:
- Base your inference ONLY on the provided traits
- Do NOT introduce new traits not in the list
- Do NOT contradict the provided traits
- Synthesize the traits into coherent personality descriptors
- Keep descriptions concise and grounded in the evidence
- Provide 3-5 personality points maximum
- If traits list is empty or insufficient, provide minimal inference

OUTPUT FORMAT (valid JSON only):
{{"character": "{character_name}", "personality": ["personality_point1", "personality_point2", "personality_point3"]}}

TRAITS:
{traits_json}
{JSON_REMINDER}"""
    return build_chat_prompt(system, user)

def build_pass4_prompt(character_name, personality):
    personality_text = "\n- ".join(personality) if personality else "No personality traits inferred."
    personality_json = json.dumps(personality)
    system = f'You are a voice casting director. Suggest a voice profile for "{character_name}" based ONLY on the personality description below.'
    user = f"""PERSONALITY:
- {personality_text}

STRICT RULES:
- Base suggestions ONLY on the provided personality description
- Suggest specific voice qualities: pitch (low/medium/high), speed (slow/medium/fast), tone
- Infer likely gender if personality suggests it
- Infer likely age range if personality suggests it
- Output must be compatible with TTS parameters: pitch (0.5-1.5), speed (0.5-1.5), energy (0.5-1.5)

OUTPUT FORMAT (valid JSON only):
{{
  "character": "{character_name}",
  "voice_profile": {{
    "pitch": 1.0, "speed": 1.0, "energy": 1.0,
    "gender": "male|female|neutral", "age": "young|middle-aged|elderly",
    "tone": "description", "accent": "neutral"
  }}
}}

PERSONALITY:
{personality_json}
{JSON_REMINDER}"""
    return build_chat_prompt(system, user)

def run_benchmark(model_name="Qwen3-4B-Q4_K_M"):
    """Run the complete 5-pass benchmark using llama-server."""
    print("=" * 70)
    print("5-PASS CHARACTER ANALYSIS BENCHMARK")
    print(f"Model: {model_name} (via llama-server)")
    print("=" * 70)

    # Check server health
    try:
        r = requests.get(f"{SERVER_URL}/health", timeout=5)
        if r.json().get("status") != "ok":
            print("ERROR: llama-server not healthy")
            return []
    except:
        print("ERROR: llama-server not running. Start it first.")
        return []

    # Extract text
    print("\nExtracting text from PDF...")
    full_text = extract_pdf_text()
    chapter_text = full_text[:2000]  # Use first 2000 chars
    print(f"Chapter text: {len(chapter_text)} chars")

    model_results = {"model": model_name, "passes": {}}

    # PASS 1: Character Names
    print("\n[PASS 1] Extracting character names...")
    prompt = build_pass1_prompt(chapter_text)
    result = run_llm(prompt, max_tokens=256, temp=0.1)
    validation = validate_json(result["json_output"], ["characters"])
    model_results["passes"]["pass1"] = {**result, "validation": validation}
    characters = validation["data"].get("characters", []) if validation["valid"] else []
    print(f"  Time: {result['elapsed']:.1f}s | PP: {result['pp_speed']:.1f} t/s | TG: {result['tg_speed']:.1f} t/s")
    print(f"  Characters: {characters}")
    print(f"  Valid JSON: {validation['valid']}")

    # PASS 2: Traits for first 2 characters
    print("\n[PASS 2] Extracting traits...")
    pass2_results = []
    for char in characters[:2]:  # Test first 2 characters
        print(f"  - {char}...")
        prompt = build_pass2_prompt(char, chapter_text)
        result = run_llm(prompt, max_tokens=256, temp=0.15)
        validation = validate_json(result["json_output"], ["character", "traits"])
        traits = validation["data"].get("traits", []) if validation["valid"] else []
        pass2_results.append({**result, "character": char, "traits": traits, "validation": validation})
        print(f"    Traits: {traits[:3]}{'...' if len(traits) > 3 else ''}")
    model_results["passes"]["pass2"] = pass2_results

    # PASS 2.5: Dialog extraction
    print("\n[PASS 2.5] Extracting dialogs...")
    prompt = build_pass2_5_prompt(chapter_text, characters)
    result = run_llm(prompt, max_tokens=512, temp=0.1)
    validation = validate_json(result["json_output"], ["dialogs"])
    dialogs = validation["data"].get("dialogs", []) if validation["valid"] else []
    model_results["passes"]["pass2_5"] = {**result, "validation": validation, "dialog_count": len(dialogs)}
    print(f"  Time: {result['elapsed']:.1f}s | Dialogs found: {len(dialogs)}")
    print(f"  Valid JSON: {validation['valid']}")

    # PASS 3: Personality inference (use traits from pass 2)
    print("\n[PASS 3] Inferring personality...")
    pass3_results = []
    for p2 in pass2_results[:2]:
        char = p2["character"]
        traits = p2["traits"]
        print(f"  - {char}...")
        prompt = build_pass3_prompt(char, traits)
        result = run_llm(prompt, max_tokens=256, temp=0.2)
        validation = validate_json(result["json_output"], ["character", "personality"])
        personality = validation["data"].get("personality", []) if validation["valid"] else []
        pass3_results.append({**result, "character": char, "personality": personality, "validation": validation})
        print(f"    Personality: {personality[:2]}{'...' if len(personality) > 2 else ''}")
    model_results["passes"]["pass3"] = pass3_results

    # PASS 4: Voice profile
    print("\n[PASS 4] Suggesting voice profiles...")
    pass4_results = []
    for p3 in pass3_results[:2]:
        char = p3["character"]
        personality = p3["personality"]
        print(f"  - {char}...")
        prompt = build_pass4_prompt(char, personality)
        result = run_llm(prompt, max_tokens=256, temp=0.2)
        validation = validate_json(result["json_output"], ["character", "voice_profile"])
        pass4_results.append({**result, "character": char, "validation": validation})
        if validation["valid"]:
            vp = validation["data"].get("voice_profile", {})
            print(f"    Voice: pitch={vp.get('pitch')}, gender={vp.get('gender')}, age={vp.get('age')}")
    model_results["passes"]["pass4"] = pass4_results

    return [model_results]

def save_results(results):
    """Save detailed results to file."""
    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        f.write("=" * 70 + "\n")
        f.write("5-PASS CHARACTER ANALYSIS BENCHMARK RESULTS\n")
        f.write(f"Date: {time.strftime('%Y-%m-%d %H:%M:%S')}\n")
        f.write("=" * 70 + "\n\n")

        for model_result in results:
            f.write(f"\n{'='*70}\n")
            f.write(f"MODEL: {model_result['model']}\n")
            f.write(f"{'='*70}\n\n")

            passes = model_result["passes"]

            # Pass 1
            p1 = passes["pass1"]
            f.write("[PASS 1] Character Name Extraction\n")
            f.write(f"  Elapsed: {p1['elapsed']:.2f}s\n")
            f.write(f"  PP Speed: {p1['pp_speed']:.1f} t/s\n")
            f.write(f"  TG Speed: {p1['tg_speed']:.1f} t/s\n")
            f.write(f"  Valid JSON: {p1['validation']['valid']}\n")
            f.write(f"  Output: {p1['json_output']}\n\n")

            # Pass 2
            f.write("[PASS 2] Trait Extraction\n")
            for p2 in passes["pass2"]:
                f.write(f"  Character: {p2['character']}\n")
                f.write(f"    Elapsed: {p2['elapsed']:.2f}s\n")
                f.write(f"    Valid JSON: {p2['validation']['valid']}\n")
                f.write(f"    Traits: {p2['traits']}\n")
            f.write("\n")

            # Pass 2.5
            p25 = passes["pass2_5"]
            f.write("[PASS 2.5] Dialog Extraction\n")
            f.write(f"  Elapsed: {p25['elapsed']:.2f}s\n")
            f.write(f"  PP Speed: {p25['pp_speed']:.1f} t/s\n")
            f.write(f"  TG Speed: {p25['tg_speed']:.1f} t/s\n")
            f.write(f"  Valid JSON: {p25['validation']['valid']}\n")
            f.write(f"  Dialogs found: {p25['dialog_count']}\n")
            f.write(f"  Output: {p25['json_output'][:500]}...\n\n")

            # Pass 3
            f.write("[PASS 3] Personality Inference\n")
            for p3 in passes["pass3"]:
                f.write(f"  Character: {p3['character']}\n")
                f.write(f"    Elapsed: {p3['elapsed']:.2f}s\n")
                f.write(f"    Valid JSON: {p3['validation']['valid']}\n")
                f.write(f"    Personality: {p3['personality']}\n")
            f.write("\n")

            # Pass 4
            f.write("[PASS 4] Voice Profile Suggestion\n")
            for p4 in passes["pass4"]:
                f.write(f"  Character: {p4['character']}\n")
                f.write(f"    Elapsed: {p4['elapsed']:.2f}s\n")
                f.write(f"    Valid JSON: {p4['validation']['valid']}\n")
                f.write(f"    Output: {p4['json_output']}\n")
            f.write("\n")

    print(f"\nResults saved to: {OUTPUT_FILE}")

if __name__ == "__main__":
    try:
        results = run_benchmark()
        save_results(results)
        print("\n" + "=" * 70)
        print("BENCHMARK COMPLETE")
        print("=" * 70)
    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()
