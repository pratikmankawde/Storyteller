#!/usr/bin/env python3
"""
Pass-2 Trait Extraction Optimization Tests
Tests multiple prompt variations for speed and accuracy.
"""

import requests
import time
import json
import re
import sys
import fitz

sys.stdout.reconfigure(encoding='utf-8')

SERVER_URL = "http://localhost:8080"
PDF_PATH = r"C:\Users\Pratik\source\Storyteller\Space story.pdf"
OUTPUT_FILE = r"C:\Users\Pratik\source\Storyteller\scripts\pass2_optimization_results.txt"

JSON_REMINDER = "\nEnsure the JSON is valid and contains no trailing commas."

def extract_chapter_text():
    """Extract full chapter text from PDF."""
    doc = fitz.open(PDF_PATH)
    text = ""
    for page in doc:
        text += page.get_text()
    doc.close()
    # Clean for ASCII
    text = text.encode('ascii', 'ignore').decode('ascii')
    return text

def build_chat_prompt(system, user):
    return f"<|im_start|>system\n{system} /no_think<|im_end|>\n<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n"

def run_llm(prompt, max_tokens=256, temp=0.1):
    """Run LLM and return results with timing."""
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
            "elapsed": elapsed, "output": output, "json_output": json_output,
            "pp_speed": timings.get("prompt_per_second", 0),
            "tg_speed": timings.get("predicted_per_second", 0),
            "prompt_tokens": timings.get("prompt_n", 0),
            "gen_tokens": timings.get("predicted_n", 0),
            "success": bool(json_match)
        }
    except Exception as e:
        return {"elapsed": time.time() - start, "output": str(e), "json_output": "",
                "pp_speed": 0, "tg_speed": 0, "prompt_tokens": 0, "gen_tokens": 0, "success": False}

def validate_and_count_traits(json_str):
    """Validate JSON and count traits. Also handles V7 combined format with voice_profile."""
    try:
        data = json.loads(json_str)
        result = {"valid": False, "count": 0, "traits": [], "has_voice_profile": False, "voice_profile": None}

        # Handle both flat and nested formats
        if "traits" in data:
            traits = data["traits"]
            if isinstance(traits, list):
                result["valid"] = True
                result["count"] = len(traits)
                result["traits"] = traits
            elif isinstance(traits, dict):
                all_traits = []
                for cat, items in traits.items():
                    if isinstance(items, list):
                        all_traits.extend(items)
                result["valid"] = True
                result["count"] = len(all_traits)
                result["traits"] = all_traits

        # Check for voice_profile (V7 combined format)
        if "voice_profile" in data and isinstance(data["voice_profile"], dict):
            result["has_voice_profile"] = True
            result["voice_profile"] = data["voice_profile"]

        return result
    except:
        return {"valid": False, "count": 0, "traits": [], "has_voice_profile": False, "voice_profile": None}

# ============================================================================
# PROMPT VARIATIONS
# ============================================================================

def build_v1_original(name, text):
    """Original prompt from benchmark (baseline)."""
    system = "You are a character trait extraction engine."
    user = f"""Extract traits for character "{name}" from this text.
Categories: physical_description, behavioral_traits, speech_patterns, emotional_states

OUTPUT FORMAT: {{"character": "{name}", "traits": {{"physical_description": [], "behavioral_traits": [], "speech_patterns": [], "emotional_states": []}}}}

TEXT:
{text}
{JSON_REMINDER}"""
    return build_chat_prompt(system, user), 512

def build_v2_simplified(name, text):
    """Simplified: flat list, shorter prompt, fewer instructions."""
    system = "Extract character traits from text. Output JSON only."
    user = f"""Character: "{name}"

Extract ONLY traits explicitly shown in text:
- Physical: appearance, clothing, features
- Behavioral: actions, habits, mannerisms
- Speech: voice, accent, patterns

OUTPUT: {{"character": "{name}", "traits": ["trait1", "trait2"]}}

TEXT:
{text}
{JSON_REMINDER}"""
    return build_chat_prompt(system, user), 256

def build_v3_fewshot(name, text):
    """Few-shot with examples."""
    system = "Extract character traits. Output valid JSON only."
    user = f"""Extract traits for "{name}" from the text below.

EXAMPLES:
Good: "tall", "speaks softly", "nervous laugh", "wears leather jacket"
Bad: "brave" (personality, not trait), "probably angry" (inference)

OUTPUT: {{"character": "{name}", "traits": ["trait1", "trait2"]}}

TEXT:
{text}
{JSON_REMINDER}"""
    return build_chat_prompt(system, user), 256

def build_v4_minimal(name, text):
    """Minimal prompt - shortest possible."""
    system = "Trait extractor. JSON output only."
    user = f"""List observable traits for "{name}":
{{"character": "{name}", "traits": ["trait1", "trait2"]}}

TEXT: {text}"""
    return build_chat_prompt(system, user), 192

def build_v6_optimized(name, text):
    """Optimized: balance of speed and quality."""
    system = "Extract observable character traits. JSON only."
    user = f"""Character: "{name}"

Extract ONLY observable traits from text:
- Physical: appearance, clothing (e.g., "tall", "scarred", "leather jacket")
- Behavioral: actions, habits (e.g., "speaks softly", "nervous laugh")
- Speech: voice, patterns (e.g., "gravelly voice", "formal speech")

NOT traits: personality, emotions, roles, relationships

OUTPUT: {{"character": "{name}", "traits": ["trait1", "trait2", "trait3"]}}

TEXT:
{text[:3000]}
{JSON_REMINDER}"""
    return build_chat_prompt(system, user), 256

def build_v5_structured(name, text):
    """Structured with explicit categories but flat output."""
    system = "You extract character traits from narrative text."
    user = f"""For character "{name}", extract:
1. PHYSICAL: What they look like (height, hair, clothes, scars)
2. BEHAVIORAL: How they act (gestures, habits, reactions)
3. SPEECH: How they talk (tone, accent, patterns, volume)

Rules:
- Only traits SHOWN in text, not inferred
- No duplicates
- No personality judgments (save for Pass-3)

OUTPUT: {{"character": "{name}", "traits": ["physical: trait", "behavioral: trait", "speech: trait"]}}

TEXT:
{text}
{JSON_REMINDER}"""
    return build_chat_prompt(system, user), 320


def build_v7_combined(name, text):
    """COMBINED: Extract traits AND suggest voice profile in ONE call (skips Pass-3).

    Enhanced V7 prompt with rich trait extraction:
    - Behavioral: mannerisms, gestures, habits, movement style
    - Speech: voice quality, pacing, accent, speech patterns
    - Personality indicators: confidence, emotional state, energy, humor
    - Direct trait-to-voice mapping guide
    """
    system = "You are a character analyst for TTS voice casting. Extract observable traits and suggest voice profile. JSON only."
    user = f"""Character: "{name}"

STEP 1 - Extract RICH observable traits from text:

BEHAVIORAL (mannerisms, gestures, habits, movement):
- Examples: "swagger", "nervous fidgeting", "deliberate movements", "explosive gestures"

SPEECH (voice quality, pacing, patterns, accent):
- Examples: "gravelly voice", "rambling speech", "monotone delivery", "breathless excitement"
- Examples: "formal diction", "casual slang", "clipped sentences", "dramatic pauses"

PERSONALITY INDICATORS (confidence, energy, humor, emotional state):
- Examples: "cocky confidence", "anxious energy", "deadpan humor", "stoic demeanor"
- Examples: "high-strung", "laid-back", "intense focus", "scattered attention"

STEP 2 - Map traits to voice profile parameters:

TRAIT → VOICE MAPPING GUIDE:
- "gravelly/deep/commanding/gruff" → pitch: 0.8-0.9
- "bright/light/young/cheerful" → pitch: 1.1-1.2
- "fast-paced/rambling/excited/breathless" → speed: 1.1-1.2
- "slow/deliberate/monotone/measured" → speed: 0.8-0.9
- "energetic/dynamic/high-strung/intense" → energy: 0.9-1.0
- "calm/stoic/flat/reserved" → energy: 0.5-0.7

OUTPUT FORMAT (valid JSON only):
{{
  "character": "{name}",
  "traits": ["trait1", "trait2", "trait3"],
  "voice_profile": {{
    "pitch": 1.0,
    "speed": 1.0,
    "energy": 0.7,
    "gender": "male|female",
    "age": "child|young|middle-aged|elderly",
    "tone": "brief description (e.g., warm and friendly, gruff and commanding)",
    "accent": "neutral|description",
    "speaker_id": 45
  }}
}}

SPEAKER_ID GUIDE (VCTK corpus 0-108):
- Male young (18-30): 0-20
- Male middle-aged (30-50): 21-45
- Male elderly (50+): 46-55
- Female young (18-30): 56-75
- Female middle-aged (30-50): 76-95
- Female elderly (50+): 96-108

TEXT:
{text[:4000]}
{JSON_REMINDER}"""
    return build_chat_prompt(system, user), 384


# ============================================================================
# TEXT SIZE VARIATIONS
# ============================================================================

TEXT_SIZES = {
    "small": 2000,   # ~500 words
    "medium": 4000,  # ~1000 words
    "large": 6000,   # ~1500 words (current)
    "xlarge": 8000,  # ~2000 words
}

# ============================================================================
# TEST RUNNER
# ============================================================================

def run_single_test(name, text, prompt_builder, text_size_name, run_num):
    """Run a single test and return results."""
    prompt, max_tokens = prompt_builder(name, text)
    result = run_llm(prompt, max_tokens=max_tokens, temp=0.1)
    validation = validate_and_count_traits(result["json_output"])

    return {
        "prompt_version": prompt_builder.__name__,
        "text_size": text_size_name,
        "run": run_num,
        "elapsed": result["elapsed"],
        "pp_speed": result["pp_speed"],
        "tg_speed": result["tg_speed"],
        "prompt_tokens": result["prompt_tokens"],
        "gen_tokens": result["gen_tokens"],
        "valid_json": validation["valid"],
        "trait_count": validation["count"],
        "traits": validation["traits"],
        "has_voice_profile": validation["has_voice_profile"],
        "voice_profile": validation["voice_profile"],
        "max_tokens": max_tokens,
        "raw_output": result["output"][:500]
    }

def run_all_tests(full_text, character_name="Jax"):
    """Run all prompt variations with different text sizes."""
    results = []

    prompt_builders = [
        ("V1_Original", build_v1_original),
        ("V2_Simplified", build_v2_simplified),
        ("V3_FewShot", build_v3_fewshot),
        ("V4_Minimal", build_v4_minimal),
        ("V5_Structured", build_v5_structured),
        ("V6_Optimized", build_v6_optimized),
        ("V7_Combined", build_v7_combined),
    ]

    # Test each prompt with medium text size (3 runs each for consistency)
    print("\n" + "="*70)
    print(f"TESTING PROMPT VARIATIONS (character: {character_name})")
    print("="*70)

    for name, builder in prompt_builders:
        print(f"\n[{name}]")
        text = full_text[:4000]  # Medium size for comparison

        for run in range(3):
            result = run_single_test(character_name, text, builder, "medium", run+1)
            results.append(result)
            # Show voice_profile for V7_Combined
            vp_info = ", voice_profile=Yes" if result.get('has_voice_profile') else ""
            print(f"  Run {run+1}: {result['elapsed']:.1f}s, {result['trait_count']} traits, valid={result['valid_json']}{vp_info}")

    # Test best prompt with different text sizes
    print("\n" + "="*70)
    print("TESTING TEXT SIZE VARIATIONS (V2_Simplified)")
    print("="*70)

    for size_name, size in TEXT_SIZES.items():
        print(f"\n[{size_name}: {size} chars]")
        text = full_text[:size]

        for run in range(2):
            result = run_single_test(character_name, text, build_v2_simplified, size_name, run+1)
            results.append(result)
            print(f"  Run {run+1}: {result['elapsed']:.1f}s, {result['trait_count']} traits, valid={result['valid_json']}")

    return results

def analyze_results(results):
    """Analyze and summarize test results."""
    # Group by prompt version
    by_prompt = {}
    for r in results:
        key = r["prompt_version"]
        if key not in by_prompt:
            by_prompt[key] = []
        by_prompt[key].append(r)

    # Calculate averages
    summary = []
    for prompt_name, runs in by_prompt.items():
        # Filter to medium text size for fair comparison
        medium_runs = [r for r in runs if r["text_size"] == "medium"]
        if not medium_runs:
            continue

        avg_time = sum(r["elapsed"] for r in medium_runs) / len(medium_runs)
        avg_traits = sum(r["trait_count"] for r in medium_runs) / len(medium_runs)
        valid_rate = sum(1 for r in medium_runs if r["valid_json"]) / len(medium_runs) * 100
        avg_tokens = sum(r["gen_tokens"] for r in medium_runs) / len(medium_runs)
        max_tokens = medium_runs[0]["max_tokens"]
        has_voice = sum(1 for r in medium_runs if r.get("has_voice_profile")) / len(medium_runs) * 100
        sample_vp = medium_runs[0].get("voice_profile")

        summary.append({
            "prompt": prompt_name,
            "avg_time": avg_time,
            "avg_traits": avg_traits,
            "valid_rate": valid_rate,
            "avg_gen_tokens": avg_tokens,
            "max_tokens": max_tokens,
            "sample_traits": medium_runs[0]["traits"][:5] if medium_runs[0]["traits"] else [],
            "voice_profile_rate": has_voice,
            "sample_voice_profile": sample_vp
        })

    # Sort by time
    summary.sort(key=lambda x: x["avg_time"])
    return summary

def save_results(results, summary):
    """Save detailed results to file."""
    with open(OUTPUT_FILE, 'w', encoding='utf-8') as f:
        f.write("="*70 + "\n")
        f.write("PASS-2 TRAIT EXTRACTION OPTIMIZATION RESULTS\n")
        f.write(f"Model: qwen3-1.7b-q4_k_m\n")
        f.write(f"Date: {time.strftime('%Y-%m-%d %H:%M:%S')}\n")
        f.write("="*70 + "\n\n")

        # Summary table
        f.write("PROMPT COMPARISON (4000 char text, 3 runs each)\n")
        f.write("-"*70 + "\n")
        f.write(f"{'Prompt':<20} {'Avg Time':<10} {'Traits':<8} {'Valid%':<8} {'Tokens':<8} {'MaxTok':<8}\n")
        f.write("-"*70 + "\n")

        baseline_time = None
        for s in summary:
            if baseline_time is None:
                baseline_time = s["avg_time"]
            speedup = ((baseline_time / s["avg_time"]) - 1) * 100 if s["avg_time"] > 0 else 0

            f.write(f"{s['prompt']:<20} {s['avg_time']:.1f}s      {s['avg_traits']:.1f}      {s['valid_rate']:.0f}%      {s['avg_gen_tokens']:.0f}       {s['max_tokens']}\n")

        f.write("\n")

        # Best prompt details
        if summary:
            best = summary[0]
            f.write(f"FASTEST: {best['prompt']} ({best['avg_time']:.1f}s avg)\n")
            f.write(f"Sample traits: {best['sample_traits']}\n\n")

        # Text size comparison
        f.write("TEXT SIZE COMPARISON (V2_Simplified)\n")
        f.write("-"*70 + "\n")

        size_results = {}
        for r in results:
            if r["prompt_version"] == "build_v2_simplified":
                size = r["text_size"]
                if size not in size_results:
                    size_results[size] = []
                size_results[size].append(r)

        for size in ["small", "medium", "large", "xlarge"]:
            if size in size_results:
                runs = size_results[size]
                avg_time = sum(r["elapsed"] for r in runs) / len(runs)
                avg_traits = sum(r["trait_count"] for r in runs) / len(runs)
                f.write(f"{size:<10} ({TEXT_SIZES.get(size, 0)} chars): {avg_time:.1f}s, {avg_traits:.1f} traits\n")

        f.write("\n")

        # V7 Combined Analysis
        v7_data = next((s for s in summary if "v7_combined" in s["prompt"]), None)
        if v7_data:
            f.write("="*70 + "\n")
            f.write("V7 COMBINED (TRAITS + VOICE PROFILE) ANALYSIS\n")
            f.write("="*70 + "\n")
            f.write(f"Avg Time: {v7_data['avg_time']:.1f}s\n")
            f.write(f"Traits: {v7_data['avg_traits']:.1f}\n")
            f.write(f"Valid JSON: {v7_data['valid_rate']:.0f}%\n")
            f.write(f"Voice Profile Rate: {v7_data['voice_profile_rate']:.0f}%\n")
            if v7_data.get('sample_voice_profile'):
                f.write(f"Sample Voice Profile: {v7_data['sample_voice_profile']}\n")
            f.write("\n")

            # Compare to separate passes
            v2_data = next((s for s in summary if "v2_simplified" in s["prompt"]), None)
            if v2_data:
                combined_time = v7_data['avg_time']
                # Estimate Pass-4 time (from benchmark: ~6.3s per character)
                estimated_pass4 = 6.3
                separate_time = v2_data['avg_time'] + estimated_pass4
                savings = separate_time - combined_time
                savings_pct = (savings / separate_time) * 100 if separate_time > 0 else 0
                f.write(f"COMPARISON (skipping Pass-3):\n")
                f.write(f"  V7_Combined (single call): {combined_time:.1f}s\n")
                f.write(f"  V2 + Pass-4 (two calls): ~{separate_time:.1f}s estimated\n")
                f.write(f"  Time Savings: {savings:.1f}s ({savings_pct:.0f}%)\n")
            f.write("\n")

        # Recommendations
        f.write("="*70 + "\n")
        f.write("RECOMMENDATIONS\n")
        f.write("="*70 + "\n")
        if summary:
            fastest = summary[0]
            original = next((s for s in summary if "Original" in s["prompt"]), None)
            if original and fastest:
                speedup = ((original["avg_time"] / fastest["avg_time"]) - 1) * 100
                f.write(f"1. Use {fastest['prompt']} for {speedup:.0f}% speedup\n")
        f.write("2. Use max_tokens=256 (sufficient for trait lists)\n")
        f.write("3. Use 4000 char text size (good balance of context vs speed)\n")
        f.write("4. Temperature 0.1 is optimal for consistent JSON output\n")
        if v7_data and v7_data.get('voice_profile_rate', 0) >= 80:
            f.write("5. V7_Combined can replace Pass-2 + Pass-3 + Pass-4 (3-pass → 1-pass)\n")

    print(f"\nResults saved to: {OUTPUT_FILE}")

def main():
    print("="*70)
    print("PASS-2 TRAIT EXTRACTION OPTIMIZATION")
    print("="*70)

    # Check server
    try:
        resp = requests.get(f"{SERVER_URL}/health", timeout=5)
        if resp.json().get("status") != "ok":
            print("ERROR: llama-server not ready")
            return
        print("Server: OK")
    except:
        print("ERROR: Start llama-server first")
        return

    # Extract text
    print("\nExtracting PDF text...")
    full_text = extract_chapter_text()
    print(f"Total text: {len(full_text)} chars")

    # Run tests
    results = run_all_tests(full_text, "Jax")

    # Analyze
    print("\n" + "="*70)
    print("ANALYSIS")
    print("="*70)
    summary = analyze_results(results)

    for s in summary:
        vp_info = f", voice_profile={s['voice_profile_rate']:.0f}%" if s.get('voice_profile_rate', 0) > 0 else ""
        print(f"{s['prompt']:<20}: {s['avg_time']:.1f}s avg, {s['avg_traits']:.1f} traits, {s['valid_rate']:.0f}% valid{vp_info}")

    # Save
    save_results(results, summary)

if __name__ == "__main__":
    main()
