#!/usr/bin/env python3
"""
Unified Benchmark Runner for Storyteller Character Analysis.

Usage:
    # Using llama-server (default)
    python -m benchmark.run_benchmark --pdf book.pdf --workflow 2pass --model llama-server

    # Using LiteRT-LM (requires lit.exe and model alias)
    python -m benchmark.run_benchmark --pdf book.pdf --workflow 2pass --model litert \\
        --model-path path/to/model.litertlm --model-alias gemma-3n-E2B --model-type gemma

    # Using GGUF model directly
    python -m benchmark.run_benchmark --pdf book.pdf --workflow 5pass --model gguf \\
        --model-path path/to/model.gguf

For LiteRT models, see scripts/benchmark/TROUBLESHOOTING.md for common issues.
"""

import argparse
import json
import sys
import time
from pathlib import Path

from .models import BaseModel, GGUFModel, LlamaServerModel, LiteRTModel
from .prompts import PromptBuilder
from .workflows import FivePassWorkflow, ThreePassWorkflow, TwoPassWorkflow, WorkflowResult


def validate_litert_args(args) -> None:
    """Validate LiteRT-specific arguments and provide helpful error messages."""
    if not args.model_path:
        print("ERROR: --model-path required for litert model", file=sys.stderr)
        print("\nExample:", file=sys.stderr)
        print("  python -m benchmark.run_benchmark --pdf book.pdf --model litert \\", file=sys.stderr)
        print("      --model-path D:\\Models\\gemma-3n-E2B-it-int4.litertlm \\", file=sys.stderr)
        print("      --model-alias gemma-3n-E2B --model-type gemma", file=sys.stderr)
        sys.exit(1)

    model_path = Path(args.model_path)
    if not model_path.is_file():
        print(f"ERROR: Model file not found: {args.model_path}", file=sys.stderr)
        sys.exit(1)

    if not model_path.suffix == ".litertlm":
        print(f"WARNING: Model file does not have .litertlm extension: {model_path.name}", file=sys.stderr)

    # Check if model alias is provided for known model types
    if not args.model_alias:
        print("WARNING: --model-alias not specified.", file=sys.stderr)
        print("  lit.exe requires a registry alias to find models.", file=sys.stderr)
        print("  Known aliases:", file=sys.stderr)
        for alias, filename in LiteRTModel.REGISTRY_FILENAMES.items():
            print(f"    {alias}: {filename}", file=sys.stderr)
        print("  Run `lit.exe list --show_all` to see all available aliases.", file=sys.stderr)

    # Check lit.exe exists
    lit_path = Path(args.lit_exe)
    if not lit_path.is_file():
        # Try in scripts directory
        scripts_dir = Path(__file__).parent.parent
        alt_path = scripts_dir / "lit.exe"
        if alt_path.is_file():
            args.lit_exe = str(alt_path)
        else:
            print(f"WARNING: lit.exe not found at: {args.lit_exe}", file=sys.stderr)
            print("  Download from: https://github.com/google-ai-edge/LiteRT-LM/releases", file=sys.stderr)


def create_model(args) -> BaseModel:
    """Create model instance based on arguments."""
    if args.model == "llama-server":
        stop_tokens = ["<|im_end|>", "<|endoftext|>"]
        if args.model_type == "gemma":
            stop_tokens = ["<end_of_turn>", "<eos>"]
        return LlamaServerModel(
            base_url=args.server_url,
            stop_tokens=stop_tokens,
            cache_prompt=True,
        )

    elif args.model == "litert":
        validate_litert_args(args)

        # Determine working directory (where lit.exe is located)
        lit_path = Path(args.lit_exe)
        if lit_path.is_absolute():
            working_dir = str(lit_path.parent)
        else:
            working_dir = str(Path(__file__).parent.parent)  # scripts/ directory

        return LiteRTModel(
            model_path=args.model_path,
            lit_exe=args.lit_exe,
            model_alias=args.model_alias,
            model_type=args.model_type,
            max_tokens=args.max_tokens,
            temperature=args.temperature,
            backend=args.backend,
            working_dir=working_dir,
        )

    elif args.model == "gguf":
        if not args.model_path:
            print("ERROR: --model-path required for gguf model", file=sys.stderr)
            sys.exit(1)
        if not Path(args.model_path).is_file():
            print(f"ERROR: Model file not found: {args.model_path}", file=sys.stderr)
            sys.exit(1)
        stop_tokens = ["<|im_end|>", "<|endoftext|>"]
        if args.model_type == "gemma":
            stop_tokens = ["<end_of_turn>", "<eos>"]
        return GGUFModel(
            model_path=args.model_path,
            n_ctx=args.context_size,
            stop_tokens=stop_tokens,
        )

    else:
        print(f"ERROR: Unknown model type: {args.model}", file=sys.stderr)
        sys.exit(1)


def create_workflow(args, model: BaseModel):
    """Create workflow instance based on arguments."""
    prompt_builder = PromptBuilder(args.model_type)

    if args.workflow == "2pass":
        return TwoPassWorkflow(model, prompt_builder)
    elif args.workflow == "3pass":
        return ThreePassWorkflow(model, prompt_builder)
    elif args.workflow == "5pass":
        return FivePassWorkflow(model, prompt_builder)
    else:
        raise ValueError(f"Unknown workflow: {args.workflow}")


def main():
    parser = argparse.ArgumentParser(
        description="Run character analysis benchmark on a PDF file.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Using llama-server (default)
  python -m benchmark.run_benchmark --pdf book.pdf --workflow 2pass

  # Using LiteRT-LM with Gemma model
  python -m benchmark.run_benchmark --pdf book.pdf --model litert \\
      --model-path D:\\Models\\gemma-3n-E2B-it-int4.litertlm \\
      --model-alias gemma-3n-E2B --model-type gemma --backend gpu

  # Using GGUF model
  python -m benchmark.run_benchmark --pdf book.pdf --model gguf \\
      --model-path D:\\Models\\model.gguf

For LiteRT troubleshooting, see scripts/benchmark/TROUBLESHOOTING.md
        """,
    )

    # Required arguments (pdf is required unless using utility commands)
    parser.add_argument("--pdf", help="Path to PDF file (required for benchmarking)")

    # Workflow selection
    parser.add_argument(
        "--workflow",
        choices=["2pass", "3pass", "5pass"],
        default="3pass",
        help="Workflow type (default: 3pass)",
    )

    # Model backend selection
    parser.add_argument(
        "--model",
        choices=["llama-server", "litert", "gguf"],
        default="llama-server",
        help="Model backend (default: llama-server)",
    )
    parser.add_argument(
        "--model-type",
        choices=["chatml", "gemma", "qwen3", "qwen2"],
        default="chatml",
        help="Model prompt format (default: chatml). Use 'gemma' for Gemma models.",
    )
    parser.add_argument(
        "--model-path",
        help="Path to model file (required for litert/gguf)",
    )
    parser.add_argument(
        "--model-alias",
        help="LiteRT model registry alias (e.g., 'gemma-3n-E2B'). "
             "Run `lit.exe list --show_all` to see available aliases.",
    )

    # llama-server options
    parser.add_argument(
        "--server-url",
        default="http://127.0.0.1:8080",
        help="llama-server URL (default: http://127.0.0.1:8080)",
    )

    # LiteRT options
    parser.add_argument(
        "--lit-exe",
        default="lit.exe",
        help="Path to lit.exe (default: lit.exe in scripts/ directory)",
    )
    parser.add_argument(
        "--backend",
        choices=["cpu", "gpu"],
        default="gpu",
        help="LiteRT backend (default: gpu)",
    )

    # Generation options
    parser.add_argument("--max-tokens", type=int, default=2048, help="Max tokens (default: 2048)")
    parser.add_argument("--temperature", type=float, default=0.3, help="Temperature (default: 0.3)")
    parser.add_argument("--context-size", type=int, default=8192, help="Context size for GGUF (default: 8192)")

    # Processing options
    parser.add_argument("--max-pages", type=int, default=50, help="Max pages to process (default: 50)")

    # Output options
    parser.add_argument("--output", "-o", help="Output JSON file (default: stdout)")
    parser.add_argument("--verbose", "-v", action="store_true", help="Verbose output")

    # Utility commands
    parser.add_argument(
        "--list-aliases",
        action="store_true",
        help="List known LiteRT model aliases and exit",
    )

    args = parser.parse_args()

    # Handle utility commands
    if args.list_aliases:
        print("Known LiteRT model aliases:")
        print("-" * 60)
        for alias, filename in LiteRTModel.REGISTRY_FILENAMES.items():
            print(f"  {alias:20} -> {filename}")
        print("-" * 60)
        print("Run `lit.exe list --show_all` to see all available aliases.")
        sys.exit(0)

    # Validate PDF is provided and exists
    if not args.pdf:
        print("ERROR: --pdf is required for benchmarking", file=sys.stderr)
        print("Use --help for usage information", file=sys.stderr)
        sys.exit(1)

    pdf_path = Path(args.pdf)
    if not pdf_path.is_file():
        print(f"ERROR: PDF not found: {args.pdf}", file=sys.stderr)
        sys.exit(1)

    if args.verbose:
        print(f"PDF: {args.pdf}")
        print(f"Workflow: {args.workflow}")
        print(f"Model: {args.model} ({args.model_type})")

    # Run benchmark
    t_start = time.time()

    with create_model(args) as model:
        workflow = create_workflow(args, model)
        result: WorkflowResult = workflow.run(
            str(pdf_path),
            max_pages=args.max_pages,
        )

    result.timing["total"] = time.time() - t_start
    result.metadata["pdf"] = str(pdf_path)
    result.metadata["workflow"] = args.workflow
    result.metadata["model"] = args.model
    result.metadata["model_type"] = args.model_type

    # Output
    output_data = result.to_dict()
    output_json = json.dumps(output_data, indent=2, ensure_ascii=False)

    if args.output:
        Path(args.output).write_text(output_json, encoding="utf-8")
        if args.verbose:
            print(f"Output written to: {args.output}")
    else:
        print(output_json)

    if args.verbose:
        print(f"\nTiming: {result.timing}")
        print(f"Characters found: {len(result.characters)}")
        print(f"Dialogs extracted: {len(result.dialogs)}")


if __name__ == "__main__":
    main()

