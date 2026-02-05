"""
Storyteller Benchmark Package

A modular benchmarking framework for testing LLM-based character analysis workflows.

Submodules:
- models: Model loaders for LiteRT-LM, llama-server, and GGUF models
- workflows: 2-pass, 3-pass, and 5-pass workflow implementations
- prompts: Prompt builders for character extraction, dialog analysis, voice profiling
- utils: PDF extraction, text splitting, JSON validation utilities

Usage:
    from benchmark import run_benchmark
    from benchmark.models import LlamaServerModel, LiteRTModel
    from benchmark.workflows import TwoPassWorkflow, ThreePassWorkflow
"""

from .utils import extract_pdf_text, split_into_segments, split_into_pages, validate_json
from .prompts import PromptBuilder
from .models import BaseModel, LlamaServerModel, LiteRTModel, GGUFModel
from .workflows import BaseWorkflow, TwoPassWorkflow, ThreePassWorkflow, FivePassWorkflow, WorkflowResult

__version__ = "1.0.0"
__all__ = [
    # Utils
    "extract_pdf_text",
    "split_into_segments",
    "split_into_pages",
    "validate_json",
    # Prompts
    "PromptBuilder",
    # Models
    "BaseModel",
    "LlamaServerModel",
    "LiteRTModel",
    "GGUFModel",
    # Workflows
    "BaseWorkflow",
    "TwoPassWorkflow",
    "ThreePassWorkflow",
    "FivePassWorkflow",
    "WorkflowResult",
]

