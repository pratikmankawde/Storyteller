Read the instruction file at `FullFeatured app Instructions.md` and create a tasklist.json file with each tasks, task and sub-tasks (each with description, ai generated work-items), milestores mentioned in the instruction file. Add a done(true/false) attribute to each task and sub tasks.
Then go through each task and implement it. Set the done flag to true when finished. Write a test activity for all the tasks that can be tested.

Iterate over the task list to finish all the tasks and achieve all the milestone.

Use placeholders stubs for ai model files.

Create usertask.md file to document all the things you need from me.
Do not wait for user input, try to finish all the tasks. Skip a task that cannot be finished without user input.

After each task is finished, build and install on the emulator. Run the corresponding test activity to test the feature. If there are any errors or issues, fix them and repeat. If you encounter file lock issues when building, kill all the OpenJDK processes and try clean build again. If that fails, relaunch Cursor IDE and try a clean build. If you encounter server error or connection issues, keep retrying. If after retrying for 15mins things still don't work, hibernet the computer.

Once all the tasks are finished(or only few left which cannot be finished without user input or unfixable errors), hibernet the computer.






The character extraction is not working well. Follow this workflow using Gwen LLM(generate appropriate prompts to extract this information or use the prompts suggested in 'C:\Users\Pratik\source\Storyteller\FullFeatured app Instructions.md'):
1. Scan PDF text to detect start of first Chapter. Simple technique of detecting a chapter could be: it will start from a new page, it starts with the chapter name, title.
2. Start the scan of the PDF from the start of the first chapter to find the start of the second chapter. The page before start of the second chapter is the end of the first chapter.
3. You can find start and end of chapters using by following this process till the end of the novel. Store the start and end pages of each chapter.
4. Now do this for first chapter:
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

Update TTS model to use models from 'D:\Learning\Ai\Models\Sherpa\vits-piper-en_US-libritts-high'. Remove old models and replace references with the new model. Copy to this project and install with the app on the device.



### 4-pass character analysis workflow

Implement a 4-pass character analysis workflow for chapter processing. Each pass should be executed sequentially, with outputs from one pass feeding into the next. Process text page-by-page (approximately 10,000 characters per segment).

**Pass-1: Character Name Extraction (Per Page)**
Extract character names from each page individually. Use this improved prompt:

```
You are a character name extraction engine. Extract ONLY character names that appear in the provided text.

STRICT RULES:
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
{"characters": ["Name1", "Name2", "Name3"]}

TEXT:
<CHAPTER_PAGE_TEXT>
```

**Pass-2: Explicit Trait Extraction (Per Character, Parallel Processing)**
For each character found in Pass-1, extract their explicitly stated traits from the same page text. Use this improved prompt:

```
You are a trait extraction engine. Extract ONLY the explicitly stated traits for the character "<CHARACTER_NAME>" from the provided text.

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
{"character": "<CHARACTER_NAME>", "traits": ["trait1", "trait2", "trait3"]}

TEXT:
<SAME_CHAPTER_PAGE_TEXT>
```

**Pass-3: Personality Inference (Per Character, Parallel Processing)**
For each character with extracted traits, infer personality characteristics. Use this improved prompt:

```
You are a personality analysis engine. Infer the personality of "<CHARACTER_NAME>" based ONLY on the traits provided below.

TRAITS:
<TRAITS_FROM_PASS2>

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

**Pass-4: Voice Profile Suggestion (Per Character)**
For each character with inferred personality, suggest TTS-compatible voice parameters. Use this improved prompt:

```
You are a voice casting director. Suggest a voice profile for "<CHARACTER_NAME>" based ONLY on the personality description below.

PERSONALITY:
<PERSONALITY_FROM_PASS3>

STRICT RULES:
- Base suggestions ONLY on the provided personality description
- Do NOT invent new personality traits
- Suggest specific voice qualities: pitch (low/medium/high), speed (slow/medium/fast), tone (warm/cold/neutral/energetic)
- Infer likely gender if personality suggests it (e.g., "masculine", "feminine", "neutral")
- Infer likely age range if personality suggests it (e.g., "young", "middle-aged", "elderly")
- Suggest accent or regional qualities if personality implies them (e.g., "formal British", "casual American", "neutral")
- Suggest emotional tendencies (e.g., "tends toward cheerful", "often serious", "emotionally varied")
- Output must be compatible with TTS parameters: pitch (0.5-1.5), speed (0.5-1.5), energy (0.5-1.5)

OUTPUT FORMAT (valid JSON only):
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

PERSONALITY:
<PERSONALITY_JSON_FROM_PASS3>
```

**Implementation Notes:**
- Pass-1 processes each page sequentially, accumulating unique character names across all pages in the chapter
- Merge results from multiple pages: combine traits for the same character across pages, removing duplicates. Do this incrementately after each step, not at the end.
- After Pass-4, use `SpeakerMatcher.suggestSpeakerId()` to assign a VCTK speaker ID (0-108) based on voice_profile.gender, voice_profile.age, and personality traits
- Store final results in database: `Character(name, traits, personalitySummary, voiceProfileJson, speakerId)`
- Handle edge cases: characters with no traits should get fallback traits based on name heuristics (see `inferTraitsFromName()`)
- Update the traits of existing characters if you get new traits in next chapter.


---


## Audio Generation and Caching Strategy

### 1. Character-Page Mapping Storage
**Question**: Does the system currently save which characters appear on which pages after LLM extraction?
**Required**: Implement a character-page mapping table in the database with schema: `(bookId, chapterId, pageNumber, characterName, firstAppearance: Boolean)`. Read following instructions before deciding the schema. More fields may be required.

### 2. Speaker Assignment Workflow (Fix Missing Assignment)
**Issue**: Extracted characters are not getting speaker IDs assigned.
**Required Fix**:
- After character extraction, verify each character has: name, traits, personality summary
- If traits or personality are missing, call LLM to extract them
- Call LLM to suggest voice profile based on personality (JSON format compatible with TTS)
- Use `SpeakerMatcher.suggestSpeakerId()` to assign a speaker ID (0-108 for VCTK model)
- Save all fields to database: `Character(name, traits, personalitySummary, voiceProfileJson, speakerId)`

### 3. Initial Audio Generation (First Chapter, First Page)
**Trigger**: When chapter 1 analysis completes
**Action**:
- Generate TTS audio for ALL dialog segments on page 1 (using each character's assigned speaker ID)
- Generate TTS audio for ALL narration segments on page 1 (using narrator speaker ID)
- Save each audio segment to persistent storage
- File naming: `audio/{bookId}/{chapterId}/page_{pageNum}/segment_{index}_{characterName}.wav`

### 4. Audio Caching and Lookup Strategy
**Requirement**: All generated audio must be saved and reused (never regenerate the same content, except when speaker changes for a character, manually changed by user or triggered because of re-analysis of chapter)
**Lookup Key**: `(bookId, chapterId, pageNumber, segmentIndex, characterName, speakerId)`
**Storage Options**:
- **Option A (Recommended)**: Save individual audio files per dialog/narration segment
  - Path: `audio/{bookId}/ch{chapterId}/p{pageNum}/seg{index}_{char}_{speakerId}.wav`
  - Pros: Easy to update individual segments when speaker changes
  - Cons: Requires stitching on playback.
- **Option B**: Save one stitched audio file per page
  - Path: `audio/{bookId}/ch{chapterId}/page_{pageNum}_full.wav`
  - Pros: Faster playback start
  - Cons: Must regenerate entire page if any speaker changes

**Decision Required**: Choose Option A for flexibility, implement stitching logic in `PlaybackEngine`
Note: So cache a stitced audio during a live session of book reading.

### 5. Speaker Change Handling
**Trigger**: User changes speaker assignment for a character in `SpeakerSelectionFragment`
**Action**:
1. Update `Character.speakerId` in database
2. Determine current page: if user never started reading, use page 1; else use `ReadingSession.lastPageIndex`
3. Find all dialog segments on current page for that character. This should already be in storage.
4. Regenerate audio for those segments with new speaker ID
5. Update cached audio files (overwrite old files)
6. If playback is active, reload the current page's audio

### 6. Playback Initialization
**Trigger**: User opens `ReaderFragment` and presses Play button
**Requirement**: Audio must already exist (pre-rendered)
**Flow**:
1. Check if audio exists for current page in cache
2. If exists: Load and play immediately
3. If missing: Show "Generating audio..." toast, generate on-demand, save, then play
4. **Goal**: Always have audio pre-generated before user presses Play

### 7. Background Pre-Generation (Lookahead)
**Trigger**: Audio playback starts on page N
**Action**:
1. While page N audio is playing, start background task to generate audio for page N+1
2. Save generated audio to cache
3. When page N finishes, immediately play page N+1 (already cached)
4. Start generating page N+2 in background
**Constant**: Always keep 1 page ahead pre-generated

### Implementation Checklist
- [ ] Add `CharacterPageMapping` table to database
- [ ] Fix speaker assignment in `ChapterCharacterExtractionUseCase.extractAndSave()`
- [ ] Create `PageAudioStorage` class for managing cached audio files
- [ ] Implement per-segment audio generation in `ReaderFragment.generatePageAudio()`
- [ ] Add audio stitching logic in `PlaybackEngine` (if using Option A)
- [ ] Implement speaker change handler in `SpeakerSelectionFragment`
- [ ] Add lookahead audio generation in `ReaderFragment` page change callback
- [ ] Update `loadSavedPageAudio()` to check cache before generating


------



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

**Implementation Notes:**
- Pass-1 processes each page sequentially, accumulating unique character names across all pages in the chapter.
- Merge results from multiple pages: combine traits for the same character across pages, removing duplicates. Do this incrementaly after each step, not at the end.
- After Pass-4, use `SpeakerMatcher.suggestSpeakerId()` to assign a VCTK speaker ID (0-108) based on voice_profile.gender, voice_profile.age, and personality traits
- Store final results in database: `Character(name, traits, personalitySummary, voiceProfileJson, speakerId)`
- Handle edge cases: characters with no traits should get fallback traits based on name heuristics (see `inferTraitsFromName()`)
- Update the traits of existing characters if you get new traits in next chapter.


-----

Update the LLM-Pipeline architecture to separate passes from the workflow executors.
There should be individual classes for each pass.
Example:
- CharacterExtractionPass
- DialogExtractionPass
- TraitExtractionPass
- VoiceProfileExtractionPass
- PersonalityExtractionPass
- ...

Separate the prompts from the extraction passes. Prompt objects should define their name, purpose, token budgets, inputs source/type and output details. Based on a selected prompt, the XXXExtractionPass should try to arrange inputs and process output of the LLM.

A MultipassPipeline class should pick these individual passes and create a LLM Analysis pipeline.
Then an Analysis service can use the MultipassPipeline class to create a pipeline and run it.

----

ChapterAnalysisService and CharacterAnalysisForegroundService services seem to have too many responsibilities. Break these down to
BackgroundAnalysisService.kt (Run a given pass/pipeline in background.)
ForegroundAnalysisService.kt (Run a given pass/pipeline in foreground.)
These shouldn't be tied to models or passes or a certain pipeline.

Then a AnalysisExecutor can choose one of these two services, assign the a pass/pipeline and ask them to execute it.

-----
