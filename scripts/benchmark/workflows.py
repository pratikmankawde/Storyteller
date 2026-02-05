"""
Workflow implementations for character analysis benchmarks.

Supports:
- TwoPassWorkflow: Segment-based extraction (chars + dialogs) + voice profiles
- ThreePassWorkflow: Page-based character extraction + dialog + traits/voice
- FivePassWorkflow: Full 5-pass analysis (names → traits → dialogs → personality → voice)
"""

import json
import time
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Optional

from .models import BaseModel
from .prompts import PromptBuilder
from .utils import (
    extract_json_from_text,
    extract_pdf_pages,
    extract_pdf_text,
    parse_characters_from_output,
    split_into_segments,
    truncate_to_tokens,
    validate_json,
)


@dataclass
class CharacterResult:
    """Result for a single character."""
    name: str
    traits: list[str] = field(default_factory=list)
    dialogs: list[dict] = field(default_factory=list)
    personality: list[str] = field(default_factory=list)
    voice_profile: dict = field(default_factory=dict)


@dataclass
class WorkflowResult:
    """Result from running a workflow."""
    characters: list[CharacterResult] = field(default_factory=list)
    dialogs: list[dict] = field(default_factory=list)
    timing: dict = field(default_factory=dict)
    metadata: dict = field(default_factory=dict)

    def to_dict(self) -> dict:
        """Convert to dictionary for JSON serialization."""
        return {
            "characters": [
                {
                    "name": c.name,
                    "traits": c.traits,
                    "dialogs": c.dialogs,
                    "personality": c.personality,
                    "voice_profile": c.voice_profile,
                }
                for c in self.characters
            ],
            "dialogs": self.dialogs,
            "timing": self.timing,
            "metadata": self.metadata,
        }


class BaseWorkflow(ABC):
    """Abstract base class for analysis workflows."""

    def __init__(self, model: BaseModel, prompt_builder: PromptBuilder):
        """Initialize workflow.

        Args:
            model: Model instance for generation
            prompt_builder: PromptBuilder for creating prompts
        """
        self.model = model
        self.prompt_builder = prompt_builder

    @abstractmethod
    def run(self, pdf_path: str, **kwargs) -> WorkflowResult:
        """Run the workflow on a PDF file."""
        pass

    def _parse_json_response(self, response: str) -> dict:
        """Parse JSON from model response."""
        json_str = extract_json_from_text(response)
        if not json_str:
            return {}
        result = validate_json(json_str)
        return result.get("data", {}) if result["valid"] else {}


class TwoPassWorkflow(BaseWorkflow):
    """Two-pass workflow: segment extraction + voice profiles."""

    def run(
        self,
        pdf_path: str,
        segment_size: int = 4000,
        max_segments: int = 10,
        **kwargs,
    ) -> WorkflowResult:
        """Run 2-pass workflow.

        Pass 1: Extract characters and dialogs from each segment
        Pass 2: Generate voice profiles for each character

        Args:
            pdf_path: Path to PDF file
            segment_size: Characters per segment
            max_segments: Maximum segments to process
        """
        result = WorkflowResult()
        timing = {}

        # Extract text and split into segments
        t0 = time.time()
        text = extract_pdf_text(pdf_path)
        segments = split_into_segments(text, segment_size)[:max_segments]
        timing["extraction"] = time.time() - t0
        result.metadata["num_segments"] = len(segments)

        # Pass 1: Extract characters and dialogs from each segment
        t0 = time.time()
        all_characters = set()
        all_dialogs = []

        for i, segment in enumerate(segments):
            # Get characters
            prompt = self.prompt_builder.build_pass1_prompt(segment)
            response = self.model.generate(prompt)
            data = self._parse_json_response(response)
            chars = data.get("characters", [])
            all_characters.update(chars)

            # Get dialogs
            prompt = self.prompt_builder.build_pass2_5_dialog_prompt(segment, list(chars))
            response = self.model.generate(prompt)
            data = self._parse_json_response(response)
            dialogs = data.get("dialogs", [])
            all_dialogs.extend(dialogs)

        timing["pass1"] = time.time() - t0

        # Ensure Narrator is included
        all_characters.add("Narrator")

        # Pass 2: Generate voice profiles
        t0 = time.time()
        for char_name in all_characters:
            char_result = CharacterResult(name=char_name)

            # Collect dialogs for this character
            char_dialogs = [d for d in all_dialogs if d.get("speaker") == char_name]
            char_result.dialogs = char_dialogs

            # Build context from dialogs
            context = " ".join(d.get("text", "") for d in char_dialogs[:20])
            if not context:
                context = f"Character named {char_name}"

            # Generate voice profile
            prompt = self.prompt_builder.build_pass3_with_context_prompt(char_name, context)
            response = self.model.generate(prompt)
            data = self._parse_json_response(response)
            char_result.traits = data.get("traits", [])
            char_result.voice_profile = data.get("voice_profile", {})

            result.characters.append(char_result)

        timing["pass2"] = time.time() - t0
        result.dialogs = all_dialogs
        result.timing = timing
        return result


class ThreePassWorkflow(BaseWorkflow):
    """Three-pass workflow with page-based context preservation."""

    def run(
        self,
        pdf_path: str,
        max_pages: int = 50,
        **kwargs,
    ) -> WorkflowResult:
        """Run 3-pass workflow.

        Pass 1: Extract character names from each page
        Pass 2: Extract dialogs with speaker attribution
        Pass 3: Generate traits and voice profiles per character

        Args:
            pdf_path: Path to PDF file
            max_pages: Maximum pages to process
        """
        result = WorkflowResult()
        timing = {}

        # Extract pages
        t0 = time.time()
        pages = extract_pdf_pages(pdf_path)[:max_pages]
        timing["extraction"] = time.time() - t0
        result.metadata["num_pages"] = len(pages)

        # Pass 1: Extract characters from each page
        t0 = time.time()
        char_page_map: dict[str, list[int]] = {}  # char -> pages where they appear

        for page_idx, page_text in enumerate(pages):
            prompt = self.prompt_builder.build_pass1_prompt(page_text)
            response = self.model.generate(prompt)
            data = self._parse_json_response(response)
            chars = data.get("characters", [])

            for char in chars:
                if char not in char_page_map:
                    char_page_map[char] = []
                char_page_map[char].append(page_idx)

        timing["pass1"] = time.time() - t0
        char_page_map["Narrator"] = list(range(len(pages)))

        # Pass 2: Extract dialogs
        t0 = time.time()
        all_dialogs = []
        for page_idx, page_text in enumerate(pages):
            page_chars = [c for c, p in char_page_map.items() if page_idx in p]
            prompt = self.prompt_builder.build_pass2_5_dialog_prompt(page_text, page_chars)
            response = self.model.generate(prompt)
            data = self._parse_json_response(response)
            dialogs = data.get("dialogs", [])
            for d in dialogs:
                d["page"] = page_idx
            all_dialogs.extend(dialogs)

        timing["pass2"] = time.time() - t0

        # Pass 3: Generate traits and voice profiles
        t0 = time.time()
        for char_name, page_indices in char_page_map.items():
            char_result = CharacterResult(name=char_name)

            # Collect context from pages where character appears
            context_parts = []
            for page_idx in page_indices[:10]:  # Limit context
                context_parts.append(pages[page_idx])
            context = truncate_to_tokens("\n\n".join(context_parts), max_tokens=2000)

            # Get dialogs for this character
            char_result.dialogs = [d for d in all_dialogs if d.get("speaker") == char_name]

            # Generate traits + voice profile
            prompt = self.prompt_builder.build_pass3_with_context_prompt(char_name, context)
            response = self.model.generate(prompt)
            data = self._parse_json_response(response)
            char_result.traits = data.get("traits", [])
            char_result.voice_profile = data.get("voice_profile", {})

            result.characters.append(char_result)

        timing["pass3"] = time.time() - t0
        result.dialogs = all_dialogs
        result.timing = timing
        return result


class FivePassWorkflow(BaseWorkflow):
    """Full 5-pass workflow for comprehensive character analysis."""

    def run(
        self,
        pdf_path: str,
        max_pages: int = 50,
        **kwargs,
    ) -> WorkflowResult:
        """Run 5-pass workflow.

        Pass 1: Extract character names
        Pass 2: Extract traits for each character
        Pass 3: Extract dialogs with speaker attribution
        Pass 4: Infer personality from traits
        Pass 5: Generate voice profiles from personality

        Args:
            pdf_path: Path to PDF file
            max_pages: Maximum pages to process
        """
        result = WorkflowResult()
        timing = {}

        # Extract pages
        t0 = time.time()
        pages = extract_pdf_pages(pdf_path)[:max_pages]
        full_text = "\n\n".join(pages)
        timing["extraction"] = time.time() - t0
        result.metadata["num_pages"] = len(pages)

        # Pass 1: Extract all character names
        t0 = time.time()
        all_characters = set()
        for page_text in pages:
            prompt = self.prompt_builder.build_pass1_prompt(page_text)
            response = self.model.generate(prompt)
            data = self._parse_json_response(response)
            chars = data.get("characters", [])
            all_characters.update(chars)

        all_characters.add("Narrator")
        timing["pass1"] = time.time() - t0

        # Pass 2: Extract traits for each character
        t0 = time.time()
        char_traits: dict[str, list[str]] = {}
        text_for_traits = truncate_to_tokens(full_text, max_tokens=1500)

        for char_name in all_characters:
            prompt = self.prompt_builder.build_pass2_trait_prompt(char_name, text_for_traits)
            response = self.model.generate(prompt)
            data = self._parse_json_response(response)
            char_traits[char_name] = data.get("traits", [])

        timing["pass2"] = time.time() - t0

        # Pass 3: Extract dialogs
        t0 = time.time()
        all_dialogs = []
        for page_idx, page_text in enumerate(pages):
            prompt = self.prompt_builder.build_pass2_5_dialog_prompt(page_text, list(all_characters))
            response = self.model.generate(prompt)
            data = self._parse_json_response(response)
            dialogs = data.get("dialogs", [])
            for d in dialogs:
                d["page"] = page_idx
            all_dialogs.extend(dialogs)

        timing["pass3"] = time.time() - t0

        # Pass 4: Infer personality from traits
        t0 = time.time()
        char_personality: dict[str, list[str]] = {}
        for char_name, traits in char_traits.items():
            prompt = self.prompt_builder.build_pass3_personality_prompt(char_name, traits)
            response = self.model.generate(prompt)
            data = self._parse_json_response(response)
            char_personality[char_name] = data.get("personality", [])

        timing["pass4"] = time.time() - t0

        # Pass 5: Generate voice profiles
        t0 = time.time()
        for char_name in all_characters:
            char_result = CharacterResult(name=char_name)
            char_result.traits = char_traits.get(char_name, [])
            char_result.personality = char_personality.get(char_name, [])
            char_result.dialogs = [d for d in all_dialogs if d.get("speaker") == char_name]

            prompt = self.prompt_builder.build_pass4_voice_prompt(
                char_name, char_result.personality
            )
            response = self.model.generate(prompt)
            data = self._parse_json_response(response)
            char_result.voice_profile = data.get("voice_profile", {})

            result.characters.append(char_result)

        timing["pass5"] = time.time() - t0
        result.dialogs = all_dialogs
        result.timing = timing
        return result

