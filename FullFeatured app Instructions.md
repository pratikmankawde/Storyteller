---

1. Overall vision and architecture

Goal: A fully offline-capable Android app, named 'Dramebaz' that turns any book (PDF/EPUB/TXT) into an immersive, AI-driven experience with:

- Character-aware voices (via SherpaTTS)  
- Emotion-aware narration  
- Dynamic sound effects and ambience  
- Deep reading tools (summaries, character encyclopedia, analysis)  
- Seamless read/listen modes  

1.1 High-level architecture

On-device components:

- LLM engine:  
  - Qwen3-4B-Q4_K_M (or compatible Qwen/AL instruct model) as primary text model  
  - Runs via a local inference backend (e.g., llama.cpp-style engine embedded in Android)  
  - Input/output: ChatML format (see Section 2.0); analysis outputs strict JSON per schemas below
- TTS engine:  
  - SherpaTTS Android engine for voice synthesis, model:vits-vctk 
- SFX/ambience engine:  
  - Local audio generation model (e.g., Stable Audio Open / AudioGen quantized)  
  - Or pre-bundled SFX library + AI selection if full audio generation is too heavy
- Storage & indexing:  
  - Room database for books, chapters, characters, profiles, bookmarks, settings  
  - Local file storage for:
    - imported books  
    - generated audio (voices, SFX, ambience)  
- PDF/EPUB/TXT parser:  
  - Extracts chapters, paragraphs, dialogs

Core pipelines:

1. Ingestion pipeline: Import ? parse ? split into chapters ? store.  
2. Analysis pipeline (per chapter): LLM/AL (Qwen3) ? JSON (characters, traits, dialogs, sound cues, summaries).  
3. Audio pipeline:  
   - SherpaTTS for voices  
   - Audio model / SFX library for sound effects & ambience  
4. Playback pipeline:  
   - Mix narration, dialog voices, SFX, ambience  
   - Sync with text view.

---

2. AI models, prompts, and schemas

2.0 LLM/AL model input and output format (compatibility)

The app uses a ChatML-style format compatible with Qwen3 and other AL/LLM instruct models:

**Input format (ChatML):**
- System: `<|im_start|>system\n{system_prompt}<|im_end|>\n`
- User: `<|im_start|>user\n{user_prompt}<|im_end|>\n`
- Assistant (invitation): `<|im_start|>assistant\n`
- No thinking block for analysis (JSON-only output).

**Output format:**
- Chapter analysis and extended analysis: model must return **only valid JSON** matching the schemas in 2.2 (and extended schema for themes/vocabulary). No markdown code fences, no explanatory text before or after.
- Story generation: model returns **only story text** (no JSON, no meta-commentary).
- Parser tolerates optional markdown code blocks (e.g. \`\`\`json ... \`\`\`) and strips them; first `{` to last `}` is used for JSON.

**Model in use:** Qwen3-4B-Q4_K_M.gguf (or equivalent instruct GGUF). Ensure the model supports ChatML and can follow strict JSON/story-only output.

2.1 Chapter analysis with Qwen/AL (core prompt)

System message:

`text
You are an NLP engine running on a mobile device.
Your task is to analyze a chapter of a novel and produce structured JSON.

Follow these rules:
1. Extract all characters mentioned in this chapter. For each character, infer personality traits based only on this chapter.
2. Follow this workflow using Gwen LLM(generate appropriate prompts to extract this information or use the prompts suggested in 'C:\Users\Pratik\source\Storyteller\FullFeatured app Instructions.md'):
  * Scan PDF text to detect start of first Chapter. Simple technique of detecting a chapter could be: it will start from a new page, it starts with the chapter name, title.
  * Start the scan of the PDF from the start of the first chapter to find the start of the second chapter. The page before start of the second chapter is the end of the first chapter.
  * You can find start and end of chapters using by following this process till the end of the novel. Store the start and end pages of each chapter.
  * Now do this for first chapter:
      * Use Gwen to detect Actors/Characters in the chapter by passing it text of one page at a time, and asking if it found a character in that page.
      * For all the found characters,
          * If the character was already in the database, move to next character.
          * Else ask Gwen to infer the personality traits of that character.
      * Create a list of these characters, with their personality traits.
      * Ask Gwen to suggest a voice profile for each of the found characters given their personality types. The output of this query should be in the format of a json, that can be passed to TTS Model.
      * Use the Gwen's output to assign a speaker to each character.
      * Store this information in database. The analysis will be keyed with book id.
  * Display the characters found and their assigned speakers on a page.
      * User should be able to listen an example of the assigned voice. Then optionally change the speaker from a list of similar speakers available in the TTS model.
  * On the reading page, show the contents of the first chapter's, first page.
  * When the reading of this page is done, show content of next page.
  * When reading of first chapter is finish, perform the same analysis for second chapter(as described above).
  * This continues as reading of a chapter is finished.
  * If user moves to a certain chapter directly, then presses the 'Read' button, start processing of that chapter in the background and show wait/loading. Start reading when the analysis is done and voices are available.
  * Populate Character Analysis and Insights pages per chapter by quering Gwen about it.

3. Build a voice-trait profile compatible with SherpaTTS:
   - pitch (0.5-1.5)
   - speed (0.5-1.5)
   - energy (0.5-1.5)
   - emotion_bias: {happy, sad, angry, fear, surprise, neutral} (0-1)
4. Extract all dialogs:
   - Identify the speaker (best guess if ambiguous)
   - Extract the dialog text
   - Determine emotion + intensity (0-1)
   - Determine prosody hints: pitchvariation, speed, stresspattern
5. Extract sound cues for sound effects and ambience:
   - event description
   - sound_prompt (short natural language description)
   - duration in seconds (1-8)
   - category: "effect" or "ambience"
6. Provide a short chapter summary and key events.
7. Output ONLY valid JSON. No explanations.
8. Do not invent characters not present in the chapter.
9. If speaker is unknown, set speaker = "unknown".
`

User message:

`text
CHAPTER_TEXT:
"""
{chaptertexthere}
"""

TASK:
Analyze this chapter and produce JSON following the required schema.
`

2.2 Chapter analysis JSON schema

`json
{
  "chapter_summary": {
    "title": "Chapter 3",
    "short_summary": "Alice confronts Bob about the missing letter.",
    "main_events": [
      "Alice finds the hidden letter.",
      "Alice confronts Bob in the marketplace.",
      "A storm begins as tension rises."
    ],
    "emotional_arc": [
      {"segment": "start", "emotion": "curiosity", "intensity": 0.4},
      {"segment": "middle", "emotion": "anger", "intensity": 0.8},
      {"segment": "end", "emotion": "unease", "intensity": 0.6}
    ]
  },

  "characters": [
    {
      "name": "Alice",
      "traits": ["curious", "impulsive", "empathetic"],
      "voice_profile": {
        "pitch": 1.1,
        "speed": 1.05,
        "energy": 0.9,
        "emotion_bias": {
          "happy": 0.4,
          "sad": 0.1,
          "angry": 0.2,
          "fear": 0.1,
          "surprise": 0.3,
          "neutral": 0.5
        }
      }
    }
  ],

  "dialogs": [
    {
      "speaker": "Alice",
      "dialog": "I can't believe you hid this from me!",
      "emotion": "anger",
      "intensity": 0.8,
      "prosody": {
        "pitch_variation": "high",
        "speed": "fast",
        "stress_pattern": "front-loaded"
      }
    }
  ],

  "sound_cues": [
    {
      "event": "Alice slams the door",
      "sound_prompt": "a wooden door slamming loudly in a quiet room",
      "duration": 2.0,
      "category": "effect"
    },
    {
      "event": "Thunderstorm begins",
      "sound_prompt": "distant thunder rumbling with light rain",
      "duration": 4.0,
      "category": "ambience"
    }
  ]
}
`

2.3 Global character merging (cross-chapter)

Task: Merge per-chapter character entries into a global character encyclopedia.

Prompt (Qwen/AL, offline batch; use same ChatML format as 2.0):

`text
SYSTEM:
You are a character analysis engine. You receive multiple JSON snippets describing the same characters across different chapters. Your task is to merge them into a single global character profile per character.

Rules:
1. Merge traits, removing duplicates.
2. Summarize personality in 2'3 sentences.
3. Merge voice_profile by averaging numeric fields.
4. Track key moments and relationships.
5. Output ONLY valid JSON.

USER:
Here are per-chapter character entries:

{listofcharacterjsonsnippets}

Produce a merged global character list.
`

Output schema (global):

`json
{
  "characters": [
    {
      "name": "Alice",
      "traits": ["curious", "impulsive", "empathetic", "stubborn"],
      "personality_summary": "Alice is driven by curiosity and emotion. She often acts before thinking, but cares deeply about others.",
      "voice_profile": {
        "pitch": 1.08,
        "speed": 1.03,
        "energy": 0.88,
        "emotion_bias": {
          "happy": 0.38,
          "sad": 0.15,
          "angry": 0.22,
          "fear": 0.12,
          "surprise": 0.28,
          "neutral": 0.48
        }
      },
      "key_moments": [
        "Confronted Bob about the missing letter in the marketplace.",
        "Chose to hide the truth from her sister."
      ],
      "relationships": [
        {
          "with": "Bob",
          "type": "conflicted",
          "summary": "Trust issues due to secrets and hidden letters."
        }
      ]
    }
  ]
}
`

2.4 Sound effect generation prompts

For each sound_cue:

`text
Prompt to audio model:
"{sound_prompt}, high quality sound effect, no music, clean, {duration} seconds"
`

If using a library instead of generation, map sound_prompt to tags and pick closest SFX.

---

3. Feature-by-feature implementation tasks

I'll group them by feature, with tasks and tests.

3.1 AI-generated character voices

Tasks:

- T1.1 Integrate SherpaTTS as a system TTS engine on Android.  
- T1.2 Implement a VoiceProfile data class mirroring the JSON schema:
  - pitch: Float
  - speed: Float
  - energy: Float
  - emotionBias: Map<String, Float>
- T1.3 Implement a mapping layer from VoiceProfile ? SherpaTTS parameters:
  - Map pitch to TTS pitch control (if available).
  - Map speed to TTS rate.
  - Use emotionBias to select or bias voice variants (e.g., different models or prosody presets).
- T1.4 For each character, store VoiceProfile in Room DB:
  - character_id
  - book_id
  - voiceprofilejson
- T1.5 Implement a 'Voice Preview' screen where users can:
  - Play sample lines with the character's voice.
  - Adjust pitch/speed sliders.
  - Save overrides.

Tests:

- Unit:  
  - Verify JSON ? VoiceProfile parsing.  
  - Verify VoiceProfile ? SherpaTTS parameter mapping.
- Integration:  
  - Generate a sample line for 3 characters and confirm distinct voices.
- UX:  
  - Ensure voice preview responds within acceptable latency on device.

---

3.2 Dynamic, emotion-aware narration

Tasks:

- T2.1 Extend dialog JSON to include emotion and intensity.  
- T2.2 Implement a ProsodyController that:
  - Adjusts TTS rate, pitch, and volume based on emotion and intensity.
- T2.3 For narration (non-dialog), compute a 'scene emotion' from chaptersummary.emotionalarc and apply softer prosody changes.
- T2.4 Implement a playback engine that:
  - Distinguishes between narration and dialog.
  - Switches voices and prosody on the fly.

Tests:

- Unit:  
  - Given emotion="anger", intensity=0.8, verify prosody parameters (e.g., higher pitch variation, faster speed).
- Integration:  
  - Play a mixed narration+dialog scene and confirm audible emotional differences.

---

3.3 Automatic sound effects & ambience

Tasks:

- T3.1 Implement SoundCue entity in Room:
  - chapterid, event, soundprompt, duration, category, file_path.
- T3.2 Implement an audio generation or selection module:
  - If generation: wrap Stable Audio Open / AudioGen in a local service.
  - If selection: map sound_prompt to tags and pick from bundled SFX.
- T3.3 Implement a SoundTimelineBuilder:
  - Align sound_cues with playback positions (e.g., before/after specific dialogs or paragraphs).
- T3.4 Implement a mixer:
  - Separate channels: narration, dialog, SFX, ambience.
  - Volume controls per channel.

Tests:

- Unit:  
  - Verify sound_cues are correctly parsed and stored.
- Integration:  
  - Play a chapter and confirm SFX trigger at expected moments.
- Performance:  
  - Ensure no audio glitches on mid-range device.

---

3.4 Intelligent chapter summaries & recaps

Tasks:

- T4.1 Store chapter_summary in DB.  
- T4.2 Implement a 'Recap' generator:
  - For session start, fetch last N chapters' summaries.
  - Optionally ask Qwen to compress them into a 'Previously on'' paragraph.
- T4.3 Provide UI:
  - Recap card before resuming playback.
  - Option to listen to recap via SherpaTTS.

Tests:

- Unit:  
  - Verify recap generation logic.
- UX:  
  - Ensure recap is skippable and fast to access.

---

3.5 Character encyclopedia

Tasks:

- T5.1 Implement Character entity:
  - id, name, traits, personalitysummary, voiceprofile, key_moments, relationships.
- T5.2 Build a 'Characters' screen:
  - List of characters per book.
  - Detail view with:
    - traits  
    - personality summary  
    - key moments  
    - relationships graph (simple list or visual)  
    - voice preview.
- T5.3 Periodically run global character merging (Section 2.3) after new chapters are analyzed.

Tests:

- Unit:  
  - Verify merging logic (traits dedup, averaging voice profiles).
- UI:  
  - Ensure character list loads quickly even for large books.

---

3.6 Adaptive reading mode (read/listen/mixed)

Tasks:

- T6.1 Implement a unified 'ReadingSession' controller:
  - Tracks current book, chapter, paragraph index, playback position.
- T6.2 Implement modes:
  - Reading: text only.  
  - Listening: audio only.  
  - Mixed: text + audio, highlight current line.
- T6.3 Sync:
  - Map text spans to audio segments (approximate by paragraph/dialog index).
- T6.4 UI toggle:
  - Floating button to switch modes instantly.

Tests:

- Integration:  
  - Switch modes mid-chapter and verify continuity.
- UX:  
  - Ensure highlighting matches audio reasonably well.

---

3.7 Smart bookmarking & memory

Tasks:

- T7.1 Implement Bookmark entity:
  - bookid, chapterid, paragraphindex, timestamp, contextsummary, charactersinvolved, emotionsnapshot.
- T7.2 When user stops:
  - Capture current context from chapter JSON and playback state.
- T7.3 'Smart bookmark' UI:
  - Show short description: 'You stopped when Alice confronted Bob in the marketplace (high tension).'

Tests:

- Unit:  
  - Verify bookmark creation and retrieval.
- UX:  
  - Resume from bookmark and confirm correct position.

---

3.8 Mood-based playback themes

Tasks:

- T8.1 Define themes:
  - CINEMATIC, RELAXED, IMMERSIVE, CLASSIC.
- T8.2 For each theme, define:
  - SFX volume multiplier  
  - Ambience volume multiplier  
  - Prosody intensity scaling  
  - Voice variation level.
- T8.3 Add settings screen to choose theme.

Tests:

- Unit:  
  - Verify theme parameters applied correctly.
- UX:  
  - A/B test feel of each theme.

---

3.9 Reading assistant & analysis tools

Tasks:

- T9.1 Extend Qwen prompts to extract:
  - themes  
  - symbols  
  - foreshadowing hints  
  - vocabulary list (difficult words + definitions).
- T9.2 Store per-chapter analysis in DB.  
- T9.3 Build 'Insights' tab:
  - Emotional graph over chapters  
  - Themes list  
  - Vocabulary builder.

Tests:

- Unit:  
  - Verify JSON parsing for analysis fields.
- UX:  
  - Ensure insights feel helpful, not overwhelming.

---

3.10 Offline-first design & import any book

Tasks:

- T10.1 Ensure all models are bundled or sideloaded and run offline.  
- T10.2 Implement import flows:
  - File picker for PDF/EPUB/TXT.  
  - Parsing and chapter splitting.  
- T10.3 Show progress UI while analyzing chapters with Qwen.

Tests:

- Integration:  
  - Import a large book and ensure app remains responsive.
- Performance:  
  - Measure time per chapter analysis on target devices.

---

4. Android app structure and Kotlin activities

4.1 Suggested package structure

- ui/
  - main/
  - library/
  - reader/
  - player/
  - characters/
  - settings/
- data/
  - db/ (Room entities, DAOs)
  - models/ (Kotlin data classes mirroring JSON)
  - repositories/
- domain/
  - usecases/
- ai/
  - llm/ (Qwen integration, prompts)
  - tts/ (SherpaTTS integration)
  - audio/ (SFX/ambience)
- playback/
  - engine/
  - mixer/

4.2 Key activities / fragments (Jetpack recommended)

- MainActivity
  - Hosts navigation graph.
- LibraryFragment
  - List of books, import button.
- BookDetailFragment
  - Book info, 'Start reading/listening', 'Characters', 'Insights'.
- ReaderFragment
  - Text view, mode toggle, playback controls, chapter navigation.
- PlayerBottomSheet
  - Play/pause, seek, theme selection.
- CharactersFragment
  - List of characters.
- CharacterDetailFragment
  - Traits, summary, key moments, voice preview.
- SettingsFragment
  - Themes, model settings, storage, offline options.

---

5. Testing strategy

5.1 Levels

- Unit tests:
  - JSON parsing  
  - DB operations  
  - prosody mapping  
  - theme application
- Integration tests:
  - End-to-end: import ? analyze ? play chapter.  
  - Voice + SFX mixing.
- Performance tests:
  - Time per chapter analysis.  
  - Memory usage during playback.
- User tests:
  - Perceived immersion.  
  - Ease of switching modes.  
  - Clarity of UI.

---

6. Milestones and progress tracking

Milestone 1: Core reading & library (M1)

- Import books (PDF/EPUB/TXT).  
- Chapter splitting and storage.  
- Basic text reader with bookmarks.

Milestone 2: Qwen integration & chapter analysis (M2)

- Local Qwen model running.  
- Per-chapter JSON generation (characters, dialogs, summaries).  
- Storage and basic display of summaries and characters.

Milestone 3: SherpaTTS integration & basic audio (M3)

- SherpaTTS working as engine.  
- Single-voice narration of chapters.  
- Basic play/pause/seek.

Milestone 4: Character voices & dialog playback (M4)

- Voice profiles per character.  
- Dialog playback with character-specific voices.  
- Mixed narration + dialog.

Milestone 5: Emotion-aware prosody & sound cues (M5)

- Emotion/intensity mapping to prosody.  
- Sound cues extracted and stored.  
- SFX/ambience playback integrated.

Milestone 6: Character encyclopedia & insights (M6)

- Global character merging.  
- Character encyclopedia UI.  
- Insights tab (themes, emotional graphs).

Milestone 7: Polishing & themes (M7)

- Mood-based playback themes.  
- Recaps, smart bookmarks.  
- Performance tuning and UX polish.

---

7. Helpful resource types (what to look up)

You (or the AI agent) can search for:

- 'SherpaTTS Android integration' for engine usage and parameters.  
- 'Qwen2.5 GGUF llama.cpp Android' for running Qwen locally.  
- 'Stable Audio Open local inference' or 'AudioGen quantized mobile' for SFX generation.  
- 'Android Room + Jetpack Navigation + ViewModel' for app structure.  
- 'AudioTrack mixer Android' for custom audio mixing.

---
