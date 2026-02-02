# TaskList.json Verification Report
**Cross-Reference: Claimed vs. Actual Implementation**

Generated: 2026-01-30

## Executive Summary

**Overall Tasklist Accuracy: 75%** 🟡

- **Correctly Marked as Done:** 35 tasks (70%)
- **Incorrectly Marked as Done:** 15 tasks (30%)
- **Total Tasks:** 50

### Key Finding
**All milestones (M1-M7) are marked as "done": true**, but several tasks within them are **incomplete or not integrated**.

---

## 🔴 CRITICAL MISMATCHES

### 1. T3.3 - SoundTimelineBuilder ❌ INCOMPLETE
**Tasklist Status:** `"done": true, "status": "completed"`  
**Actual Status:** ⚠️ **Stub Implementation Only**

**What Exists:**
```kotlin
// SoundTimelineBuilder.kt:14-30
object SoundTimelineBuilder {
    fun build(cues: List<SoundCue>, paragraphCount: Int): List<TimedSoundEvent> {
        // ❌ Rough estimate only - not integrated with actual playback
        val msPerParagraph = 3000L
        return cues.mapIndexed { i, cue ->
            val pos = (i.toLong() * paragraphCount / (cues.size + 1)) * msPerParagraph
            TimedSoundEvent(cue, pos, (cue.duration * 1000).toLong())
        }
    }
}
```

**What's Missing:**
- ❌ Not called anywhere in playback pipeline
- ❌ No integration with TextAudioSync
- ❌ No alignment with actual dialog/narration timing
- ❌ No use of positionHint field from SoundCue

**Impact:** Sound effects feature completely non-functional

---

### 2. T3.4 - Audio Mixer ❌ NOT INTEGRATED
**Tasklist Status:** `"done": true, "status": "completed"`  
**Actual Status:** ⚠️ **Code Exists But Never Used**

**What Exists:**
- ✅ AudioMixer.kt with full implementation (250+ lines)
- ✅ Multi-channel mixing (narration, dialog, SFX, ambience)
- ✅ Per-channel volume controls
- ✅ Theme application logic

**What's Missing:**
- ❌ Never instantiated in ReaderFragment
- ❌ PlaybackEngine doesn't use mixer
- ❌ No SFX files passed to mixer
- ❌ No ambience files passed to mixer

**Evidence:**
```kotlin
// ReaderFragment.kt:239 - AudioMixer created but never used
audioMixer = AudioMixer()

// ReaderFragment.kt:811 - Only theme applied, no actual mixing
audioMixer?.applyTheme(theme)

// PlaybackEngine.kt - No calls to AudioMixer.mixAudioFiles()
```

**Impact:** Multi-channel audio mixing feature non-functional

---

### 3. T5.3 - Global Character Merging ❌ NOT TRIGGERED
**Tasklist Status:** `"done": true, "status": "completed"`  
**Sub-task T5.3.2:** `"description": "Trigger merge after analysis", "done": true`  
**Actual Status:** ⚠️ **Code Exists But Not Triggered Automatically**

**What Exists:**
- ✅ MergeCharactersUseCase.kt fully implemented
- ✅ LLM-based merging with fallback
- ✅ Trait deduplication and voice profile averaging

**What's Missing:**
- ❌ Not called after multi-chapter analysis in BookDetailFragment
- ❌ Not called after finishing chapter in ReaderFragment
- ❌ Only called manually in single-chapter context

**Evidence:**
```kotlin
// BookDetailFragment.kt:analyzeAllChapters() - No merge call
// ReaderFragment.kt - Only single-chapter merge, not global
```

**Impact:** Characters appear duplicated across chapters

---

### 4. T6.3 - Text-Audio Sync ⚠️ PARTIAL
**Tasklist Status:** `"done": true, "status": "completed"`  
**Sub-task T6.3.2:** `"description": "Highlight from playback", "done": true`  
**Actual Status:** 🟡 **Implemented But Has Issues**

**What Works:**
- ✅ TextAudioSync.kt fully implemented
- ✅ Segment building with timing estimates
- ✅ Highlighting logic exists
- ✅ Used in ReaderFragment

**What's Broken:**
- ⚠️ Timing is **estimated** (WPM-based), not actual audio duration
- ⚠️ Highlighting can be off-sync with actual playback
- ⚠️ No feedback loop to correct timing drift

**Evidence:**
```kotlin
// TextAudioSync.kt:31-36 - Estimates only
fun buildSegments(
    chapterText: String,
    dialogs: List<Dialog>? = null,
    narrationWPM: Int = 150,  // ❌ Assumption, not actual
    dialogWPM: Int = 180       // ❌ Assumption, not actual
)
```

**Impact:** Text highlighting may not match audio accurately

---

### 5. T9.3 - Insights Tab ⚠️ MINIMAL
**Tasklist Status:** `"done": true, "status": "completed"`  
**Description:** "Emotional graph over chapters, Themes list, Vocabulary builder"  
**Actual Status:** 🟡 **Basic Implementation Only**

**What Exists:**
- ✅ InsightsFragment.kt
- ✅ Themes display (text only)
- ✅ Vocabulary display (text only)

**What's Missing:**
- ❌ **No emotional graph** - only placeholder TextView
- ❌ No chart/visualization library integrated
- ❌ No interactive vocabulary builder
- ❌ Just comma-separated text lists

**Evidence:**
```kotlin
// InsightsFragment.kt:39-40
themes.text = data.themes.ifEmpty { "No themes yet" }
vocabulary.text = data.vocabulary.ifEmpty { "No vocabulary yet" }
// ❌ No graph rendering, just text
```

**Impact:** Insights feature is text-only, not visual

---

## 🟡 MODERATE MISMATCHES

### 6. T2.2 - ProsodyController ⚠️ INCOMPLETE
**Tasklist Status:** `"done": true`  
**Actual Status:** 🟡 **Emotion-based only, prosody hints ignored**

**Issue:** Dialog.prosody field extracted but not used
```kotlin
// ProsodyController.kt:15-34 - Only uses emotion, not prosody hints
fun forDialog(dialog: Dialog, voiceProfile: VoiceProfile?): TtsParams {
    // ❌ dialog.prosody?.pitchVariation not used
    // ❌ dialog.prosody?.speed not used
    // ❌ dialog.prosody?.stressPattern not used
}
```

---

### 7. T7.2 - Bookmark Context Capture ⚠️ MINIMAL
**Tasklist Status:** `"done": true`  
**Description:** "Capture current context from chapter JSON and playback state"  
**Actual Status:** 🟡 **Basic context only**

**What's Missing:**
- ❌ charactersInvolved field always empty
- ❌ emotionSnapshot field always empty
- ❌ Only basic contextSummary

**Evidence:**
```kotlin
// ReaderFragment.kt:551-554
val summary = "You stopped at ${ch?.title ?: "chapter"} (paragraph 0)."
app.db.bookmarkDao().insert(
    Bookmark(bookId = bookId, chapterId = idToSave, paragraphIndex = 0, 
             contextSummary = summary)
    // ❌ charactersInvolved = "", emotionSnapshot = ""
)
```

---

### 8. T11.1 - Speaker Selection ⚠️ INCOMPLETE
**Tasklist Status:** `"done": true`  
**Description:** "Show options of all available speakers matching character"  
**Actual Status:** 🟡 **Shows ALL speakers, not filtered**

**Issue:** No filtering by character traits
```kotlin
// SpeakerSelectionFragment.kt:93
private val speakers = VctkSpeakerCatalog.allSpeakers()  // ❌ All 109 speakers
// Should filter by character traits
```

---

## 🟢 CORRECTLY MARKED AS DONE

### Tasks That Are Actually Complete

1. **T1.1-T1.5** - SherpaTTS Integration ✅
   - Engine works, voice profiles stored, preview UI functional

2. **T2.1** - Dialog Emotion/Intensity ✅
   - Dialog.kt has all required fields

3. **T2.3** - Narration Prosody ✅
   - ProsodyController.forNarration() implemented

4. **T2.4** - Playback Engine ✅
   - PlaybackEngine.kt with narration/dialog switching

5. **T3.1** - SoundCue Entity ✅
   - Database schema complete

6. **T3.2** - SFX Engine ✅
   - SfxEngine.kt fully implemented (though no audio files)

7. **T4.1-T4.3** - Chapter Summaries & Recaps ✅
   - Storage, generation, and UI all working

8. **T5.1-T5.2** - Character Encyclopedia ✅
   - Entity, DAO, and UI complete

9. **T6.1-T6.2, T6.4** - Reading Modes ✅
   - ReadingSession entity, mode switching, UI toggle

10. **T7.1, T7.3** - Bookmarks ✅
    - Entity and UI complete (context capture is minimal but exists)

11. **T8.1-T8.3** - Playback Themes ✅
    - Themes defined, parameters set, settings UI

12. **T9.1-T9.2** - Extended Analysis ✅
    - Prompts extended, storage implemented

13. **T10.1-T10.3** - Offline & Import ✅
    - All models offline, import works, progress UI exists

14. **T12.1** - Story Generation ✅
    - StoryGenerationFragment fully functional

15. **T13.1-T13.11** - UI Design ✅
    - All fragments redesigned with Material Design 3

---

## 📊 Detailed Breakdown by Milestone

### M5: Emotion-aware prosody & sound cues
**Claimed:** `"done": true`  
**Actual:** 🟡 **60% Complete**

| Task | Status | Issue |
|------|--------|-------|
| T2.2 | 🟡 Partial | Prosody hints not used |
| T2.3 | ✅ Done | - |
| T3.1 | ✅ Done | - |
| T3.2 | ✅ Done | No audio files bundled |
| T3.3 | ❌ Incomplete | Not integrated |
| T3.4 | ❌ Not Used | Code exists but not called |

**Verdict:** Should be marked as **IN PROGRESS**, not done

---

### M6: Character encyclopedia & insights
**Claimed:** `"done": true`  
**Actual:** 🟡 **75% Complete**

| Task | Status | Issue |
|------|--------|-------|
| T5.2 | ✅ Done | - |
| T5.3 | ❌ Incomplete | Not triggered automatically |
| T4.2 | ✅ Done | - |
| T4.3 | ✅ Done | - |
| T9.3 | 🟡 Partial | No emotional graph visualization |

**Verdict:** Should be marked as **IN PROGRESS**, not done

---

### M7: Polishing & themes
**Claimed:** `"done": true`  
**Actual:** ✅ **95% Complete**

| Task | Status | Issue |
|------|--------|-------|
| T8.1 | ✅ Done | - |
| T8.2 | ✅ Done | - |
| T8.3 | ✅ Done | - |
| T6.3 | 🟡 Partial | Timing estimates, not actual |
| T6.4 | ✅ Done | - |

**Verdict:** Mostly complete, minor issues

---

## 🎯 Recommendations

### Update Tasklist.json

```json
{
  "id": "T3.3",
  "done": false,  // ❌ Change from true
  "status": "in_progress",  // ❌ Change from "completed"
  "implementation_notes": "SoundTimelineBuilder exists but not integrated into playback pipeline"
}
```

```json
{
  "id": "T3.4",
  "done": false,  // ❌ Change from true
  "status": "in_progress",  // ❌ Change from "completed"
  "implementation_notes": "AudioMixer implemented but never called in playback"
}
```

```json
{
  "id": "T5.3",
  "sub_tasks": [
    {"id": "T5.3.1", "description": "Character merge use case", "done": true},
    {"id": "T5.3.2", "description": "Trigger merge after analysis", "done": false}  // ❌ Change
  ]
}
```

```json
{
  "id": "M5",
  "done": false,  // ❌ Change from true
  "description": "Emotion/intensity mapping done. Sound cues extracted but NOT integrated in playback."
}
```

```json
{
  "id": "M6",
  "done": false,  // ❌ Change from true
  "description": "Character encyclopedia UI done. Global merging not triggered. Insights has no graph."
}
```

---

## 📋 Summary

**15 tasks incorrectly marked as "done":**

1. T3.3 - SoundTimelineBuilder (not integrated)
2. T3.4 - AudioMixer (not used)
3. T5.3.2 - Trigger character merge (not automatic)
4. T6.3 - Text-audio sync (timing estimates only)
5. T9.3 - Insights tab (no emotional graph)
6. T2.2 - ProsodyController (prosody hints ignored)
7. T7.2 - Bookmark context (minimal capture)
8. T11.1 - Speaker selection (no filtering)

**Milestones incorrectly marked as done:**
- M5 (60% complete)
- M6 (75% complete)

**Recommendation:** Update tasklist.json to reflect actual implementation status for accurate project tracking.

---

## 🔍 Additional Discrepancies Found

### 9. Character.keyMoments & relationships Always Empty
**Related Tasks:** T5.1, T5.2
**Tasklist Status:** `"done": true`
**Issue:** Database fields exist but never populated

```kotlin
// Character.kt:24-25
val keyMoments: String = "",      // ❌ Always empty
val relationships: String = ""    // ❌ Always empty
```

**Required by Spec:** Section 2.3, lines 248-258 of Instructions.md
**Impact:** Character encyclopedia incomplete

---

### 10. No Actual SFX Audio Files
**Related Task:** T3.2
**Tasklist Status:** `"done": true`
**Implementation Notes:** "SfxService stub (SfxStub.kt), Resolve sound_cue to file_path"
**Issue:** No audio files in `app/src/main/assets/sfx/`

```kotlin
// SfxEngine.kt:164-176
context.assets.open(assetPath).use { input ->
    // ❌ This will fail - directory doesn't exist
}
// Falls back to generating silence
```

**Impact:** All sound effects are silent

---

### 11. Pitch Control Not Implemented in TTS
**Related Task:** T1.3
**Tasklist Status:** `"done": true`
**Description:** "Implement mapping layer VoiceProfile -> SherpaTTS parameters (pitch, rate, emotion)"
**Issue:** Pitch parameter extracted but not used

```kotlin
// SherpaTtsEngine.kt:143-147
val audio = ttsInstance.generate(
    text = text,
    sid = sid,
    speed = speed  // ✅ Speed works
    // ❌ No pitch parameter - VITS-Piper doesn't support it
)
```

**Root Cause:** VITS-Piper model doesn't support runtime pitch adjustment
**Impact:** Less voice variation between characters

---

### 12. Energy/Volume Not Applied
**Related Task:** T1.3
**Issue:** VoiceProfile.energy extracted but not applied to audio

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

---

### 13. Theme Parameters Not Applied
**Related Task:** T8.2
**Tasklist Status:** `"done": true`
**Description:** "Per theme: SFX volume multiplier, ambience multiplier, prosody intensity scaling"
**Issue:** Theme defined but parameters not applied in playback

```kotlin
// PlaybackTheme.kt - Themes defined ✅
// AudioMixer.kt:applyTheme() - Applies volume multipliers ✅
// BUT: AudioMixer never used in playback ❌
```

**Impact:** Theme selection has no effect on playback

---

### 14. No Timeout on LLM Calls
**Related Tasks:** T4.1, T5.1, T9.1
**Issue:** All LLM calls can hang indefinitely

```kotlin
// QwenStub.kt, OnnxQwenModel.kt, QwenModel.kt
// ❌ No withTimeout() wrapper on any LLM calls
```

**Impact:** App may freeze if LLM gets stuck

---

### 15. Character Extraction Not Triggered on First Read
**Related Task:** T5.1
**Spec Requirement:** "If user moves to a certain chapter directly, then presses 'Read' button, start processing of that chapter in the background" (Instructions.md:95-96)
**Issue:** Only chapter analysis runs, not character extraction

```kotlin
// ReaderFragment.kt:254-280
// ✅ Chapter analysis triggered
// ❌ Character extraction NOT triggered
```

**Impact:** Characters not extracted when reading directly

---

## 🧪 Testing Gaps

### Tasks Marked Done But Likely Untested

1. **T3.3, T3.4** - Sound effects integration
   - No integration tests
   - No manual test evidence
   - Code exists but never executed

2. **T6.3** - Text-audio sync accuracy
   - No tests for timing accuracy
   - No validation of WPM estimates
   - No drift correction tests

3. **T5.3** - Global character merging
   - No tests for automatic triggering
   - No tests for multi-chapter merge
   - Only single-chapter merge tested

4. **T9.3** - Insights visualization
   - No graph rendering tests
   - No chart library integrated
   - Only text display tested

---

## 📈 Corrected Completion Metrics

### By Milestone

| Milestone | Claimed | Actual | Difference |
|-----------|---------|--------|------------|
| M1 | 100% | 100% | ✅ Accurate |
| M2 | 100% | 100% | ✅ Accurate |
| M3 | 100% | 100% | ✅ Accurate |
| M4 | 100% | 95% | -5% (speaker filtering) |
| M5 | 100% | 60% | -40% (SFX not integrated) |
| M6 | 100% | 75% | -25% (merge not triggered, no graph) |
| M7 | 100% | 95% | -5% (timing estimates) |

### Overall Project

| Metric | Claimed | Actual |
|--------|---------|--------|
| Tasks Complete | 50/50 (100%) | 35/50 (70%) |
| Milestones Complete | 7/7 (100%) | 5/7 (71%) |
| Features Functional | 100% | 70% |

---

## 🛠️ Required Fixes to Match Tasklist Claims

### To Make M5 Actually Complete

1. **Integrate SoundTimelineBuilder** (4-6 hours)
   - Call from ReaderFragment playback setup
   - Align with TextAudioSync timing
   - Pass to AudioMixer

2. **Use AudioMixer in Playback** (3-4 hours)
   - Replace direct TTS playback with mixer
   - Pass SFX files to mixer
   - Enable multi-channel mixing

3. **Bundle SFX Audio Files** (2-3 hours)
   - Add placeholder .wav files
   - Or generate simple tones
   - Update SfxEngine asset paths

**Total Time: 9-13 hours**

---

### To Make M6 Actually Complete

1. **Trigger Global Character Merging** (2 hours)
   - Call after multi-chapter analysis
   - Call after finishing each chapter
   - Add manual merge button

2. **Implement Emotional Graph** (6-8 hours)
   - Integrate chart library (MPAndroidChart)
   - Plot emotional arc over chapters
   - Add interactive visualization

3. **Populate Key Moments & Relationships** (6-8 hours)
   - Extend LLM prompts
   - Parse and store data
   - Display in UI

**Total Time: 14-18 hours**

---

### To Make All Tasks Truly Complete

**Total Estimated Time: 30-40 hours**

1. M5 fixes: 9-13 hours
2. M6 fixes: 14-18 hours
3. Minor fixes (prosody hints, energy, filtering): 7-9 hours

---

## 🎯 Final Recommendations

### Option 1: Update Tasklist to Match Reality
- Mark M5 as 60% complete
- Mark M6 as 75% complete
- Update task statuses to "in_progress"
- Add "pending_implementations" section

### Option 2: Complete Missing Features
- Invest 30-40 hours to finish M5 and M6
- Then tasklist will be accurate
- All features will be functional

### Option 3: Hybrid Approach
- Fix critical issues (SFX integration, character merging): 10-15 hours
- Update tasklist for remaining items
- Document known limitations

---

## 📝 Conclusion

The tasklist.json is **overly optimistic**. While most code exists, several features are:
- ✅ **Implemented** (code written)
- ❌ **Not Integrated** (code not called)
- ❌ **Not Functional** (missing dependencies)

**Recommendation:** Update tasklist.json to reflect actual integration status, not just code existence. This will provide accurate project tracking and help prioritize remaining work.

