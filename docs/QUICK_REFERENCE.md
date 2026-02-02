# Quick Reference Card
**Dramebaz Implementation - At a Glance**

## 🎯 Model Capabilities & Limitations

### Qwen3-1.7B-Q4-ONNX (LLM)
✅ **CAN DO:**
- JSON extraction
- Character & dialog analysis
- Emotion detection
- Story generation
- 32K token context (use 10K chars)

⚠️ **OPTIMIZE:**
- Temp 0.1-0.2 for JSON
- Max 2 concurrent requests
- Cache responses

### VITS-Piper TTS
✅ **CAN DO:**
- 109 speakers
- Speed 0.5-2.0x
- High quality

❌ **CANNOT DO:**
- Runtime pitch control
- Runtime energy control
- Emotion presets

✅ **WORKAROUNDS:**
- Pitch → Different speakers
- Energy → Post-process samples
- Emotion → Speed + speaker combo

---

## 🚀 Quick Start (First 8 Hours)

### Day 1: Critical Fixes (4 hours)
```kotlin
// 1. Fix service leak (6 min)
context?.unbindService(serviceConnection)

// 2. Fix PDF import (30 min)
throw ImportException("Failed: ${e.message}", e)

// 3. Add LLM timeout (1.5 hours)
withTimeout(60_000L) { onnxModel?.analyzeChapter(text) }

// 4. Fix coroutine scope (1.5 hours)
PlaybackEngine(context, tts, viewLifecycleOwner.lifecycleScope)

// 5. Fix speaker detection (2 hours)
// Use proximity-based algorithm
```

### Day 2: Quick Wins (4 hours)
```kotlin
// 6. Navigate after extraction (1.5 hours)
findNavController().navigate(R.id.action_to_characters)

// 7. Auto character merge (2.5 hours)
MergeCharactersUseCase().invoke(bookId)
```

**Result: 8 hours → 5 major issues fixed**

---

## 📋 Phase Checklist

```
□ PHASE 1: Critical Bugs (4h) - MUST DO FIRST
  □ AUG-001: Service leak
  □ AUG-002: PDF import
  □ AUG-003: LLM timeout
  □ AUG-004: Coroutine scope
  □ AUG-005: Speaker detection

□ PHASE 2: Character Integration (12h)
  □ AUG-006: Trigger extraction
  □ AUG-007: Navigate to characters
  □ AUG-008: Auto merging
  □ AUG-009: Trait inference
  □ AUG-010: Key moments
  □ AUG-011: Relationships

□ PHASE 3: TTS Enhancement (10h)
  □ AUG-012: Energy control
  □ AUG-013: Prosody hints
  □ AUG-014: Speaker filtering
  □ AUG-015: Voice preview
  □ AUG-016: Pitch categorization

□ PHASE 4: Playback & Sync (14h)
  □ AUG-017: Actual durations
  □ AUG-018: Progress persistence
  □ AUG-019: Pre-generation queue
  □ AUG-020: Bookmark context
  □ AUG-021: Speed control UI

□ PHASE 5: UI/UX (16h)
  □ AUG-022: Emotional graph
  □ AUG-023: Relationships graph
  □ AUG-024: Moments timeline
  □ AUG-025: Progress indicators
  □ AUG-026: Character search
  □ AUG-027: Statistics dashboard

□ PHASE 6: Data Quality (18h)
  □ AUG-028: Vocabulary builder
  □ AUG-029: Themes analysis
  □ AUG-030: Chapter summaries
  □ AUG-031: Voice consistency
  □ AUG-032: Dialog confidence

□ PHASE 7: Performance (12h)
  □ AUG-033: LLM optimization
  □ AUG-034: TTS caching
  □ AUG-035: DB indexes
  □ AUG-036: Lazy loading
  □ AUG-037: Pre-analysis

□ PHASE 8: Error Handling (8h)
  □ AUG-038: Logging
  □ AUG-039: Error handling
  □ AUG-040: Input validation
  □ AUG-041: Graceful degradation

□ PHASE 9: Testing (10h)
  □ AUG-042: Unit tests
  □ AUG-043: Integration tests
  □ AUG-044: UI tests
  □ AUG-045: Benchmarks
```

---

## 🔧 Common Code Patterns

### LLM Call with Timeout
```kotlin
suspend fun safeLlmCall(text: String): Result? = withContext(Dispatchers.IO) {
    try {
        withTimeout(60_000L) {
            onnxModel?.analyzeChapter(text)
        }
    } catch (e: TimeoutCancellationException) {
        AppLogger.w(tag, "LLM timeout, using fallback")
        null
    } catch (e: Exception) {
        AppLogger.e(tag, "LLM error", e)
        null
    } ?: stubFallback(text)
}
```

### Energy as Volume
```kotlin
fun applyEnergy(samples: FloatArray, energy: Float): FloatArray {
    return samples.map { (it * energy).coerceIn(-1.0f, 1.0f) }.toFloatArray()
}
```

### Speaker Selection for Pitch
```kotlin
fun selectSpeaker(gender: String, pitchVariation: String?): Int {
    val pitch = when (pitchVariation) {
        "high" -> PitchLevel.HIGH
        "low" -> PitchLevel.LOW
        else -> PitchLevel.MEDIUM
    }
    return VctkSpeakerCatalog.getSpeakersByPitch(gender, pitch).randomOrNull() ?: 0
}
```

### Actual Audio Duration
```kotlin
fun updateSegmentTiming(segment: TextSegment, audioFile: File): TextSegment {
    val actualDuration = MediaPlayer().apply {
        setDataSource(audioFile.absolutePath)
        prepare()
    }.duration.toLong()
    
    return segment.copy(
        audioFile = audioFile,
        actualDurationMs = actualDuration,
        audioEndMs = segment.audioStartMs + actualDuration
    )
}
```

---

## 📊 Success Metrics

After completion:
- ✅ 0 crashes from known issues
- ✅ 0 duplicate characters
- ✅ 100% character extraction success
- ✅ < 3 sec playback start time
- ✅ < 100ms text-audio sync drift
- ✅ 70%+ code coverage
- ✅ All 45 tasks complete

---

## 📁 File Locations

**Task List:**
- `augTaskList.json` - All 45 tasks

**Guides:**
- `IMPLEMENTATION_PLAN_SUMMARY.md` - Executive overview
- `IMPLEMENTATION_GUIDE.md` - Step-by-step instructions
- `TECHNICAL_SPECIFICATIONS.md` - Complex feature specs
- `QUICK_REFERENCE.md` - This file

**Analysis:**
- `COMPREHENSIVE_ISSUES_SUMMARY.md` - All issues
- `TASKLIST_VERIFICATION.md` - Tasklist accuracy
- `FEATURE_COMPLIANCE_ANALYSIS.md` - Requirements check

---

## 🎯 Priority Order

1. **Week 1-2:** PHASE 1 + PHASE 2 (16h) → Stable + Characters
2. **Week 3-4:** PHASE 3 + PHASE 4 (24h) → Voice + Playback
3. **Week 5-6:** PHASE 5 + PHASE 6 (34h) → UI + Quality
4. **Week 7-8:** PHASE 7 + PHASE 8 + PHASE 9 (30h) → Polish + Test

**Total: 104 hours over 8-10 weeks**

---

## 🚨 Critical Reminders

1. **VITS has NO pitch/energy control** → Use workarounds
2. **LLM needs timeout** → Always wrap with withTimeout()
3. **Test each phase** → Don't skip ahead
4. **Start with PHASE 1** → Fixes critical bugs
5. **Use lifecycle scope** → Prevent leaks

---

## 📞 Need Help?

- Check `TECHNICAL_SPECIFICATIONS.md` for complex features
- Review `IMPLEMENTATION_GUIDE.md` for code examples
- See `augTaskList.json` for full task details
- Refer to model compatibility notes above

**Ready to implement! Start with AUG-001 (6 minutes).**

