#!/usr/bin/env python3
"""
2-Pass Segment-Based Character Analysis with Context Preservation

NEW 2-PASS ARCHITECTURE:
- PASS 1: Segment-based extraction (characters + dialogs + traits in ONE call per segment)
- PASS 2: Voice profile matching (ONE call per character)

SEGMENTATION:
- Split text into 4000-character segments (trimmed to last complete sentence)
- Process segments sequentially with context accumulation
- Pass accumulated character list to each segment for speaker attribution

Also includes legacy 3-pass workflow for comparison.
"""

import requests
import time
import json
import re
import sys
import argparse
import fitz  # PyMuPDF

sys.stdout.reconfigure(encoding='utf-8')

# Parse command-line arguments
def parse_args():
    parser = argparse.ArgumentParser(description='Character Analysis Benchmark (2-pass or 3-pass)')
    parser.add_argument('--pdf', type=str, default=r"C:\Users\Pratik\Downloads\HP First3Chapters.pdf",
                        help='Path to the PDF file to analyze')
    parser.add_argument('--output', type=str, default=r"C:\Users\Pratik\source\Storyteller\scripts\HpFirst3Chapters_3pass_results.txt",
                        help='Path to save the results')
    parser.add_argument('--server', type=str, default="http://localhost:8080",
                        help='llama-server URL')
    parser.add_argument('--model-name', type=str, default="unknown",
                        help='Name of the model being tested (for reporting)')
    parser.add_argument('--workflow', type=str, default="2pass", choices=["2pass", "3pass"],
                        help='Workflow to run: 2pass (segment-based) or 3pass (legacy page-based)')
    return parser.parse_args()

# Configuration (set from args in main())
SERVER_URL = "http://localhost:8080"
PDF_PATH = r"C:\Users\Pratik\Downloads\HP First3Chapters.pdf"
OUTPUT_FILE = r"C:\Users\Pratik\source\Storyteller\scripts\HpFirst3Chapters_3pass_results.txt"
MODEL_NAME = "unknown"
PAGE_SIZE_CHARS = 10000
V7_MAX_CONTEXT = 6000

# New 2-pass segment configuration
SEGMENT_SIZE_CHARS = 4000  # Characters per segment (not words)

JSON_REMINDER = "\nEnsure the JSON is valid and contains no trailing commas."

# Track conversation context per character
character_context = {}  # {char_lower: {"name": str, "dialogs": [], "traits": [], "page_excerpts": []}}

def extract_pdf_text():
    """Extract full text from PDF."""
    doc = fitz.open(PDF_PATH)
    text = "".join([p.get_text() for p in doc])
    doc.close()
    return text.encode('ascii', 'ignore').decode('ascii')

def split_into_pages(text, page_size=PAGE_SIZE_CHARS):
    """Split text into pages at word boundaries (legacy 3-pass)."""
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

def split_into_segments(text, segment_size=SEGMENT_SIZE_CHARS):
    """Split text into segments at sentence boundaries.

    - Each segment is approximately segment_size characters
    - Trim each segment to the last complete sentence (last period '.')
    - Next segment starts immediately after the period
    - Handles segment continuity across the entire text
    """
    segments = []
    start = 0
    text_len = len(text)

    while start < text_len:
        # Calculate tentative end
        end = min(start + segment_size, text_len)

        # If we're not at the end of text, trim to last sentence boundary
        if end < text_len:
            segment = text[start:end]
            # Find last period followed by space or end of segment
            last_period = -1
            for i in range(len(segment) - 1, -1, -1):
                if segment[i] == '.':
                    # Check if it's likely end of sentence (followed by space, quote, or end)
                    if i + 1 >= len(segment) or segment[i + 1] in ' \n\t"\'':
                        last_period = i
                        break

            if last_period > 0:
                end = start + last_period + 1  # Include the period
            else:
                # No period found, try to break at last space
                last_space = segment.rfind(' ')
                if last_space > 0:
                    end = start + last_space

        segment_text = text[start:end].strip()
        if segment_text:
            segments.append(segment_text)
        start = end

    return segments if segments else [text]

def get_model_type():
    """Detect model type from MODEL_NAME."""
    name_lower = MODEL_NAME.lower()
    if "gemma" in name_lower:
        return "gemma"
    elif "qwen3" in name_lower:
        return "qwen3"
    elif "qwen2" in name_lower or "qwen-2" in name_lower:
        return "qwen2"
    else:
        return "chatml"  # Default to ChatML format

def build_chat_prompt(system, user, assistant_history=None):
    """Build prompt in appropriate format for the model type."""
    model_type = get_model_type()

    if model_type == "gemma":
        # Gemma 3 format: <start_of_turn>user\n...<end_of_turn>\n<start_of_turn>model\n
        # Combine system and user into single user turn, add explicit JSON instruction
        prompt = f"<start_of_turn>user\n{system}\n\n{user}\n\nRespond with valid JSON only. No explanations.<end_of_turn>\n<start_of_turn>model\n"
        return prompt

    elif model_type == "qwen3":
        # Qwen3 non-thinking mode: use /no_think directive in user message
        # The model will skip thinking and output directly (no <think> tags needed)
        # See: https://huggingface.co/Qwen/Qwen3-1.7B-GGUF
        prompt = f"<|im_start|>system\n{system}<|im_end|>\n"
        if assistant_history:
            for turn in assistant_history:
                prompt += f"<|im_start|>user\n{turn['user']}<|im_end|>\n"
                prompt += f"<|im_start|>assistant\n{turn['assistant']}<|im_end|>\n"
        # Add /no_think at end of user message - model will skip thinking and output directly
        prompt += f"<|im_start|>user\n{user}\n/no_think<|im_end|>\n<|im_start|>assistant\n"
        return prompt

    else:
        # Standard ChatML for Qwen2 and others (no /no_think)
        prompt = f"<|im_start|>system\n{system}<|im_end|>\n"
        if assistant_history:
            for turn in assistant_history:
                prompt += f"<|im_start|>user\n{turn['user']}<|im_end|>\n"
                prompt += f"<|im_start|>assistant\n{turn['assistant']}<|im_end|>\n"
        prompt += f"<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n"
        return prompt

def get_stop_tokens():
    """Get appropriate stop tokens for the model type."""
    model_type = get_model_type()
    if model_type == "gemma":
        return ["<end_of_turn>", "<eos>"]
    elif model_type == "qwen3":
        # Don't include </think> as stop token - model outputs directly with /no_think
        return ["<|im_end|>", "<|endoftext|>"]
    else:
        return ["<|im_end|>", "<|endoftext|>"]

def run_llm(prompt, max_tokens=512, temp=0.7, debug=False):
    """Run LLM via llama-server HTTP API.

    Uses model-specific sampling parameters:
    - Qwen3: temp=0.7, top_p=0.8, top_k=20, presence_penalty=1.5
    - Gemma: temp=0.7, top_p=0.95, top_k=40, repeat_penalty=1.1
    """
    start = time.time()
    stop_tokens = get_stop_tokens()
    model_type = get_model_type()

    # Model-specific sampling parameters
    if model_type == "gemma":
        # Gemma models work better with standard sampling and repeat_penalty
        sampling_params = {
            "prompt": prompt,
            "n_predict": max_tokens,
            "temperature": temp,
            "top_p": 0.95,
            "top_k": 40,
            "repeat_penalty": 1.1,  # Gemma uses repeat_penalty instead of presence_penalty
            "stop": stop_tokens,
            "cache_prompt": True,  # Enable prompt caching for repeated similar prompts
        }
    else:
        # Qwen3/Qwen2 recommended params for non-thinking mode
        # See: https://huggingface.co/Qwen/Qwen3-1.7B-GGUF
        sampling_params = {
            "prompt": prompt,
            "n_predict": max_tokens,
            "temperature": temp,
            "top_p": 0.8,
            "top_k": 20,
            "min_p": 0,
            "presence_penalty": 1.5,  # Critical for quantized models to prevent repetition
            "stop": stop_tokens,
            "cache_prompt": True,  # Enable prompt caching for repeated similar prompts
        }

    try:
        response = requests.post(
            f"{SERVER_URL}/completion",
            json=sampling_params,
            timeout=180
        )
        elapsed = time.time() - start
        data = response.json()
        output = data.get("content", "")
        timings = data.get("timings", {})

        # Remove any thinking tags from output
        output = re.sub(r'<think>.*?</think>', '', output, flags=re.DOTALL)
        output = re.sub(r'<think>.*', '', output, flags=re.DOTALL)  # Unclosed think tag
        output = output.replace('/no_think', '').strip()

        if debug:
            print(f"    [DEBUG] Raw output ({len(output)} chars): {output[:300]}...")

        # Try multiple JSON extraction patterns
        json_output = ""

        # Pattern 1: Look for JSON with arrays inside (for dialogs/characters)
        json_match = re.search(r'\{[^{}]*\[[^\]]*\][^{}]*\}', output, re.DOTALL)
        if json_match:
            json_output = json_match.group(0)

        # Pattern 2: Nested JSON with voice_profile object
        if not json_output:
            json_match = re.search(r'\{[^{}]*"voice_profile"\s*:\s*\{[^{}]*\}[^{}]*\}', output, re.DOTALL)
            if json_match:
                json_output = json_match.group(0)

        # Pattern 3: Any JSON object with nested braces
        if not json_output:
            json_match = re.search(r'\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}', output, re.DOTALL)
            if json_match:
                json_output = json_match.group(0)

        # Pattern 4: Simple JSON object (greedy, last resort)
        if not json_output:
            json_match = re.search(r'\{.*\}', output, re.DOTALL)
            if json_match:
                json_output = json_match.group(0)

        return {
            "elapsed": elapsed,
            "pp_speed": timings.get("prompt_per_second", 0),
            "tg_speed": timings.get("predicted_per_second", 0),
            "output": output,
            "json_output": json_output,
            "success": bool(json_output),
            "prompt_tokens": timings.get("prompt_n", 0),
            "generated_tokens": timings.get("predicted_n", 0)
        }
    except Exception as e:
        elapsed = time.time() - start
        print(f"    [ERROR] LLM call failed: {e}")
        return {"elapsed": elapsed, "pp_speed": 0, "tg_speed": 0,
                "output": str(e), "json_output": "", "success": False,
                "prompt_tokens": 0, "generated_tokens": 0}

def validate_json(json_str, required_keys=None):
    """Validate JSON and check for required keys."""
    try:
        data = json.loads(json_str)
        missing = [k for k in (required_keys or []) if k not in data]
        return {"valid": True, "data": data, "missing_keys": missing}
    except json.JSONDecodeError as e:
        return {"valid": False, "error": str(e), "data": {}, "missing_keys": required_keys or []}

# ============================================================================
# PROMPT BUILDERS (Matching Android app's Qwen3Model.kt prompts exactly)
# ============================================================================

def build_pass1_prompt(text):
    """Pass-1: Character name extraction (matches Qwen3Model.buildPass1ExtractNamesPrompt)."""
    text = text[:10000]
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

def build_pass2_prompt(text, character_names):
    """Pass-2: Dialog extraction (matches Qwen3Model.buildPass2_5ExtractDialogsPrompt)."""
    text = text[:10000]
    chars_json = json.dumps(character_names[:10])

    system = "You are a dialog extraction engine. Extract quoted speech and attribute it to the correct speaker. Output valid JSON only."
    user = f"""CHARACTERS ON THIS PAGE: {chars_json}

EXTRACTION RULES:
1. DIALOGS - Extract text within quotation marks ("..." or '...'):
   - Attribute each dialog to the nearest character name appearing BEFORE or AFTER the quote (within ~200 chars)
   - Use attribution patterns: "said [Name]", "[Name] said", "[Name]:", "[Name] asked", "[Name] replied", "whispered", "shouted", "muttered", etc.
   - If a pronoun (he/she/they) refers to a recently mentioned character, attribute to that character
   - If speaker cannot be determined, use "Unknown"

2. NARRATOR TEXT - Extract descriptive prose between dialogs:
   - Scene descriptions, action descriptions, internal thoughts (if not in quotes)
   - Attribute narrator text to "Narrator"
   - Keep narrator segments reasonably sized (1-3 sentences each)

3. EMOTION DETECTION - For each segment:
   - Infer emotion: neutral, happy, sad, angry, surprised, fearful, excited, worried, curious, defiant
   - Estimate intensity: 0.0 (very mild) to 1.0 (very intense)
   - Use context clues: exclamation marks, word choice, described actions

4. ORDERING - Maintain the order of appearance in the text

OUTPUT FORMAT (valid JSON only):
{{
  "dialogs": [
    {{"speaker": "Character Name", "text": "Exact quoted speech or narrator text", "emotion": "neutral", "intensity": 0.5}},
    {{"speaker": "Narrator", "text": "Descriptive prose between dialogs", "emotion": "neutral", "intensity": 0.3}}
  ]
}}

TEXT:
{text}
{JSON_REMINDER}"""
    return build_chat_prompt(system, user)

def build_pass3_prompt(char_name, char_context):
    """Pass-3: Traits + Voice Profile (matches Qwen3Model.buildPass3WithAggregatedContext for single character)."""
    text = char_context[:10000]

    system = "You are a character analyst for TTS voice casting. Extract observable traits and suggest voice profile. JSON only."
    user = f"""CHARACTER: "{char_name}"

TEXT:
{text}

EXTRACT CONCISE TRAITS (1-2 words only):
- Examples: "gravelly voice", "nervous fidgeting", "dry humor", "rambling", "high-pitched", "slow pacing"
- DO NOT write verbose descriptions like "TTS Voice Traits: Pitch: Low..."

TRAIT → VOICE MAPPING:
- "gravelly/deep/commanding" → pitch: 0.8-0.9
- "bright/light/young" → pitch: 1.1-1.2
- "nervous/anxious/frantic" → speed: 1.1-1.2, energy: 0.8
- "calm/measured/authoritative" → speed: 0.9, energy: 0.6
- "energetic/excited/enthusiastic" → energy: 0.9-1.0

GENDER/AGE from traits:
- Male indicators: "male", "man", "mr", "sir", "lord", "king"
- Female indicators: "female", "woman", "mrs", "miss", "lady", "queen"
- Young: "young", "child", "teen", "boy", "girl"
- Elderly: "old", "elderly", "aged", "senior"

SPEAKER_ID (0-108 VCTK range):
- Female young: 10-30
- Female adult: 31-50
- Male young: 51-70
- Male adult: 71-90
- Elderly/character: 91-108

OUTPUT FORMAT (valid JSON only):
{{
  "character": "{char_name}",
  "traits": ["trait1", "trait2", "trait3"],
  "voice_profile": {{"pitch": 1.0, "speed": 1.0, "energy": 0.7, "gender": "male|female", "age": "child|young|middle-aged|elderly", "tone": "brief description", "speaker_id": 45}}
}}
{JSON_REMINDER}"""
    return build_chat_prompt(system, user)


# ============================================================================
# 2-PASS SEGMENT-BASED PROMPT BUILDERS
# ============================================================================

def build_2pass_segment_prompt(segment_text, accumulated_characters):
    """Pass-1 (2-pass): Combined extraction of characters, dialogs, and traits from a segment.

    This combines the functionality of the old Pass-1, Pass-2, and part of Pass-3 into
    a single LLM call per segment.
    """
    segment_text = segment_text[:4500]  # Limit segment size with some buffer
    chars_json = json.dumps(list(accumulated_characters)[:15]) if accumulated_characters else "[]"

    system = "You are a text analysis engine. Extract characters, dialogs, and traits from the provided text segment. Output valid JSON only."

    user = f"""CHARACTERS FROM PREVIOUS SEGMENTS: {chars_json}

TASK: Analyze the text segment below and extract THREE types of data:

## 1. CHARACTER NAMES
STRICT RULES:
- Extract ONLY proper names explicitly written in the text (e.g., "Harry Potter", "Hermione", "Mr. Dursley")
- Do NOT include pronouns (he, she, they, etc.)
- Do NOT include generic descriptions (the boy, the woman, the teacher)
- Do NOT include titles alone (Professor, Sir, Madam) unless used as the character's actual name
- Do NOT infer or guess names not explicitly mentioned
- Include a name only if the character speaks, acts, or is directly described in this segment

## 2. DIALOGS
Extract quoted speech with speaker attribution:
- Look for text within quotation marks ("..." or '...')
- Attribute each dialog to the speaker using these methods:
  1. Direct attribution: "said [Name]", "[Name] said", "[Name] asked", "[Name] replied"
  2. Pronoun resolution: If "he/she/they" refers to a recently mentioned character, use that character
  3. Use characters from PREVIOUS SEGMENTS list above if they match
  4. If speaker is ambiguous, use "unknown"
  5. For narrative descriptions (not quoted), use "narrator"

## 3. TRAITS/ADJECTIVES
Extract descriptive adjectives and behavioral traits:
- Physical descriptions: "tall", "thin", "bespectacled"
- Behavioral traits: "nervous", "stern", "cheerful"
- Voice/speech patterns: "whispered", "shouted", "stammered"
- Associate each trait with the character it describes
- Use "narrator" for traits describing the narrative style
- Use "unknown" if association is unclear

OUTPUT FORMAT (valid JSON only):
{{
  "characters": ["Name1", "Name2"],
  "dialogs": [
    {{"text": "quoted speech here", "speaker": "CharacterName"}},
    {{"text": "narrative description", "speaker": "narrator"}}
  ],
  "traits": [
    {{"adjectives": ["nervous", "fidgeting"], "associated_with": "CharacterName"}},
    {{"adjectives": ["dark", "ominous"], "associated_with": "narrator"}}
  ]
}}

TEXT SEGMENT:
{segment_text}
{JSON_REMINDER}"""
    return build_chat_prompt(system, user)


def build_2pass_voice_prompt(char_name, dialogs, traits):
    """Pass-2 (2-pass): Voice profile suggestion based on accumulated dialogs and traits."""

    # Format dialogs for context (limit to avoid token overflow)
    dialog_samples = []
    for d in dialogs[:10]:
        text = d.get("text", "")[:100]
        dialog_samples.append(f'  - "{text}"')
    dialogs_str = "\n".join(dialog_samples) if dialog_samples else "  (no dialogs extracted)"

    # Format traits
    all_adjectives = []
    for t in traits:
        all_adjectives.extend(t.get("adjectives", []))
    traits_str = ", ".join(all_adjectives[:15]) if all_adjectives else "(no traits extracted)"

    system = "You are a TTS voice casting director. Suggest a voice profile based on character traits and speech patterns. Output valid JSON only."

    user = f"""CHARACTER: "{char_name}"

EXTRACTED TRAITS: {traits_str}

SAMPLE DIALOGS:
{dialogs_str}

VOICE PROFILE MAPPING GUIDE:
- Trait "gravelly/deep/commanding" → pitch: 0.8-0.9
- Trait "bright/light/young" → pitch: 1.1-1.2
- Trait "nervous/anxious/frantic" → speed: 1.1-1.2, energy: 0.8
- Trait "calm/measured/authoritative" → speed: 0.9, energy: 0.6
- Trait "energetic/excited/enthusiastic" → energy: 0.9-1.0

GENDER/AGE INFERENCE:
- Male indicators: "mr", "sir", "lord", "king", "man", "boy", "he"
- Female indicators: "mrs", "miss", "lady", "queen", "woman", "girl", "she"
- Young: "child", "teen", "boy", "girl", "young"
- Elderly: "old", "elderly", "aged", "senior"

SPEAKER_ID RANGES (VCTK 0-108):
- Female young: 10-30
- Female adult: 31-50
- Male young: 51-70
- Male adult: 71-90
- Elderly/character voices: 91-108

OUTPUT FORMAT (valid JSON only):
{{
  "character": "{char_name}",
  "voice_profile": {{
    "pitch": 1.0,
    "speed": 1.0,
    "energy": 0.7,
    "gender": "male|female",
    "age": "child|young|middle-aged|elderly",
    "tone": "brief description of voice quality",
    "accent": "neutral",
    "speaker_id": 45
  }}
}}
{JSON_REMINDER}"""
    return build_chat_prompt(system, user)


# ============================================================================
# 2-PASS SEGMENT-BASED WORKFLOW
# ============================================================================

def run_two_pass_segment_workflow():
    """Run 2-pass segment-based workflow.

    PASS 1: Process text in 4000-char segments
      - Extract characters, dialogs, and traits in ONE LLM call per segment
      - Accumulate character list across segments for speaker attribution
      - Trim segments to sentence boundaries

    PASS 2: Voice profile matching
      - For each unique character, make ONE LLM call
      - Use accumulated dialogs and traits to suggest voice profile
    """
    results = {
        "workflow": "2-pass-segment-based",
        "total_time": 0,
        "pass_times": {"pass1": 0, "pass2": 0},
        "characters": {},
        "all_dialogs": [],
        "all_traits": [],
        "json_validity": {"pass1": 0, "pass2": 0},
        "json_total": {"pass1": 0, "pass2": 0},
        "segments_processed": 0,
        "total_prompt_tokens": 0,
        "total_generated_tokens": 0
    }

    start_time = time.time()

    print("\nExtracting text from PDF...")
    full_text = extract_pdf_text()
    print(f"Total text: {len(full_text)} chars")

    # Split into segments
    segments = split_into_segments(full_text, SEGMENT_SIZE_CHARS)
    results["segments_processed"] = len(segments)
    print(f"\n[2-PASS WORKFLOW] Processing {len(segments)} segments (~{SEGMENT_SIZE_CHARS} chars each)...")

    # Track accumulated data
    accumulated_characters = set()  # All character names found so far
    character_data = {}  # char_lower -> {"name": str, "dialogs": [], "traits": []}

    # =========================================================================
    # PASS 1: Segment-Based Extraction (Characters + Dialogs + Traits)
    # =========================================================================
    print(f"\n{'='*70}")
    print("PASS 1: Segment-Based Extraction")
    print("(Characters + Dialogs + Traits in ONE call per segment)")
    print(f"{'='*70}")

    for seg_idx, segment_text in enumerate(segments):
        seg_num = seg_idx + 1
        print(f"\n{'='*50}")
        print(f"SEGMENT {seg_num}/{len(segments)} ({len(segment_text)} chars)")
        print(f"{'='*50}")
        print(f"  Preview: {segment_text[:80]}...")
        print(f"  Known characters: {list(accumulated_characters)[:10]}")

        prompt = build_2pass_segment_prompt(segment_text, accumulated_characters)
        result = run_llm(prompt, max_tokens=1024, temp=0.1, debug=True)
        results["pass_times"]["pass1"] += result["elapsed"]
        results["json_total"]["pass1"] += 1
        results["total_prompt_tokens"] += result.get("prompt_tokens", 0)
        results["total_generated_tokens"] += result.get("generated_tokens", 0)

        validation = validate_json(result["json_output"], ["characters", "dialogs", "traits"])
        if validation["valid"]:
            results["json_validity"]["pass1"] += 1
            data = validation["data"]

            # Process characters
            seg_chars = data.get("characters", [])
            for char in seg_chars:
                if char and isinstance(char, str):
                    accumulated_characters.add(char)
                    char_lower = char.lower()
                    if char_lower not in character_data:
                        character_data[char_lower] = {"name": char, "dialogs": [], "traits": []}

            # Process dialogs
            seg_dialogs = data.get("dialogs", [])
            for dialog in seg_dialogs:
                if isinstance(dialog, dict):
                    dialog["segment"] = seg_num
                    results["all_dialogs"].append(dialog)

                    # Associate with character
                    speaker = dialog.get("speaker", "unknown").lower()
                    if speaker in character_data:
                        character_data[speaker]["dialogs"].append(dialog)

            # Process traits
            seg_traits = data.get("traits", [])
            for trait in seg_traits:
                if isinstance(trait, dict):
                    trait["segment"] = seg_num
                    results["all_traits"].append(trait)

                    # Associate with character
                    assoc = trait.get("associated_with", "unknown").lower()
                    if assoc in character_data:
                        character_data[assoc]["traits"].append(trait)

            print(f"  ✓ Characters: {seg_chars}")
            print(f"  ✓ Dialogs: {len(seg_dialogs)} extracted")
            print(f"  ✓ Traits: {len(seg_traits)} extracted")
            print(f"  ({result['elapsed']:.1f}s)")
        else:
            print(f"  ✗ Invalid JSON ({result['elapsed']:.1f}s): {result['json_output'][:100]}")

    print(f"\n{'='*70}")
    print(f"PASS 1 COMPLETE")
    print(f"{'='*70}")
    print(f"  Total characters: {len(accumulated_characters)}")
    print(f"  Total dialogs: {len(results['all_dialogs'])}")
    print(f"  Total traits: {len(results['all_traits'])}")
    print(f"  Characters: {list(accumulated_characters)}")

    # =========================================================================
    # PASS 2: Voice Profile Matching (Per Character)
    # =========================================================================
    print(f"\n{'='*70}")
    print("PASS 2: Voice Profile Matching (Per Character)")
    print(f"{'='*70}")

    for char_lower, char_info in character_data.items():
        char_name = char_info["name"]
        char_dialogs = char_info["dialogs"]
        char_traits = char_info["traits"]

        # Skip narrator and unknown
        if char_lower in ["narrator", "unknown"]:
            continue

        print(f"\n  [PASS 2] {char_name}...")
        print(f"      Dialogs: {len(char_dialogs)}, Traits: {len(char_traits)}")

        prompt = build_2pass_voice_prompt(char_name, char_dialogs, char_traits)
        result = run_llm(prompt, max_tokens=384, temp=0.1)
        results["pass_times"]["pass2"] += result["elapsed"]
        results["json_total"]["pass2"] += 1
        results["total_prompt_tokens"] += result.get("prompt_tokens", 0)
        results["total_generated_tokens"] += result.get("generated_tokens", 0)

        validation = validate_json(result["json_output"], ["character", "voice_profile"])
        if validation["valid"]:
            results["json_validity"]["pass2"] += 1
            vp_data = validation["data"]
            voice_profile = vp_data.get("voice_profile", {})

            # Store in results
            results["characters"][char_lower] = {
                "name": char_name,
                "dialogs": char_dialogs,
                "traits": char_traits,
                "voice_profile": voice_profile
            }

            print(f"      ✓ Voice: pitch={voice_profile.get('pitch')}, speed={voice_profile.get('speed')}, "
                  f"gender={voice_profile.get('gender')}, speaker_id={voice_profile.get('speaker_id')}")
            print(f"      ({result['elapsed']:.1f}s)")
        else:
            # Store character without voice profile
            results["characters"][char_lower] = {
                "name": char_name,
                "dialogs": char_dialogs,
                "traits": char_traits,
                "voice_profile": {}
            }
            print(f"      ✗ Invalid JSON ({result['elapsed']:.1f}s): {result['json_output'][:100]}")

    print(f"\n{'='*70}")
    print(f"PASS 2 COMPLETE: {len(results['characters'])} characters processed")
    print(f"{'='*70}")

    results["total_time"] = time.time() - start_time
    return results


# ============================================================================
# 3-PASS WORKFLOW WITH CONTEXT PRESERVATION (Legacy)
# ============================================================================

def run_three_pass_workflow_with_context():
    """Run 3-pass workflow with interleaved Pass-1 + Pass-2 per page, then Pass-3 per character.

    FOR EACH PAGE (Interleaved Pass-1 + Pass-2):
      - PASS 1: Extract character names from this page
      - PASS 2: Extract dialogs ONLY if Pass-1 found characters on this page
               (This implements "start from the page where the character appears first")

    AFTER ALL PAGES:
      - PASS 3: For each character, extract traits + voice profile with aggregated context
    """
    results = {
        "workflow": "3-pass-with-context",
        "total_time": 0,
        "pass_times": {"pass1": 0, "pass2": 0, "pass3": 0},
        "characters": {},
        "all_dialogs": [],
        "json_validity": {"pass1": 0, "pass2": 0, "pass3": 0},
        "json_total": {"pass1": 0, "pass2": 0, "pass3": 0},
        "pages_processed": 0,
        "total_prompt_tokens": 0,
        "total_generated_tokens": 0
    }

    start_time = time.time()

    print("\nExtracting text from PDF...")
    full_text = extract_pdf_text()
    print(f"Total text: {len(full_text)} chars")

    pages = split_into_pages(full_text)
    results["pages_processed"] = len(pages)
    print(f"\n[3-PASS WORKFLOW] Processing {len(pages)} pages...")

    # Track character appearances and their page texts
    character_pages = {}  # name_lower -> {"name": str, "pages": [page_texts], "dialogs": []}
    all_characters = set()  # Deduplicated character names
    pages_with_characters = set()  # Track which pages have characters (for Pass-2)

    # =========================================================================
    # INTERLEAVED PASS-1 + PASS-2 (Per-Page Processing)
    # Pass-2 only runs on pages where Pass-1 found characters
    # =========================================================================
    print(f"\n{'='*70}")
    print("PASS 1 + PASS 2: Interleaved Page-by-Page Processing")
    print("(Pass-2 only runs on pages where characters are found)")
    print(f"{'='*70}")

    for page_idx, page_text in enumerate(pages):
        page_num = page_idx + 1
        print(f"\n{'='*50}")
        print(f"PAGE {page_num}/{len(pages)} ({len(page_text)} chars)")
        print(f"{'='*50}")

        # ----- PASS 1: Extract character names from this page -----
        print(f"\n  [PASS 1] Extracting character names...")
        prompt = build_pass1_prompt(page_text)
        result = run_llm(prompt, max_tokens=256, temp=0.1, debug=True)
        results["pass_times"]["pass1"] += result["elapsed"]
        results["json_total"]["pass1"] += 1
        results["total_prompt_tokens"] += result.get("prompt_tokens", 0)
        results["total_generated_tokens"] += result.get("generated_tokens", 0)

        validation = validate_json(result["json_output"], ["characters"])
        page_chars = []
        if validation["valid"]:
            results["json_validity"]["pass1"] += 1
            page_chars = validation["data"].get("characters", [])
            for char in page_chars:
                char_lower = char.lower()
                all_characters.add(char)
                if char_lower not in character_pages:
                    character_pages[char_lower] = {"name": char, "pages": [], "dialogs": []}
                # Track which pages this character appears on
                character_pages[char_lower]["pages"].append(page_text)
            print(f"    Found: {page_chars} ({result['elapsed']:.1f}s)")
        else:
            print(f"    [WARN] Invalid JSON ({result['elapsed']:.1f}s): {result['json_output'][:100]}")

        # ----- PASS 2: Extract dialogs ONLY if characters were found -----
        if not page_chars:
            print(f"\n  [PASS 2] Skipping - no characters found on this page")
            continue

        pages_with_characters.add(page_idx)
        print(f"\n  [PASS 2] Extracting dialogs (characters: {page_chars})...")
        prompt = build_pass2_prompt(page_text, page_chars)  # Use only this page's characters
        result = run_llm(prompt, max_tokens=1024, temp=0.15, debug=True)
        results["pass_times"]["pass2"] += result["elapsed"]
        results["json_total"]["pass2"] += 1
        results["total_prompt_tokens"] += result.get("prompt_tokens", 0)
        results["total_generated_tokens"] += result.get("generated_tokens", 0)

        validation = validate_json(result["json_output"], ["dialogs"])
        if validation["valid"]:
            results["json_validity"]["pass2"] += 1
            dialogs = validation["data"].get("dialogs", [])

            # Store dialogs with page tracking
            for dialog in dialogs:
                speaker = dialog.get("speaker", "Unknown")
                speaker_lower = speaker.lower()
                dialog["page"] = page_num
                results["all_dialogs"].append(dialog)

                # Track per-character dialogs
                if speaker_lower in character_pages:
                    character_pages[speaker_lower]["dialogs"].append(dialog)

            print(f"    Found {len(dialogs)} dialogs ({result['elapsed']:.1f}s)")
            for d in dialogs[:3]:
                print(f"      {d.get('speaker', 'Unknown')}: \"{d.get('text', '')[:50]}...\"")
            if len(dialogs) > 3:
                print(f"      ... and {len(dialogs)-3} more")
        else:
            print(f"    [WARN] Invalid JSON ({result['elapsed']:.1f}s): {result['json_output'][:100]}")

    # Initialize results for all discovered characters
    for char_lower, char_data in character_pages.items():
        results["characters"][char_lower] = {
            "name": char_data["name"],
            "traits": [],
            "voice_profile": {},
            "dialogs": char_data["dialogs"]  # Include dialogs collected during Pass-2
        }

    print(f"\n{'='*70}")
    print(f"PASS 1 + PASS 2 COMPLETE")
    print(f"{'='*70}")
    print(f"  Characters found: {len(all_characters)}")
    print(f"  Pages with characters: {len(pages_with_characters)}/{len(pages)}")
    print(f"  Total dialogs extracted: {len(results['all_dialogs'])}")
    print(f"  Character list: {list(all_characters)}")

    # =========================================================================
    # PASS 3: Traits + Voice Profile (Per Character with Aggregated Context)
    # =========================================================================
    print(f"\n{'='*70}")
    print("PASS 3: Traits & Voice Profile (Per-Character with Full Context)")
    print(f"{'='*70}")

    for char_lower, char_data in results["characters"].items():
        char_name = char_data["name"]
        char_dialogs = char_data.get("dialogs", [])

        # Aggregate all pages where this character appears
        char_page_texts = character_pages.get(char_lower, {}).get("pages", [])
        aggregated_context = "\n---PAGE BREAK---\n".join(char_page_texts)
        aggregated_context = aggregated_context[:V7_MAX_CONTEXT]

        print(f"\n  [PASS 3] Analyzing {char_name}...")
        print(f"      Context: {len(aggregated_context)} chars from {len(char_page_texts)} pages, {len(char_dialogs)} dialogs")

        prompt = build_pass3_prompt(char_name, aggregated_context)
        result = run_llm(prompt, max_tokens=384, temp=0.1)
        results["pass_times"]["pass3"] += result["elapsed"]
        results["json_total"]["pass3"] += 1
        results["total_prompt_tokens"] += result.get("prompt_tokens", 0)
        results["total_generated_tokens"] += result.get("generated_tokens", 0)

        validation = validate_json(result["json_output"], ["traits", "voice_profile"])
        if validation["valid"]:
            results["json_validity"]["pass3"] += 1
            char_data["traits"] = validation["data"].get("traits", [])
            char_data["voice_profile"] = validation["data"].get("voice_profile", {})
            print(f"      Traits: {char_data['traits']}")
            vp = char_data["voice_profile"]
            print(f"      Voice: pitch={vp.get('pitch')}, speed={vp.get('speed')}, gender={vp.get('gender')}")
        else:
            print(f"      [WARN] Invalid JSON: {result['json_output'][:100]}")

    print(f"\n  >> PASS 3 COMPLETE: Processed {len(results['characters'])} characters")

    results["total_time"] = time.time() - start_time
    return results


# ============================================================================
# RESULTS OUTPUT
# ============================================================================

def save_results(results):
    """Save detailed results to file."""
    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        f.write("=" * 70 + "\n")
        f.write("3-PASS WORKFLOW WITH CONTEXT PRESERVATION - BENCHMARK RESULTS\n")
        f.write(f"Model: {results.get('model_name', MODEL_NAME)}\n")
        f.write(f"PDF: {PDF_PATH}\n")
        f.write(f"Date: {time.strftime('%Y-%m-%d %H:%M:%S')}\n")
        f.write("=" * 70 + "\n\n")

        # Summary
        f.write("SUMMARY\n")
        f.write("-" * 40 + "\n")
        f.write(f"Model: {results.get('model_name', MODEL_NAME)}\n")
        f.write(f"Total Processing Time: {results['total_time']:.1f}s ({results['total_time']/60:.1f} min)\n")
        f.write(f"Pages Processed: {results['pages_processed']}\n")
        f.write(f"Total Characters Found: {len(results['characters'])}\n")
        f.write(f"Total Dialogs Extracted: {len(results['all_dialogs'])}\n")
        f.write(f"Total Prompt Tokens: {results['total_prompt_tokens']}\n")
        f.write(f"Total Generated Tokens: {results['total_generated_tokens']}\n\n")

        # Pass times
        f.write("PASS TIMING BREAKDOWN\n")
        f.write("-" * 40 + "\n")
        for pass_name, elapsed in results["pass_times"].items():
            pct = (elapsed / results["total_time"] * 100) if results["total_time"] > 0 else 0
            f.write(f"  {pass_name:<15}: {elapsed:>8.1f}s ({pct:>5.1f}%)\n")
        f.write("\n")

        # JSON validity
        f.write("JSON VALIDITY STATISTICS\n")
        f.write("-" * 40 + "\n")
        for pass_name in results["json_total"]:
            total = results["json_total"][pass_name]
            valid = results["json_validity"][pass_name]
            rate = (valid / total * 100) if total > 0 else 0
            f.write(f"  {pass_name:<15}: {valid}/{total} ({rate:.0f}% valid)\n")
        f.write("\n")

        # Character details
        f.write("=" * 70 + "\n")
        f.write("CHARACTER DETAILS\n")
        f.write("=" * 70 + "\n\n")

        for char_lower, data in results["characters"].items():
            char_name = data["name"]
            dialogs = data.get("dialogs", [])
            traits = data.get("traits", [])
            vp = data.get("voice_profile", {})

            f.write(f"\n{char_name.upper()}\n")
            f.write("-" * 40 + "\n")
            f.write(f"Dialogs: {len(dialogs)}\n")
            f.write(f"Traits: {traits}\n")
            f.write(f"Voice Profile:\n")
            f.write(f"  - Pitch: {vp.get('pitch', 'N/A')}\n")
            f.write(f"  - Speed: {vp.get('speed', 'N/A')}\n")
            f.write(f"  - Energy: {vp.get('energy', 'N/A')}\n")
            f.write(f"  - Gender: {vp.get('gender', 'N/A')}\n")
            f.write(f"  - Age: {vp.get('age', 'N/A')}\n")
            f.write(f"  - Tone: {vp.get('tone', 'N/A')}\n")
            f.write(f"  - Speaker ID: {vp.get('speaker_id', 'N/A')}\n")

            # Sample dialogs
            if dialogs:
                f.write(f"\nSample Dialogs:\n")
                for d in dialogs[:5]:
                    text = d.get("text", "")[:80]
                    emotion = d.get("emotion", "neutral")
                    page = d.get("page", "?")
                    f.write(f"  [Page {page}] ({emotion}) \"{text}...\"\n")
                if len(dialogs) > 5:
                    f.write(f"  ... and {len(dialogs)-5} more dialogs\n")

        # All dialogs summary
        f.write("\n\n" + "=" * 70 + "\n")
        f.write("ALL EXTRACTED DIALOGS\n")
        f.write("=" * 70 + "\n\n")

        for i, d in enumerate(results["all_dialogs"]):
            speaker = d.get("speaker", "Unknown")
            text = d.get("text", "")
            emotion = d.get("emotion", "neutral")
            intensity = d.get("intensity", 0.5)
            page = d.get("page", "?")
            f.write(f"{i+1}. [Page {page}] {speaker} ({emotion}, {intensity:.1f}): \"{text}\"\n")

        f.write("\n\n" + "=" * 70 + "\n")
        f.write("CONTEXT PRESERVATION NOTES\n")
        f.write("=" * 70 + "\n")
        f.write("""
3-PASS WORKFLOW STRUCTURE (Interleaved Pass-1 + Pass-2):

FOR EACH PAGE:
  PASS 1 - Character Name Extraction:
    - ONE LLM call to extract character names from this page
    - Track which pages each character appears on
    - Accumulate unique names across all pages

  PASS 2 - Dialog Extraction (ONLY if characters found on this page):
    - SKIPPED if Pass-1 found no characters (optimization!)
    - ONE LLM call to extract dialogs from this page
    - Uses ONLY characters found on THIS page for speaker attribution
    - This implements "start from the page where the character appears first"

AFTER ALL PAGES:
  PASS 3 - Traits & Voice Profile:
    - Process EACH CHARACTER discovered across all pages
    - ONE LLM call per character with aggregated context from ALL pages
    - Extracts traits and voice profile in a single call
    - Output: Traits and voice profile for each character
""")

    print(f"\nResults saved to: {OUTPUT_FILE}")


def save_2pass_results(results):
    """Save detailed results for the 2-pass segment-based workflow."""
    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        f.write("=" * 70 + "\n")
        f.write("2-PASS SEGMENT-BASED WORKFLOW - BENCHMARK RESULTS\n")
        f.write(f"Model: {results.get('model_name', MODEL_NAME)}\n")
        f.write(f"PDF: {PDF_PATH}\n")
        f.write(f"Date: {time.strftime('%Y-%m-%d %H:%M:%S')}\n")
        f.write("=" * 70 + "\n\n")

        # Summary
        f.write("SUMMARY\n")
        f.write("-" * 40 + "\n")
        f.write(f"Model: {results.get('model_name', MODEL_NAME)}\n")
        f.write(f"Total Processing Time: {results['total_time']:.1f}s ({results['total_time']/60:.1f} min)\n")
        f.write(f"Segments Processed: {results['segments_processed']}\n")
        f.write(f"Total Characters Found: {len(results['characters'])}\n")
        f.write(f"Total Dialogs Extracted: {len(results['all_dialogs'])}\n")
        f.write(f"Total Traits Extracted: {len(results['all_traits'])}\n")
        f.write(f"Total Prompt Tokens: {results['total_prompt_tokens']}\n")
        f.write(f"Total Generated Tokens: {results['total_generated_tokens']}\n\n")

        # Pass times
        f.write("PASS TIMING BREAKDOWN\n")
        f.write("-" * 40 + "\n")
        for pass_name, elapsed in results["pass_times"].items():
            pct = (elapsed / results["total_time"] * 100) if results["total_time"] > 0 else 0
            f.write(f"  {pass_name:<15}: {elapsed:>8.1f}s ({pct:>5.1f}%)\n")
        f.write("\n")

        # JSON validity
        f.write("JSON VALIDITY STATISTICS\n")
        f.write("-" * 40 + "\n")
        for pass_name in results["json_total"]:
            total = results["json_total"][pass_name]
            valid = results["json_validity"][pass_name]
            rate = (valid / total * 100) if total > 0 else 0
            f.write(f"  {pass_name:<15}: {valid}/{total} ({rate:.0f}% valid)\n")
        f.write("\n")

        # Character details
        f.write("=" * 70 + "\n")
        f.write("CHARACTER DETAILS\n")
        f.write("=" * 70 + "\n\n")

        for char_lower, data in results["characters"].items():
            char_name = data["name"]
            dialogs = data.get("dialogs", [])
            traits = data.get("traits", [])
            vp = data.get("voice_profile", {})

            f.write(f"\n{char_name.upper()}\n")
            f.write("-" * 40 + "\n")
            f.write(f"Dialogs: {len(dialogs)}\n")

            # Format traits
            all_adjectives = []
            for t in traits:
                all_adjectives.extend(t.get("adjectives", []))
            f.write(f"Traits: {all_adjectives}\n")

            f.write(f"Voice Profile:\n")
            f.write(f"  - Pitch: {vp.get('pitch', 'N/A')}\n")
            f.write(f"  - Speed: {vp.get('speed', 'N/A')}\n")
            f.write(f"  - Energy: {vp.get('energy', 'N/A')}\n")
            f.write(f"  - Gender: {vp.get('gender', 'N/A')}\n")
            f.write(f"  - Age: {vp.get('age', 'N/A')}\n")
            f.write(f"  - Tone: {vp.get('tone', 'N/A')}\n")
            f.write(f"  - Speaker ID: {vp.get('speaker_id', 'N/A')}\n")

            # Sample dialogs
            if dialogs:
                f.write(f"\nSample Dialogs:\n")
                for d in dialogs[:5]:
                    text = d.get("text", "")[:80]
                    seg = d.get("segment", "?")
                    f.write(f"  [Seg {seg}] \"{text}...\"\n")
                if len(dialogs) > 5:
                    f.write(f"  ... and {len(dialogs)-5} more dialogs\n")

        # All dialogs summary
        f.write("\n\n" + "=" * 70 + "\n")
        f.write("ALL EXTRACTED DIALOGS\n")
        f.write("=" * 70 + "\n\n")

        for i, d in enumerate(results["all_dialogs"][:100]):  # Limit to first 100
            speaker = d.get("speaker", "Unknown")
            text = d.get("text", "")
            seg = d.get("segment", "?")
            f.write(f"{i+1}. [Seg {seg}] {speaker}: \"{text}\"\n")

        if len(results["all_dialogs"]) > 100:
            f.write(f"\n... and {len(results['all_dialogs'])-100} more dialogs\n")

        f.write("\n\n" + "=" * 70 + "\n")
        f.write("2-PASS WORKFLOW STRUCTURE\n")
        f.write("=" * 70 + "\n")
        f.write("""
2-PASS SEGMENT-BASED WORKFLOW:

PASS 1 - Segment-Based Extraction:
  - Split text into ~4000-char segments (trimmed to sentence boundaries)
  - ONE LLM call per segment to extract:
    * Character names
    * Dialogs with speaker attribution
    * Traits/adjectives associated with characters
  - Accumulate character list across segments for context

PASS 2 - Voice Profile Matching:
  - ONE LLM call per unique character
  - Uses accumulated dialogs and traits for each character
  - Suggests TTS voice profile (pitch, speed, gender, speaker_id)
""")

    print(f"\nResults saved to: {OUTPUT_FILE}")


# ============================================================================
# MAIN
# ============================================================================

def main():
    """Run the character analysis workflow benchmark."""
    global SERVER_URL, PDF_PATH, OUTPUT_FILE, MODEL_NAME

    # Parse command-line arguments
    args = parse_args()
    SERVER_URL = args.server
    PDF_PATH = args.pdf
    OUTPUT_FILE = args.output
    MODEL_NAME = args.model_name
    workflow = args.workflow

    workflow_name = "2-PASS SEGMENT-BASED" if workflow == "2pass" else "3-PASS LEGACY"
    print("=" * 70)
    print(f"{workflow_name} WORKFLOW BENCHMARK")
    print(f"Model: {MODEL_NAME}")
    print("=" * 70)

    # Check server health
    try:
        r = requests.get(f"{SERVER_URL}/health", timeout=5)
        if r.json().get("status") != "ok":
            print("ERROR: llama-server not healthy")
            return None
        print("✓ llama-server is running")
    except:
        print("ERROR: llama-server not running. Start it first:")
        print('  & ".\\scripts\\llama-cpp\\llama-server.exe" -m "D:\\Learning\\Ai\\Models\\LLM\\qwen3-1.7b-q4_k_m.gguf" -c 4096 --port 8080')
        return None

    # Run appropriate workflow
    if workflow == "2pass":
        results = run_two_pass_segment_workflow()
    else:
        results = run_three_pass_workflow_with_context()

    results["model_name"] = MODEL_NAME

    # Print summary
    print("\n" + "=" * 70)
    print("BENCHMARK COMPLETE")
    print("=" * 70)
    print(f"Workflow: {workflow_name}")
    print(f"Model: {MODEL_NAME}")
    print(f"Total Time: {results['total_time']:.1f}s ({results['total_time']/60:.1f} min)")
    print(f"Characters: {len(results['characters'])}")
    print(f"Dialogs: {len(results['all_dialogs'])}")

    if workflow == "2pass":
        print(f"Segments: {results.get('segments_processed', 'N/A')}")
        print(f"Traits: {len(results.get('all_traits', []))}")

    # Save results
    if workflow == "2pass":
        save_2pass_results(results)
    else:
        save_results(results)

    return results


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()
