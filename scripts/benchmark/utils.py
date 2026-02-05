"""
Utility functions for benchmark scripts.

Provides PDF extraction, text splitting, JSON validation, and other common utilities.
"""

import json
import re
import sys
from pathlib import Path
from typing import Optional

# Optional: PyMuPDF for PDF extraction
try:
    import fitz  # PyMuPDF
except ImportError:
    fitz = None

# Ensure UTF-8 output
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')


# ---------------------------------------------------------------------------
# PDF Extraction
# ---------------------------------------------------------------------------

def extract_pdf_text(pdf_path: str) -> str:
    """Extract full text from PDF using PyMuPDF.
    
    Args:
        pdf_path: Path to the PDF file
        
    Returns:
        Extracted text as a single string
        
    Raises:
        RuntimeError: If PyMuPDF is not installed
        FileNotFoundError: If PDF file doesn't exist
    """
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


def extract_pdf_pages(pdf_path: str, ascii_only: bool = True) -> list[str]:
    """Extract text from each PDF page separately.
    
    Args:
        pdf_path: Path to the PDF file
        ascii_only: If True, strip non-ASCII characters
        
    Returns:
        List of page texts
    """
    if not fitz:
        raise RuntimeError("PyMuPDF not installed. Run: pip install pymupdf")
    doc = fitz.open(pdf_path)
    pages = []
    for page in doc:
        text = page.get_text()
        if ascii_only:
            text = text.encode('ascii', 'ignore').decode('ascii')
        pages.append(text)
    doc.close()
    return pages


# ---------------------------------------------------------------------------
# Text Splitting
# ---------------------------------------------------------------------------

def _last_boundary_before(text: str, max_chars: int, prefer_paragraph: bool = True) -> int:
    """Return index to truncate at: full paragraph or full sentence before max_chars."""
    if len(text) <= max_chars:
        return len(text)
    chunk = text[:max_chars + 1]
    
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


def split_into_segments(text: str, segment_size: int = 4000) -> list[str]:
    """Split text into segments at sentence boundaries.
    
    Args:
        text: Text to split
        segment_size: Target size in characters per segment
        
    Returns:
        List of text segments
    """
    segments = []
    start = 0
    text_len = len(text)
    
    while start < text_len:
        end = min(start + segment_size, text_len)
        
        if end < text_len:
            segment = text[start:end]
            # Find last sentence boundary
            last_period = -1
            for i in range(len(segment) - 1, -1, -1):
                if segment[i] == '.':
                    if i + 1 >= len(segment) or segment[i + 1] in ' \n\t"\'':
                        last_period = i
                        break
            
            if last_period > 0:
                end = start + last_period + 1
            else:
                last_space = segment.rfind(' ')
                if last_space > 0:
                    end = start + last_space
        
        segment_text = text[start:end].strip()
        if segment_text:
            segments.append(segment_text)
        start = end
    
    return segments if segments else [text]


def split_into_pages(text: str, page_size: int = 10000) -> list[str]:
    """Split text into pages at word boundaries.
    
    Args:
        text: Text to split
        page_size: Target size in characters per page
        
    Returns:
        List of page texts
    """
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


def truncate_to_tokens(text: str, max_tokens: int, chars_per_token: int = 4) -> str:
    """Truncate text to approximately max_tokens at paragraph or sentence boundary.

    Args:
        text: Text to truncate
        max_tokens: Maximum number of tokens
        chars_per_token: Estimated characters per token (default 4 for English)

    Returns:
        Truncated text
    """
    max_chars = max_tokens * chars_per_token
    if len(text) <= max_chars:
        return text
    idx = _last_boundary_before(text, max_chars, prefer_paragraph=True)
    return text[:idx].rstrip()


def estimate_tokens(text: str, chars_per_token: int = 4) -> int:
    """Estimate token count for text.

    Args:
        text: Text to estimate
        chars_per_token: Estimated characters per token

    Returns:
        Estimated token count
    """
    return max(1, len(text) // chars_per_token)


# ---------------------------------------------------------------------------
# Chapter Detection
# ---------------------------------------------------------------------------

def split_into_chapters(book_text: str) -> list[tuple[str, str]]:
    """Split full book text into chapters.

    Args:
        book_text: Full book text

    Returns:
        List of (chapter_title, chapter_body) tuples
    """
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


def detect_chapters_from_pages(pages: list[str]) -> list[dict]:
    """Detect chapter boundaries from PDF pages.

    Args:
        pages: List of page texts

    Returns:
        List of chapter dicts with 'title', 'body', 'pages' keys
    """
    chapter_pattern = re.compile(
        r'^\s*(?:Chapter\s+\d+|Chapter\s+[A-Za-z\s]+|CHAPTER\s+\d+|Part\s+[One\d\s]+|'
        r'PART\s+[ONE\d\s]+|#+\s*.+|Prologue|Epilogue|Foreword|Introduction|\d+[.)]\s+.+)\s*$',
        re.IGNORECASE
    )

    chapter_starts = [0]
    for i, page in enumerate(pages):
        if i == 0:
            continue
        first_line = next((l.strip() for l in page.split('\n') if l.strip()), "")[:200]
        if first_line and chapter_pattern.match(first_line):
            chapter_starts.append(i)

    chapter_starts.append(len(pages))

    chapters = []
    for k in range(len(chapter_starts) - 1):
        start, end = chapter_starts[k], chapter_starts[k + 1]
        if start >= end:
            continue
        range_pages = pages[start:end]
        body = "\n\n".join(p.strip() for p in range_pages).strip()
        body = re.sub(r'\n{3,}', '\n\n', body)

        first_line = next((l.strip() for l in body.split('\n') if l.strip()), f"Chapter {k+1}")[:80]
        title = first_line if first_line else f"Chapter {k+1}"

        chapters.append({"title": title, "body": body, "pages": range_pages})

    return chapters


# ---------------------------------------------------------------------------
# JSON Validation
# ---------------------------------------------------------------------------

def validate_json(json_str: str, required_keys: Optional[list[str]] = None) -> dict:
    """Validate JSON string and check for required keys.

    Args:
        json_str: JSON string to validate
        required_keys: Optional list of required keys

    Returns:
        Dict with 'valid', 'data', 'error', 'missing_keys' fields
    """
    try:
        data = json.loads(json_str)
        missing = [k for k in (required_keys or []) if k not in data]
        return {"valid": True, "data": data, "error": None, "missing_keys": missing}
    except json.JSONDecodeError as e:
        return {"valid": False, "data": {}, "error": str(e), "missing_keys": required_keys or []}


def extract_json_from_text(text: str) -> str:
    """Extract JSON object from text that may contain other content.

    Args:
        text: Text potentially containing JSON

    Returns:
        Extracted JSON string or empty string if not found
    """
    # Remove thinking tags
    text = re.sub(r'<think>.*?</think>', '', text, flags=re.DOTALL)
    text = re.sub(r'<think>.*', '', text, flags=re.DOTALL)
    text = text.replace('/no_think', '').strip()

    # Pattern 1: JSON with arrays inside
    json_match = re.search(r'\{[^{}]*\[[^\]]*\][^{}]*\}', text, re.DOTALL)
    if json_match:
        return json_match.group(0)

    # Pattern 2: Nested JSON with voice_profile object
    json_match = re.search(r'\{[^{}]*"voice_profile"\s*:\s*\{[^{}]*\}[^{}]*\}', text, re.DOTALL)
    if json_match:
        return json_match.group(0)

    # Pattern 3: Any JSON object with nested braces
    json_match = re.search(r'\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}', text, re.DOTALL)
    if json_match:
        return json_match.group(0)

    # Pattern 4: Simple JSON object (greedy, last resort)
    json_match = re.search(r'\{.*\}', text, re.DOTALL)
    if json_match:
        return json_match.group(0)

    return ""


# ---------------------------------------------------------------------------
# Character Parsing
# ---------------------------------------------------------------------------

def parse_characters_from_output(output: str) -> list[str]:
    """Extract character names from LLM output.

    Args:
        output: LLM output text

    Returns:
        List of character names (always includes 'Narrator')
    """
    chars = []

    # Prefer "Characters: A, B, C" line
    m = re.search(r"Characters?\s*:\s*([^\n]+)", output, re.IGNORECASE)
    if m:
        part = m.group(1).strip()
        for name in re.split(r"[,;]|\band\b", part):
            name = name.strip().strip("*").strip()
            if name and len(name) < 50 and name not in ("Traits", "Vibe", "Voice", "profile"):
                chars.append(name)

    # Else collect bullet names under "Characters" section
    if not chars:
        in_section = False
        for line in output.splitlines():
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

