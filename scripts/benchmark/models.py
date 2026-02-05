"""
Model loader classes for benchmark workflows.

Supports:
- LlamaServerModel: HTTP API client for llama-server (llama.cpp)
- LiteRTModel: LiteRT-LM CLI wrapper with interactive session support
"""

import json
import subprocess
import time
from abc import ABC, abstractmethod
from pathlib import Path
from typing import Optional
import requests


class BaseModel(ABC):
    """Abstract base class for model loaders."""

    @abstractmethod
    def generate(self, prompt: str, max_tokens: int = 2048, temperature: float = 0.3) -> str:
        """Generate text from prompt."""
        pass

    @abstractmethod
    def close(self) -> None:
        """Clean up resources."""
        pass

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()


class LlamaServerModel(BaseModel):
    """HTTP client for llama-server API."""

    def __init__(
        self,
        base_url: str = "http://127.0.0.1:8080",
        stop_tokens: Optional[list[str]] = None,
        cache_prompt: bool = True,
    ):
        """Initialize llama-server client.

        Args:
            base_url: llama-server URL (default: http://127.0.0.1:8080)
            stop_tokens: Stop sequences (default: ["<|im_end|>", "<|endoftext|>"])
            cache_prompt: Enable prompt caching (default: True)
        """
        self.base_url = base_url.rstrip("/")
        self.stop_tokens = stop_tokens or ["<|im_end|>", "<|endoftext|>"]
        self.cache_prompt = cache_prompt
        self.session = requests.Session()

    def generate(self, prompt: str, max_tokens: int = 2048, temperature: float = 0.3) -> str:
        """Generate completion via llama-server API."""
        payload = {
            "prompt": prompt,
            "n_predict": max_tokens,
            "temperature": temperature,
            "stop": self.stop_tokens,
            "cache_prompt": self.cache_prompt,
        }
        try:
            resp = self.session.post(
                f"{self.base_url}/completion",
                json=payload,
                timeout=300,
            )
            resp.raise_for_status()
            return resp.json().get("content", "")
        except requests.RequestException as e:
            print(f"[LlamaServerModel] Request failed: {e}")
            return ""

    def health_check(self) -> bool:
        """Check if llama-server is healthy."""
        try:
            resp = self.session.get(f"{self.base_url}/health", timeout=5)
            return resp.status_code == 200
        except requests.RequestException:
            return False

    def close(self) -> None:
        """Close session."""
        self.session.close()


class LiteRTModel(BaseModel):
    """LiteRT-LM CLI wrapper using file-based prompts.

    IMPORTANT: lit.exe has specific requirements for model caching:
    1. Models must be in ~/.litert-lm/models/ (user's home directory)
    2. The filename must match the registry's expected filename for the alias
    3. Use `lit.exe list --show_all` to see available aliases and expected filenames

    Common issues:
    - "model not found in local cache": The model file exists but has wrong filename
    - Solution: Use model_alias parameter matching a known registry alias

    See scripts/benchmark/TROUBLESHOOTING.md for detailed troubleshooting.
    """

    # Known model registry mappings: alias -> expected filename
    # lit.exe run <alias> looks for ~/.litert-lm/models/<expected_filename>
    # Run `lit.exe list --show_all` to see all available aliases
    REGISTRY_FILENAMES = {
        # Gemma models
        "gemma-3n-E2B": "gemma-3n-E2B-it-int4.litertlm",
        "gemma-3n-E4B": "gemma-3n-E4B-it-int4.litertlm",
        "gemma3-1b": "gemma3-1b-it-int4.litertlm",
        # Phi models
        "phi-4-mini": "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        # Qwen models
        "qwen2.5-1.5b": "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
    }

    # Model type to stop token mapping
    STOP_TOKENS = {
        "gemma": "<end_of_turn>",
        "chatml": "<|im_end|>",
        "qwen3": "<|im_end|>",
        "qwen2": "<|im_end|>",
    }

    def __init__(
        self,
        model_path: str,
        lit_exe: str = ".\\lit.exe",
        stop_token: Optional[str] = None,
        max_tokens: int = 2048,
        temperature: float = 0.3,
        start_token: str = "",
        backend: str = "gpu",
        working_dir: Optional[str] = None,
        model_alias: Optional[str] = None,
        model_type: Optional[str] = None,
    ):
        """Initialize LiteRT-LM model.

        Args:
            model_path: Path to .litertlm model file
            lit_exe: Path to lit.exe executable
            stop_token: Stop token for generation (auto-detected from model_type if not provided)
            max_tokens: Default max tokens (not used by lit.exe directly)
            temperature: Default temperature (not used by lit.exe directly)
            start_token: Token to prefix responses (not used by lit.exe directly)
            backend: Backend to use ('cpu' or 'gpu')
            working_dir: Working directory for lit.exe (default: directory containing lit_exe)
            model_alias: Alias name for the model in lit registry (e.g., 'gemma-3n-E2B').
                        IMPORTANT: Must match a known registry alias for lit.exe to find the model.
            model_type: Model type for prompt formatting ('gemma', 'chatml', 'qwen3', 'qwen2')
        """
        self.model_path = model_path
        self.lit_exe = lit_exe
        self.max_tokens = max_tokens
        self.temperature = temperature
        self.start_token = start_token
        self.backend = backend
        self.working_dir = working_dir or str(Path(lit_exe).parent)
        self.model_type = model_type

        # Auto-detect stop token from model type
        if stop_token:
            self.stop_token = stop_token
        elif model_type and model_type in self.STOP_TOKENS:
            self.stop_token = self.STOP_TOKENS[model_type]
        else:
            self.stop_token = "<|im_end|>"  # Default fallback

        # Validate and set model alias
        self.model_alias = self._resolve_model_alias(model_path, model_alias)

        self._model_cached = False
        self._cache_error = None
        self._validate_lit_exe()
        self._setup_model_cache()

    def _validate_lit_exe(self) -> None:
        """Validate that lit.exe exists and is executable."""
        lit_path = Path(self.working_dir) / self.lit_exe if not Path(self.lit_exe).is_absolute() else Path(self.lit_exe)
        if not lit_path.exists():
            # Try relative to working dir
            alt_path = Path(self.working_dir) / "lit.exe"
            if alt_path.exists():
                self.lit_exe = str(alt_path)
            else:
                print(f"[LiteRTModel] WARNING: lit.exe not found at {lit_path}")
                print(f"[LiteRTModel] Download from: https://github.com/google-ai-edge/LiteRT-LM/releases")

    def _resolve_model_alias(self, model_path: str, provided_alias: Optional[str]) -> str:
        """Resolve the model alias, with validation and helpful error messages."""
        if provided_alias:
            if provided_alias not in self.REGISTRY_FILENAMES:
                print(f"[LiteRTModel] WARNING: Alias '{provided_alias}' not in known registry.")
                print(f"[LiteRTModel] Known aliases: {list(self.REGISTRY_FILENAMES.keys())}")
                print(f"[LiteRTModel] Run `lit.exe list --show_all` to see all available aliases.")
            return provided_alias

        # Try to find alias by matching source filename to registry
        src_name = Path(model_path).name
        for alias, expected_file in self.REGISTRY_FILENAMES.items():
            if src_name == expected_file:
                print(f"[LiteRTModel] Auto-detected alias: {alias}")
                return alias

        # Try partial match (e.g., "gemma-3n-E2B" in filename)
        src_stem = Path(model_path).stem.lower()
        for alias in self.REGISTRY_FILENAMES.keys():
            if alias.lower() in src_stem or src_stem in alias.lower():
                print(f"[LiteRTModel] Auto-detected alias from partial match: {alias}")
                return alias

        # Fallback: use stem, but warn user
        fallback = Path(model_path).stem
        print(f"[LiteRTModel] WARNING: Could not auto-detect registry alias for: {src_name}")
        print(f"[LiteRTModel] Using fallback alias: {fallback}")
        print(f"[LiteRTModel] This may cause 'model not found' errors.")
        print(f"[LiteRTModel] Specify model_alias parameter with a known registry alias.")
        print(f"[LiteRTModel] Known aliases: {list(self.REGISTRY_FILENAMES.keys())}")
        return fallback

    def _setup_model_cache(self) -> None:
        """Copy model to lit.exe cache directory with the expected registry filename.

        lit.exe has a specific model lookup behavior:
        1. `lit.exe list` shows models from ~/.litert-lm/models/
        2. `lit.exe run <alias>` looks for a file matching the registry's expected filename
        3. The alias and filename must match the registry exactly

        For example, alias "gemma-3n-E2B" expects file "gemma-3n-E2B-it-int4.litertlm"
        """
        model_file = Path(self.model_path)
        if not model_file.is_file():
            self._cache_error = f"Model file not found: {self.model_path}"
            print(f"[LiteRTModel] ERROR: {self._cache_error}")
            return

        # lit.exe looks for models in ~/.litert-lm/models/ with registry filename
        cache_dir = Path.home() / ".litert-lm" / "models"
        cache_dir.mkdir(parents=True, exist_ok=True)

        # Get expected filename from registry, or use source filename
        if self.model_alias in self.REGISTRY_FILENAMES:
            expected_filename = self.REGISTRY_FILENAMES[self.model_alias]
        else:
            # Unknown alias - use source filename but warn
            expected_filename = model_file.name
            print(f"[LiteRTModel] WARNING: Using source filename '{expected_filename}' for unknown alias.")
            print(f"[LiteRTModel] If you get 'model not found' errors, check the registry with:")
            print(f"[LiteRTModel]   lit.exe list --show_all")

        cached_path = cache_dir / expected_filename
        self._cached_path = cached_path

        # Copy if not cached or size differs
        src_size = model_file.stat().st_size
        if not cached_path.is_file() or cached_path.stat().st_size != src_size:
            import shutil
            print(f"[LiteRTModel] Copying model to cache: {cached_path}")
            print(f"[LiteRTModel] This may take a while for large models...")
            try:
                shutil.copy2(model_file, cached_path)
                print(f"[LiteRTModel] Model cached successfully ({src_size / 1e9:.2f} GB)")
            except Exception as e:
                self._cache_error = f"Failed to copy model: {e}"
                print(f"[LiteRTModel] ERROR: {self._cache_error}")
                return
        else:
            print(f"[LiteRTModel] Model already cached: {cached_path}")

        self._model_cached = True

    def verify_model(self) -> bool:
        """Verify that lit.exe can find and run the model.

        Returns True if model is ready, False otherwise with diagnostic info.
        """
        if not self._model_cached:
            print(f"[LiteRTModel] Model not cached. Error: {self._cache_error}")
            return False

        # Try to list models and check if ours is there
        try:
            result = subprocess.run(
                [self.lit_exe, "list"],
                capture_output=True,
                text=True,
                timeout=30,
                cwd=self.working_dir,
            )
            if self.model_alias in result.stdout:
                print(f"[LiteRTModel] Model '{self.model_alias}' found in lit.exe cache")
                return True
            else:
                print(f"[LiteRTModel] WARNING: Model '{self.model_alias}' not found in lit.exe list output")
                print(f"[LiteRTModel] lit.exe list output:\n{result.stdout}")
                return False
        except Exception as e:
            print(f"[LiteRTModel] Could not verify model: {e}")
            return False

    def generate(self, prompt: str, max_tokens: int = 2048, temperature: float = 0.3) -> str:
        """Generate text using file-based prompt."""
        import tempfile
        import re

        if not self._model_cached:
            print(f"[LiteRTModel] Model not cached, cannot generate. Error: {self._cache_error}")
            return ""

        # Write prompt to temp file
        with tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False, encoding="utf-8") as f:
            f.write(prompt)
            temp_path = f.name

        try:
            cmd = [
                self.lit_exe, "run", self.model_alias,
                "--backend", self.backend,
                "--input_prompt_file", temp_path,
            ]

            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=600,
                cwd=self.working_dir,
            )

            output = result.stdout

            # Check for errors with helpful diagnostics
            if result.returncode != 0:
                stderr = result.stderr or ""
                if "not found in local cache" in stderr:
                    print(f"[LiteRTModel] ERROR: Model '{self.model_alias}' not found in lit.exe cache")
                    print(f"[LiteRTModel] TROUBLESHOOTING:")
                    print(f"[LiteRTModel]   1. Check cache dir: {Path.home() / '.litert-lm' / 'models'}")
                    print(f"[LiteRTModel]   2. Run: lit.exe list --show_all")
                    print(f"[LiteRTModel]   3. Ensure model_alias matches a registry alias")
                    print(f"[LiteRTModel]   4. See scripts/benchmark/TROUBLESHOOTING.md")
                else:
                    print(f"[LiteRTModel] lit.exe returned {result.returncode}")
                    if stderr:
                        print(f"[LiteRTModel] stderr: {stderr[:300]}")

            # Remove stop token if present
            if self.stop_token and self.stop_token in output:
                output = output.split(self.stop_token)[0]

            # Clean up thinking tags if present (Qwen3 models)
            output = re.sub(r'<think>.*?</think>', '', output, flags=re.DOTALL)

            return output.strip()

        except subprocess.TimeoutExpired:
            print("[LiteRTModel] Generation timed out (600s limit)")
            return ""
        except FileNotFoundError:
            print(f"[LiteRTModel] lit.exe not found at: {self.lit_exe}")
            print(f"[LiteRTModel] Download from: https://github.com/google-ai-edge/LiteRT-LM/releases")
            return ""
        except Exception as e:
            print(f"[LiteRTModel] Generation failed: {e}")
            return ""
        finally:
            # Clean up temp file
            try:
                Path(temp_path).unlink()
            except Exception:
                pass

    def close(self) -> None:
        """Clean up resources."""
        pass

    @classmethod
    def list_known_aliases(cls) -> dict[str, str]:
        """Return dict of known model aliases and their expected filenames."""
        return cls.REGISTRY_FILENAMES.copy()

    @classmethod
    def get_stop_token(cls, model_type: str) -> str:
        """Get the appropriate stop token for a model type."""
        return cls.STOP_TOKENS.get(model_type, "<|im_end|>")


class GGUFModel(BaseModel):
    """Direct GGUF model loader using llama-cpp-python (optional)."""

    def __init__(
        self,
        model_path: str,
        n_ctx: int = 8192,
        n_gpu_layers: int = -1,
        stop_tokens: Optional[list[str]] = None,
    ):
        """Initialize GGUF model.

        Args:
            model_path: Path to .gguf model file
            n_ctx: Context window size
            n_gpu_layers: GPU layers (-1 for all)
            stop_tokens: Stop sequences
        """
        self.model_path = model_path
        self.n_ctx = n_ctx
        self.n_gpu_layers = n_gpu_layers
        self.stop_tokens = stop_tokens or ["<|im_end|>", "<|endoftext|>"]
        self._llm = None

    def _load_model(self):
        """Lazy load llama-cpp-python model."""
        if self._llm is not None:
            return

        try:
            from llama_cpp import Llama
            self._llm = Llama(
                model_path=self.model_path,
                n_ctx=self.n_ctx,
                n_gpu_layers=self.n_gpu_layers,
                verbose=False,
            )
        except ImportError:
            raise RuntimeError("llama-cpp-python not installed. Run: pip install llama-cpp-python")

    def generate(self, prompt: str, max_tokens: int = 2048, temperature: float = 0.3) -> str:
        """Generate text using llama-cpp-python."""
        self._load_model()

        output = self._llm(
            prompt,
            max_tokens=max_tokens,
            temperature=temperature,
            stop=self.stop_tokens,
        )
        return output["choices"][0]["text"].strip()

    def close(self) -> None:
        """Release model."""
        self._llm = None

