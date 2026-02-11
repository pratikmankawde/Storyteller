# Action Items - Character, Dialog & TTS Features

## 🔴 CRITICAL ISSUES (Fix Immediately)

### 1. Sound Effects Not Working
**Status:** Code exists but not integrated  
**Impact:** Feature completely non-functional  
**Files:** `ReaderFragment.kt`, `PlaybackEngine.kt`, `app/src/main/assets/sfx/`

**What's Wrong:**
- Sound cues extracted from chapters but never played
- SFX audio files missing from assets folder
- AudioMixer exists but not used in playback

**Action:**
1. Add SFX integration to playback pipeline
2. Bundle placeholder audio files or generate tones
3. Test with a chapter that has sound cues

**Estimated Time:** 4-6 hours

---

### 2. Characters Not Displayed After Extraction
**Status:** Missing UI flow  
**Impact:** User doesn't see extraction results  
**Files:** `BookDetailFragment.kt`

**What's Wrong:**
- Character extraction completes successfully
- No automatic navigation to show results
- User must manually find Characters tab

**Action:**
1. After extraction, navigate to CharactersFragment
2. Show toast with character count
3. Add progress updates during extraction

**Estimated Time:** 1-2 hours

---

### 3. Dialog Speaker Detection Broken in Stub Mode
**Status:** Bug in fallback logic  
**Impact:** Wrong voices when LLM unavailable  
**Files:** `QwenStub.kt:210-220`

**What's Wrong:**
- All dialogs assigned to first character
- No proximity-based speaker detection

**Action:**
1. Find nearest character name before each dialog
2. Use context clues (he said, she said)
3. Test with stub mode enabled

**Estimated Time:** 2 hours

---

## 🟡 HIGH PRIORITY (Fix This Week)

### 4. Speaker Selection Shows All 109 Speakers
**Status:** Missing feature  
**Impact:** Poor user experience  
**Files:** `SpeakerSelectionFragment.kt`, `SpeakerMatcher.kt`

**What's Wrong:**
- User sees all speakers regardless of character traits
- No filtering by gender, age, accent

**Action:**
1. Add `getSimilarSpeakers()` method to SpeakerMatcher
2. Filter speakers by character traits
3. Show top 20-30 matches only

**Estimated Time:** 3-4 hours

---

### 5. No Voice Preview in Speaker Selection
**Status:** Missing feature  
**Impact:** User can't hear before selecting  
**Files:** `SpeakerSelectionFragment.kt`, `res/layout/item_speaker.xml`

**What's Wrong:**
- Speaker list shows traits but no play button
- User must select blindly

**Action:**
1. Add play button to each speaker row
2. Generate sample audio on button click
3. Use MediaPlayer for playback

**Estimated Time:** 3 hours

---

### 6. Character Merging Not Automatic
**Status:** Code exists but not triggered  
**Impact:** Duplicate characters across chapters  
**Files:** `BookDetailFragment.kt`, `ReaderFragment.kt`

**What's Wrong:**
- MergeCharactersUseCase implemented
- Never called after multi-chapter analysis

**Action:**
1. Call merge after analyzing all chapters
2. Call merge after finishing each chapter
3. Add manual "Merge Characters" button

**Estimated Time:** 2 hours

---

## 🟢 MEDIUM PRIORITY (Fix Next Week)

### 7. Prosody Hints Not Used
**Status:** Extracted but ignored  
**Impact:** Less expressive dialog  
**Files:** `ProsodyController.kt`

**What's Wrong:**
- LLM extracts pitch_variation, speed, stress_pattern
- ProsodyController only uses emotion

**Action:**
1. Map prosody hints to TTS parameters
2. Combine with emotion-based adjustments
3. Test with various dialog types

**Estimated Time:** 3 hours

---

### 8. Energy/Volume Not Applied
**Status:** Parameter extracted but unused  
**Impact:** No volume variation  
**Files:** `SherpaTtsEngine.kt`, `VoiceProfileMapper.kt`

**What's Wrong:**
- VoiceProfile.energy extracted
- Not applied to audio output

**Action:**
1. Scale audio samples by energy value
2. Apply after TTS generation
3. Test with high/low energy characters

**Estimated Time:** 1-2 hours

---

### 9. Key Moments & Relationships Not Tracked
**Status:** Database fields empty  
**Impact:** Character encyclopedia incomplete  
**Files:** `OnnxQwenModel.kt`, `MergeCharactersUseCase.kt`

**What's Wrong:**
- Character.keyMoments always empty
- Character.relationships always empty
- LLM prompts don't extract this data

**Action:**
1. Extend LLM prompts for key moments
2. Extract character relationships
3. Update merge logic
4. Update UI to display

**Estimated Time:** 6-8 hours

---

## 📋 BUGS TO FIX

### Bug #1: Empty Traits Get Generic Fallback
**Location:** `ChapterCharacterExtractionUseCase.kt:106-110`  
**Issue:** Characters without traits get "story_character"  
**Fix:** Retry trait inference or use better heuristics

### Bug #2: Substring Dialog Matching
**Location:** `ReaderFragment.kt:628-630`  
**Issue:** `paragraph.contains(dialog.dialog)` matches substrings  
**Fix:** Use exact phrase matching with word boundaries

### Bug #3: No Timeout on LLM Calls
**Location:** `QwenStub.kt`, `OnnxQwenModel.kt`  
**Issue:** App can hang indefinitely  
**Fix:** Add `withTimeout(60_000L)` to all LLM calls

---

## ✅ WHAT'S WORKING WELL

1. ✅ Character extraction with parallel LLM calls
2. ✅ Voice profile generation and storage
3. ✅ Speaker ID assignment via SpeakerMatcher
4. ✅ SherpaTTS integration with 109 voices
5. ✅ Voice preview with pitch/speed sliders
6. ✅ Dialog extraction with emotion/intensity
7. ✅ Story generation feature
8. ✅ Character merging logic (when triggered)

---

## 🎯 QUICK WINS (Do First)

**Total Time: 6-8 hours**  
**Impact: Fixes 4 major issues**

1. **Character Display** (1-2 hours) - Navigate to characters after extraction
2. **Dialog Speaker Fix** (2 hours) - Proximity-based speaker detection
3. **Auto Merge** (2 hours) - Trigger character merging automatically
4. **Energy Volume** (1-2 hours) - Apply energy as volume scaling

---

## 📊 COMPLETION STATUS

| Component | Status | Completion |
|-----------|--------|------------|
| Character Extraction | 🟡 Partial | 85% |
| Dialog Extraction | 🟡 Partial | 75% |
| TTS Generation | ✅ Done | 95% |
| Voice Customization | 🟡 Partial | 70% |
| Sound Effects | ❌ Broken | 30% |
| Character Merging | 🟡 Partial | 60% |
| **OVERALL** | 🟡 **Partial** | **70%** |

---

## 🚀 RECOMMENDED NEXT STEPS

### This Week
1. Fix character display after extraction
2. Fix dialog speaker detection in stub
3. Add speaker filtering by traits
4. Add voice preview to speaker selection

### Next Week
5. Integrate sound effects in playback
6. Trigger automatic character merging
7. Use prosody hints in TTS
8. Apply energy as volume

### Later
9. Track key moments and relationships
10. Bundle actual SFX audio files
11. Add dialog speaker correction UI
12. Improve error handling throughout

---

## 📝 NOTES

- Most core functionality is implemented
- Main issues are integration and UI flows
- LLM integration is solid with good fallbacks
- TTS engine works well with VCTK model
- Sound effects need the most work
- Character merging logic is good but not triggered

**Focus on Quick Wins first to get immediate user-facing improvements!**

