"""
Prompt builders for character analysis workflows.

Provides prompt templates for:
- Pass 1: Character name extraction
- Pass 2: Trait extraction
- Pass 2.5: Dialog extraction
- Pass 3: Personality inference
- Pass 4: Voice profile suggestion
- Combined segment-based extraction (2-pass workflow)
"""

import json
from typing import Optional

JSON_REMINDER = "\nEnsure the JSON is valid and contains no trailing commas."

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


class PromptBuilder:
    """Builds prompts for different model types and workflow passes."""
    
    def __init__(self, model_type: str = "chatml"):
        """Initialize prompt builder.
        
        Args:
            model_type: One of 'chatml', 'gemma', 'qwen3', 'qwen2'
        """
        self.model_type = model_type.lower()
    
    @classmethod
    def from_model_name(cls, model_name: str) -> "PromptBuilder":
        """Create PromptBuilder from model name string."""
        name_lower = model_name.lower()
        if "gemma" in name_lower:
            return cls("gemma")
        elif "qwen3" in name_lower:
            return cls("qwen3")
        elif "qwen2" in name_lower or "qwen-2" in name_lower:
            return cls("qwen2")
        else:
            return cls("chatml")
    
    def build_chat_prompt(self, system: str, user: str, assistant_history: Optional[list] = None) -> str:
        """Build prompt in appropriate format for the model type."""
        if self.model_type == "gemma":
            return f"<start_of_turn>user\n{system}\n\n{user}\n\nRespond with valid JSON only. No explanations.<end_of_turn>\n<start_of_turn>model\n"
        
        elif self.model_type == "qwen3":
            prompt = f"<|im_start|>system\n{system}<|im_end|>\n"
            if assistant_history:
                for turn in assistant_history:
                    prompt += f"<|im_start|>user\n{turn['user']}<|im_end|>\n"
                    prompt += f"<|im_start|>assistant\n{turn['assistant']}<|im_end|>\n"
            prompt += f"<|im_start|>user\n{user}\n/no_think<|im_end|>\n<|im_start|>assistant\n"
            return prompt
        
        else:  # chatml, qwen2
            prompt = f"<|im_start|>system\n{system}<|im_end|>\n"
            if assistant_history:
                for turn in assistant_history:
                    prompt += f"<|im_start|>user\n{turn['user']}<|im_end|>\n"
                    prompt += f"<|im_start|>assistant\n{turn['assistant']}<|im_end|>\n"
            prompt += f"<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n"
            return prompt
    
    def get_stop_tokens(self) -> list[str]:
        """Get appropriate stop tokens for the model type."""
        if self.model_type == "gemma":
            return ["<end_of_turn>", "<eos>"]
        else:
            return ["<|im_end|>", "<|endoftext|>"]
    
    # -------------------------------------------------------------------------
    # Pass 1: Character Name Extraction
    # -------------------------------------------------------------------------
    
    def build_pass1_prompt(self, text: str, max_chars: int = 10000) -> str:
        """Build Pass-1 prompt for character name extraction."""
        text = text[:max_chars]
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
        return self.build_chat_prompt(system, user)
    
    # -------------------------------------------------------------------------
    # Pass 2: Trait Extraction
    # -------------------------------------------------------------------------
    
    def build_pass2_trait_prompt(self, character_name: str, text: str, max_chars: int = 6000) -> str:
        """Build Pass-2 prompt for trait extraction."""
        text = text[:max_chars]
        system = f'You are a trait extraction engine. Extract ONLY the explicitly stated traits for the character "{character_name}" from the provided text.'
        user = f"""STRICT RULES:
- Extract ONLY traits directly stated or shown in the text
- Include physical descriptions if explicitly mentioned (e.g., "tall", "red hair", "scarred")
- Include behavioral traits if explicitly shown (e.g., "spoke softly", "slammed the door", "laughed nervously")
- Include speech patterns if demonstrated (e.g., "stutters", "uses formal language", "speaks with accent")
- Include emotional states if explicitly described (e.g., "angry", "frightened", "cheerful")
- Do NOT infer personality from actions
- Do NOT add interpretations or assumptions
- Do NOT include traits of other characters
- If no traits are found for this character on this page, return an empty list

OUTPUT FORMAT (valid JSON only):
{{"character": "{character_name}", "traits": ["trait1", "trait2", "trait3"]}}

TEXT:
{text}
{JSON_REMINDER}"""
        return self.build_chat_prompt(system, user)

    # -------------------------------------------------------------------------
    # Pass 2.5: Dialog Extraction
    # -------------------------------------------------------------------------

    def build_pass2_5_dialog_prompt(self, text: str, character_names: list[str], max_chars: int = 10000) -> str:
        """Build Pass-2.5 prompt for dialog extraction."""
        text = text[:max_chars]
        chars_json = json.dumps(character_names[:10])

        system = "You are a dialog extraction engine. Extract quoted speech and attribute it to the correct speaker. Output valid JSON only."
        user = f"""CHARACTERS ON THIS PAGE: {chars_json}

EXTRACTION RULES:
1. DIALOGS - Extract text within quotation marks ("..." or '...'):
   - Attribute each dialog to the nearest character name appearing BEFORE or AFTER the quote
   - Use attribution patterns: "said [Name]", "[Name] said", "[Name]:", etc.
   - If speaker cannot be determined, use "Unknown"

2. EMOTION DETECTION - For each segment:
   - Infer emotion: neutral, happy, sad, angry, surprised, fearful, excited, worried, curious, defiant
   - Estimate intensity: 0.0 (very mild) to 1.0 (very intense)

OUTPUT FORMAT (valid JSON only):
{{"dialogs": [{{"speaker": "Name", "text": "dialog", "emotion": "neutral", "intensity": 0.5}}]}}

TEXT:
{text}
{JSON_REMINDER}"""
        return self.build_chat_prompt(system, user)

    # -------------------------------------------------------------------------
    # Pass 3: Personality Inference
    # -------------------------------------------------------------------------

    def build_pass3_personality_prompt(self, character_name: str, traits: list[str]) -> str:
        """Build Pass-3 prompt for personality inference."""
        traits_text = "\n- ".join(traits) if traits else "No explicit traits found."
        traits_json = json.dumps(traits)

        system = f'You are a personality analysis engine. Infer the personality of "{character_name}" based ONLY on the traits provided below.'
        user = f"""TRAITS:
- {traits_text}

STRICT RULES:
- Base your inference ONLY on the provided traits
- Synthesize the traits into coherent personality descriptors
- Provide 3-5 personality points maximum

OUTPUT FORMAT (valid JSON only):
{{"character": "{character_name}", "personality": ["personality_point1", "personality_point2", "personality_point3"]}}

TRAITS:
{traits_json}
{JSON_REMINDER}"""
        return self.build_chat_prompt(system, user)

    # -------------------------------------------------------------------------
    # Pass 4: Voice Profile Suggestion
    # -------------------------------------------------------------------------

    def build_pass4_voice_prompt(self, character_name: str, personality: list[str]) -> str:
        """Build Pass-4 prompt for voice profile suggestion."""
        personality_text = "\n- ".join(personality) if personality else "No personality traits inferred."
        personality_json = json.dumps(personality)

        system = f'You are a voice casting director. Suggest a voice profile for "{character_name}" based ONLY on the personality description below.'
        user = f"""PERSONALITY:
- {personality_text}

OUTPUT FORMAT (valid JSON only):
{{
  "character": "{character_name}",
  "voice_profile": {{
    "pitch": 1.0, "speed": 1.0, "energy": 1.0,
    "gender": "male|female|neutral", "age": "young|middle-aged|elderly",
    "tone": "description", "accent": "neutral"
  }}
}}

PERSONALITY:
{personality_json}
{JSON_REMINDER}"""
        return self.build_chat_prompt(system, user)

    # -------------------------------------------------------------------------
    # Pass 3 with Context (Combined traits + voice)
    # -------------------------------------------------------------------------

    def build_pass3_with_context_prompt(self, character_name: str, context: str, max_chars: int = 10000) -> str:
        """Build Pass-3 prompt with aggregated context for traits + voice profile."""
        context = context[:max_chars]

        system = "You are a character analyst for TTS voice casting. Extract observable traits and suggest voice profile. JSON only."
        user = f"""CHARACTER: "{character_name}"

TEXT:
{context}

EXTRACT CONCISE TRAITS (1-2 words only):
- Examples: "gravelly voice", "nervous fidgeting", "dry humor"

SPEAKER_ID (0-108 VCTK range):
- Female young: 10-30, Female adult: 31-50
- Male young: 51-70, Male adult: 71-90
- Elderly/character: 91-108

OUTPUT FORMAT (valid JSON only):
{{
  "character": "{character_name}",
  "traits": ["trait1", "trait2", "trait3"],
  "voice_profile": {{"pitch": 1.0, "speed": 1.0, "energy": 0.7, "gender": "male|female", "age": "child|young|middle-aged|elderly", "tone": "brief description", "speaker_id": 45}}
}}
{JSON_REMINDER}"""
        return self.build_chat_prompt(system, user)

