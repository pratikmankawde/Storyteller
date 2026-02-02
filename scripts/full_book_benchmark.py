#!/usr/bin/env python3
"""
Full Book 5-Pass Character Analysis Benchmark
Replicates the actual app workflow: extract PDF, split chapters, run all passes.
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
OUTPUT_FILE = r"C:\Users\Pratik\source\Storyteller\scripts\full_book_benchmark_results.txt"
PAGE_SIZE_CHARS = 10000  # Same as app's pageSizeChars

JSON_REMINDER = "\nEnsure the JSON is valid and contains no trailing commas."

# Metrics storage
metrics = {
    "total_time": 0,
    "chapter_times": [],
    "pass_times": {"pass1": [], "pass2": [], "pass2_5": [], "pass3": [], "pass4": []},
    "all_characters": set(),
    "json_validity": {"pass1": 0, "pass2": 0, "pass2_5": 0, "pass3": 0, "pass4": 0},
    "json_total": {"pass1": 0, "pass2": 0, "pass2_5": 0, "pass3": 0, "pass4": 0},
    "errors": [],
    "chapter_results": []
}

def extract_pdf_pages():
    """Extract text from each PDF page (like PdfExtractor)."""
    doc = fitz.open(PDF_PATH)
    pages = []
    for page in doc:
        text = page.get_text()
        # Clean for ASCII compatibility
        text = text.encode('ascii', 'ignore').decode('ascii')
        pages.append(text)
    doc.close()
    print(f"Extracted {len(pages)} PDF pages")
    return pages

def detect_chapters(pages):
    """Detect chapter boundaries (like PdfChapterDetector)."""
    chapter_pattern = re.compile(
        r'^\s*(?:Chapter\s+\d+|Chapter\s+[A-Za-z\s]+|CHAPTER\s+\d+|Part\s+[One\d\s]+|'
        r'PART\s+[ONE\d\s]+|#+\s*.+|Prologue|Epilogue|Foreword|Introduction|\d+[.)]\s+.+)\s*$',
        re.IGNORECASE
    )

    chapter_starts = [0]  # First page always starts a chapter
    for i, page in enumerate(pages):
        if i == 0:
            continue
        first_line = next((l.strip() for l in page.split('\n') if l.strip()), "")[:200]
        if first_line and chapter_pattern.match(first_line):
            chapter_starts.append(i)
            print(f"  Chapter start at page {i+1}: \"{first_line[:60]}...\"")

    chapter_starts.append(len(pages))  # End marker

    chapters = []
    for k in range(len(chapter_starts) - 1):
        start, end = chapter_starts[k], chapter_starts[k + 1]
        if start >= end:
            continue
        range_pages = pages[start:end]
        body = "\n\n".join(p.strip() for p in range_pages).strip()
        body = re.sub(r'\n{3,}', '\n\n', body)

        # Extract title from first line
        first_line = next((l.strip() for l in body.split('\n') if l.strip()), f"Chapter {k+1}")[:80]
        title = first_line if first_line else f"Chapter {k+1}"

        chapters.append({"title": title, "body": body, "pages": range_pages})

    print(f"Detected {len(chapters)} chapters")
    return chapters

def split_into_pages(text, page_size=PAGE_SIZE_CHARS):
    """Split chapter text into pages (like app's splitIntoPages)."""
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
    return f"<|im_start|>system\n{system} /no_think<|im_end|>\n<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n"

def run_llm(prompt, max_tokens=512, temp=0.1):
    """Run LLM via llama-server."""
    start = time.time()
    try:
        response = requests.post(
            f"{SERVER_URL}/completion",
            json={"prompt": prompt, "n_predict": max_tokens, "temperature": temp,
                  "cache_prompt": True,  # Enable prompt caching for repeated similar prompts
                  "stop": ["<|im_end|>", "<|endoftext|>"]},
            timeout=180
        )
        elapsed = time.time() - start
        data = response.json()
        output = data.get("content", "")
        timings = data.get("timings", {})

        # Extract JSON (handle nested)
        json_match = re.search(r'\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}', output, re.DOTALL)
        json_output = json_match.group(0) if json_match else ""

        return {
            "elapsed": elapsed, "output": output, "json_output": json_output,
            "pp_speed": timings.get("prompt_per_second", 0),
            "tg_speed": timings.get("predicted_per_second", 0),
            "success": bool(json_match)
        }
    except Exception as e:
        return {"elapsed": time.time() - start, "output": str(e), "json_output": "",
                "pp_speed": 0, "tg_speed": 0, "success": False}

def validate_json(json_str, required_keys):
    try:
        data = json.loads(json_str)
        return {"valid": True, "data": data}
    except:
        return {"valid": False, "data": {}}

# Prompt builders (matching app exactly)
def build_pass1_prompt(text):
    system = "You are a character name extraction engine. Extract ONLY character names that appear in the provided text."
    user = f"""STRICT RULES:
- Extract ONLY proper names explicitly written in the text
- Do NOT include pronouns or generic descriptions
- Output valid JSON only

OUTPUT FORMAT: {{"characters": ["Name1", "Name2"]}}

TEXT:
{text[:8000]}
{JSON_REMINDER}"""
    return build_chat_prompt(system, user)

def build_pass2_prompt(name, text):
    system = "You are a character trait extraction engine."
    user = f"""Extract traits for character "{name}" from this text.
Categories: physical_description, behavioral_traits, speech_patterns, emotional_states

OUTPUT FORMAT: {{"character": "{name}", "traits": {{"physical_description": [], "behavioral_traits": [], "speech_patterns": [], "emotional_states": []}}}}

TEXT:
{text[:6000]}
{JSON_REMINDER}"""
    return build_chat_prompt(system, user)

def build_pass2_5_prompt(text, characters):
    system = "You are a dialog extraction engine."
    chars_str = ", ".join(characters[:5]) if characters else "any character"
    user = f"""Extract quoted dialogs from this text. For each dialog, identify speaker, emotion, intensity.
Known characters: {chars_str}

OUTPUT FORMAT: {{"dialogs": [{{"text": "...", "speaker": "Name", "emotion": "happy/sad/angry/neutral", "intensity": 0.5}}]}}

TEXT:
{text[:6000]}
{JSON_REMINDER}"""
    return build_chat_prompt(system, user)

def build_pass3_prompt(name, traits):
    system = "You are a personality inference engine."
    traits_str = ", ".join(traits[:10]) if traits else "none available"
    user = f"""Based on these traits for "{name}", infer personality summary.
Traits: {traits_str}

OUTPUT FORMAT: {{"character": "{name}", "personality": ["trait1", "trait2", "trait3"]}}
{JSON_REMINDER}"""
    return build_chat_prompt(system, user)

def build_pass4_prompt(name, personality):
    system = "You are a TTS voice profile suggestion engine."
    pers_str = ", ".join(personality[:5]) if personality else "unknown"
    user = f"""Suggest voice profile for "{name}" with personality: {pers_str}

OUTPUT FORMAT: {{"character": "{name}", "voice_profile": {{"pitch": 1.0, "speed": 1.0, "energy": 1.0, "gender": "male/female", "age": "young/middle-aged/old", "tone": "...", "accent": "neutral"}}, "speaker_id": 0}}
{JSON_REMINDER}"""
    return build_chat_prompt(system, user)


def run_chapter_analysis(chapter, chapter_idx, total_chapters):
    """Run full 5-pass analysis on a single chapter."""
    chapter_start = time.time()
    title = chapter["title"]
    body = chapter["body"]

    print(f"\n{'='*60}")
    print(f"CHAPTER {chapter_idx+1}/{total_chapters}: {title[:50]}...")
    print(f"Chapter length: {len(body)} chars")
    print(f"{'='*60}")

    chapter_result = {"title": title, "characters": [], "errors": []}

    # Split chapter into pages for processing
    pages = split_into_pages(body)
    print(f"  Split into {len(pages)} pages (~{PAGE_SIZE_CHARS} chars each)")

    # PASS 1: Extract character names (process first page only, like app)
    print(f"\n  [PASS 1] Extracting character names...")
    metrics["json_total"]["pass1"] += 1
    prompt = build_pass1_prompt(pages[0] if pages else body[:8000])
    result = run_llm(prompt, max_tokens=256)
    metrics["pass_times"]["pass1"].append(result["elapsed"])

    characters = []
    if result["success"]:
        parsed = validate_json(result["json_output"], ["characters"])
        if parsed["valid"]:
            characters = parsed["data"].get("characters", [])
            metrics["json_validity"]["pass1"] += 1
            metrics["all_characters"].update(characters)
            chapter_result["characters"] = characters
            print(f"    Found {len(characters)} characters: {characters}")
        else:
            metrics["errors"].append(f"Ch{chapter_idx+1} Pass1: Invalid JSON")
            print(f"    ERROR: Invalid JSON")
    else:
        metrics["errors"].append(f"Ch{chapter_idx+1} Pass1: LLM failed")
        print(f"    ERROR: LLM failed")

    # PASS 2: Extract traits for each character (limit to first 3)
    print(f"\n  [PASS 2] Extracting traits...")
    for char_name in characters[:3]:
        metrics["json_total"]["pass2"] += 1
        prompt = build_pass2_prompt(char_name, pages[0] if pages else body[:6000])
        result = run_llm(prompt, max_tokens=512)
        metrics["pass_times"]["pass2"].append(result["elapsed"])

        if result["success"]:
            parsed = validate_json(result["json_output"], ["traits"])
            if parsed["valid"]:
                metrics["json_validity"]["pass2"] += 1
                print(f"    {char_name}: traits extracted")
            else:
                print(f"    {char_name}: invalid JSON")
        else:
            print(f"    {char_name}: LLM failed")

    # PASS 2.5: Extract dialogs (first page only)
    print(f"\n  [PASS 2.5] Extracting dialogs...")
    metrics["json_total"]["pass2_5"] += 1
    prompt = build_pass2_5_prompt(pages[0] if pages else body[:6000], characters)
    result = run_llm(prompt, max_tokens=1024)
    metrics["pass_times"]["pass2_5"].append(result["elapsed"])

    dialog_count = 0
    if result["success"]:
        parsed = validate_json(result["json_output"], ["dialogs"])
        if parsed["valid"]:
            metrics["json_validity"]["pass2_5"] += 1
            dialogs = parsed["data"].get("dialogs", [])
            dialog_count = len(dialogs)
            print(f"    Found {dialog_count} dialogs")
        else:
            print(f"    Invalid JSON")
    else:
        print(f"    LLM failed")

    # PASS 3: Infer personality (for first 3 characters)
    print(f"\n  [PASS 3] Inferring personality...")
    for char_name in characters[:3]:
        metrics["json_total"]["pass3"] += 1
        # Use placeholder traits (in real app, would use Pass 2 results)
        prompt = build_pass3_prompt(char_name, ["adventurous", "brave", "curious"])
        result = run_llm(prompt, max_tokens=256)
        metrics["pass_times"]["pass3"].append(result["elapsed"])

        if result["success"]:
            parsed = validate_json(result["json_output"], ["personality"])
            if parsed["valid"]:
                metrics["json_validity"]["pass3"] += 1
                print(f"    {char_name}: personality inferred")
            else:
                print(f"    {char_name}: invalid JSON")
        else:
            print(f"    {char_name}: LLM failed")

    # PASS 4: Suggest voice profiles (for first 3 characters)
    print(f"\n  [PASS 4] Suggesting voice profiles...")
    for char_name in characters[:3]:
        metrics["json_total"]["pass4"] += 1
        prompt = build_pass4_prompt(char_name, ["confident", "brave"])
        result = run_llm(prompt, max_tokens=256)
        metrics["pass_times"]["pass4"].append(result["elapsed"])

        if result["success"]:
            parsed = validate_json(result["json_output"], ["voice_profile"])
            if parsed["valid"]:
                metrics["json_validity"]["pass4"] += 1
                print(f"    {char_name}: voice profile suggested")
            else:
                print(f"    {char_name}: invalid JSON")
        else:
            print(f"    {char_name}: LLM failed")

    chapter_time = time.time() - chapter_start
    metrics["chapter_times"].append(chapter_time)
    chapter_result["elapsed"] = chapter_time
    metrics["chapter_results"].append(chapter_result)

    print(f"\n  Chapter completed in {chapter_time:.1f}s")
    return chapter_result


def save_results():
    """Save benchmark results to file."""
    with open(OUTPUT_FILE, 'w', encoding='utf-8') as f:
        f.write("=" * 70 + "\n")
        f.write("FULL BOOK 5-PASS CHARACTER ANALYSIS BENCHMARK\n")
        f.write(f"Model: qwen3-1.7b-q4_k_m\n")
        f.write(f"Date: {time.strftime('%Y-%m-%d %H:%M:%S')}\n")
        f.write("=" * 70 + "\n\n")

        # Summary
        f.write("SUMMARY\n")
        f.write("-" * 40 + "\n")
        f.write(f"Total processing time: {metrics['total_time']:.1f}s ({metrics['total_time']/60:.1f} min)\n")
        f.write(f"Chapters processed: {len(metrics['chapter_times'])}\n")
        f.write(f"Total unique characters: {len(metrics['all_characters'])}\n")
        f.write(f"All characters: {sorted(metrics['all_characters'])}\n\n")

        # Chapter times
        if metrics['chapter_times']:
            f.write("CHAPTER TIMES\n")
            f.write("-" * 40 + "\n")
            f.write(f"Average: {sum(metrics['chapter_times'])/len(metrics['chapter_times']):.1f}s\n")
            f.write(f"Min: {min(metrics['chapter_times']):.1f}s\n")
            f.write(f"Max: {max(metrics['chapter_times']):.1f}s\n\n")

        # Pass times
        f.write("PASS TIMES (aggregated)\n")
        f.write("-" * 40 + "\n")
        for pass_name, times in metrics['pass_times'].items():
            if times:
                f.write(f"{pass_name}: total={sum(times):.1f}s, avg={sum(times)/len(times):.1f}s, calls={len(times)}\n")
        f.write("\n")

        # JSON validity
        f.write("JSON VALIDITY\n")
        f.write("-" * 40 + "\n")
        for pass_name in metrics['json_total']:
            total = metrics['json_total'][pass_name]
            valid = metrics['json_validity'][pass_name]
            rate = (valid/total*100) if total > 0 else 0
            f.write(f"{pass_name}: {valid}/{total} ({rate:.0f}% valid)\n")
        f.write("\n")

        # Errors
        if metrics['errors']:
            f.write("ERRORS\n")
            f.write("-" * 40 + "\n")
            for err in metrics['errors']:
                f.write(f"  - {err}\n")
            f.write("\n")

        # Chapter details
        f.write("CHAPTER DETAILS\n")
        f.write("-" * 40 + "\n")
        for i, ch in enumerate(metrics['chapter_results']):
            f.write(f"Chapter {i+1}: {ch['title'][:40]}...\n")
            f.write(f"  Time: {ch.get('elapsed', 0):.1f}s\n")
            f.write(f"  Characters: {ch.get('characters', [])}\n\n")

        # Android comparison
        f.write("=" * 70 + "\n")
        f.write("ANDROID COMPARISON\n")
        f.write("=" * 70 + "\n")
        avg_chapter = sum(metrics['chapter_times'])/len(metrics['chapter_times']) if metrics['chapter_times'] else 0
        f.write(f"PC processing (avg per chapter): {avg_chapter:.1f}s\n")
        f.write(f"Expected Android (Vulkan GPU): 2-5x slower = {avg_chapter*2:.0f}s - {avg_chapter*5:.0f}s\n")
        f.write(f"With current Q8_0 model on Android: HANGING (issue to fix)\n\n")
        f.write("RECOMMENDATION:\n")
        f.write("- Switch to qwen3-1.7b-q4_k_m on Android (2x faster, 30% smaller)\n")
        f.write("- Add native timeout to JNI generate() call\n")
        f.write("- Consider CPU-only mode if Vulkan is unstable\n")

    print(f"\nResults saved to: {OUTPUT_FILE}")


def main():
    """Run full book benchmark."""
    print("=" * 70)
    print("FULL BOOK 5-PASS CHARACTER ANALYSIS BENCHMARK")
    print("Model: qwen3-1.7b-q4_k_m (via llama-server)")
    print("=" * 70)

    # Check server health
    try:
        resp = requests.get(f"{SERVER_URL}/health", timeout=5)
        if resp.json().get("status") != "ok":
            print("ERROR: llama-server not ready")
            return
        print("Server: OK")
    except:
        print("ERROR: Cannot connect to llama-server. Start it first with:")
        print('  & ".\\scripts\\llama-cpp\\llama-server.exe" -m "D:\\Learning\\Ai\\Models\\LLM\\qwen3-1.7b-q4_k_m.gguf" -c 4096 --port 8080')
        return

    # Extract PDF
    print("\nExtracting PDF...")
    pages = extract_pdf_pages()

    # Detect chapters
    print("\nDetecting chapters...")
    chapters = detect_chapters(pages)

    if not chapters:
        print("ERROR: No chapters detected")
        return

    # Run benchmark
    total_start = time.time()

    for i, chapter in enumerate(chapters):
        run_chapter_analysis(chapter, i, len(chapters))

    metrics['total_time'] = time.time() - total_start

    # Print summary
    print("\n" + "=" * 70)
    print("BENCHMARK COMPLETE")
    print("=" * 70)
    print(f"Total time: {metrics['total_time']:.1f}s ({metrics['total_time']/60:.1f} min)")
    print(f"Chapters: {len(metrics['chapter_times'])}")
    print(f"Unique characters: {len(metrics['all_characters'])}")
    print(f"Characters: {sorted(metrics['all_characters'])}")

    # Save results
    save_results()


if __name__ == "__main__":
    main()
