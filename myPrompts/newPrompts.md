
### multi-pass analysis workflow
Update the chapter analysis pipeline.
On the extracted chapter text from the pdf, remove headers and footer and page numbers. Then break the text at paragraph boundaries. Then clean the text by removing repeating spaces, and repeating new lines, keep just one. Keep text organized as paragraphs. This cleaned text will be passed to the below mentioned multi-pass analysis pipeline.

Implement this multi-pass analysis workflow for book processing. Each pass should be executed sequentially, with outputs from one pass feeding into the next whereever required.
How to compute: Input/Output sizes: We have a total budget of 4096 tokens shared between prompt+input_text+output. So we need to use this wisely. Each pass below shows the details on the budget to use.
The prompt lenght is fixed. But you can fine tune input text length. Make sure you truncate text preferably on a paragraph boundary else on sentence boundary. If you have ample input, try to fill atleast 90% percent of the input budget.

**Pass-1: Character Name Extraction (Per Page)**
Extract character names from each page individually.
Token Budget:
Prompt+Input: 3500 tokens.
Output: 100 tokens

Prompt:
```
You are a character name extraction engine. Extract ONLY character names that appear in the provided story text.

OUTPUT FORMAT (valid JSON only):
{"characters": ["Name1", "Name2", "Name3"]}

TEXT:
<TEXT_SEGMENT>
```
You can create a mapping of extracted Character to the pages where this character appeared since you know which page was used as input to the LLM.

**Pass-2: Extract dialogs (For all Characters)**
Extract the dialogs of each character we extracted in pass-1. Provide the list of characters in the LLM prompt and ask it to get the dialogs of these characters in the given segment.
Use the character-page mapping from pass-1 to filter out the characters which do not appear on the current page being analyzed.
Token Budget:
Prompt+Input: 1800 tokens.
Output: 2200 tokens

Prompt:
```
You are a dialog extraction engine. Read the text sequentially and extract all the dialogs of "<CHARACTER_NAMES_APPEARING_IN_CURRENT_PAGE>" in the given story excerpt. Handle cases where a character is referenced by a pronoun. The character name or pronoun referencing them might appear before or after the dialog. Dialogs are usually quoted strings. Assign 'Unknown' to dialogs which can't be attributed to a character with high confidence.

OUTPUT FORMAT (valid JSON only):
{"Dialogs": [{"<CHARACTER_NAME>", "<Dialog>"}, ...]}

TEXT:
<TEXT_SEGMENT>
```
Save a multi-index mapping of character and dialogs. So that you can locate which character said a certain dialog. This will help you in creating audio for the dialog in the voice of the character. All the text which is not attributed to characters or Unknown, should be attributed to Narrator.


**Pass-3: Voice Profile Suggestion**
For each character in the extracted character list, suggest TTS-compatible voice parameters. Use this improved prompt:
Token Budget:
Prompt+Input: 2500 tokens.
Output: 1500 tokens

```
You are a voice casting director. Suggest a voice profile for "<CHARACTER_NAMES>" based ONLY on their depiction in the story.

STRICT RULES:
- Do NOT invent new personality traits
- Suggest specific voice qualities: pitch (low/medium/high), speed (slow/medium/fast), tone (warm/cold/neutral/energetic)
- Infer 'gender' based on how Narrator addresses them in the story.
- Infer age range from the details(baby boy/girl=kid, boy/girl=young, Uncle/Aunt=Young Adult, Mr./Mrs.=Young Adult) mentioned in the story.
- Infer accent or regional qualities based on their dialogs (e.g., "formal British", "casual American", "neutral")
- Infer emotional tendencies (e.g., "tends toward cheerful", "often serious", "emotionally varied") based on their dialogs.
- Output must be compatible with TTS parameters: pitch (0.5-1.5), speed (0.5-1.5), energy (0.5-1.5)

OUTPUT FORMAT (valid JSON only):
{
  "VoiceProfiles":[{
  "character": "<CHARACTER_NAME>",
  "voice_profile": {
    "pitch": 1.0,
    "speed": 1.0,
    "energy": 1.0,
    "gender": "male|female|unknown",
    "age": "kid|teen|young|adult|middle-aged|elderly",
    "tone": "description",
    "accent": "description or neutral",
    "emotion_bias": {"happy": 0.3, "sad": 0.1, "angry": 0.2, "neutral": 0.4, "fear": 0.1, "surprise": 0.4, "excited": 0.5, "disappointed": 0.1, "curious": 0.3, "defiant": 0.1}
  }},..]
}

TEXT:
<TEXT_SEGMENT>
```

**Implementation Notes:**
- In Pass-1 processes each page sequentially, accumulating unique character names across all pages in the chapter.
- Merge results from multiple pages: combine traits for the same character across pages, removing duplicates. Do this incrementaly after each step, not at the end.
- After Pass-3, use the voice profile to assign a TTS model speaker ID to each character.
- Store results of each pass in database as they become available. Use check-pointing after each step of each pass.
- Make sure the timeout on each LLM call is 10mins.

----
Implementation of two more passes.

**Explicit Trait Extraction**
For each character found in Pass-1, extract their explicitly stated traits. Use this improved prompt:
Token Budget:
Prompt+Input: 3000 tokens
Output: 1000 tokens

```
You are a trait extraction engine. Extract ONLY the explicitly stated traits for the characters "<CHARACTER_NAMES>" from the provided text.

STRICT RULES:
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
{"Traits" : [{"<CHARACTER_NAME>": ["trait1", "trait2", "trait3"]}] }

TEXT:
<TEXT_SEGMENT>
```

**Personality Inference**
For each character with extracted traits, infer personality characteristics. Use this improved prompt:
Token Budget:
Prompt+Input: 3000 tokens
Output: 1000 tokens

```
You are a personality analysis engine. Infer the personality of "<CHARACTER_NAMES>" based ONLY on the traits provided below.

TRAITS:
<PER_CHARACTER_TRAITS_FROM_TRAITS_PASS>

STRICT RULES:
- Base your inference ONLY on the provided traits
- Do NOT introduce new traits not in the list
- Do NOT contradict the provided traits
- Synthesize the traits into coherent personality descriptors
- Keep descriptions concise and grounded in the evidence
- Provide 3-5 personality points maximum
- If traits list is empty or insufficient, provide minimal inference (e.g., ["minor character", "limited information"])

OUTPUT FORMAT (valid JSON only):
{"character": "<CHARACTER_NAME>", "personality": ["personality_point1", "personality_point2", "personality_point3"]}

TRAITS:
<TRAITS_JSON_FROM_PASS2>
```
