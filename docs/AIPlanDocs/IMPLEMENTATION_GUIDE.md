# Dramebaz Implementation Guide
**Complete Feature Implementation Plan**

Generated: 2026-01-30

## 📋 Overview

This guide provides detailed instructions for implementing all missing, incomplete, and partially implemented features in the Dramebaz app (excluding SFX audio generation).

**Total Scope:**
- 45 tasks across 9 phases
- Estimated 104 hours (8-10 weeks, 1-2 developers part-time)
- Priority: 5 critical, 16 high, 16 medium, 8 low

---

## 🎯 Model Compatibility Analysis

### LLM: Qwen3-1.7B-Q4-ONNX

**Capabilities:**
- ✅ JSON extraction with structured output
- ✅ Character and dialog analysis
- ✅ Emotion and trait inference
- ✅ Story generation
- ✅ Multi-turn conversation
- ✅ Context length: 32K tokens (using 10K chars for mobile performance)

**Limitations:**
- ⚠️ Smaller model (1.7B) → lower accuracy than larger variants
- ⚠️ 4-bit quantization → trades quality for speed
- ⚠️ Mobile RAM constraints → limit context window usage

**Optimizations:**
- Temperature 0.1-0.2 for JSON extraction (deterministic)
- Max tokens 256-2048 based on task complexity
- Parallel segment processing (max 2 concurrent)
- Input truncation at 10K chars per request

### TTS: Modular Pipeline (Sherpa-ONNX) - Updated 2026-02-06

**Architecture:**
- ✅ Modular design with pluggable TTS engines
- ✅ Easy model switching via configuration
- ✅ Support for multiple model types (VITS-Piper, Kokoro)

**Available Models:**

| Model | Speakers | Sample Rate | Location |
|-------|----------|-------------|----------|
| LibriTTS-904 | 904 voices | 22050 Hz | App assets |
| Kokoro v0.19 | 10 voices | 24000 Hz | External storage |

**LibriTTS (VITS-Piper) Capabilities:**
- ✅ Multi-speaker synthesis (904 voices)
- ✅ Speed control (0.5x - 2.0x)
- ✅ High-quality neural TTS
- ❌ No runtime pitch control (architecture limitation)
- ❌ No runtime energy control (post-process workaround)

**Kokoro Capabilities:**
- ✅ 10 high-quality voices (American & British)
- ✅ Speed control
- ✅ Higher sample rate (24000 Hz)
- ✅ Voice embeddings for consistent character voices

**Workarounds (VITS):**
1. **Pitch variation:** Use different speaker IDs (categorize by pitch level)
2. **Volume control:** Post-process audio samples (energy scaling)
3. **Emotion:** Combine speed adjustment + speaker selection
4. **Character voices:** Pre-select speakers by gender/age/accent traits

---

## 🚀 Implementation Phases

### PHASE 1: Critical Bug Fixes & Safety (4 hours) 🔴

**Priority:** MUST DO FIRST

#### AUG-001: Fix Service Connection Memory Leak (6 min)
```kotlin
// File: ReaderFragment.kt:996-1003
// BEFORE:
override fun onDestroy() {
    if (isServiceBound) {
        requireContext().unbindService(serviceConnection)  // ❌ Crashes if fragment detached
    }
}

// AFTER:
override fun onDestroy() {
    if (isServiceBound) {
        context?.unbindService(serviceConnection)  // ✅ Safe
        isServiceBound = false
    }
    super.onDestroy()
}
```

#### AUG-002: Fix PDF Import Silent Failure (30 min)
```kotlin
// File: ImportBookUseCase.kt:57-60

// 1. Create exception class
// File: app/src/main/java/com/dramebaz/app/domain/exceptions/ImportException.kt
class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

// 2. Update ImportBookUseCase
} catch (e: Exception) {
    AppLogger.e(tag, "PDF extraction failed", e)
    throw ImportException("Failed to extract text from PDF: ${e.message}", e)
    // ❌ Remove: listOf(title to " ")
}

// 3. Update LibraryFragment to catch and display error
try {
    importBookUseCase(uri)
} catch (e: ImportException) {
    MaterialAlertDialogBuilder(requireContext())
        .setTitle("Import Failed")
        .setMessage(e.message)
        .setPositiveButton("OK", null)
        .show()
}
```

#### AUG-003: Add LLM Timeout Protection (1.5 hours)
```kotlin
// File: QwenStub.kt (or use LlmService.kt facade)
// Note: LLM code has been refactored into models/, prompts/ subfolders with design patterns
// See docs/QWEN_MODEL_INTEGRATION.md for the new architecture

suspend fun analyzeChapter(chapterText: String): ChapterAnalysisResponse = withContext(Dispatchers.IO) {
    ensureInitialized()
    val result = try {
        withTimeout(60_000L) {  // ✅ 60 second timeout
            onnxModel?.analyzeChapter(chapterText)
        }
    } catch (e: TimeoutCancellationException) {
        AppLogger.w("QwenStub", "LLM analysis timed out after 60s, using fallback")
        null
    } catch (e: Exception) {
        AppLogger.e("QwenStub", "Error in model analysis, using fallback", e)
        null
    }
    result ?: stubAnalyzeChapter(chapterText)
}

// Apply to all LLM methods:
// - extendedAnalysisJson
// - extractCharactersAndTraitsInSegment
// - detectCharactersOnPage
// - inferTraitsForCharacter
// - generateStory
// - mergeCharacters
```

#### AUG-004: Fix PlaybackEngine Coroutine Scope (1.5 hours)
```kotlin
// File: PlaybackEngine.kt

// BEFORE:
class PlaybackEngine(
    private val context: Context,
    private val ttsEngine: SherpaTtsEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)  // ❌ Leaks
}

// AFTER:
class PlaybackEngine(
    private val context: Context,
    private val ttsEngine: SherpaTtsEngine,
    private val scope: CoroutineScope  // ✅ Accept from caller
) {
    // Remove internal scope
}

// File: ReaderFragment.kt
// Pass lifecycle-aware scope
playbackEngine = PlaybackEngine(
    context = requireContext(),
    ttsEngine = sherpaTtsEngine,
    scope = viewLifecycleOwner.lifecycleScope  // ✅ Auto-cancelled
)
```

#### AUG-005: Fix Dialog Speaker Detection (2 hours)
```kotlin
// File: QwenStub.kt:210-220

private fun stubAnalyzeChapter(chapterText: String): ChapterAnalysisResponse {
    // Extract character names
    val characterNames = extractCharacterNames(chapterText)

    // Extract dialogs with proximity-based speaker detection
    val dialogs = extractDialogsWithSpeakers(chapterText, characterNames)

    // ... rest of stub logic
}

private fun extractDialogsWithSpeakers(text: String, characters: List<String>): List<Dialog> {
    val dialogs = mutableListOf<Dialog>()
    val dialogPattern = """"([^"]+)"""".toRegex()

    dialogPattern.findAll(text).forEach { match ->
        val dialogText = match.groupValues[1]
        val dialogStart = match.range.first

        // Search backwards for speaker
        val contextBefore = text.substring(
            maxOf(0, dialogStart - 200),
            dialogStart
        )

        // Find nearest character name
        var speaker = characters.firstOrNull()  // Fallback
        var minDistance = Int.MAX_VALUE

        for (name in characters) {
            val lastIndex = contextBefore.lastIndexOf(name, ignoreCase = true)
            if (lastIndex >= 0) {
                val distance = contextBefore.length - lastIndex
                if (distance < minDistance) {
                    minDistance = distance
                    speaker = name
                }
            }
        }

        // Check for attribution patterns
        val attributionPatterns = listOf(
            """said\s+(\w+)""",
            """(\w+)\s+said""",
            """(\w+)\s*:""",
            """(\w+)\s+asked""",
            """(\w+)\s+replied"""
        )

        for (pattern in attributionPatterns) {
            val attrMatch = pattern.toRegex(RegexOption.IGNORE_CASE)
                .find(contextBefore)
            if (attrMatch != null) {
                val name = attrMatch.groupValues[1]
                if (characters.any { it.equals(name, ignoreCase = true) }) {
                    speaker = name
                    break
                }
            }
        }

        dialogs.add(Dialog(
            speaker = speaker ?: "Unknown",
            text = dialogText,
            emotion = "neutral",
            intensity = 0.5f
        ))
    }

    return dialogs
}
```

---

### PHASE 2: Character & Dialog Integration (12 hours) 🔴

See augTaskList.json tasks AUG-006 through AUG-011 for detailed implementation.

**Key Tasks:**
1. Trigger character extraction on first read
2. Navigate to characters after extraction
3. Implement automatic global character merging
4. Improve character trait inference
5. Track character key moments
6. Track character relationships

---

### PHASE 3: TTS Enhancement & Voice Control (10 hours) 🔴

**Critical: Work within VITS model limitations**

#### AUG-012: Energy as Volume Control (1.5 hours)
```kotlin
// File: SherpaTtsEngine.kt:143-175

val audio = ttsInstance.generate(text = text, sid = sid, speed = speed)

// ✅ Apply energy as post-processing volume
val energy = params.energy.coerceIn(0.5f, 1.5f)
val scaledSamples = audio.samples.map { sample ->
    (sample * energy).coerceIn(-1.0f, 1.0f)
}.toFloatArray()

// Save scaled audio
val outputFile = saveAudioToFile(scaledSamples, audio.sampleRate, text.hashCode().toString())
```

#### AUG-013: Prosody Hints (3 hours)
```kotlin
// File: ProsodyController.kt

fun forDialog(dialog: Dialog, voiceProfile: VoiceProfile?): TtsParams {
    val baseParams = VoiceProfileMapper.toTtsParams(voiceProfile)

    // Apply prosody hints
    val prosody = dialog.prosody
    val speed = when (prosody?.speed) {
        "fast" -> baseParams.speed * 1.2f
        "slow" -> baseParams.speed * 0.8f
        else -> baseParams.speed
    }

    // Pitch variation via speaker selection (VITS limitation workaround)
    val speakerId = when (prosody?.pitchVariation) {
        "high" -> selectHighPitchSpeaker(voiceProfile?.gender)
        "low" -> selectLowPitchSpeaker(voiceProfile?.gender)
        else -> baseParams.speakerId
    }

    // Stress pattern affects energy
    val energy = when (prosody?.stressPattern) {
        "emphasized" -> baseParams.energy * 1.2f
        "subdued" -> baseParams.energy * 0.8f
        else -> baseParams.energy
    }

    return baseParams.copy(
        speed = speed.coerceIn(0.5f, 2.0f),
        speakerId = speakerId,
        energy = energy.coerceIn(0.5f, 1.5f)
    )
}

private fun selectHighPitchSpeaker(gender: String?): Int {
    // Use speaker pitch categorization (AUG-016)
    return LibrittsSpeakerCatalog.getSpeakersByPitch(gender, PitchLevel.HIGH).firstOrNull() ?: 0
}
```

See augTaskList.json for AUG-014 through AUG-016.

---

## 📊 Progress Tracking

Use this checklist to track implementation:

```
PHASE 1: Critical Bug Fixes (4 hours)
[ ] AUG-001: Service connection leak
[ ] AUG-002: PDF import failure
[ ] AUG-003: LLM timeouts
[ ] AUG-004: Coroutine scope
[ ] AUG-005: Dialog speaker detection

PHASE 2: Character & Dialog (12 hours)
[ ] AUG-006: Trigger extraction on read
[ ] AUG-007: Navigate after extraction
[ ] AUG-008: Auto character merging
[ ] AUG-009: Improve trait inference
[ ] AUG-010: Key moments tracking
[ ] AUG-011: Relationships tracking

PHASE 3: TTS Enhancement (10 hours)
[ ] AUG-012: Energy as volume
[ ] AUG-013: Prosody hints
[ ] AUG-014: Speaker filtering
[ ] AUG-015: Voice preview
[ ] AUG-016: Speaker pitch categorization

... (continue for all phases)
```

---

## 🔗 Related Documents

- **augTaskList.json** - Complete task list with all 45 tasks
- **COMPREHENSIVE_ISSUES_SUMMARY.md** - All identified issues
- **TASKLIST_VERIFICATION.md** - Tasklist accuracy analysis
- **FEATURE_COMPLIANCE_ANALYSIS.md** - Feature requirements check

---

## 📝 Notes

1. **Model Limitations:** VITS TTS has NO pitch/energy control - use workarounds
2. **LLM Performance:** Keep context under 10K chars for mobile performance
3. **Testing:** Test each phase before moving to next
4. **Prioritization:** Complete PHASE 1 first (critical bugs)
