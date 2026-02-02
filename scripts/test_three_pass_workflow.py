#!/usr/bin/env python3
"""
3-Pass vs 4-Pass Character Analysis Workflow Comparison

Tests the proposed 3-pass workflow (eliminating Pass-2) against the current 4-pass workflow.

3-Pass Workflow:
- Pass-1: Character name extraction (per page, ~10K chars)
- Pass-2.5: Dialog extraction (per page)
- V7_Combined: Traits + Voice Profile (once per character, using aggregated context)

4-Pass Workflow (current):
- Pass-1: Character name extraction (per page)
- Pass-2: Explicit trait extraction (per character, per page)
- Pass-2.5: Dialog extraction (per page)
- V7_Combined: Traits + Voice Profile (once per character, first page only)
"""

import requests
import time
import json
import re
import sys
import fitz  # PyMuPDF

sys.stdout.reconfigure(encoding='utf-8')

# Configuration
SERVER_URL = "http://localhost:8080"
PDF_PATH = r"C:\Users\Pratik\source\Storyteller\Space story.pdf"
OUTPUT_FILE = r"C:\Users\Pratik\source\Storyteller\scripts\three_pass_benchmark_results.txt"
PAGE_SIZE_CHARS = 10000  # Same as app's pageSizeChars
V7_MAX_CONTEXT = 6000   # Max chars for V7_Combined aggregated context

JSON_REMINDER = "\nEnsure the JSON is valid and contains no trailing commas."

# ============================================================================
# HELPERS
# ============================================================================

def extract_pdf_text():
    """Extract full text from PDF."""
    doc = fitz.open(PDF_PATH)
    text = "".join([p.get_text() for p in doc])
    doc.close()
    return text.encode('ascii', 'ignore').decode('ascii')

def split_into_pages(text, page_size=PAGE_SIZE_CHARS):
    """Split text into pages at word boundaries."""
    pages = []
    start = 0
    while start < len(text):
        end = min(start + page_size, len(text))
        if end < len(text):
            segment = text[start:end]
            last_space = segment.rfind(' ')
            if last_space > 0:
                end = start + last_space
        pages.append(text[start:end])
        start = end
    return pages if pages else [text]

def build_chat_prompt(system, user):
    """Build ChatML format with /no_think directive."""
    return f"<|im_start|>system\n{system} /no_think<|im_end|>\n<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n"

def run_llm(prompt, max_tokens=512, temp=0.1):
    """Run LLM via llama-server HTTP API."""
    start = time.time()
    try:
        response = requests.post(
            f"{SERVER_URL}/completion",
            json={"prompt": prompt, "n_predict": max_tokens, "temperature": temp,
                  "cache_prompt": True,  # Enable prompt caching for repeated similar prompts
                  "stop": ["<|im_end|>", "<|endoftext|>"]},
            timeout=120
        )
        elapsed = time.time() - start
        data = response.json()
        output = data.get("content", "")
        timings = data.get("timings", {})

        # Extract JSON
        json_match = re.search(r'\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}', output, re.DOTALL)
        json_output = json_match.group(0) if json_match else ""

        return {
            "elapsed": elapsed,
            "pp_speed": timings.get("prompt_per_second", 0),
            "tg_speed": timings.get("predicted_per_second", 0),
            "output": output,
            "json_output": json_output,
            "success": bool(json_match)
        }
    except Exception as e:
        return {"elapsed": time.time() - start, "pp_speed": 0, "tg_speed": 0,
                "output": str(e), "json_output": "", "success": False}

def validate_json(json_str, required_keys=None):
    """Validate JSON and check for required keys."""
    try:
        data = json.loads(json_str)
        missing = [k for k in (required_keys or []) if k not in data]
        return {"valid": True, "data": data, "missing_keys": missing}
    except json.JSONDecodeError as e:
        return {"valid": False, "error": str(e), "data": {}, "missing_keys": required_keys or []}

# ============================================================================
# PROMPT BUILDERS
# ============================================================================

def build_pass1_prompt(text):
    """Pass-1: Character name extraction."""
    system = "You are a character name extraction engine. Extract ONLY character names that appear in the provided text."
    user = f"""STRICT RULES:
- Extract ONLY proper names explicitly written in the text (e.g., "Harry Potter", "Hermione")
- Do NOT include pronouns, generic descriptions, or group references
- Do NOT include titles alone unless used as the character's actual name
- Include a name only if the character speaks, acts, or is directly described

OUTPUT FORMAT (valid JSON only):
{{"characters": ["Name1", "Name2", "Name3"]}}

TEXT:
{text}
{JSON_REMINDER}"""
    return build_chat_prompt(system, user)

def build_pass2_prompt(character_name, text):
    """Pass-2: Explicit trait extraction (for 4-pass workflow)."""
    system = f'You are a trait extraction engine. Extract ONLY the explicitly stated traits for "{character_name}".'
    user = f"""STRICT RULES:
- Extract ONLY traits directly stated or shown in the text
- Include physical, behavioral, speech patterns, emotional states
- Do NOT infer personality from actions
- If no traits found, return empty list

OUTPUT FORMAT (valid JSON only):
{{"character": "{character_name}", "traits": ["trait1", "trait2"]}}

TEXT:
{text}
{JSON_REMINDER}"""
    return build_chat_prompt(system, user)

def build_pass2_5_prompt(text, character_names):
    """Pass-2.5: Dialog extraction."""
    chars_json = json.dumps(character_names)
    system = "You are a dialog extraction engine. Extract quoted speech and attribute to speakers. JSON only."
    user = f"""CHARACTERS: {chars_json}

RULES:
- Extract text within quotation marks ("..." or '...')
- Attribute each dialog to the nearest character name
- Infer emotion: neutral, happy, sad, angry, surprised, fearful, excited, worried

OUTPUT FORMAT (valid JSON only):
{{"dialogs": [{{"speaker": "Name", "text": "dialog", "emotion": "neutral", "intensity": 0.5}}]}}

TEXT:
{text}
{JSON_REMINDER}"""
    return build_chat_prompt(system, user)

def build_v7_combined(name, text):
    """V7_Combined: Extract traits AND suggest voice profile in ONE call."""
    system = "You are a character analyst for TTS voice casting. Extract observable traits and suggest voice profile. JSON only."
    user = f"""Character: "{name}"

STEP 1 - Extract RICH observable traits from text:

BEHAVIORAL (mannerisms, gestures, habits, movement):
- Examples: "swagger", "nervous fidgeting", "deliberate movements"

SPEECH (voice quality, pacing, patterns, accent):
- Examples: "gravelly voice", "rambling speech", "monotone delivery"

PERSONALITY INDICATORS (confidence, energy, humor, emotional state):
- Examples: "cocky confidence", "anxious energy", "deadpan humor"

STEP 2 - Map traits to voice profile parameters:

TRAIT → VOICE MAPPING:
- "gravelly/deep/commanding" → pitch: 0.8-0.9
- "bright/light/young" → pitch: 1.1-1.2
- "fast-paced/rambling/excited" → speed: 1.1-1.2
- "slow/deliberate/monotone" → speed: 0.8-0.9
- "energetic/dynamic/intense" → energy: 0.9-1.0
- "calm/stoic/reserved" → energy: 0.5-0.7

OUTPUT FORMAT (valid JSON only):
{{
  "character": "{name}",
  "traits": ["trait1", "trait2", "trait3"],
  "voice_profile": {{
    "pitch": 1.0, "speed": 1.0, "energy": 0.7,
    "gender": "male|female",
    "age": "child|young|middle-aged|elderly",
    "tone": "brief description",
    "speaker_id": 45
  }}
}}

SPEAKER_ID GUIDE (VCTK 0-108): Male young 0-20, middle-aged 21-45, elderly 46-55; Female young 56-75, middle-aged 76-95, elderly 96-108

TEXT:
{text[:V7_MAX_CONTEXT]}
{JSON_REMINDER}"""
    return build_chat_prompt(system, user)


# ============================================================================
# 4-PASS WORKFLOW (CURRENT)
# ============================================================================

def run_four_pass_workflow(chapter_text):
    """Run current 4-pass workflow: Pass-1 → Pass-2 → Pass-2.5 → V7_Combined"""
    results = {
        "workflow": "4-pass",
        "total_time": 0,
        "pass_times": {"pass1": 0, "pass2": 0, "pass2_5": 0, "v7_combined": 0},
        "characters": {},
        "dialogs": [],
        "json_validity": {"pass1": 0, "pass2": 0, "pass2_5": 0, "v7_combined": 0},
        "json_total": {"pass1": 0, "pass2": 0, "pass2_5": 0, "v7_combined": 0}
    }

    start_time = time.time()
    pages = split_into_pages(chapter_text)
    print(f"\n[4-PASS] Processing {len(pages)} pages...")

    # Track character appearances per page
    character_page_indices = {}  # name -> [page_indices]
    all_characters = set()

    for page_idx, page_text in enumerate(pages):
        # PASS-1: Extract character names
        prompt = build_pass1_prompt(page_text)
        result = run_llm(prompt, max_tokens=256, temp=0.1)
        results["pass_times"]["pass1"] += result["elapsed"]
        results["json_total"]["pass1"] += 1

        validation = validate_json(result["json_output"], ["characters"])
        page_chars = []
        if validation["valid"]:
            results["json_validity"]["pass1"] += 1
            page_chars = validation["data"].get("characters", [])
            for char in page_chars:
                char_lower = char.lower()
                all_characters.add(char)
                if char_lower not in character_page_indices:
                    character_page_indices[char_lower] = []
                    results["characters"][char_lower] = {"name": char, "pass2_traits": set(), "v7_traits": [], "voice_profile": {}}
                character_page_indices[char_lower].append(page_idx)
        print(f"  Page {page_idx+1} Pass-1: {len(page_chars)} chars ({result['elapsed']:.1f}s)")

        # PASS-2: Extract traits for each character on this page
        for char in page_chars:
            char_lower = char.lower()
            prompt = build_pass2_prompt(char, page_text)
            result = run_llm(prompt, max_tokens=256, temp=0.15)
            results["pass_times"]["pass2"] += result["elapsed"]
            results["json_total"]["pass2"] += 1

            validation = validate_json(result["json_output"], ["traits"])
            if validation["valid"]:
                results["json_validity"]["pass2"] += 1
                traits = validation["data"].get("traits", [])
                results["characters"][char_lower]["pass2_traits"].update(traits)

        # PASS-2.5: Extract dialogs
        prompt = build_pass2_5_prompt(page_text, list(all_characters))
        result = run_llm(prompt, max_tokens=512, temp=0.1)
        results["pass_times"]["pass2_5"] += result["elapsed"]
        results["json_total"]["pass2_5"] += 1

        validation = validate_json(result["json_output"], ["dialogs"])
        if validation["valid"]:
            results["json_validity"]["pass2_5"] += 1
            dialogs = validation["data"].get("dialogs", [])
            results["dialogs"].extend(dialogs)

    # V7_COMBINED: Once per character, using first page only (current behavior)
    first_page = pages[0] if pages else ""
    for char_lower, char_data in results["characters"].items():
        prompt = build_v7_combined(char_data["name"], first_page)
        result = run_llm(prompt, max_tokens=384, temp=0.1)
        results["pass_times"]["v7_combined"] += result["elapsed"]
        results["json_total"]["v7_combined"] += 1

        validation = validate_json(result["json_output"], ["traits", "voice_profile"])
        if validation["valid"]:
            results["json_validity"]["v7_combined"] += 1
            char_data["v7_traits"] = validation["data"].get("traits", [])
            char_data["voice_profile"] = validation["data"].get("voice_profile", {})

        # Convert pass2_traits to list and merge
        char_data["pass2_traits"] = list(char_data["pass2_traits"])
        char_data["all_traits"] = list(set(char_data["pass2_traits"] + char_data["v7_traits"]))
        print(f"  V7_Combined {char_data['name']}: {len(char_data['all_traits'])} traits")

    results["total_time"] = time.time() - start_time
    return results

# ============================================================================
# 3-PASS WORKFLOW (PROPOSED)
# ============================================================================

def run_three_pass_workflow(chapter_text):
    """Run proposed 3-pass workflow: Pass-1 → Pass-2.5 → V7_Combined (with aggregated context)"""
    results = {
        "workflow": "3-pass",
        "total_time": 0,
        "pass_times": {"pass1": 0, "pass2_5": 0, "v7_combined": 0},
        "characters": {},
        "dialogs": [],
        "json_validity": {"pass1": 0, "pass2_5": 0, "v7_combined": 0},
        "json_total": {"pass1": 0, "pass2_5": 0, "v7_combined": 0}
    }

    start_time = time.time()
    pages = split_into_pages(chapter_text)
    print(f"\n[3-PASS] Processing {len(pages)} pages...")

    # Track character appearances and their page texts
    character_pages = {}  # name_lower -> [page_texts where they appear]
    all_characters = set()

    for page_idx, page_text in enumerate(pages):
        # PASS-1: Extract character names
        prompt = build_pass1_prompt(page_text)
        result = run_llm(prompt, max_tokens=256, temp=0.1)
        results["pass_times"]["pass1"] += result["elapsed"]
        results["json_total"]["pass1"] += 1

        validation = validate_json(result["json_output"], ["characters"])
        page_chars = []
        if validation["valid"]:
            results["json_validity"]["pass1"] += 1
            page_chars = validation["data"].get("characters", [])
            for char in page_chars:
                char_lower = char.lower()
                all_characters.add(char)
                if char_lower not in character_pages:
                    character_pages[char_lower] = {"name": char, "pages": []}
                    results["characters"][char_lower] = {"name": char, "v7_traits": [], "voice_profile": {}}
                character_pages[char_lower]["pages"].append(page_text)
        print(f"  Page {page_idx+1} Pass-1: {len(page_chars)} chars ({result['elapsed']:.1f}s)")

        # PASS-2.5: Extract dialogs (NO PASS-2!)
        prompt = build_pass2_5_prompt(page_text, list(all_characters))
        result = run_llm(prompt, max_tokens=512, temp=0.1)
        results["pass_times"]["pass2_5"] += result["elapsed"]
        results["json_total"]["pass2_5"] += 1

        validation = validate_json(result["json_output"], ["dialogs"])
        if validation["valid"]:
            results["json_validity"]["pass2_5"] += 1
            dialogs = validation["data"].get("dialogs", [])
            results["dialogs"].extend(dialogs)

    # V7_COMBINED: Once per character, using AGGREGATED context from all pages
    for char_lower, char_data in results["characters"].items():
        # Aggregate all pages where this character appears
        char_page_texts = character_pages.get(char_lower, {}).get("pages", [])
        aggregated_context = "\n---PAGE BREAK---\n".join(char_page_texts)
        aggregated_context = aggregated_context[:V7_MAX_CONTEXT]

        prompt = build_v7_combined(char_data["name"], aggregated_context)
        result = run_llm(prompt, max_tokens=384, temp=0.1)
        results["pass_times"]["v7_combined"] += result["elapsed"]
        results["json_total"]["v7_combined"] += 1

        validation = validate_json(result["json_output"], ["traits", "voice_profile"])
        if validation["valid"]:
            results["json_validity"]["v7_combined"] += 1
            char_data["v7_traits"] = validation["data"].get("traits", [])
            char_data["voice_profile"] = validation["data"].get("voice_profile", {})

        char_data["all_traits"] = char_data["v7_traits"]
        print(f"  V7_Combined {char_data['name']}: {len(char_data['all_traits'])} traits (ctx: {len(aggregated_context)} chars)")

    results["total_time"] = time.time() - start_time
    return results


# ============================================================================
# COMPARISON AND REPORTING
# ============================================================================

def compare_results(four_pass, three_pass):
    """Compare 4-pass and 3-pass workflow results."""
    print("\n" + "=" * 70)
    print("COMPARISON: 4-PASS vs 3-PASS WORKFLOW")
    print("=" * 70)

    # Time comparison
    time_saved = four_pass["total_time"] - three_pass["total_time"]
    time_saved_pct = (time_saved / four_pass["total_time"]) * 100 if four_pass["total_time"] > 0 else 0

    print(f"\n[TIME COMPARISON]")
    print(f"  4-Pass Total: {four_pass['total_time']:.1f}s")
    print(f"  3-Pass Total: {three_pass['total_time']:.1f}s")
    print(f"  Time Saved:   {time_saved:.1f}s ({time_saved_pct:.1f}%)")

    print(f"\n[PASS BREAKDOWN]")
    print(f"  {'Pass':<15} {'4-Pass':>10} {'3-Pass':>10} {'Diff':>10}")
    print(f"  {'-'*45}")

    all_passes = ["pass1", "pass2", "pass2_5", "v7_combined"]
    for p in all_passes:
        t4 = four_pass["pass_times"].get(p, 0)
        t3 = three_pass["pass_times"].get(p, 0)
        diff = t3 - t4
        print(f"  {p:<15} {t4:>9.1f}s {t3:>9.1f}s {diff:>+9.1f}s")

    # Trait comparison
    print(f"\n[TRAIT COMPARISON]")
    print(f"  {'Character':<20} {'4-Pass':>10} {'3-Pass':>10} {'Diff':>10}")
    print(f"  {'-'*50}")

    total_4p_traits = 0
    total_3p_traits = 0

    for char_lower in four_pass["characters"]:
        char_4p = four_pass["characters"].get(char_lower, {})
        char_3p = three_pass["characters"].get(char_lower, {})

        t4 = len(char_4p.get("all_traits", []))
        t3 = len(char_3p.get("all_traits", []))
        total_4p_traits += t4
        total_3p_traits += t3
        diff = t3 - t4

        name = char_4p.get("name", char_lower)[:18]
        print(f"  {name:<20} {t4:>10} {t3:>10} {diff:>+10}")

    trait_retention = (total_3p_traits / total_4p_traits * 100) if total_4p_traits > 0 else 0
    print(f"  {'-'*50}")
    print(f"  {'TOTAL':<20} {total_4p_traits:>10} {total_3p_traits:>10} ({trait_retention:.0f}% retained)")

    # JSON validity comparison
    print(f"\n[JSON VALIDITY]")
    for p in all_passes:
        valid_4 = four_pass["json_validity"].get(p, 0)
        total_4 = four_pass["json_total"].get(p, 0)
        valid_3 = three_pass["json_validity"].get(p, 0)
        total_3 = three_pass["json_total"].get(p, 0)

        pct_4 = (valid_4 / total_4 * 100) if total_4 > 0 else 0
        pct_3 = (valid_3 / total_3 * 100) if total_3 > 0 else 0

        if total_4 > 0 or total_3 > 0:
            print(f"  {p:<15} 4-Pass: {valid_4}/{total_4} ({pct_4:.0f}%) | 3-Pass: {valid_3}/{total_3} ({pct_3:.0f}%)")

    # Voice profile comparison
    print(f"\n[VOICE PROFILE COMPARISON]")
    print(f"  {'Character':<20} {'Param':<8} {'4-Pass':>8} {'3-Pass':>8}")
    print(f"  {'-'*46}")

    for char_lower in four_pass["characters"]:
        char_4p = four_pass["characters"].get(char_lower, {})
        char_3p = three_pass["characters"].get(char_lower, {})

        vp_4 = char_4p.get("voice_profile", {})
        vp_3 = char_3p.get("voice_profile", {})

        name = char_4p.get("name", char_lower)[:18]
        for param in ["pitch", "speed", "energy", "gender", "age"]:
            v4 = vp_4.get(param, "N/A")
            v3 = vp_3.get(param, "N/A")
            print(f"  {name if param == 'pitch' else '':<20} {param:<8} {str(v4):>8} {str(v3):>8}")

    return {
        "time_saved_seconds": time_saved,
        "time_saved_percent": time_saved_pct,
        "trait_retention_percent": trait_retention,
        "total_4p_traits": total_4p_traits,
        "total_3p_traits": total_3p_traits
    }

def save_results(four_pass, three_pass, comparison):
    """Save detailed results to file."""
    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        f.write("=" * 70 + "\n")
        f.write("3-PASS vs 4-PASS WORKFLOW BENCHMARK RESULTS\n")
        f.write(f"Date: {time.strftime('%Y-%m-%d %H:%M:%S')}\n")
        f.write("=" * 70 + "\n\n")

        f.write("SUMMARY\n")
        f.write("-" * 40 + "\n")
        f.write(f"4-Pass Total Time: {four_pass['total_time']:.1f}s\n")
        f.write(f"3-Pass Total Time: {three_pass['total_time']:.1f}s\n")
        f.write(f"Time Saved: {comparison['time_saved_seconds']:.1f}s ({comparison['time_saved_percent']:.1f}%)\n")
        f.write(f"Trait Retention: {comparison['trait_retention_percent']:.1f}%\n")
        f.write(f"Characters Analyzed: {len(four_pass['characters'])}\n\n")

        f.write("SUCCESS CRITERIA CHECK\n")
        f.write("-" * 40 + "\n")
        time_ok = 25 <= comparison['time_saved_percent'] <= 35
        trait_ok = comparison['trait_retention_percent'] >= 90
        f.write(f"[{'✓' if time_ok else '✗'}] Time reduced 25-35%: {comparison['time_saved_percent']:.1f}%\n")
        f.write(f"[{'✓' if trait_ok else '✗'}] Trait retention ≥90%: {comparison['trait_retention_percent']:.1f}%\n\n")

        f.write("PASS TIME BREAKDOWN\n")
        f.write("-" * 40 + "\n")
        f.write(f"{'Pass':<15} {'4-Pass':>10} {'3-Pass':>10}\n")
        for p in ["pass1", "pass2", "pass2_5", "v7_combined"]:
            t4 = four_pass["pass_times"].get(p, 0)
            t3 = three_pass["pass_times"].get(p, 0)
            f.write(f"{p:<15} {t4:>9.1f}s {t3:>9.1f}s\n")
        f.write("\n")

        f.write("CHARACTER DETAILS (4-PASS)\n")
        f.write("-" * 40 + "\n")
        for char_lower, data in four_pass["characters"].items():
            f.write(f"\n{data['name']}:\n")
            f.write(f"  Pass-2 Traits: {data.get('pass2_traits', [])}\n")
            f.write(f"  V7 Traits: {data.get('v7_traits', [])}\n")
            f.write(f"  All Traits: {data.get('all_traits', [])}\n")
            f.write(f"  Voice Profile: {data.get('voice_profile', {})}\n")

        f.write("\n\nCHARACTER DETAILS (3-PASS)\n")
        f.write("-" * 40 + "\n")
        for char_lower, data in three_pass["characters"].items():
            f.write(f"\n{data['name']}:\n")
            f.write(f"  V7 Traits: {data.get('v7_traits', [])}\n")
            f.write(f"  Voice Profile: {data.get('voice_profile', {})}\n")

        f.write("\n\nRECOMMENDATION\n")
        f.write("-" * 40 + "\n")
        if time_ok and trait_ok:
            f.write("✓ PROCEED with 3-pass implementation in Android app.\n")
            f.write("  - Use Option A: Concatenate all pages where character appears (up to 6000 chars)\n")
        elif trait_ok and not time_ok:
            f.write("⚠ 3-pass is faster but not within target range.\n")
            f.write("  - Consider proceeding if trait quality is acceptable.\n")
        else:
            f.write("✗ DO NOT proceed with 3-pass implementation.\n")
            f.write("  - Trait retention is below acceptable threshold.\n")

    print(f"\nResults saved to: {OUTPUT_FILE}")

# ============================================================================
# MAIN
# ============================================================================

def main():
    """Run the 3-pass vs 4-pass workflow comparison."""
    print("=" * 70)
    print("3-PASS vs 4-PASS WORKFLOW BENCHMARK")
    print("=" * 70)

    # Check server health
    try:
        r = requests.get(f"{SERVER_URL}/health", timeout=5)
        if r.json().get("status") != "ok":
            print("ERROR: llama-server not healthy")
            return
        print("✓ llama-server is running")
    except:
        print("ERROR: llama-server not running. Start it first:")
        print('  llama-server.exe -m "path/to/qwen3-1.7b-q4_k_m.gguf" -c 4096 --port 8080')
        return

    # Extract text
    print("\nExtracting text from PDF...")
    full_text = extract_pdf_text()
    print(f"Total text: {len(full_text)} chars")

    # Use first chapter (approximately)
    chapter_text = full_text[:30000]  # First ~30K chars (3 pages)
    print(f"Chapter text: {len(chapter_text)} chars")

    # Run both workflows
    print("\n" + "=" * 70)
    print("RUNNING 4-PASS WORKFLOW (CURRENT)")
    print("=" * 70)
    four_pass_results = run_four_pass_workflow(chapter_text)

    print("\n" + "=" * 70)
    print("RUNNING 3-PASS WORKFLOW (PROPOSED)")
    print("=" * 70)
    three_pass_results = run_three_pass_workflow(chapter_text)

    # Compare and report
    comparison = compare_results(four_pass_results, three_pass_results)

    # Save results
    save_results(four_pass_results, three_pass_results, comparison)

    print("\n" + "=" * 70)
    print("BENCHMARK COMPLETE")
    print("=" * 70)

if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()
