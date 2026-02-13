#!/usr/bin/env python3
"""
LLM Prompt Tester for Character/Dialog Extraction

This script replicates the LLM Benchmark functionality from LlmBenchmarkActivity.kt
to iteratively test and improve character/dialog extraction prompts.

Features:
- Uses GGUF model via llama-cpp-python
- Extracts text from PDF using PyMuPDF
- Implements the same analysis workflow as the Android app
- Calculates precision, recall, F1 scores
- Tracks prompt versions and their scores
- Supports iterative prompt refinement

Usage:
    python prompt_tester.py --model <path_to_gguf> --pdf <path_to_pdf> --expected <path_to_json>
"""

import argparse
import json
import logging
import os
import re
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, Optional

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[
        logging.StreamHandler(),
        logging.FileHandler('prompt_tester.log', mode='a')
    ]
)
logger = logging.getLogger(__name__)


# =============================================================================
# Data Classes
# =============================================================================

@dataclass
class ExtractedCharacter:
    """Extracted character data."""
    name: str
    dialogs: list[str] = field(default_factory=list)
    traits: list[str] = field(default_factory=list)
    voice_profile: dict = field(default_factory=dict)


@dataclass
class ExpectedData:
    """Expected results from SpaceStoryAnalysis.json."""
    characters: list[str]
    chapters: list[dict]

    @classmethod
    def from_json(cls, json_path: str) -> "ExpectedData":
        """Load expected data from JSON file."""
        with open(json_path, 'r', encoding='utf-8') as f:
            data = json.load(f)
        return cls(
            characters=data.get("characters", []),
            chapters=data.get("chapters", [])
        )

    def get_all_dialogs(self) -> list[tuple[str, str]]:
        """Get all dialogs as (speaker, text) tuples."""
        dialogs = []
        for chapter in self.chapters:
            for dialog in chapter.get("dialogs", []):
                dialogs.append((dialog["speaker"], dialog["text"]))
        return dialogs


@dataclass
class EvaluationMetrics:
    """Evaluation metrics for extraction results."""
    precision: float = 0.0
    recall: float = 0.0
    f1: float = 0.0
    true_positives: int = 0
    false_positives: int = 0
    false_negatives: int = 0

    def __str__(self) -> str:
        return f"P={self.precision:.2%}, R={self.recall:.2%}, F1={self.f1:.2%}"


@dataclass
class BenchmarkResult:
    """Complete benchmark result."""
    timestamp: str
    model_name: str
    prompt_version: str
    character_metrics: EvaluationMetrics
    dialog_metrics: EvaluationMetrics
    extracted_characters: list[str]
    expected_characters: list[str]
    extracted_dialogs: list[tuple[str, str]]
    expected_dialogs: list[tuple[str, str]]
    timing_ms: dict[str, float] = field(default_factory=dict)
    raw_llm_responses: list[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        """Convert to dictionary for JSON serialization."""
        return {
            "timestamp": self.timestamp,
            "model_name": self.model_name,
            "prompt_version": self.prompt_version,
            "character_metrics": {
                "precision": self.character_metrics.precision,
                "recall": self.character_metrics.recall,
                "f1": self.character_metrics.f1,
                "true_positives": self.character_metrics.true_positives,
                "false_positives": self.character_metrics.false_positives,
                "false_negatives": self.character_metrics.false_negatives,
            },
            "dialog_metrics": {
                "precision": self.dialog_metrics.precision,
                "recall": self.dialog_metrics.recall,
                "f1": self.dialog_metrics.f1,
            },
            "extracted_characters": self.extracted_characters,
            "expected_characters": self.expected_characters,
            "timing_ms": self.timing_ms,
        }


# =============================================================================
# Prompt Definitions (Extracted from Android App)
# =============================================================================

class PromptDefinitions:
    """Prompt definitions matching the Android app exactly."""

    # Version tracking for iterative refinement
    VERSION = "v5.0"  # Added pronoun resolution and repetition penalties

    # Temperature settings (matching Android app - very low for consistency)
    BATCHED_ANALYSIS_TEMPERATURE = 0.01  # Android uses 0.01
    CHARACTER_EXTRACTION_TEMPERATURE = 0.1
    DIALOG_EXTRACTION_TEMPERATURE = 0.15
    VOICE_PROFILE_TEMPERATURE = 0.2

    # Token budget configuration (from BatchedPipelineConfig.kt)
    CHARS_PER_TOKEN = 4
    ANALYSIS_PROMPT_TOKENS = 300
    ANALYSIS_INPUT_TOKENS = 3700
    ANALYSIS_OUTPUT_TOKENS = 2048  # Increased from 1000 to allow complete output
    ANALYSIS_MAX_INPUT_CHARS = ANALYSIS_INPUT_TOKENS * CHARS_PER_TOKEN  # 14,800

    # -------------------------------------------------------------------------
    # Batched Analysis Prompt (EXACT COPY from BatchedAnalysisPrompt.kt)
    # -------------------------------------------------------------------------

    BATCHED_SYSTEM_PROMPT = """You are a Story analysis engine. Output one complete and valid JSON object as requested in the user prompt, from the given Story excerpt."""

    @classmethod
    def build_batched_analysis_prompt(cls, text: str) -> str:
        """Build the batched analysis prompt (matching BatchedAnalysisPrompt.kt)."""
        return f'''Extract all the characters, dialogs spoken by them, their traits and inferred voice profile from the given Story excerpt.
RULES:
1. ONLY include Characters who have quoted dialogs.
2. DO NOT classify locations, objects, creatures or entities that don't speak as Characters.
3. Do not repeat Characters in the output.
4. Attribute dialogs by Character name and pronouns referring them. Each dialog belongs to only one Character.
5. Identify Character traits explicitly mentioned in the story by the Narrator.
6. Based on the traits, infer a voice profile.

Keys for output:
D:Array of exact quoted dialogs spoken by current Character
T:Array of Character traits (personalities, adjectives)
V:Voice profile as a tuple of "Gender,Age,Accent,Pitch,Speed".
Possible values:
Gender (inferred from pronouns): male|female
Age (explicitly mentioned or inferred): child|young|young-adult|middle-aged|elderly
Accent (inferred from the dialogs): neutral|british|american|asian
Pitch (of voice) within the range: 0.5-1.5
Speed (speed of speaking) within the range: 0.5-2.0

OUTPUT FORMAT:
{
  "CharacterName1": {"D": ["this character's first dialog", "their next dialog"], "T": ["trait", "another trait"], "V": "Gender,Age,Accent,Pitch,Speed"},
  "CharacterName2": {"D": ["this character's first dialog"], "T": ["trait"], "V": "Gender,Age,Accent,Pitch,Speed"}
}

Story Excerpt:
{text}

JSON:'''

    # -------------------------------------------------------------------------
    # Character Extraction Prompt (from CharacterExtractionPrompt.kt)
    # -------------------------------------------------------------------------

    CHARACTER_SYSTEM_PROMPT = """You are a character name extraction engine. Extract ONLY character names that appear in the provided story text."""

    @classmethod
    def build_character_extraction_prompt(cls, text: str) -> str:
        """Build Pass-1 character extraction prompt."""
        return f'''OUTPUT FORMAT (valid JSON only):
{{"characters": ["Name1", "Name2", "Name3"]}}

TEXT:
{text}'''

    # -------------------------------------------------------------------------
    # Dialog Extraction Prompt (from DialogExtractionPrompt.kt)
    # -------------------------------------------------------------------------

    DIALOG_SYSTEM_PROMPT = """You are a dialog extraction engine. Extract ALL spoken dialogs from the story. Resolve pronouns (he/she/they) to the actual character name based on context."""

    @classmethod
    def build_dialog_extraction_prompt(cls, text: str, character_names: list[str]) -> str:
        """Build Pass-2 dialog extraction prompt with pronoun resolution."""
        chars_str = ", ".join(character_names)
        return f'''Extract ALL dialogs spoken by these characters: {chars_str}

RULES:
1. Extract EVERY quoted dialog from the text
2. Identify the speaker for each dialog (look for "X said", "X asked", "X shouted", etc.)
3. When pronouns are used (he/she/they said), resolve to the actual character name from context
4. Include dialogs from ALL parts of the story (beginning, middle, and end)
5. Each dialog should appear EXACTLY ONCE

OUTPUT FORMAT (JSON array, one entry per dialog in order of appearance):
[{{"speaker": "CharacterName", "text": "exact dialog text"}}]

CHARACTERS: {chars_str}

TEXT:
{text}

JSON:'''



# =============================================================================
# LLM Engine (supports llama-server HTTP API or llama-cpp-python)
# =============================================================================

class LLMEngine:
    """LLM inference engine supporting multiple backends."""

    def __init__(
        self,
        model_path: str = None,
        server_url: str = None,
        n_ctx: int = 4096,
        n_gpu_layers: int = -1
    ):
        """Initialize the LLM engine.

        Args:
            model_path: Path to the GGUF model file (for llama-cpp-python)
            server_url: URL of llama-server (e.g., http://127.0.0.1:8080)
            n_ctx: Context window size
            n_gpu_layers: Number of layers to offload to GPU (-1 = all)
        """
        self.model_path = model_path
        self.server_url = server_url
        self.n_ctx = n_ctx
        self.n_gpu_layers = n_gpu_layers
        self._llm = None
        self._session = None

        # Determine backend
        if server_url:
            self.backend = "server"
            self.model_name = "llama-server"
            import requests
            self._session = requests.Session()
            logger.info(f"Using llama-server at {server_url}")
        elif model_path:
            self.backend = "llama-cpp"
            self.model_name = Path(model_path).stem
        else:
            raise ValueError("Either model_path or server_url must be provided")

    def _load_model(self):
        """Lazy load the model (for llama-cpp-python backend)."""
        if self.backend != "llama-cpp" or self._llm is not None:
            return

        logger.info(f"Loading model: {self.model_path}")
        try:
            from llama_cpp import Llama
            self._llm = Llama(
                model_path=self.model_path,
                n_ctx=self.n_ctx,
                n_gpu_layers=self.n_gpu_layers,
                verbose=False,
            )
            logger.info("Model loaded successfully")
        except ImportError:
            raise RuntimeError(
                "llama-cpp-python not installed. Either:\n"
                "  1. Install it: pip install llama-cpp-python\n"
                "  2. Use llama-server: --server http://127.0.0.1:8080"
            )

    def _build_prompt(self, system_prompt: str, user_prompt: str) -> str:
        """Build ChatML format prompt."""
        prompt = f"<|im_start|>system\n{system_prompt}<|im_end|>\n"
        prompt += f"<|im_start|>user\n{user_prompt}<|im_end|>\n"
        prompt += "<|im_start|>assistant\n"
        return prompt

    def generate(
        self,
        system_prompt: str,
        user_prompt: str,
        temperature: float = 0.1,
        max_tokens: int = 2048
    ) -> str:
        """Generate text using the LLM.

        Args:
            system_prompt: System message
            user_prompt: User message
            temperature: Sampling temperature
            max_tokens: Maximum tokens to generate

        Returns:
            Generated text
        """
        prompt = self._build_prompt(system_prompt, user_prompt)
        logger.debug(f"Prompt length: {len(prompt)} chars")

        if self.backend == "server":
            return self._generate_server(prompt, temperature, max_tokens)
        else:
            return self._generate_llama_cpp(prompt, temperature, max_tokens)

    def _generate_server(self, prompt: str, temperature: float, max_tokens: int) -> str:
        """Generate using llama-server HTTP API."""
        import requests

        payload = {
            "prompt": prompt,
            "n_predict": max_tokens,
            "temperature": temperature,
            "stop": ["<|im_end|>", "<|endoftext|>"],
            "cache_prompt": True,
            # Light repetition penalties - balance between stopping loops and completing output
            "repeat_penalty": 1.1,
            "repeat_last_n": 64,
        }

        try:
            resp = self._session.post(
                f"{self.server_url}/completion",
                json=payload,
                timeout=300,
            )
            resp.raise_for_status()
            response = resp.json().get("content", "").strip()
            logger.debug(f"Response length: {len(response)} chars")
            return response
        except requests.RequestException as e:
            logger.error(f"Server request failed: {e}")
            return ""

    def _generate_llama_cpp(self, prompt: str, temperature: float, max_tokens: int) -> str:
        """Generate using llama-cpp-python."""
        self._load_model()

        output = self._llm(
            prompt,
            max_tokens=max_tokens,
            temperature=temperature,
            stop=["<|im_end|>", "<|endoftext|>"],
        )

        response = output["choices"][0]["text"].strip()
        logger.debug(f"Response length: {len(response)} chars")
        return response

    def health_check(self) -> bool:
        """Check if the backend is ready."""
        if self.backend == "server":
            try:
                import requests
                resp = self._session.get(f"{self.server_url}/health", timeout=5)
                return resp.status_code == 200
            except:
                return False
        else:
            try:
                self._load_model()
                return self._llm is not None
            except:
                return False

    def close(self):
        """Release resources."""
        self._llm = None
        if self._session:
            self._session.close()
            self._session = None


# =============================================================================
# PDF Extractor
# =============================================================================

class PDFExtractor:
    """Extract text from PDF files using PyMuPDF."""

    @staticmethod
    def extract_text(pdf_path: str) -> str:
        """Extract full text from PDF.

        Args:
            pdf_path: Path to the PDF file

        Returns:
            Extracted text as a single string
        """
        try:
            import fitz  # PyMuPDF
        except ImportError:
            raise RuntimeError("PyMuPDF not installed. Run: pip install pymupdf")

        path = Path(pdf_path)
        if not path.is_file():
            raise FileNotFoundError(f"PDF not found: {pdf_path}")

        logger.info(f"Extracting text from: {pdf_path}")
        doc = fitz.open(pdf_path)
        try:
            text = ""
            for page in doc:
                text += page.get_text()
            logger.info(f"Extracted {len(text)} characters from {doc.page_count} pages")
            return text.strip()
        finally:
            doc.close()

    @staticmethod
    def extract_pages(pdf_path: str) -> list[str]:
        """Extract text from each page separately.

        Args:
            pdf_path: Path to the PDF file

        Returns:
            List of page texts
        """
        try:
            import fitz
        except ImportError:
            raise RuntimeError("PyMuPDF not installed. Run: pip install pymupdf")

        path = Path(pdf_path)
        if not path.is_file():
            raise FileNotFoundError(f"PDF not found: {pdf_path}")

        doc = fitz.open(pdf_path)
        try:
            pages = [page.get_text().strip() for page in doc]
            logger.info(f"Extracted {len(pages)} pages")
            return pages
        finally:
            doc.close()



# =============================================================================
# JSON Parser (matching Android app's robust parsing)
# =============================================================================

class JSONParser:
    """Robust JSON parser matching BatchedAnalysisPrompt.kt logic."""

    @staticmethod
    def extract_json_from_response(response: str) -> Optional[str]:
        """Extract JSON from LLM response, handling markdown code blocks."""
        text = response.strip()

        # Remove markdown code blocks
        code_block_pattern = r'```(?:json)?\s*([\s\S]*?)```'
        match = re.search(code_block_pattern, text)
        if match:
            text = match.group(1).strip()

        # Find JSON object or array
        # Try to find object first
        obj_start = text.find('{')
        arr_start = text.find('[')

        if obj_start == -1 and arr_start == -1:
            return None

        if obj_start != -1 and (arr_start == -1 or obj_start < arr_start):
            # Find matching brace
            return JSONParser._extract_balanced(text, obj_start, '{', '}')
        else:
            # Find matching bracket
            return JSONParser._extract_balanced(text, arr_start, '[', ']')

    @staticmethod
    def _extract_balanced(text: str, start: int, open_char: str, close_char: str) -> Optional[str]:
        """Extract balanced brackets/braces."""
        depth = 0
        in_string = False
        escape_next = False

        for i in range(start, len(text)):
            char = text[i]

            if escape_next:
                escape_next = False
                continue

            if char == '\\':
                escape_next = True
                continue

            if char == '"':
                in_string = not in_string
                continue

            if in_string:
                continue

            if char == open_char:
                depth += 1
            elif char == close_char:
                depth -= 1
                if depth == 0:
                    return text[start:i+1]

        # If unbalanced (truncated), try to repair by finding last complete entry
        if open_char == '{' and depth > 0:
            return JSONParser._repair_truncated_json(text[start:])
        return None

    @staticmethod
    def _repair_truncated_json(json_str: str) -> Optional[str]:
        """Attempt to repair truncated JSON by removing incomplete trailing entries."""
        # Find the last complete key-value pair by finding last complete nested object
        # Look for pattern: "Name": {...}, and truncate after last complete one

        # Find all complete character entries: "Name": {"D": [...], "T": [...], "V": "..."}
        # We'll do this by finding the last }, that's followed by potential whitespace/comma

        last_good_pos = -1
        depth = 0
        in_string = False
        escape_next = False

        for i, char in enumerate(json_str):
            if escape_next:
                escape_next = False
                continue
            if char == '\\':
                escape_next = True
                continue
            if char == '"':
                in_string = not in_string
                continue
            if in_string:
                continue

            if char == '{':
                depth += 1
            elif char == '}':
                depth -= 1
                if depth == 1:  # Just closed a nested object (character entry)
                    last_good_pos = i

        if last_good_pos > 0:
            # Truncate at last complete entry and close the outer object
            repaired = json_str[:last_good_pos + 1]
            # Remove trailing comma if present
            repaired = repaired.rstrip().rstrip(',')
            repaired += '}'
            logger.debug(f"Repaired truncated JSON, length: {len(repaired)}")
            return repaired

        return None

    @staticmethod
    def _truncate_at_duplicate_key(json_str: str) -> str:
        """Truncate JSON at first duplicate key (matching Android's truncateAtDuplicateKey).

        This handles LLM repetition by cutting off at the first repeated character name.
        """
        seen_keys = set()
        depth = 0
        in_string = False
        escape_next = False
        key_start = -1
        current_key = None
        last_valid_end = -1

        for i, c in enumerate(json_str):
            if escape_next:
                escape_next = False
                continue
            if c == '\\':
                escape_next = True
                continue

            if c == '"':
                if not in_string:
                    in_string = True
                    if depth == 1:
                        key_start = i + 1  # Start of key (after quote)
                else:
                    in_string = False
                    if depth == 1 and key_start >= 0:
                        # End of a top-level key
                        current_key = json_str[key_start:i]
                        key_start = -1
                continue

            if in_string:
                continue

            if c == '{':
                depth += 1
                if depth == 2 and current_key:
                    # Starting a character object - check for duplicate
                    if current_key.lower() in seen_keys:
                        logger.warning(f"Found duplicate key '{current_key}' at position {i}, truncating")
                        # Truncate just before this key
                        truncate_pos = JSONParser._find_truncate_position(json_str, i, current_key)
                        truncated = json_str[:truncate_pos] + "}"
                        return truncated
                    seen_keys.add(current_key.lower())
            elif c == '}':
                if depth == 2:
                    # End of a character object - this is a valid point
                    last_valid_end = i
                depth -= 1

        # If JSON doesn't end properly but we have a valid position, truncate there
        if last_valid_end > 0 and not json_str.rstrip().endswith('}'):
            return json_str[:last_valid_end + 1] + "}"

        return json_str

    @staticmethod
    def _find_truncate_position(json_str: str, current_pos: int, duplicate_key: str) -> int:
        """Find position to truncate before a duplicate key."""
        pos = current_pos
        depth = 0
        while pos > 0:
            pos -= 1
            c = json_str[pos]
            if c == '}':
                depth += 1
            elif c == '{':
                if depth == 0:
                    # Continue searching for comma
                    pass
                depth -= 1
            elif c == ',' and depth == 0:
                # Found the comma before the duplicate entry
                return pos
        return current_pos

    @staticmethod
    def _parse_json_with_duplicates(json_str: str) -> dict[str, dict]:
        """Parse JSON that may have duplicate keys, keeping first occurrence of each."""
        # First, truncate at duplicate keys (matching Android behavior)
        json_str = JSONParser._truncate_at_duplicate_key(json_str)

        result = {}
        # Use a custom decoder that tracks seen keys
        try:
            # First try normal parsing
            data = json.loads(json_str)
            return data if isinstance(data, dict) else {}
        except json.JSONDecodeError:
            pass

        # Manual extraction for duplicate key handling
        # Pattern: "CharName": {...}
        pattern = r'"([^"]+)":\s*(\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\})'
        seen = set()
        for match in re.finditer(pattern, json_str):
            name = match.group(1)
            if name.lower() not in seen:
                seen.add(name.lower())
                try:
                    obj = json.loads(match.group(2))
                    result[name] = obj
                except json.JSONDecodeError:
                    continue
        return result

    @staticmethod
    def parse_batched_response(response: str) -> dict[str, dict]:
        """Parse batched analysis response.

        Handles multiple formats:
        - Single JSON object
        - JSONL (multiple JSON objects on separate lines)
        - Duplicate keys (keeps first occurrence)
        - Truncated JSON (repairs by removing incomplete entries)

        Returns:
            Dictionary mapping character names to their data
        """
        json_str = JSONParser.extract_json_from_response(response)
        if not json_str:
            logger.warning("No JSON found in response")
            # Try direct repair on raw response
            json_str = JSONParser._repair_truncated_json(response)
            if not json_str:
                return {}

        # Try to parse, handling duplicates
        data = JSONParser._parse_json_with_duplicates(json_str)
        if data:
            return JSONParser._normalize_character_data(data)

        # Try to extract multiple JSON objects and merge
        objects = JSONParser._extract_multiple_json_objects(response)
        if objects:
            merged = {}
            for obj in objects:
                merged.update(obj)
            return JSONParser._normalize_character_data(merged)

        return {}

    @staticmethod
    def _extract_multiple_json_objects(text: str) -> list[dict]:
        """Extract multiple JSON objects from text (JSONL format)."""
        objects = []
        pattern = r'\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}'

        for match in re.finditer(pattern, text):
            try:
                obj = json.loads(match.group())
                objects.append(obj)
            except json.JSONDecodeError:
                continue

        return objects

    @staticmethod
    def _normalize_character_data(data: dict) -> dict[str, dict]:
        """Normalize character data to standard format."""
        result = {}
        for name, char_data in data.items():
            if not isinstance(char_data, dict):
                continue

            normalized = {
                "dialogs": [],
                "traits": [],
                "voice": ""
            }

            # Handle different key formats (D/d/dialogs, T/t/traits, V/v/voice)
            for key, value in char_data.items():
                key_lower = key.lower()
                if key_lower in ('d', 'dialogs', 'dialogue', 'dialogues'):
                    if isinstance(value, list):
                        normalized["dialogs"] = value
                elif key_lower in ('t', 'traits', 'trait'):
                    if isinstance(value, list):
                        normalized["traits"] = value
                elif key_lower in ('v', 'voice', 'voice_profile'):
                    if isinstance(value, str):
                        normalized["voice"] = value

            result[name] = normalized

        return result

    @staticmethod
    def parse_character_list(response: str) -> list[str]:
        """Parse character extraction response."""
        json_str = JSONParser.extract_json_from_response(response)
        if not json_str:
            return []

        try:
            data = json.loads(json_str)
            if isinstance(data, dict):
                return data.get("characters", [])
            elif isinstance(data, list):
                return data
        except json.JSONDecodeError:
            pass

        return []

    @staticmethod
    def parse_dialog_list(response: str) -> list[dict]:
        """Parse dialog extraction response with deduplication and truncation handling."""
        # Try to extract individual dialog objects using regex (handles truncated arrays)
        # Pattern: {"speaker": "Name", "text": "Dialog"}
        pattern = r'\{\s*"speaker"\s*:\s*"([^"]+)"\s*,\s*"text"\s*:\s*"([^"]+)"\s*\}'

        dialogs = []
        seen = set()

        for match in re.finditer(pattern, response):
            speaker = match.group(1)
            text = match.group(2)

            # Create a key for deduplication
            key = (speaker.lower(), text.lower()[:50])  # Use first 50 chars for matching

            if key not in seen:
                seen.add(key)
                dialogs.append({"speaker": speaker, "text": text})

        if dialogs:
            logger.debug(f"Extracted {len(dialogs)} unique dialogs using regex")
            return dialogs

        # Fallback to standard JSON parsing
        json_str = JSONParser.extract_json_from_response(response)
        if not json_str:
            return []

        try:
            data = json.loads(json_str)
            if isinstance(data, list):
                return data
            elif isinstance(data, dict):
                return data.get("dialogs", [])

        except json.JSONDecodeError:
            pass

        return []


# =============================================================================
# Evaluator
# =============================================================================

class Evaluator:
    """Evaluate extraction results against expected data."""

    @staticmethod
    def evaluate_characters(
        extracted: list[str],
        expected: list[str]
    ) -> EvaluationMetrics:
        """Evaluate character extraction results.

        Args:
            extracted: List of extracted character names
            expected: List of expected character names

        Returns:
            Evaluation metrics
        """
        # Normalize names for comparison (case-insensitive)
        extracted_normalized = {name.lower().strip() for name in extracted}
        expected_normalized = {name.lower().strip() for name in expected}

        true_positives = len(extracted_normalized & expected_normalized)
        false_positives = len(extracted_normalized - expected_normalized)
        false_negatives = len(expected_normalized - extracted_normalized)

        precision = true_positives / len(extracted_normalized) if extracted_normalized else 0.0
        recall = true_positives / len(expected_normalized) if expected_normalized else 0.0
        f1 = 2 * precision * recall / (precision + recall) if (precision + recall) > 0 else 0.0

        return EvaluationMetrics(
            precision=precision,
            recall=recall,
            f1=f1,
            true_positives=true_positives,
            false_positives=false_positives,
            false_negatives=false_negatives
        )

    @staticmethod
    def evaluate_dialogs(
        extracted: list[tuple[str, str]],
        expected: list[tuple[str, str]]
    ) -> EvaluationMetrics:
        """Evaluate dialog extraction results with fuzzy matching.

        Args:
            extracted: List of (speaker, text) tuples
            expected: List of (speaker, text) tuples

        Returns:
            Evaluation metrics
        """
        # Normalize dialog texts for comparison
        def normalize_dialog(text: str) -> str:
            # Remove punctuation and extra whitespace, lowercase
            import re
            text = text.lower().strip()
            text = re.sub(r'[^\w\s]', '', text)  # Remove punctuation
            text = re.sub(r'\s+', ' ', text)  # Normalize whitespace
            return text

        def dialogs_match(ext_text: str, exp_text: str) -> bool:
            """Check if two dialogs match (exact or fuzzy)."""
            ext_norm = normalize_dialog(ext_text)
            exp_norm = normalize_dialog(exp_text)

            # Exact match
            if ext_norm == exp_norm:
                return True

            # Substring match (either contains the other)
            if ext_norm in exp_norm or exp_norm in ext_norm:
                return True

            # First N words match (handles truncation)
            ext_words = ext_norm.split()[:5]  # First 5 words
            exp_words = exp_norm.split()[:5]
            if ext_words == exp_words and len(ext_words) >= 3:
                return True

            return False

        # Find matches using fuzzy matching
        matched_expected = set()
        matched_extracted = set()

        for i, (ext_speaker, ext_text) in enumerate(extracted):
            for j, (exp_speaker, exp_text) in enumerate(expected):
                if j in matched_expected:
                    continue
                # Check if dialog text matches (ignore speaker for now since that's error-prone)
                if dialogs_match(ext_text, exp_text):
                    matched_expected.add(j)
                    matched_extracted.add(i)
                    logger.debug(f"Matched dialog: '{ext_text[:50]}...' <-> '{exp_text[:50]}...'")
                    break

        matches = len(matched_expected)

        precision = matches / len(extracted) if extracted else 0.0
        recall = matches / len(expected) if expected else 0.0
        f1 = 2 * precision * recall / (precision + recall) if (precision + recall) > 0 else 0.0

        return EvaluationMetrics(
            precision=precision,
            recall=recall,
            f1=f1,
            true_positives=matches,
            false_positives=len(extracted) - matches,
            false_negatives=len(expected) - matches
        )

    @staticmethod
    def log_differences(
        extracted: list[str],
        expected: list[str],
        label: str = "items"
    ):
        """Log detailed differences between extracted and expected."""
        extracted_set = {name.lower().strip() for name in extracted}
        expected_set = {name.lower().strip() for name in expected}

        missing = expected_set - extracted_set
        extra = extracted_set - expected_set

        if missing:
            logger.warning(f"Missing {label}: {sorted(missing)}")
        if extra:
            logger.warning(f"Extra {label}: {sorted(extra)}")



# =============================================================================
# Main Prompt Tester
# =============================================================================

class PromptTester:
    """Main class for testing and evaluating extraction prompts."""

    def __init__(
        self,
        pdf_path: str,
        expected_json_path: str,
        model_path: str = None,
        server_url: str = None,
        n_ctx: int = 4096,
        n_gpu_layers: int = -1
    ):
        """Initialize the prompt tester.

        Args:
            pdf_path: Path to PDF file
            expected_json_path: Path to expected results JSON
            model_path: Path to GGUF model (for llama-cpp-python)
            server_url: URL of llama-server (e.g., http://127.0.0.1:8080)
            n_ctx: Context window size
            n_gpu_layers: GPU layers (-1 = all)
        """
        self.model_path = model_path
        self.server_url = server_url
        self.pdf_path = pdf_path
        self.expected_json_path = expected_json_path

        self.llm = LLMEngine(
            model_path=model_path,
            server_url=server_url,
            n_ctx=n_ctx,
            n_gpu_layers=n_gpu_layers
        )
        self.expected = ExpectedData.from_json(expected_json_path)
        self.results_history: list[BenchmarkResult] = []

        # Load results history if exists
        self.history_file = Path("benchmark_history.json")
        self._load_history()

    def _load_history(self):
        """Load previous results history."""
        if self.history_file.exists():
            try:
                with open(self.history_file, 'r') as f:
                    data = json.load(f)
                    # Just keep track of count for now
                    logger.info(f"Loaded {len(data)} previous results")
            except Exception as e:
                logger.warning(f"Could not load history: {e}")

    def _save_result(self, result: BenchmarkResult):
        """Save result to history."""
        history = []
        if self.history_file.exists():
            try:
                with open(self.history_file, 'r') as f:
                    history = json.load(f)
            except Exception:
                pass

        history.append(result.to_dict())

        with open(self.history_file, 'w') as f:
            json.dump(history, f, indent=2)

    def run_batched_analysis(self, text: str) -> dict[str, dict]:
        """Run batched analysis (combined character+dialog extraction).

        Args:
            text: Text to analyze

        Returns:
            Dictionary mapping character names to their data
        """
        # Truncate text to fit within token budget
        max_chars = PromptDefinitions.ANALYSIS_MAX_INPUT_CHARS
        if len(text) > max_chars:
            logger.info(f"Truncating text from {len(text)} to {max_chars} chars")
            text = text[:max_chars]

        user_prompt = PromptDefinitions.build_batched_analysis_prompt(text)

        logger.info("Running batched analysis...")
        start_time = time.time()
        response = self.llm.generate(
            PromptDefinitions.BATCHED_SYSTEM_PROMPT,
            user_prompt,
            temperature=PromptDefinitions.BATCHED_ANALYSIS_TEMPERATURE,
            max_tokens=PromptDefinitions.ANALYSIS_OUTPUT_TOKENS
        )
        elapsed = (time.time() - start_time) * 1000
        logger.info(f"Batched analysis completed in {elapsed:.0f}ms")
        logger.debug(f"Raw response:\n{response}")

        return JSONParser.parse_batched_response(response), response

    def run_two_pass_analysis(self, text: str) -> tuple[list[str], list[dict], list[str]]:
        """Run two-pass analysis (character extraction + dialog extraction).

        Args:
            text: Text to analyze

        Returns:
            Tuple of (characters, dialogs, raw_responses)
        """
        raw_responses = []

        # Pass 1: Character extraction
        logger.info("=== PASS 1: Character Extraction ===")
        user_prompt = PromptDefinitions.build_character_extraction_prompt(text)

        start_time = time.time()
        response = self.llm.generate(
            PromptDefinitions.CHARACTER_SYSTEM_PROMPT,
            user_prompt,
            temperature=PromptDefinitions.CHARACTER_EXTRACTION_TEMPERATURE
        )
        elapsed = (time.time() - start_time) * 1000
        logger.info(f"Pass 1 completed in {elapsed:.0f}ms")
        logger.debug(f"Raw response:\n{response}")
        raw_responses.append(response)

        characters = JSONParser.parse_character_list(response)
        logger.info(f"Extracted {len(characters)} characters: {characters}")

        # Pass 2: Dialog extraction
        logger.info("=== PASS 2: Dialog Extraction ===")
        user_prompt = PromptDefinitions.build_dialog_extraction_prompt(text, characters)

        start_time = time.time()
        response = self.llm.generate(
            PromptDefinitions.DIALOG_SYSTEM_PROMPT,
            user_prompt,
            temperature=PromptDefinitions.DIALOG_EXTRACTION_TEMPERATURE
        )
        elapsed = (time.time() - start_time) * 1000
        logger.info(f"Pass 2 completed in {elapsed:.0f}ms")
        logger.debug(f"Raw response:\n{response}")
        raw_responses.append(response)

        dialogs = JSONParser.parse_dialog_list(response)
        logger.info(f"Extracted {len(dialogs)} dialogs")

        return characters, dialogs, raw_responses


    def run_benchmark(self, mode: str = "batched") -> BenchmarkResult:
        """Run a complete benchmark.

        Args:
            mode: "batched" for combined analysis, "two_pass" for separate passes

        Returns:
            Benchmark result with metrics
        """
        logger.info("=" * 60)
        logger.info(f"Starting benchmark (mode={mode})")
        logger.info(f"Model: {self.model_path}")
        logger.info(f"PDF: {self.pdf_path}")
        logger.info("=" * 60)

        overall_start = time.time()
        timing = {}
        raw_responses = []

        # Extract PDF text
        logger.info("Extracting PDF text...")
        start_time = time.time()
        text = PDFExtractor.extract_text(self.pdf_path)
        timing["pdf_extraction"] = (time.time() - start_time) * 1000
        logger.info(f"Extracted {len(text)} characters in {timing['pdf_extraction']:.0f}ms")

        # Run analysis
        if mode == "batched":
            start_time = time.time()
            char_data, response = self.run_batched_analysis(text)
            timing["analysis"] = (time.time() - start_time) * 1000
            raw_responses.append(response)

            # Extract characters and dialogs from batched result
            extracted_characters = list(char_data.keys())
            extracted_dialogs = []
            for name, data in char_data.items():
                for dialog in data.get("dialogs", []):
                    extracted_dialogs.append((name, dialog))
        else:
            start_time = time.time()
            extracted_characters, dialog_list, responses = self.run_two_pass_analysis(text)
            timing["analysis"] = (time.time() - start_time) * 1000
            raw_responses.extend(responses)

            extracted_dialogs = [
                (d.get("speaker", "Unknown"), d.get("text", ""))
                for d in dialog_list
            ]

        timing["total"] = (time.time() - overall_start) * 1000

        # Evaluate results
        logger.info("=" * 60)
        logger.info("EVALUATION RESULTS")
        logger.info("=" * 60)

        expected_characters = self.expected.characters
        expected_dialogs = self.expected.get_all_dialogs()

        char_metrics = Evaluator.evaluate_characters(extracted_characters, expected_characters)
        dialog_metrics = Evaluator.evaluate_dialogs(extracted_dialogs, expected_dialogs)

        logger.info(f"Characters: {char_metrics}")
        logger.info(f"  Extracted: {extracted_characters}")
        logger.info(f"  Expected:  {expected_characters}")
        Evaluator.log_differences(extracted_characters, expected_characters, "characters")

        logger.info(f"Dialogs: {dialog_metrics}")
        logger.info(f"  Extracted: {len(extracted_dialogs)} dialogs")
        logger.info(f"  Expected:  {len(expected_dialogs)} dialogs")

        # Create result
        result = BenchmarkResult(
            timestamp=datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            model_name=self.llm.model_name,
            prompt_version=PromptDefinitions.VERSION,
            character_metrics=char_metrics,
            dialog_metrics=dialog_metrics,
            extracted_characters=extracted_characters,
            expected_characters=expected_characters,
            extracted_dialogs=extracted_dialogs,
            expected_dialogs=expected_dialogs,
            timing_ms=timing,
            raw_llm_responses=raw_responses
        )

        # Save result
        self._save_result(result)
        self.results_history.append(result)

        # Print summary
        self._print_summary(result)

        return result

    def _print_summary(self, result: BenchmarkResult):
        """Print a summary of the benchmark result."""
        print("\n" + "=" * 60)
        print("BENCHMARK SUMMARY")
        print("=" * 60)
        print(f"Timestamp:      {result.timestamp}")
        print(f"Model:          {result.model_name}")
        print(f"Prompt Version: {result.prompt_version}")
        print("-" * 60)
        print("CHARACTER EXTRACTION:")
        print(f"  Precision: {result.character_metrics.precision:.1%}")
        print(f"  Recall:    {result.character_metrics.recall:.1%}")
        print(f"  F1 Score:  {result.character_metrics.f1:.1%}")
        print(f"  Extracted: {len(result.extracted_characters)} | Expected: {len(result.expected_characters)}")
        print("-" * 60)
        print("DIALOG EXTRACTION:")
        print(f"  Precision: {result.dialog_metrics.precision:.1%}")
        print(f"  Recall:    {result.dialog_metrics.recall:.1%}")
        print(f"  F1 Score:  {result.dialog_metrics.f1:.1%}")
        print(f"  Extracted: {len(result.extracted_dialogs)} | Expected: {len(result.expected_dialogs)}")
        print("-" * 60)
        print("TIMING:")
        for key, value in result.timing_ms.items():
            print(f"  {key}: {value:.0f}ms")
        print("=" * 60)

        # Check if target accuracy achieved
        target = 0.9
        if result.character_metrics.f1 >= target and result.dialog_metrics.f1 >= target:
            print(f"\n[PASS] TARGET ACCURACY ({target:.0%}) ACHIEVED!")
        else:
            print(f"\n[FAIL] Target accuracy ({target:.0%}) not yet achieved.")
            if result.character_metrics.f1 < target:
                print(f"   Character F1 needs improvement: {result.character_metrics.f1:.1%} < {target:.0%}")
            if result.dialog_metrics.f1 < target:
                print(f"   Dialog F1 needs improvement: {result.dialog_metrics.f1:.1%} < {target:.0%}")

    def close(self):
        """Release resources."""
        self.llm.close()



# =============================================================================
# Main Entry Point
# =============================================================================

def main():
    """Main entry point."""
    parser = argparse.ArgumentParser(
        description="LLM Prompt Tester for Character/Dialog Extraction",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Run with llama-server (recommended)
  python prompt_tester.py --server http://127.0.0.1:8080

  # Run with GGUF model directly (requires llama-cpp-python)
  python prompt_tester.py --model D:\\Models\\Qwen2.5-1.5B-Instruct-Q4_K_M.gguf

  # Run two-pass analysis
  python prompt_tester.py --server http://127.0.0.1:8080 --mode two_pass

  # Run with debug logging
  python prompt_tester.py --server http://127.0.0.1:8080 --debug
"""
    )

    # Default paths (relative to script location)
    script_dir = Path(__file__).parent
    repo_root = script_dir.parent.parent

    default_model = r"D:\Learning\Ai\Models\LLM\Qwen\Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"
    default_pdf = repo_root / "app" / "src" / "main" / "assets" / "demo" / "SpaceStory.pdf"
    default_expected = repo_root / "app" / "src" / "main" / "assets" / "demo" / "SpaceStoryAnalysis.json"

    parser.add_argument(
        "--model", "-m",
        type=str,
        default=None,
        help=f"Path to GGUF model file (requires llama-cpp-python)"
    )
    parser.add_argument(
        "--server", "-s",
        type=str,
        default=None,
        help="URL of llama-server (e.g., http://127.0.0.1:8080)"
    )
    parser.add_argument(
        "--pdf", "-p",
        type=str,
        default=str(default_pdf),
        help=f"Path to PDF file (default: {default_pdf})"
    )
    parser.add_argument(
        "--expected", "-e",
        type=str,
        default=str(default_expected),
        help=f"Path to expected results JSON (default: {default_expected})"
    )
    parser.add_argument(
        "--mode",
        choices=["batched", "two_pass"],
        default="batched",
        help="Analysis mode: 'batched' (combined) or 'two_pass' (separate)"
    )
    parser.add_argument(
        "--n-ctx",
        type=int,
        default=4096,
        help="Context window size (default: 4096)"
    )
    parser.add_argument(
        "--n-gpu-layers",
        type=int,
        default=-1,
        help="Number of GPU layers (-1 = all, 0 = CPU only)"
    )
    parser.add_argument(
        "--debug",
        action="store_true",
        help="Enable debug logging"
    )

    args = parser.parse_args()

    # Set logging level
    if args.debug:
        logging.getLogger().setLevel(logging.DEBUG)

    # Validate backend
    if not args.model and not args.server:
        # Default to server if neither specified
        args.server = "http://127.0.0.1:8080"
        logger.info("No backend specified, defaulting to llama-server at http://127.0.0.1:8080")

    if args.model and not Path(args.model).exists():
        logger.error(f"Model file not found: {args.model}")
        sys.exit(1)
    if not Path(args.pdf).exists():
        logger.error(f"PDF file not found: {args.pdf}")
        sys.exit(1)
    if not Path(args.expected).exists():
        logger.error(f"Expected JSON not found: {args.expected}")
        sys.exit(1)

    # Run benchmark
    try:
        tester = PromptTester(
            pdf_path=args.pdf,
            expected_json_path=args.expected,
            model_path=args.model,
            server_url=args.server,
            n_ctx=args.n_ctx,
            n_gpu_layers=args.n_gpu_layers
        )

        result = tester.run_benchmark(mode=args.mode)

        # Return exit code based on results
        target = 0.9
        if result.character_metrics.f1 >= target and result.dialog_metrics.f1 >= target:
            sys.exit(0)  # Success
        else:
            sys.exit(1)  # Needs improvement

    except KeyboardInterrupt:
        logger.info("Interrupted by user")
        sys.exit(130)
    except Exception as e:
        logger.exception(f"Error: {e}")
        sys.exit(1)
    finally:
        if 'tester' in locals():
            tester.close()


if __name__ == "__main__":
    main()
