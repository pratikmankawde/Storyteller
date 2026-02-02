# Why Pass 1 Finished Quickly with 0 Characters

## Root cause: checkpoint resume

From the device logs:

```
CharAnalysisFgService: 📖 Starting Gemma 2-pass analysis: bookId=6, chapter=— CHAPTER ONE —
...
GemmaCharAnalysis: 📂 Loaded checkpoint: pass=1, segment=1
GemmaCharAnalysis: 📄 Text split into 5 segments for Pass 1
GemmaCharAnalysis: 📊 Pass 1 complete: 0 characters found
```

What happened:

1. **A checkpoint was loaded** for this book/chapter: `pass=1, segment=1`.
2. **Checkpoint semantics**: `lastCompletedPass = 1` means “Pass 1 is done”; `lastCompletedSegment = 1` is the index of the last Pass-1 segment that was completed (so segments 0 and 1 were already run in a previous session).
3. **Resume logic** in `GemmaCharacterAnalysisUseCase`:
   - `pass1StartSegment = if (lastCompletedPass >= 1) Int.MAX_VALUE else lastCompletedSegment + 1`
   - So with `lastCompletedPass = 1` → `pass1StartSegment = Int.MAX_VALUE`.
   - The Pass 1 loop runs only when `pass1StartSegment < pass1Segments.size` (e.g. 5). So the loop is **skipped entirely** — no Pass 1 LLM calls.
4. **State comes from the checkpoint**: `extractedCharacters = checkpoint.extractedCharacters`. In the previous run, Pass 1 had already been “completed” for 2 segments but had found **no characters** (empty list from the model or from parsing). So the saved checkpoint had `extractedCharacters = empty`.
5. **Result**: Current run skips Pass 1, uses that empty map → “Pass 1 complete: 0 characters found” and then Pass 2 runs with no character names (only “Narrator” in the prompt).

So Pass 1 “finished so quickly” because it **did not run at all**; the run resumed from an old checkpoint that said Pass 1 was done but had stored 0 characters.

## How to avoid this next time

- **Clear checkpoint** for this book/chapter if you want a full re-run (e.g. delete the checkpoint file or add a “Re-analyze from scratch” that calls `deleteCheckpoint(bookId, chapterIndex)` before starting).
- Optionally **discard checkpoint when character count is 0** after “Pass 1 complete” (e.g. treat 0 characters as invalid and re-run Pass 1 or delete checkpoint so the next run starts fresh).

---

# Pass 1 and Pass 2 prompts (LiteRtLmEngine)

Below are the **exact** prompts used for the two passes in `LiteRtLmEngine.kt`.

---

## Pass 1: Character + voice profile extraction

**System prompt** (constant `PASS1_SYSTEM_PROMPT`):

```
You are a character extraction engine for TTS voice casting.
Extract ALL character names and suggest voice profiles for text-to-speech. Output valid JSON only.
```

**User prompt** (from `buildPass1Prompt(text)`; `<TEXT>...</TEXT>` is replaced with the segment text):

```
Analyze this story text and extract ALL characters with their voice profiles.

EXTRACTION RULES:
- Extract ONLY proper names of people/beings who speak or act in the story
- Include characters with dialog (quoted speech) or performing actions
- DO NOT include places, objects, abstract concepts, or the narrator
- For each character, suggest a complete voice_profile for TTS

VOICE PROFILE PARAMETERS:
- pitch: 0.2-1.8 (0.8-0.9 for deep/commanding, 1.1-1.2 for bright/young)
- speed: 0.5-1.5 (0.8-0.9 for slow/deliberate, 1.1-1.2 for fast/excited)
- energy: 0.1-1.0 (0.5-0.7 for calm/reserved, 0.9-1.0 for energetic)
- gender: "male" | "female" | "neutral"
- age: "child" | "young" | "middle-aged" | "elderly"
- tone: brief 1-2 word description (e.g., "warm friendly", "gruff commanding")
- accent: "neutral" | "british" | "american" | "other"
- emotion_bias: map of emotion weights (e.g., {"joy": 0.3, "calm": 0.5})

SPEAKER_ID GUIDE (VCTK corpus 0-108):
- Male young: 0-20, middle-aged: 21-45, elderly: 46-55
- Female young: 56-75, middle-aged: 76-95, elderly: 96-108

OUTPUT FORMAT (valid JSON only):
{
  "characters": [
    {
      "name": "Character Name",
      "traits": ["trait1", "trait2"],
      "voice_profile": {
        "pitch": 1.0,
        "speed": 1.0,
        "energy": 0.7,
        "gender": "male",
        "age": "middle-aged",
        "tone": "warm friendly",
        "accent": "neutral",
        "emotion_bias": {"calm": 0.5},
        "speaker_id": 35
      }
    }
  ]
}

<TEXT>
{segment text}
</TEXT>
```

Pass 1 uses **segments** from `segmentTextForPass1(fullText)` (~12,000 chars / ~3000 tokens per segment). Each segment is sent in `<TEXT>...</TEXT>`.

---

## Pass 2: Dialog extraction with speaker attribution

**System prompt** (constant `PASS2_SYSTEM_PROMPT`):

```
You are a dialog extraction engine.
Extract quoted speech and attribute it to the correct speaker. Output valid JSON only.
```

**User prompt** (from `buildPass2Prompt(text, characterNames)`; `$charactersJson` is the list of character names from Pass 1 plus `"Narrator"`, and `<TEXT>...</TEXT>` is the segment text):

```
Extract ALL dialogs and narrator text from this passage.

KNOWN CHARACTERS: ["Char1","Char2",...,"Narrator"]

ATTRIBUTION RULES:
1. Use EXPLICIT attribution when available:
   - "X said/asked/replied/whispered" → speaker is X
   - Direct quotes immediately after character action → speaker is that character

2. Use CONTEXTUAL clues when no explicit attribution:
   - Pronoun references (he/she) → refer to most recently mentioned character
   - Dialog in a scene with only two characters → alternate speakers
   - If truly uncertain, use "Unknown"

3. NARRATOR for non-dialog prose:
   - Descriptions, scene-setting, internal thoughts (if not quoted)
   - Keep narrator segments reasonably sized (not entire paragraphs)

EMOTION DETECTION:
- Detect emotion from dialog content and attribution verbs
- Common emotions: neutral, happy, sad, angry, fearful, surprised, disgusted, excited, calm
- Intensity: 0.0-1.0 (how strong the emotion is)

OUTPUT FORMAT (valid JSON only):
{
  "dialogs": [
    {"speaker": "Character Name", "text": "Exact quoted text", "emotion": "neutral", "intensity": 0.5},
    {"speaker": "Narrator", "text": "Prose description...", "emotion": "neutral", "intensity": 0.3}
  ]
}

<TEXT>
{segment text}
</TEXT>
```

Pass 2 uses **segments** from `segmentTextForPass2(fullText)` (~6,000 chars / ~1500 tokens per segment). When Pass 1 returned 0 characters, `characterNames` is empty, so KNOWN CHARACTERS is only `["Narrator"]`.

---

## Source locations

- **Pass 1**: `app/src/main/java/com/dramebaz/app/ai/llm/LiteRtLmEngine.kt`  
  - `PASS1_SYSTEM_PROMPT`, `buildPass1Prompt(text)`, `pass1ExtractCharactersAndVoiceProfiles(segmentText)`
- **Pass 2**: same file  
  - `PASS2_SYSTEM_PROMPT`, `buildPass2Prompt(text, characterNames)`, `pass2ExtractDialogs(segmentText, characterNames)`
- **Checkpoint**: `app/src/main/java/com/dramebaz/app/domain/usecases/GemmaCharacterAnalysisUseCase.kt`  
  - `loadCheckpoint`, `saveCheckpoint`, `deleteCheckpoint`, `getCheckpointFile`
