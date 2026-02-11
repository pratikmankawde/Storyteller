# Feature Compliance Analysis
**Character Extraction, Dialog Extraction & TTS Generation**

Generated: 2026-01-30

## Executive Summary

**Overall Status:** 🟡 **Partially Compliant** (70% complete)

- ✅ **Character Extraction:** Implemented but incomplete
- ✅ **Dialog Extraction:** Implemented but basic
- ✅ **TTS Generation:** Fully implemented
- ⚠️ **Voice Customization:** Implemented but missing features
- ❌ **Sound Effects:** Partially implemented (no actual audio files)
- ❌ **Global Character Merging:** Implemented but not triggered automatically

---

## 1. Character Extraction Analysis

### ✅ What's Working

1. **Per-Chapter Extraction** (`ChapterCharacterExtractionUseCase.kt`)
   - ✅ Splits chapter into segments (pages)
   - ✅ Parallel LLM calls for character detection
   - ✅ Trait extraction per character
   - ✅ Voice profile suggestion via LLM
   - ✅ Speaker ID assignment via `SpeakerMatcher`
   - ✅ Database storage with proper schema

2. **LLM Integration**
   - ✅ ONNX model support (Qwen3-1.7B-Q4-ONNX)
   - ✅ Stub fallback when models unavailable
   - ✅ Proper prompt engineering for character extraction

### ❌ Missing Features (Per FullFeatured app Instructions.md)

#### **CRITICAL: Workflow Mismatch**

**Required Workflow (Section 2.1, lines 76-96):**
```
1. Scan PDF to detect chapter start/end pages ✅ DONE
2. For each chapter:
   - Detect characters page-by-page ✅ DONE
   - For found characters:
     - If already in DB, skip ✅ DONE
     - Else infer traits ✅ DONE
   - Create character list with traits ✅ DONE
   - Suggest voice profiles (TTS JSON) ✅ DONE
   - Assign speakers ✅ DONE
   - Store in database ✅ DONE
3. Display characters with assigned speakers ❌ MISSING
4. User can listen to voice examples ✅ DONE (VoicePreviewFragment)
5. User can change speaker from similar options ✅ DONE (SpeakerSelectionFragment)
6. When reading finishes, analyze next chapter ✅ DONE (ReaderFragment:254-280)
```

**Issue #1: Character Display After Analysis**
- **Location:** `BookDetailFragment.kt`
- **Problem:** After character extraction completes, there's no automatic navigation to show extracted characters
- **Expected:** "Display the characters found and their assigned speakers on a page" (line 89)
- **Current:** User must manually navigate to Characters tab

**Issue #2: No Progress Feedback During Extraction**
- **Location:** `BookDetailFragment.kt:analyzeCharacters()`
- **Problem:** Progress dialog shows but doesn't update with meaningful status
- **Expected:** Show "Chapter 1/5: segment 3/10..." progress
- **Current:** Generic "Analyzing..." message

**Issue #3: Character Extraction Not Triggered on First Read**
- **Location:** `ReaderFragment.kt`
- **Problem:** When user opens a chapter for first time, character extraction doesn't run automatically
- **Expected:** "If user moves to a certain chapter directly, then presses 'Read' button, start processing of that chapter in the background" (lines 95-96)
- **Current:** Only chapter analysis runs, not character extraction

### 🐛 Bugs

**Bug #1: Empty Traits Handling**
```kotlin
// ChapterCharacterExtractionUseCase.kt:106-110
for (name in allNames) {
    if (nameToTraits[name].orEmpty().isEmpty()) {
        nameToTraits[name] = listOf("story_character")  // ❌ Generic fallback
    }
}
```
**Issue:** Characters with no traits get generic "story_character" trait
**Impact:** Poor voice matching, all unknown characters sound similar
**Fix:** Should retry trait inference or use context-based heuristics

**Bug #2: Stub Dialog Extraction Too Simple**
```kotlin
// QwenStub.kt:210-220
val dialogPattern = Regex("\"([^\"]+)\"")
val dialogs = dialogPattern.findAll(chapterText).map { matchResult ->
    val dialogText = matchResult.groupValues[1]
    val speaker = characterCandidates.firstOrNull() ?: "Unknown"  // ❌ Always first character
    Dialog(speaker = speaker, dialog = dialogText, emotion = "neutral", intensity = 0.5f)
}.toList()
```
**Issue:** All dialogs assigned to first character when using stub
**Impact:** Wrong voices for dialogs when LLM unavailable
**Fix:** Use proximity-based speaker detection (find nearest character name before quote)

---

## 2. Dialog Extraction Analysis

### ✅ What's Working

1. **LLM-Based Extraction** (`OnnxQwenModel.kt`, `QwenModel.kt`)
   - ✅ Extracts speaker, dialog text, emotion, intensity
   - ✅ Prosody hints (pitch_variation, speed, stress_pattern)
   - ✅ Proper JSON schema matching (Section 2.2, lines 170-182)

2. **Dialog Playback** (`PlaybackEngine.kt`, `ReaderFragment.kt`)
   - ✅ Separate voices for each character
   - ✅ Emotion-aware prosody adjustments
   - ✅ Text-audio synchronization

### ❌ Missing Features

**Issue #4: No Dialog Speaker Correction UI**
- **Required:** User should be able to correct misidentified speakers
- **Current:** No UI to edit dialog-speaker mappings
- **Impact:** Stuck with wrong voices if LLM misidentifies speaker

**Issue #5: Prosody Hints Not Fully Utilized**
```kotlin
// ProsodyController.kt:15-34
fun forDialog(dialog: Dialog, voiceProfile: VoiceProfile?): TtsParams {
    // ❌ prosody.pitchVariation, prosody.speed, prosody.stressPattern are ignored
    val speedScale = when (dialog.emotion.lowercase()) {
        "anger", "fear" -> 1f + intensity * 0.2f
        // ...
    }
}
```
**Issue:** Dialog.prosody field extracted but not used in TTS
**Impact:** Missing fine-grained prosody control from LLM analysis

### 🐛 Bugs

**Bug #3: Dialog Matching in Paragraphs**
```kotlin
// ReaderFragment.kt:628-630
val paragraphDialogs = analysis.dialogs?.filter { dialog ->
    paragraph.contains(dialog.dialog, ignoreCase = true)  // ❌ Substring match
} ?: emptyList()
```
**Issue:** Substring matching can match wrong dialogs
**Example:** Dialog "I can't" matches paragraph containing "I can't believe it" AND "You can't do that"
**Fix:** Use exact phrase matching with word boundaries

---

## 3. TTS Generation Analysis

### ✅ What's Working

1. **SherpaTTS Integration** (`SherpaTtsEngine.kt`)
   - ✅ VITS-Piper en_US-libritts-high model
   - ✅ 904 speakers (ID 0-903)
   - ✅ Speed control (0.5-2.0x)
   - ✅ Speaker ID selection
   - ✅ Proper audio file generation

2. **Voice Profile Mapping** (`VoiceProfileMapper.kt`)
   - ✅ Pitch, speed, energy mapping
   - ✅ Emotion preset selection
   - ✅ Speaker ID support

3. **Character-Specific Voices**
   - ✅ Each character gets unique speaker ID
   - ✅ Voice profiles stored in database
   - ✅ User can preview and customize

### ⚠️ Partial Implementation

**Issue #6: Pitch Control Not Implemented**
```kotlin
// SherpaTtsEngine.kt:143-147
val audio = ttsInstance.generate(
    text = text,
    sid = sid,
    speed = speed  // ✅ Speed works
    // ❌ No pitch parameter - VITS-Piper doesn't support runtime pitch adjustment
)
```
**Issue:** VoiceProfile.pitch is extracted but not used
**Reason:** Sherpa-ONNX VITS-Piper model doesn't support pitch adjustment
**Impact:** Less voice variation between characters
**Workaround:** Use different speaker IDs for pitch variation

**Issue #7: Energy/Volume Not Implemented**
```kotlin
// VoiceProfileMapper.kt:19-29
fun toTtsParams(profile: VoiceProfile?): TtsParams {
    return TtsParams(
        pitch = profile.pitch.coerceIn(0.5f, 1.5f),  // ❌ Not used
        speed = profile.speed.coerceIn(0.5f, 1.5f),  // ✅ Used
        energy = profile.energy.coerceIn(0.5f, 1.5f),  // ❌ Not used
        emotionPreset = dominant
    )
}
```
**Issue:** Energy parameter extracted but not applied
**Impact:** No volume variation based on character energy
**Fix:** Apply energy as post-processing volume adjustment

---

## 4. Voice Customization Analysis

### ✅ What's Working

1. **Voice Preview** (`VoicePreviewFragment.kt`)
   - ✅ Play sample with character voice
   - ✅ Pitch slider (0.5-1.5)
   - ✅ Speed slider (0.5-1.5)
   - ✅ Save overrides to database

2. **Speaker Selection** (`SpeakerSelectionFragment.kt`)
   - ✅ List all 904 LibriTTS speakers
   - ✅ Show speaker traits (gender, accent)
   - ✅ Save selection to database

### ❌ Missing Features (Per MoreFeatures.md)

**Issue #8: No "Similar Speakers" Filtering**
- **Required:** "Show options of all the speakers matching the character" (MoreFeatures.md:3)
- **Current:** Shows ALL 904 speakers
- **Expected:** Filter speakers by character traits (gender, age, accent)
- **Impact:** User overwhelmed with irrelevant options

**Suggested Fix:**
```kotlin
// SpeakerSelectionFragment.kt
private fun getMatchingSpeakers(characterTraits: String): List<LibrittsSpeakerTraits> {
    val traits = SpeakerMatcher.parseTraits(characterTraits)
    return LibrittsSpeakerCatalog.allSpeakers().filter { speaker ->
        // Score each speaker and return top 20
        SpeakerMatcher.scoreSpeaker(speaker, traits) > 0
    }.sortedByDescending { SpeakerMatcher.scoreSpeaker(it, traits) }.take(20)
}
```

**Issue #9: No Voice Example Playback in Speaker Selection**
- **Required:** "User should be able to listen an example of the assigned voice" (Instructions.md:90)
- **Current:** SpeakerSelectionFragment shows list but no preview button
- **Expected:** Each speaker row should have a "Play" button
- **Impact:** User can't hear voice before selecting

---

## 5. Sound Effects & Ambience Analysis

### ⚠️ Partially Implemented

1. **SFX Engine** (`SfxEngine.kt`)
   - ✅ Tag-based sound library mapping
   - ✅ Keyword matching algorithm
   - ✅ Category-based fallback
   - ❌ **NO ACTUAL AUDIO FILES** in assets

2. **Sound Cue Extraction**
   - ✅ LLM extracts sound_cues from chapter
   - ✅ Stored in database (SoundCue entity)
   - ❌ **NOT USED IN PLAYBACK**

### ❌ Critical Missing Implementation

**Issue #10: Sound Cues Not Integrated in Playback**
```kotlin
// ReaderFragment.kt - playback code
// ❌ chapterAnalysis.soundCues is extracted but never used
// ❌ No calls to SfxEngine.resolveToFile()
// ❌ No mixing of SFX with narration/dialog
```

**Required Implementation:**
1. Extract sound_cues from chapter analysis ✅ DONE
2. Resolve sound prompts to audio files ✅ CODE EXISTS (SfxEngine)
3. Align sound cues with playback timeline ❌ MISSING
4. Mix SFX/ambience with narration/dialog ✅ CODE EXISTS (AudioMixer) but NOT USED

**Issue #11: No Actual SFX Audio Files**
```kotlin
// SfxEngine.kt:164-176
context.assets.open(assetPath).use { input ->
    // ❌ This will fail - no files in app/src/main/assets/sfx/
}
// Falls back to generating silence
```
**Impact:** All sound effects are silent
**Fix:** Need to bundle actual .wav files or generate them

---

## 6. Global Character Merging Analysis

### ✅ What's Working

1. **Merge Logic** (`MergeCharactersUseCase.kt`)
   - ✅ Merges traits across chapters
   - ✅ Averages voice profiles
   - ✅ LLM-based merging when available
   - ✅ Stub fallback merging

### ❌ Missing Features

**Issue #12: Merging Not Triggered Automatically**
- **Required:** "Periodically run global character merging after new chapters are analyzed" (Instructions.md:398)
- **Current:** `MergeCharactersUseCase` exists but is only called from `ReaderFragment` for single-chapter analysis
- **Expected:** After analyzing multiple chapters, merge all characters
- **Impact:** Characters appear duplicated across chapters

**Suggested Trigger Points:**
1. After analyzing all chapters in a book (BookDetailFragment)
2. After finishing reading a chapter (ReaderFragment)
3. Manual "Merge Characters" button in Characters screen

**Issue #13: No Key Moments or Relationships Tracking**
```kotlin
// Character.kt:24-25
val keyMoments: String = "", // JSON array - ❌ Always empty
val relationships: String = ""  // JSON array - ❌ Always empty
```
**Issue:** Fields exist but never populated
**Required:** "Track key moments and relationships" (Instructions.md:214, 248-257)
**Impact:** Character encyclopedia incomplete

---

## 7. Compliance Summary by Feature

| Feature                     | Spec Reference           | Status                  | Completion |
| --------------------------- | ------------------------ | ----------------------- | ---------- |
| **Character Extraction**    | Section 2.1, T5.1        | 🟡 Partial               | 85%        |
| - Per-chapter detection     | Lines 81-82              | ✅ Done                  | 100%       |
| - Trait inference           | Lines 84                 | ✅ Done                  | 100%       |
| - Voice profile suggestion  | Lines 86-87              | ✅ Done                  | 100%       |
| - Speaker assignment        | Lines 87                 | ✅ Done                  | 100%       |
| - Display to user           | Lines 89-90              | ❌ Missing               | 0%         |
| - Voice preview             | Lines 90                 | ✅ Done                  | 100%       |
| - Speaker change UI         | Lines 90, MoreFeatures:3 | 🟡 Partial               | 60%        |
| **Dialog Extraction**       | Section 2.2, T2.1        | 🟡 Partial               | 75%        |
| - Speaker identification    | Lines 104-105            | ✅ Done                  | 100%       |
| - Emotion + intensity       | Lines 106                | ✅ Done                  | 100%       |
| - Prosody hints             | Lines 107                | 🟡 Extracted, not used   | 50%        |
| **TTS Generation**          | Section 3.1, T1.1-T1.5   | ✅ Done                  | 95%        |
| - SherpaTTS integration     | T1.1                     | ✅ Done                  | 100%       |
| - Voice profiles            | T1.2-T1.3                | 🟡 Partial (no pitch)    | 80%        |
| - Character-specific voices | T1.4                     | ✅ Done                  | 100%       |
| - Voice preview UI          | T1.5                     | ✅ Done                  | 100%       |
| **Sound Effects**           | Section 3.3, T3.1-T3.4   | ❌ Incomplete            | 30%        |
| - Sound cue extraction      | T3.1                     | ✅ Done                  | 100%       |
| - SFX generation/selection  | T3.2                     | 🟡 Code exists, no files | 50%        |
| - Timeline alignment        | T3.3                     | ❌ Missing               | 0%         |
| - Audio mixing              | T3.4                     | 🟡 Code exists, not used | 50%        |
| **Global Merging**          | Section 2.3, T5.3        | 🟡 Partial               | 60%        |
| - Merge logic               | Lines 201-224            | ✅ Done                  | 100%       |
| - Automatic triggering      | Line 398                 | ❌ Missing               | 0%         |
| - Key moments tracking      | Lines 248-250            | ❌ Missing               | 0%         |
| - Relationships tracking    | Lines 252-258            | ❌ Missing               | 0%         |
| **Story Generation**        | MoreFeatures:4           | ✅ Done                  | 100%       |

**Overall Completion: 70%**

---

## 8. Prioritized Solutions

### 🔴 CRITICAL FIXES (Must Do)

#### **Fix #1: Integrate Sound Effects in Playback**
**Priority:** CRITICAL
**Effort:** Medium (4-6 hours)
**Files:** `ReaderFragment.kt`, `PlaybackEngine.kt`

```kotlin
// ReaderFragment.kt - Add after line 618
private suspend fun prepareSoundCues(analysis: ChapterAnalysisResponse): List<Pair<SoundCue, File?>> {
    val sfxEngine = SfxEngine(requireContext())
    return analysis.soundCues?.mapNotNull { cue ->
        val audioFile = sfxEngine.resolveToFile(cue.soundPrompt, cue.duration, cue.category)
        if (audioFile != null) cue to audioFile else null
    } ?: emptyList()
}

// In playback setup (around line 640)
val soundCueFiles = prepareSoundCues(analysis)
// Pass to PlaybackEngine or AudioMixer
```

**Also Need:**
1. Bundle actual SFX audio files in `app/src/main/assets/sfx/`
2. Or use Android MediaPlayer to generate simple tones as placeholder
3. Implement timeline alignment in `TextAudioSync.kt`

#### **Fix #2: Trigger Character Display After Extraction**
**Priority:** CRITICAL
**Effort:** Low (1-2 hours)
**Files:** `BookDetailFragment.kt`

```kotlin
// BookDetailFragment.kt - After analyzeCharacters() completes
private fun analyzeCharacters() {
    // ... existing code ...
    viewLifecycleOwner.lifecycleScope.launch {
        // ... extraction code ...

        withContext(Dispatchers.Main) {
            progressDialog?.dismiss()

            // Navigate to Characters tab
            val charactersFragment = CharactersFragment().apply {
                arguments = Bundle().apply { putLong("bookId", bookId) }
            }
            findNavController().navigate(
                R.id.action_bookDetailFragment_to_charactersFragment,
                charactersFragment.arguments
            )

            Toast.makeText(context, "Found ${characterCount} characters", Toast.LENGTH_LONG).show()
        }
    }
}
```

#### **Fix #3: Fix Dialog Speaker Detection in Stub**
**Priority:** HIGH
**Effort:** Low (2 hours)
**Files:** `QwenStub.kt`

```kotlin
// QwenStub.kt:210-220 - Replace with proximity-based detection
private fun stubAnalyzeChapter(chapterText: String): ChapterAnalysisResponse {
    val characterCandidates = stubDetectCharactersOnPage(chapterText)
    val dialogPattern = Regex("\"([^\"]+)\"")

    val dialogs = mutableListOf<Dialog>()
    var lastPos = 0

    for (match in dialogPattern.findAll(chapterText)) {
        val dialogText = match.groupValues[1]
        val dialogStart = match.range.first

        // Find nearest character name before the dialog
        val precedingText = chapterText.substring(lastPos, dialogStart)
        val speaker = characterCandidates.findLast { name ->
            precedingText.contains(name, ignoreCase = true)
        } ?: characterCandidates.firstOrNull() ?: "Unknown"

        dialogs.add(Dialog(
            speaker = speaker,
            dialog = dialogText,
            emotion = "neutral",
            intensity = 0.5f
        ))
        lastPos = match.range.last
    }

    return ChapterAnalysisResponse(/* ... */, dialogs = dialogs, /* ... */)
}
```

### 🟡 HIGH PRIORITY FIXES

#### **Fix #4: Filter Similar Speakers in Selection**
**Priority:** HIGH
**Effort:** Medium (3-4 hours)
**Files:** `SpeakerSelectionFragment.kt`, `SpeakerMatcher.kt`

```kotlin
// SpeakerMatcher.kt - Add new method
fun getSimilarSpeakers(traits: String?, personalitySummary: String?, name: String?, topN: Int = 20): List<LibrittsSpeakerTraits> {
    val traitTokens = parseTraits(traits).toMutableList()
    personalitySummary?.split(Regex("\\s+"))?.map { it.trim().lowercase() }?.let { traitTokens.addAll(it) }

    val scored = LibrittsSpeakerCatalog.allSpeakers().map { speaker ->
        val score = scoreSpeaker(speaker, traitTokens)
        speaker to score
    }.filter { it.second > 0 }
      .sortedByDescending { it.second }
      .take(topN)

    return scored.map { it.first }
}

private fun scoreSpeaker(speaker: LibrittsSpeakerTraits, traitTokens: List<String>): Int {
    var score = 0
    for (t in traitTokens) {
        when {
            t in setOf("female", "woman") && speaker.isFemale -> score += 10
            t in setOf("male", "man") && speaker.isMale -> score += 10
            t in setOf("young") && speaker.ageBucket == "young" -> score += 5
            t in setOf("old", "elder") && speaker.ageBucket == "older" -> score += 5
            t in setOf("british", "english") && speaker.region == "England" -> score += 3
            // ... more matching rules
        }
    }
    return score
}

// SpeakerSelectionFragment.kt - Use filtered list
override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    // ...
    viewLifecycleOwner.lifecycleScope.launch {
        val character = app.db.characterDao().getById(characterId)
        currentSpeakerId = character?.speakerId

        // Get similar speakers instead of all
        val similarSpeakers = SpeakerMatcher.getSimilarSpeakers(
            character?.traits,
            character?.personalitySummary,
            character?.name,
            topN = 30
        )

        val adapter = SpeakerAdapter(currentSpeakerId, similarSpeakers) { selectedSpeakerId ->
            saveSpeakerSelection(selectedSpeakerId)
        }
        recycler.adapter = adapter
    }
}
```

#### **Fix #5: Add Voice Preview to Speaker Selection**
**Priority:** HIGH
**Effort:** Medium (3 hours)
**Files:** `SpeakerSelectionFragment.kt`, `res/layout/item_speaker.xml`

```xml
<!-- res/layout/item_speaker.xml - New layout -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:padding="12dp">

    <TextView
        android:id="@+id/speaker_label"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:textSize="16sp" />

    <ImageButton
        android:id="@+id/btn_preview"
        android:layout_width="48dp"
        android:layout_height="48dp"
        android:src="@android:drawable/ic_media_play"
        android:background="?attr/selectableItemBackgroundBorderless" />
</LinearLayout>
```

```kotlin
// SpeakerSelectionFragment.kt - Update adapter
private class SpeakerAdapter(
    private val currentSelection: Int?,
    private val speakers: List<LibrittsSpeakerTraits>,
    private val onPreview: (Int) -> Unit,
    private val onSpeakerSelected: (Int) -> Unit
) : RecyclerView.Adapter<SpeakerAdapter.ViewHolder>() {

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val speaker = speakers[position]
        holder.labelView.text = speaker.displayLabel()
        holder.labelView.isSelected = speaker.speakerId == currentSelection

        holder.itemView.setOnClickListener {
            onSpeakerSelected(speaker.speakerId)
        }

        holder.previewButton.setOnClickListener {
            onPreview(speaker.speakerId)
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val labelView: TextView = view.findViewById(R.id.speaker_label)
        val previewButton: ImageButton = view.findViewById(R.id.btn_preview)
    }
}

// In fragment
private fun previewSpeaker(speakerId: Int) {
    viewLifecycleOwner.lifecycleScope.launch {
        val sampleText = "Hello, this is speaker $speakerId. How do I sound?"
        val result = app.ttsEngine.speak(sampleText, null, null, speakerId)
        result.onSuccess { audioFile ->
            audioFile?.let { playAudio(it) }
        }
    }
}
```

#### **Fix #6: Trigger Automatic Character Merging**
**Priority:** HIGH
**Effort:** Low (2 hours)
**Files:** `BookDetailFragment.kt`, `ReaderFragment.kt`

```kotlin
// BookDetailFragment.kt - After analyzing all chapters
private fun analyzeAllChapters() {
    viewLifecycleOwner.lifecycleScope.launch {
        // ... analyze each chapter ...

        // After all chapters analyzed, merge characters
        val perChapterJsons = mutableListOf<String>()
        chapters.forEach { chapter ->
            chapter.fullAnalysisJson?.let { json ->
                val analysis = gson.fromJson(json, ChapterAnalysisResponse::class.java)
                analysis.characters?.let { chars ->
                    perChapterJsons.add(gson.toJson(chars))
                }
            }
        }

        if (perChapterJsons.isNotEmpty()) {
            AppLogger.d(tag, "Merging characters from ${perChapterJsons.size} chapters")
            MergeCharactersUseCase(app.db.characterDao(), requireContext())
                .mergeAndSave(bookId, perChapterJsons)
            AppLogger.i(tag, "Character merging complete")
        }
    }
}
```

### 🟢 MEDIUM PRIORITY FIXES

#### **Fix #7: Use Prosody Hints in TTS**
**Priority:** MEDIUM
**Effort:** Medium (3 hours)
**Files:** `ProsodyController.kt`

```kotlin
// ProsodyController.kt - Enhanced version
fun forDialog(dialog: Dialog, voiceProfile: VoiceProfile?): TtsParams {
    val base = VoiceProfileMapper.toTtsParams(voiceProfile)
    val intensity = dialog.intensity.coerceIn(0f, 1f)

    // Use prosody hints if available
    val prosody = dialog.prosody
    val speedScale = when {
        prosody?.speed == "fast" -> 1.2f
        prosody?.speed == "slow" -> 0.8f
        dialog.emotion.lowercase() in setOf("anger", "fear") -> 1f + intensity * 0.2f
        dialog.emotion.lowercase() == "sad" -> 1f - intensity * 0.15f
        else -> 1f
    }

    val pitchScale = when {
        prosody?.pitchVariation == "high" -> 1.15f
        prosody?.pitchVariation == "low" -> 0.85f
        dialog.emotion.lowercase() in setOf("anger", "surprise") -> 1f + intensity * 0.1f
        dialog.emotion.lowercase() == "sad" -> 1f - intensity * 0.05f
        else -> 1f
    }

    return TtsParams(
        pitch = base.pitch * pitchScale,
        speed = base.speed * speedScale,
        energy = base.energy * (0.8f + intensity * 0.2f),
        emotionPreset = dialog.emotion.ifEmpty { base.emotionPreset }
    )
}
```

#### **Fix #8: Apply Energy as Volume**
**Priority:** MEDIUM
**Effort:** Low (1-2 hours)
**Files:** `SherpaTtsEngine.kt`

```kotlin
// SherpaTtsEngine.kt - After audio generation
val audio = ttsInstance.generate(text = text, sid = sid, speed = speed)

// Apply energy as volume scaling
val energyScale = params.energy.coerceIn(0.5f, 1.5f)
val scaledSamples = audio.samples.map { it * energyScale }.toFloatArray()

val outputFile = saveAudioToFile(scaledSamples, audio.sampleRate, text.hashCode().toString())
```

#### **Fix #9: Track Key Moments and Relationships**
**Priority:** MEDIUM
**Effort:** High (6-8 hours)
**Files:** `OnnxQwenModel.kt`, `QwenModel.kt`, `MergeCharactersUseCase.kt`

**Requires:**
1. Extend LLM prompts to extract key moments per character
2. Extract relationships between characters
3. Update merge logic to combine key moments
4. Update UI to display this information

---

## 9. Testing Checklist

### Character Extraction
- [ ] Import a book with multiple characters
- [ ] Verify each character has unique traits
- [ ] Check speaker IDs are assigned correctly
- [ ] Confirm voice profiles are stored in DB
- [ ] Test with LLM model available
- [ ] Test with stub fallback (no model)

### Dialog Extraction
- [ ] Verify dialogs are extracted with correct speakers
- [ ] Check emotion and intensity values are reasonable
- [ ] Test prosody hints are present in analysis
- [ ] Verify dialog playback uses correct character voices

### TTS Generation
- [ ] Test voice preview for each character
- [ ] Verify speed adjustment works (0.5x - 1.5x)
- [ ] Check different speaker IDs produce different voices
- [ ] Test long text (>1000 chars) doesn't crash

### Voice Customization
- [ ] Open voice preview, adjust sliders, save
- [ ] Verify saved settings persist after app restart
- [ ] Test speaker selection shows filtered list
- [ ] Verify voice preview plays in speaker selection

### Sound Effects
- [ ] Check sound cues are extracted from chapter
- [ ] Verify SFX files resolve correctly
- [ ] Test audio mixing with narration + SFX
- [ ] Check timeline alignment is correct

### Character Merging
- [ ] Analyze multiple chapters
- [ ] Verify characters are merged (no duplicates)
- [ ] Check traits are combined correctly
- [ ] Verify voice profiles are averaged

---

## 10. Quick Wins (Low Effort, High Impact)

1. **Fix #2:** Show characters after extraction (1-2 hours) ⭐⭐⭐
2. **Fix #3:** Better dialog speaker detection (2 hours) ⭐⭐⭐
3. **Fix #6:** Auto-trigger character merging (2 hours) ⭐⭐
4. **Fix #8:** Apply energy as volume (1-2 hours) ⭐⭐

**Total Quick Wins Time: 6-8 hours**
**Impact: Fixes 4 major user-facing issues**

---

## 11. Recommended Implementation Order

### Phase 1: Critical Fixes (Week 1)
1. Fix #2: Character display after extraction
2. Fix #3: Dialog speaker detection
3. Fix #1: Sound effects integration (basic)

### Phase 2: High Priority (Week 2)
4. Fix #4: Filter similar speakers
5. Fix #5: Voice preview in speaker selection
6. Fix #6: Automatic character merging

### Phase 3: Polish (Week 3)
7. Fix #7: Use prosody hints
8. Fix #8: Apply energy as volume
9. Fix #9: Key moments and relationships
10. Bundle actual SFX audio files

**Total Estimated Time: 3 weeks (60-80 hours)**
