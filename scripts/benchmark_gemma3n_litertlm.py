#!/usr/bin/env python3
"""
Benchmark script for Gemma 3n E2B (LiteRT-LM) model.

Uses the full text from 'Space story.pdf' as input. Runs in two passes: (1) Pass 1: ~3000 token input → characters + traits + voice_profile (JSON).
(2) Pass 2: ~1500 token segments → Character:Dialog mapping (character list from pass 1 + "Narrator").
Input is truncated/split at paragraph or sentence boundaries. On "Max number of tokens reached",
retries with 500 fewer tokens. Output is combined into one file.

Requires:
- PyMuPDF: pip install pymupdf
- LiteRT-LM CLI: Build litert_lm_main from https://github.com/google-ai-edge/LiteRT-LM
  (bazel build //runtime/engine:litert_lm_main --config=windows) and set LITERT_LM_MAIN
  or put the binary in PATH.

Model: gemma-3n-E2B-it-int4.litertlm
Ref: https://huggingface.co/google/gemma-3n-E2B-it-litert-lm

Speed: Use --session and LITERT_BACKEND=gpu (or npu on supported devices). Reduce PASS1_TARGET_TOKENS /
PASS2_TARGET_TOKENS in script for faster, shorter runs (more segments). See scripts/SPEED_AND_CONFIG.md.
"""

import os
import re
import sys
import time
import json
import subprocess
import tempfile
import threading
from pathlib import Path

# Optional: PyMuPDF for PDF extraction
try:
    import fitz  # PyMuPDF
except ImportError:
    fitz = None

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
MODEL_PATH = os.environ.get(
    "GEMMA3N_MODEL_PATH",
    r"D:\Learning\Ai\Models\LLM\gemma-3n-E2B-it-int4.litertlm"
)
PDF_PATH = os.environ.get(
    "BENCHMARK_PDF_PATH",
    str(Path(__file__).resolve().parent.parent / "Space story.pdf")
)
OUTPUT_DIR = Path(__file__).resolve().parent / "benchmarkResults"
SCRIPT_DIR = Path(__file__).resolve().parent
# Prefer lit.exe in script dir if present (prebuilt from LiteRT-LM releases), else litert_lm_main
LITERT_LM_MAIN = os.environ.get("LITERT_LM_MAIN") or (
    str(SCRIPT_DIR / "lit.exe") if (SCRIPT_DIR / "lit.exe").is_file() else "litert_lm_main"
)
BACKEND = os.environ.get("LITERT_BACKEND", "cpu")
# Alias used when running via lit CLI (model is copied to .litert-lm/models/ if needed)
LIT_MODEL_ALIAS = "gemma3n-e2b"

# Token-based input sizing (model context 4096; ~4 chars/token for English)
CHARS_PER_TOKEN = 4
PASS1_TARGET_TOKENS = 3000
PASS2_TARGET_TOKENS = 1500
RETRY_REDUCE_TOKENS = 500
MAX_PASS1_RETRIES = 4  # 3000 -> 2500 -> 2000 -> 1500
MAX_PASS2_RETRIES = 3   # 1500 -> 1000 -> 500

# Voice profile output format (from docs/my_prompt.md)
VOICE_PROFILE_FORMAT = """
For each character, output a voice_profile in this exact JSON shape:
{
  "character": "<CHARACTER_NAME>",
  "voice_profile": {
    "pitch": 1.0,
    "speed": 1.0,
    "energy": 1.0,
    "gender": "male|female|neutral",
    "age": "kid|teen|young|adult|middle-aged|elderly",
    "tone": "description",
    "accent": "description or neutral",
    "emotion_bias": {"happy": 0.3, "sad": 0.1, "angry": 0.2, "neutral": 0.4, "fear": 0.1, "surprise": 0.4, "excited": 0.5, "disappointed": 0.1, "curious": 0.3, "defiant": 0.1}
  }
}
Use values in ranges: pitch/speed/energy 0.5–1.5; emotion_bias 0.0–1.0. Infer from character traits and dialogs.
"""

# Session mode: keep lit running, send prompts via stdin to minimize model loads
SESSION_RESPONSE_IDLE_SEC = 10.0   # no new output for this long = response complete
SESSION_START_TIMEOUT_SEC = 300.0  # wait for "ready" prompt or first output
LIT_READY_MARKERS = ("Please enter the prompt", "press Enter to end", "input_prompt:")


def estimate_tokens(text: str) -> int:
    """Rough token count (~4 chars per token for English)."""
    return max(1, len(text) // CHARS_PER_TOKEN)


def _last_boundary_before(text: str, max_chars: int, prefer_paragraph: bool) -> int:
    """Return index to truncate at: full paragraph or full sentence before max_chars."""
    if len(text) <= max_chars:
        return len(text)
    chunk = text[: max_chars + 1]
    # Prefer paragraph boundary (\n\n)
    if prefer_paragraph:
        last_pp = chunk.rfind("\n\n")
        if last_pp > max_chars // 2:
            return last_pp + 2
    # Sentence boundary: last . ! ? followed by space or end
    for sep in (". ", "! ", "? ", ".\n", "!\n", "?\n"):
        last = chunk.rfind(sep)
        if last >= 0:
            return last + len(sep)
    # Fallback: last space to avoid mid-word
    last_space = chunk.rfind(" ")
    if last_space > max_chars // 2:
        return last_space + 1
    return max_chars


def truncate_to_tokens(text: str, max_tokens: int) -> str:
    """Truncate text to ~max_tokens at paragraph or sentence boundary."""
    max_chars = max_tokens * CHARS_PER_TOKEN
    if len(text) <= max_chars:
        return text
    idx = _last_boundary_before(text, max_chars, prefer_paragraph=True)
    return text[:idx].rstrip()


def split_into_segments(text: str, segment_tokens: int) -> list[str]:
    """Split text into segments of ~segment_tokens at paragraph or sentence boundary."""
    segment_chars = segment_tokens * CHARS_PER_TOKEN
    if len(text) <= segment_chars:
        return [text] if text.strip() else []
    segments = []
    start = 0
    while start < len(text):
        end_cap = min(start + segment_chars, len(text))
        if end_cap < len(text):
            slice_text = text[start : end_cap + 1]
            cut = _last_boundary_before(slice_text, segment_chars, prefer_paragraph=True)
            end = start + cut
        else:
            end = end_cap
        part = text[start:end].strip()
        if part:
            segments.append(part)
        start = end
    return segments


def extract_pdf_text(pdf_path: str) -> str:
    """Extract full text from PDF using PyMuPDF."""
    if not fitz:
        raise RuntimeError("PyMuPDF not installed. Run: pip install pymupdf")
    path = Path(pdf_path)
    if not path.is_file():
        raise FileNotFoundError(f"PDF not found: {pdf_path}")
    doc = fitz.open(pdf_path)
    try:
        text = "".join(page.get_text() for page in doc)
    finally:
        doc.close()
    return text.strip()


def split_book_into_chapters(book_text: str) -> list[tuple[str, str]]:
    """Split full book text into chapters. Returns [(chapter_title, chapter_body), ...]."""
    # Match "Chapter N" or "Chapter One" etc. at start of line
    chapter_pattern = re.compile(
        r"^\s*(Chapter\s+\d+|Chapter\s+[A-Za-z]+|CHAPTER\s+\d+)\s*[:\-]?\s*",
        re.IGNORECASE | re.MULTILINE,
    )
    matches = list(chapter_pattern.finditer(book_text))
    if not matches:
        return [("Full book", book_text.strip())]
    chapters = []
    for i, m in enumerate(matches):
        start = m.start()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(book_text)
        title = m.group(1).strip()
        body = book_text[start:end].strip()
        if body:
            chapters.append((title, body))
    return chapters if chapters else [("Full book", book_text.strip())]


def build_prompt_pass1(book_text: str) -> str:
    """Pass 1: characters + traits + voice_profile only (no dialogs)."""
    return f"""Analyze the following book text and provide the following in your response, in this order.

1. **Characters**: List all named characters in the book. Start with a single line: "Characters: Name1, Name2, Name3" (comma-separated), then you may repeat as a bullet list if you like.

2. **Traits and Vibe**: For each character, describe their personality, role, and overall vibe based on the text.

3. **Voice profile (TTS)**: For each character, infer a voice type suitable for text-to-speech. Output a JSON array with one object per character using this format:
{VOICE_PROFILE_FORMAT}

Use the full book text below. Do NOT include any dialog or Character:Dialog mapping in this response.

---
BOOK TEXT:
---
{book_text}
---
"""


def build_prompt_pass2(book_text: str, character_list: list[str]) -> str:
    """Pass 2: Character:Dialog mapping with pronoun/attribution rules (from app Pass-2.5)."""
    chars_str = ", ".join(character_list)
    return f"""The characters in this book are: {chars_str}.

Extract EVERY spoken or narrated line from the book text below. For each line, output exactly:
Character: "exact quote"

SPEAKER ATTRIBUTION RULES (do not assign dialogue to Narrator unless it is truly narration):
1. QUOTED SPEECH (text in "..." or '...'): Attribute to the speaking character, not Narrator.
   - Find the nearest character name appearing BEFORE or AFTER the quote (within a few sentences).
   - Use attribution cues: "said [Name]", "[Name] said", "[Name]:", "[Name] asked", "[Name] replied", "[Name] whispered", "[Name] shouted", "[Name] muttered", "[Name] answered", "[Name] cried", "[Name] called", etc.
   - PRONOUNS: If "he", "she", "they", "him", "her", "his" etc. refer to a character just mentioned in the same or previous sentence, that character is the speaker. Track who was last mentioned and attribute the next quote to them when the text uses a pronoun.
   - Only use "Narrator" for prose that is NOT inside quotation marks (scene description, action, thoughts not in quotes).
   - If the speaker of a quote truly cannot be determined from context, use "Unknown" (not Narrator).

2. NARRATOR: Use "Narrator" only for descriptive prose BETWEEN or AROUND dialogue—scene descriptions, actions, inner thoughts that are not in quotes. Do not use Narrator for quoted speech.

3. ORDER: One line per dialog/narration, in order of appearance. Do not skip or summarize—include every line.

---
BOOK TEXT:
---
{book_text}
---
"""


def parse_characters_from_pass1(pass1_output: str) -> list[str]:
    """Extract character names from pass 1 output; always include Narrator."""
    chars = []
    # Prefer "Characters: A, B, C" line
    m = re.search(r"Characters?\s*:\s*([^\n]+)", pass1_output, re.IGNORECASE)
    if m:
        part = m.group(1).strip()
        for name in re.split(r"[,;]|\band\b", part):
            name = name.strip().strip("*").strip()
            if name and len(name) < 50 and name not in ("Traits", "Vibe", "Voice", "profile"):
                chars.append(name)
    # Else collect bullet names under "Characters" section (e.g. * Jax)
    if not chars:
        in_section = False
        for line in pass1_output.splitlines():
            line_strip = line.strip()
            if re.match(r"#+\s*1\.?\s*\*?\s*Character", line_strip, re.I) or "**Characters**" in line_strip:
                in_section = True
                continue
            if in_section and (line_strip.startswith("*") or line_strip.startswith("-")):
                name = line_strip.lstrip("*-\t ").strip()
                if name and len(name) < 50 and not name.startswith("**"):
                    chars.append(name)
            if in_section and re.match(r"#+\s*2\.|^\s*\*\*\s*(Traits|Vibe)", line_strip, re.I):
                break
    if "Narrator" not in chars:
        chars.append("Narrator")
    return chars


class LitSession:
    """
    Keeps lit.exe running with the model loaded; sends prompts via stdin and reads
    responses from stdout to minimize model loads. Only used when lit CLI is detected
    (not litert_lm_main). Falls back to single-run if process dies or times out.
    """

    def __init__(self, model_arg: str, backend: str, exe: str, cwd: str | None):
        self.model_arg = model_arg
        self.backend = backend
        self.exe = exe
        self.cwd = cwd
        self._proc: subprocess.Popen | None = None
        self._out_buffer: list[str] = []
        self._out_lock = threading.Lock()
        self._reader_done = threading.Event()
        self._reader_exception: BaseException | None = None

    def _reader_thread(self) -> None:
        """Read stdout+stderr into _out_buffer; runs until process closes or exception."""
        try:
            if self._proc is None:
                return
            proc = self._proc
            # Merge stdout and stderr by reading both (lit may print prompt to stderr)
            while True:
                line = None
                if proc.stdout:
                    line = proc.stdout.readline()
                if line is None or line == "":
                    if proc.poll() is not None:
                        break
                    continue
                with self._out_lock:
                    self._out_buffer.append(line)
        except Exception as e:
            self._reader_exception = e
        finally:
            self._reader_done.set()

    def start(self) -> bool:
        """Spawn lit run <model> --backend <backend> without -f (interactive). Returns True if started."""
        args = [self.exe, "run", self.model_arg, "--backend", self.backend]
        try:
            self._proc = subprocess.Popen(
                args,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding="utf-8",
                errors="replace",
                cwd=self.cwd,
                bufsize=1,
            )
        except FileNotFoundError:
            return False
        self._out_buffer = []
        self._reader_done.clear()
        self._reader_exception = None
        t = threading.Thread(target=self._reader_thread, daemon=True)
        t.start()
        # Allow a short time for initial output (model load, optional "Please enter the prompt")
        time.sleep(2.0)
        return self._proc.poll() is None

    def send_prompt(self, prompt: str, idle_timeout_sec: float = SESSION_RESPONSE_IDLE_SEC) -> tuple[str, float] | None:
        """
        Send prompt via stdin (prompt + newline + empty line), then read stdout until
        idle_timeout_sec with no new data or a ready marker. Returns (combined_output, wall_seconds) or None on failure.
        """
        if self._proc is None or self._proc.poll() is not None:
            return None
        with self._out_lock:
            self._out_buffer.clear()
        start = time.perf_counter()
        try:
            self._proc.stdin.write(prompt)
            self._proc.stdin.write("\n\n")
            self._proc.stdin.flush()
        except (OSError, BrokenPipeError):
            return None
        last_len = 0
        last_change = time.perf_counter()
        while True:
            time.sleep(0.2)
            if self._proc.poll() is not None:
                break
            with self._out_lock:
                text = "".join(self._out_buffer)
            if len(text) > last_len:
                last_len = len(text)
                last_change = time.perf_counter()
            # End on idle timeout
            if (time.perf_counter() - last_change) >= idle_timeout_sec:
                break
            # End on ready marker (next turn prompt)
            if any(m in text for m in LIT_READY_MARKERS):
                # Only treat as end if we've seen substantial output (avoid first-time prompt)
                if last_len > 200:
                    break
            if self._reader_exception:
                break
        elapsed = time.perf_counter() - start
        with self._out_lock:
            out = "".join(self._out_buffer)
        # Strip known init/echo lines so we return mainly the model response
        out = out.strip()
        return out, elapsed

    def stop(self) -> None:
        """Send empty line to stdin to exit interactive loop, then wait for process."""
        if self._proc is None:
            return
        try:
            if self._proc.stdin and self._proc.poll() is None:
                self._proc.stdin.write("\n")
                self._proc.stdin.flush()
        except (OSError, BrokenPipeError):
            pass
        try:
            self._proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self._proc.kill()
        self._proc = None
        self._reader_done.wait(timeout=2)


def start_lit_session(model_path: str, backend: str) -> LitSession | None:
    """
    Start a LitSession if using lit CLI (not litert_lm_main). Resolves model path
    to cache/alias like run_litert_lm_main. Returns None if not lit CLI or start failed.
    """
    exe = LITERT_LM_MAIN
    if sys.platform == "win32" and not exe.lower().endswith(".exe"):
        alt = exe + ".exe"
        if Path(alt).is_file():
            exe = alt
    is_lit_cli = "lit" in Path(exe).name.lower() and "litert_lm_main" not in exe.lower()
    if not is_lit_cli:
        return None
    model_arg = model_path
    if Path(model_path).is_file():
        cache_dir = SCRIPT_DIR / ".litert-lm" / "models"
        cache_dir.mkdir(parents=True, exist_ok=True)
        cached = cache_dir / f"{LIT_MODEL_ALIAS}.litertlm"
        src_size = Path(model_path).stat().st_size
        if not cached.is_file() or cached.stat().st_size != src_size:
            import shutil
            shutil.copy2(model_path, cached)
        model_arg = LIT_MODEL_ALIAS
    cwd = str(SCRIPT_DIR)
    session = LitSession(model_arg, backend, exe, cwd)
    if not session.start():
        return None
    return session


def run_litert_lm_main(model_path: str, prompt: str, backend: str) -> tuple[str, float]:
    """
    Run LiteRT-LM CLI (lit or litert_lm_main) with the given model and prompt.
    Uses --input_prompt_file when prompt is long or when using lit CLI.
    Returns (stdout + stderr combined, wall_seconds).
    """
    exe = LITERT_LM_MAIN
    if sys.platform == "win32" and not exe.lower().endswith(".exe"):
        alt = exe + ".exe"
        if Path(alt).is_file():
            exe = alt

    is_lit_cli = "lit" in Path(exe).name.lower() and "litert_lm_main" not in exe.lower()

    # When using lit CLI with a local model path, copy to lit cache and use alias
    model_arg = model_path
    if is_lit_cli and Path(model_path).is_file():
        cache_dir = SCRIPT_DIR / ".litert-lm" / "models"
        cache_dir.mkdir(parents=True, exist_ok=True)
        cached = cache_dir / f"{LIT_MODEL_ALIAS}.litertlm"
        src_size = Path(model_path).stat().st_size
        if not cached.is_file() or cached.stat().st_size != src_size:
            import shutil
            shutil.copy2(model_path, cached)
        model_arg = LIT_MODEL_ALIAS

    # lit CLI only supports --input_prompt_file; litert_lm_main supports both
    use_file = is_lit_cli or len(prompt) > 8000
    prompt_file_path = None
    if use_file:
        fd, prompt_file_path = tempfile.mkstemp(suffix=".txt", prefix="litert_prompt_")
        try:
            os.write(fd, prompt.encode("utf-8"))
            os.close(fd)
            fd = None
        finally:
            if fd is not None:
                os.close(fd)

    if is_lit_cli:
        args = [
            exe,
            "run",
            model_arg,
            "--input_prompt_file",
            prompt_file_path,
            "--backend",
            backend,
        ]
    elif use_file:
        args = [
            exe,
            f"--backend={backend}",
            f"--model_path={model_path}",
            f"--input_prompt_file={prompt_file_path}",
        ]
    else:
        args = [
            exe,
            f"--backend={backend}",
            f"--model_path={model_path}",
            f"--input_prompt={prompt}",
        ]

    start = time.perf_counter()
    cwd = str(SCRIPT_DIR) if is_lit_cli else None  # lit looks for .litert-lm in cwd
    try:
        result = subprocess.run(
            args,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=600,
            cwd=cwd,
        )
        elapsed = time.perf_counter() - start
        out = (result.stdout or "") + "\n" + (result.stderr or "")
        if result.returncode != 0:
            out += f"\n[Process exited with code {result.returncode}]"
        return out.strip(), elapsed
    except FileNotFoundError:
        elapsed = time.perf_counter() - start
        return (
            f"Error: Could not run '{exe}'. Build LiteRT-LM and set LITERT_LM_MAIN or add to PATH.\n"
            "See: https://github.com/google-ai-edge/LiteRT-LM",
            elapsed,
        )
    except subprocess.TimeoutExpired:
        elapsed = time.perf_counter() - start
        return f"Error: Process timed out after {elapsed:.1f}s.", elapsed
    finally:
        if prompt_file_path and Path(prompt_file_path).is_file():
            try:
                os.unlink(prompt_file_path)
            except OSError:
                pass


MAX_TOKENS_ERR = "Error: stream error: Max number of tokens reached."


def _run_one_prompt(prompt: str, session: LitSession | None) -> tuple[str, float] | None:
    """Run one prompt via session if available, else run_litert_lm_main. Returns (raw_output, elapsed) or None on failure."""
    if session:
        return session.send_prompt(prompt)
    out, elapsed = run_litert_lm_main(MODEL_PATH, prompt, BACKEND)
    return out, elapsed


def run_benchmark_for_text(
    book_text: str,
    strip_stream_error_fn,
    hit_max_tokens_fn,
    session: LitSession | None = None,
) -> tuple[str, str, float, float, list[str]]:
    """Run two-pass benchmark on given text. Returns (output1, output2, time1, time2, character_list)."""
    pass1_tokens = PASS1_TARGET_TOKENS
    output1 = ""
    time1 = 0.0
    for attempt in range(MAX_PASS1_RETRIES):
        segment = truncate_to_tokens(book_text, pass1_tokens)
        prompt1 = build_prompt_pass1(segment)
        result = _run_one_prompt(prompt1, session)
        if result is None:
            result = run_litert_lm_main(MODEL_PATH, prompt1, BACKEND)
            out, elapsed = result
        else:
            out, elapsed = result
        time1 += elapsed
        output1 = strip_stream_error_fn(out)
        if not hit_max_tokens_fn(out):
            break
        pass1_tokens -= RETRY_REDUCE_TOKENS
    character_list = parse_characters_from_pass1(output1)
    segments_p2 = split_into_segments(book_text, PASS2_TARGET_TOKENS) or [book_text]
    output2_parts = []
    time2 = 0.0
    for seg in segments_p2:
        pass2_tokens = PASS2_TARGET_TOKENS
        out_clean = ""
        for _ in range(MAX_PASS2_RETRIES):
            seg_use = truncate_to_tokens(seg, pass2_tokens) if estimate_tokens(seg) > pass2_tokens else seg
            prompt2 = build_prompt_pass2(seg_use, character_list)
            result = _run_one_prompt(prompt2, session)
            if result is None:
                result = run_litert_lm_main(MODEL_PATH, prompt2, BACKEND)
                out, elapsed = result
            else:
                out, elapsed = result
            time2 += elapsed
            out_clean = strip_stream_error_fn(out)
            if not hit_max_tokens_fn(out):
                break
            pass2_tokens -= RETRY_REDUCE_TOKENS
        output2_parts.append(out_clean)
    output2 = "\n\n".join(output2_parts)
    return output1, output2, time1, time2, character_list


def main() -> None:
    import argparse
    parser = argparse.ArgumentParser(description="Gemma 3n E2B LiteRT-LM benchmark")
    parser.add_argument("--pdf", type=str, default=None, help="PDF path (default: BENCHMARK_PDF_PATH)")
    parser.add_argument("--output", type=str, default=None, help="Output file path (per-chapter if PDF has chapters)")
    parser.add_argument(
        "--session",
        action="store_true",
        help="Keep lit running with model loaded; send prompts via stdin to minimize model loads (lit CLI only)",
    )
    parser.add_argument(
        "--max-chapters",
        type=int,
        default=None,
        metavar="N",
        help="When using --output, run only the first N chapters (e.g. 1 for first chapter only)",
    )
    args = parser.parse_args()

    pdf_path = args.pdf or PDF_PATH
    out_path = Path(args.output).resolve() if args.output else None
    use_session = args.session or os.environ.get("LITERT_SESSION_MODE", "").lower() in ("1", "true", "yes")
    max_chapters = args.max_chapters

    print("=" * 60)
    print("Gemma 3n E2B (LiteRT-LM) benchmark")
    print("=" * 60)

    if not Path(MODEL_PATH).exists():
        print(f"ERROR: Model not found at {MODEL_PATH}")
        print("Set GEMMA3N_MODEL_PATH to your gemma-3n-E2B-it-int4.litertlm path.")
        sys.exit(1)

    session: LitSession | None = None
    if use_session:
        session = start_lit_session(MODEL_PATH, BACKEND)
        if session:
            print("\nSession mode: lit running with model loaded (prompts via stdin)")
        else:
            print("\nSession mode requested but not available (using per-call invocations)")
            session = None

    print(f"\n1. Extracting text from: {pdf_path}")
    if not Path(pdf_path).is_file():
        print(f"ERROR: PDF not found at {pdf_path}")
        sys.exit(1)
    try:
        book_text = extract_pdf_text(pdf_path)
    except Exception as e:
        print(f"ERROR: {e}")
        sys.exit(1)
    print(f"   Extracted {len(book_text)} characters")

    def strip_stream_error(text: str) -> str:
        return text.replace(MAX_TOKENS_ERR, "").strip()

    def hit_max_tokens(out: str) -> bool:
        return MAX_TOKENS_ERR in out

    if out_path:
        # Per-chapter run: detect chapters and run benchmark for each
        chapters = split_book_into_chapters(book_text)
        if max_chapters is not None:
            chapters = chapters[: max_chapters]
            print(f"   Detected {len(split_book_into_chapters(book_text))} chapters; running first {len(chapters)} only")
        else:
            print(f"   Detected {len(chapters)} chapters")
        out_path.parent.mkdir(parents=True, exist_ok=True)
        total_time1, total_time2 = 0.0, 0.0
        total_chapters = len(chapters)
        with open(out_path, "w", encoding="utf-8") as f:
            f.write("Gemma 3n E2B LiteRT-LM benchmark (per chapter)\n")
            f.write("=" * 60 + "\n")
            f.write(f"Model: {MODEL_PATH}\n")
            f.write(f"PDF: {pdf_path}\n")
            f.write("=" * 60 + "\n\n")
            for idx, (title, body) in enumerate(chapters):
                print(f"\n--- Chapter {idx + 1}/{total_chapters}: {title} ({len(body)} chars) ---")
                output1, output2, time1, time2, _ = run_benchmark_for_text(
                    body, strip_stream_error, hit_max_tokens, session=session
                )
                total_time1 += time1
                total_time2 += time2
                f.write(f"=== Chapter {idx + 1}: {title} ===\n\n")
                f.write("--- PASS 1: Characters + Traits + Voice profile ---\n\n")
                f.write(output1)
                f.write("\n\n--- PASS 2: Character:Dialog mapping ---\n\n")
                f.write(output2)
                f.write("\n\n")
        print(f"\nTotal wall time: Pass1={total_time1:.2f}s, Pass2={total_time2:.2f}s")
        print(f"   Output saved to: {out_path}")
        if session:
            session.stop()
        return

    # Single run (full book or default output)
    print("\n2. Pass 1: characters + traits + voice_profile (~3000 token input)...")
    output1, output2, time1, time2, character_list = run_benchmark_for_text(
        book_text, strip_stream_error, hit_max_tokens, session=session
    )
    print(f"   Characters for pass 2: {character_list}")
    total_seconds = time1 + time2
    print(f"\n4. Total wall time: {total_seconds:.2f} s")

    combined = (
        "=== PASS 1: Characters + Traits + Voice profile ===\n\n"
        + output1
        + "\n\n"
        + "=== PASS 2: Character:Dialog mapping ===\n\n"
        + output2
    )

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    out_file = out_path or (OUTPUT_DIR / "gemma3n_litertlm_benchmark_output.txt")
    if not out_file.is_absolute():
        out_file = OUTPUT_DIR / out_file
    with open(out_file, "w", encoding="utf-8") as f:
        f.write("Gemma 3n E2B LiteRT-LM benchmark\n")
        f.write("=" * 60 + "\n")
        f.write(f"Model: {MODEL_PATH}\n")
        f.write(f"PDF: {pdf_path} ({len(book_text)} chars)\n")
        f.write(f"Wall time: Pass1={time1:.2f}s, Pass2={time2:.2f}s, Total={total_seconds:.2f}s\n")
        f.write("=" * 60 + "\n\n")
        f.write(combined)
    print(f"   Output saved to: {out_file}")

    print("\n" + "-" * 60)
    print("Output preview (first 600 chars):")
    print("-" * 60)
    print(combined[:600] + ("..." if len(combined) > 600 else ""))
    print("-" * 60)

    if session:
        session.stop()


if __name__ == "__main__":
    main()
