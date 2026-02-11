# Quick Fix Checklist
**Dramebaz - Prioritized Issues to Fix**

## 🔥 URGENT (Do Today - 2-3 hours)

- [ ] **Fix service connection leak** (5 min)
  - File: `ReaderFragment.kt:996-1003`
  - Change: `requireContext()` → `context?`

- [ ] **Fix PDF import silent failure** (30 min)
  - File: `ImportBookUseCase.kt:57-60`
  - Change: Throw exception instead of empty content

- [ ] **Fix dialog speaker detection in stub** (2 hours)
  - File: `QwenStub.kt:210-220`
  - Add: Proximity-based speaker detection

---

## 🔴 CRITICAL (This Week - 12-15 hours)

- [ ] **Integrate sound effects in playback** (4-6 hours)
  - Files: `ReaderFragment.kt`, `PlaybackEngine.kt`
  - Add: Call SoundTimelineBuilder and pass to mixer
  - Add: Bundle placeholder SFX audio files

- [ ] **Use AudioMixer in playback** (3-4 hours)
  - File: `ReaderFragment.kt`, `PlaybackEngine.kt`
  - Add: Replace direct TTS with mixer.mixAudioFiles()

- [ ] **Trigger character merging automatically** (2 hours)
  - File: `BookDetailFragment.kt`
  - Add: Call MergeCharactersUseCase after multi-chapter analysis

- [ ] **Navigate to characters after extraction** (1-2 hours)
  - File: `BookDetailFragment.kt`
  - Add: Navigate to CharactersFragment after completion

- [ ] **Add LLM timeouts** (1-2 hours)
  - Files: `QwenStub.kt`, `OnnxQwenModel.kt`
  - Add: `withTimeout(60_000L)` wrapper on all LLM calls

---

## 🟡 HIGH PRIORITY (Next Week - 15-20 hours)

- [ ] **Filter speakers by character traits** (3-4 hours)
  - File: `SpeakerSelectionFragment.kt`
  - Add: SpeakerMatcher.getSimilarSpeakers()

- [ ] **Add voice preview to speaker selection** (3 hours)
  - File: `SpeakerSelectionFragment.kt`
  - Add: Play button in each speaker row

- [ ] **Use prosody hints in TTS** (3 hours)
  - File: `ProsodyController.kt`
  - Add: Map dialog.prosody to TTS params

- [ ] **Apply energy as volume** (1-2 hours)
  - File: `SherpaTtsEngine.kt`
  - Add: Scale audio samples by energy value

- [ ] **Fix coroutine scope lifecycle** (1-2 hours)
  - File: `PlaybackEngine.kt`
  - Change: Accept scope from caller instead of creating own

- [ ] **Populate bookmark context** (2-3 hours)
  - File: `ReaderFragment.kt`
  - Add: Extract charactersInvolved and emotionSnapshot

- [ ] **Improve text-audio sync timing** (4-6 hours)
  - File: `TextAudioSync.kt`
  - Change: Use actual TTS duration instead of WPM estimates

---

## 🟢 MEDIUM PRIORITY (Week 3 - 15-20 hours)

- [ ] **Implement emotional graph** (6-8 hours)
  - File: `InsightsFragment.kt`
  - Add: Integrate MPAndroidChart library
  - Add: Plot emotional arc visualization

- [ ] **Track key moments & relationships** (6-8 hours)
  - Files: `OnnxQwenModel.kt`, `MergeCharactersUseCase.kt`
  - Add: Extend LLM prompts
  - Add: Parse and populate Character fields

- [ ] **Standardize logging** (2-3 hours)
  - Files: Multiple
  - Change: Replace android.util.Log with AppLogger

- [ ] **Clean up deprecated code** (1-2 hours)
  - File: `SherpaTtsStub.kt`
  - Action: Remove if no longer needed

---

## 📊 Progress Tracking

### Completion Status

```
Total Issues: 25
├─ Critical: 6  [ ] [ ] [ ] [ ] [ ] [ ]
├─ High: 7      [ ] [ ] [ ] [ ] [ ] [ ] [ ]
├─ Medium: 7    [ ] [ ] [ ] [ ] [ ] [ ] [ ]
└─ Low: 5       [ ] [ ] [ ] [ ] [ ]
```

### Time Investment

```
Week 1 (Critical):     15-20 hours  [          ]
Week 2 (High):         15-20 hours  [          ]
Week 3 (Medium):       15-20 hours  [          ]
Total:                 45-60 hours  [          ]
```

---

## 🎯 Quick Wins (Do First)

These 4 fixes take only 6-8 hours but have huge impact:

1. ✅ Navigate to characters after extraction (1-2 hours)
2. ✅ Fix dialog speaker detection (2 hours)
3. ✅ Trigger character merging (2 hours)
4. ✅ Apply energy as volume (1-2 hours)

**Impact:** Fixes 4 major user-facing issues in one day!

---

## 📝 Notes

- Check off items as you complete them
- Update time estimates based on actual work
- Add new issues as discovered
- Prioritize based on user feedback

---

## 🔗 Related Documents

- **COMPREHENSIVE_ISSUES_SUMMARY.md** - Full analysis
- **FEATURE_COMPLIANCE_ANALYSIS.md** - Feature requirements check
- **TASKLIST_VERIFICATION.md** - Tasklist accuracy check
- **CODEBASE_ISSUES.md** - General code issues
- **ACTION_ITEMS.md** - Detailed action items

