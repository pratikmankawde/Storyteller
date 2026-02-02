# Codebase Issues Report

Generated: 2026-01-30

## 🔴 Critical Issues

### 1. Memory Leak in ReaderFragment - Handler Not Removed
**Location:** `app/src/main/java/com/dramebaz/app/util/MemoryMonitor.kt:17-28`
**Severity:** HIGH
**Issue:** The `Handler` with `Runnable` is started but may not be properly cleaned up if the fragment is destroyed while monitoring is active.
**Impact:** Memory leak, potential crashes
**Fix:** Ensure `stop()` is called in `onDestroyView()` (already done) but add null checks and ensure handler callbacks are removed.

### 2. Service Connection Leak Risk
**Location:** `app/src/main/java/com/dramebaz/app/ui/reader/ReaderFragment.kt:996-1003`
**Issue:** Service unbinding in `onDestroy()` uses `requireContext()` which can throw if fragment is detached.
**Impact:** Potential crash during fragment destruction
**Fix:** Use `context?.unbindService()` instead of `requireContext().unbindService()`

### 3. Coroutine Scope Not Cancelled in PlaybackEngine
**Location:** `app/src/main/java/com/dramebaz/app/playback/engine/PlaybackEngine.kt:24`
**Issue:** `CoroutineScope` with `SupervisorJob` is created but only cancelled in `cleanup()`. If `cleanup()` is not called, jobs may leak.
**Impact:** Memory leak, background tasks continue running
**Fix:** Ensure `cleanup()` is always called, or use a lifecycle-aware scope

### 4. MediaPlayer Not Released on Error Path
**Location:** `app/src/main/java/com/dramebaz/app/playback/engine/PlaybackEngine.kt:237-240`
**Issue:** In on-demand synthesis error path, the result is null but playback continues without proper cleanup.
**Impact:** Potential resource leak
**Fix:** Add proper error handling and cleanup

### 5. Race Condition in Pre-Synthesis
**Location:** `app/src/main/java/com/dramebaz/app/playback/engine/PlaybackEngine.kt:170-175`
**Issue:** `preSynthesisJob` is launched but there's no synchronization between pre-synthesis completion and playback start.
**Impact:** Playback may start before audio is ready, causing delays
**Fix:** Add proper synchronization or await pre-synthesis for first segment

## ⚠️ High Priority Issues

### 6. TODO Comment in Asset File
**Location:** `app/src/main/assets/models/tts/sherpa/espeak-ng-data/lang/roa/ht:4`
**Issue:** `// TODO somebody should take responsibility for this`
**Impact:** Unmaintained language file
**Fix:** Assign maintainer or remove if not needed

### 7. Fallback to Single Chapter on PDF Error
**Location:** `app/src/main/java/com/dramebaz/app/domain/usecases/ImportBookUseCase.kt:57-60`
**Issue:** When PDF extraction fails, falls back to single chapter with body " " (single space)
**Impact:** User imports PDF but gets empty content
**Fix:** Show error to user instead of silently failing with empty content

### 8. Context Null Check Missing
**Location:** `app/src/main/java/com/dramebaz/app/ui/reader/ReaderFragment.kt:270-273`
**Issue:** `context?.let { ctx ->` - if context is null, characters won't be saved
**Impact:** Silent failure to save character data
**Fix:** Log warning when context is null

### 9. Deprecated Class Still in Codebase
**Location:** `app/src/main/java/com/dramebaz/app/ai/tts/SherpaTtsStub.kt:1-39`
**Issue:** Entire class is deprecated but still present
**Impact:** Code bloat, confusion
**Fix:** Remove deprecated stub class if no longer needed

### 10. No Error Handling for Model Initialization Failure
**Location:** `app/src/main/java/com/dramebaz/app/ai/llm/QwenStub.kt:75-82`
**Issue:** When model initialization fails, `modelInitialized` is set to `true` anyway
**Impact:** App thinks model is ready but will use stub fallback silently
**Fix:** Add user notification when falling back to stub

## 🟡 Medium Priority Issues

### 11. Hardcoded Device ID in Script
**Location:** `scripts/capture_logs.bat:4`
**Issue:** `set DEVICE=04f8cf65` - hardcoded device serial
**Impact:** Script won't work on other devices
**Fix:** Make device ID a parameter or auto-detect

### 12. Missing AAR Fallback Documentation
**Location:** `app/libs/README.md:9`
**Issue:** Says "app will still build and fall back" but doesn't explain performance impact
**Impact:** Users may not realize they're using slower fallback
**Fix:** Add performance comparison and clear instructions

### 13. Incomplete CMake Git Tag
**Location:** `app/src/main/cpp/CMakeLists.txt:20`
**Issue:** `GIT_TAG b6916` - incomplete tag (should be full commit hash or version tag)
**Impact:** Build may fail or use wrong version
**Fix:** Use full commit hash or proper version tag

### 14. No Validation for Speaker ID Range
**Location:** `app/src/main/java/com/dramebaz/app/data/db/Character.kt:23`
**Issue:** `speakerId: Int?` - comment says 0-108 but no validation
**Impact:** Invalid speaker IDs could be stored
**Fix:** Add validation in DAO or use enum/sealed class

### 15. Potential Integer Overflow in Seek
**Location:** `app/src/main/java/com/dramebaz/app/playback/engine/PlaybackEngine.kt:340-345`
**Issue:** `position.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()` - long audio files may overflow
**Impact:** Seeking may fail for very long audio
**Fix:** Handle long durations properly or document limitation

## 🟢 Low Priority Issues

### 16. Inconsistent Logging
**Issue:** Mix of `AppLogger` and `android.util.Log` throughout codebase
**Locations:** Multiple files (QwenStub.kt, OnnxQwenModel.kt, etc.)
**Impact:** Inconsistent log filtering and formatting
**Fix:** Standardize on `AppLogger` everywhere

### 17. Magic Numbers
**Location:** `app/src/main/java/com/dramebaz/app/util/MemoryMonitor.kt:18`
**Issue:** `private val updateInterval = 2000L // 2 seconds` - hardcoded
**Impact:** Not configurable
**Fix:** Make configurable or use constant

### 18. Empty Catch Blocks
**Location:** Various (e.g. `OnnxQwenModel.kt`, `QwenStub.kt`)
**Issue:** Some `catch` blocks swallow exceptions without logging
**Impact:** Silent failure, hard to debug
**Fix:** Add logging in catch blocks

### 19. Duplicate Code in Service Start
**Location:** `app/src/main/java/com/dramebaz/app/ui/reader/ReaderFragment.kt:905-909, 945-949`
**Issue:** Same service start code repeated multiple times
**Impact:** Code duplication
**Fix:** Extract to helper function

### 20. No Timeout for LLM Calls
**Issue:** LLM analysis calls have no timeout
**Locations:** QwenStub.analyzeChapter, extractCharactersAndTraitsInSegment, etc.
**Impact:** App may hang indefinitely on slow/stuck LLM
**Fix:** Add timeout with `withTimeout()` coroutine

## 📊 Summary

- **Critical Issues:** 5
- **High Priority:** 5  
- **Medium Priority:** 5
- **Low Priority:** 5
- **Total Issues:** 20

## 🎯 Recommended Action Plan

1. **Immediate:** Fix critical memory leaks (#1, #2, #3)
2. **Short-term:** Handle PDF import errors properly (#7), fix service unbinding (#2)
3. **Medium-term:** Clean up deprecated code (#9), standardize logging (#16)
4. **Long-term:** Add LLM timeouts (#20), improve error handling throughout

## 🔍 Additional Observations

### Architecture Strengths
- ✅ Good separation of concerns (MVVM pattern)
- ✅ Proper use of Room database with DAOs
- ✅ Coroutines used correctly in most places
- ✅ Comprehensive logging for performance monitoring
- ✅ Parallel processing for LLM and TTS operations

### Architecture Concerns
- ⚠️ Fragment is doing too much (1006 lines) - consider splitting
- ⚠️ PlaybackEngine uses its own CoroutineScope instead of lifecycle-aware scope
- ⚠️ No repository pattern for character/bookmark data (only for books)
- ⚠️ Direct database access from fragments in some places
- ⚠️ Service lifecycle not fully integrated with fragment lifecycle

### Testing Gaps
- ❌ No unit tests for critical business logic (only instrumented tests)
- ❌ No tests for error handling paths
- ❌ No tests for memory leak scenarios
- ❌ No tests for service lifecycle

### Performance Concerns
- 🐌 Large chapter text loaded entirely into memory
- 🐌 No pagination for very long books
- 🐌 Pre-generation of audio could consume significant storage
- 🐌 No cleanup of old cached audio files

### Security/Privacy
- ✅ All processing is offline (good for privacy)
- ⚠️ No encryption for stored book content
- ⚠️ File paths stored as absolute paths (may break on app updates)

## 🛠️ Detailed Fix Examples

### Fix #2: Service Connection Leak
```kotlin
// Current (line 996-1003)
override fun onDestroy() {
    super.onDestroy()
    if (isServiceBound) {
        try {
            requireContext().unbindService(serviceConnection)  // ❌ Can crash
            isServiceBound = false
        } catch (e: Exception) {
            AppLogger.w(tag, "Error unbinding service", e)
        }
    }
}

// Fixed
override fun onDestroy() {
    super.onDestroy()
    if (isServiceBound) {
        try {
            context?.unbindService(serviceConnection)  // ✅ Safe
            isServiceBound = false
        } catch (e: Exception) {
            AppLogger.w(tag, "Error unbinding service", e)
        }
    }
}
```

### Fix #3: PlaybackEngine Scope
```kotlin
// Current (line 24)
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

// Better approach: Accept scope from caller
class PlaybackEngine(
    private val context: Context,
    private val ttsEngine: SherpaTtsEngine,
    private val scope: CoroutineScope  // ✅ Lifecycle-aware scope from fragment
) {
    // Remove internal scope creation
}
```

### Fix #7: PDF Import Error Handling
```kotlin
// Current (line 57-60)
} catch (e: Exception) {
    AppLogger.e(tag, "PDF extraction/detection failed; falling back to single chapter", e)
    listOf(title to " ")  // ❌ Silent failure with empty content
}

// Fixed
} catch (e: Exception) {
    AppLogger.e(tag, "PDF extraction/detection failed", e)
    throw ImportException("Failed to extract text from PDF: ${e.message}", e)  // ✅ Propagate error
}
```

### Fix #20: LLM Timeout
```kotlin
// Add to QwenStub
suspend fun analyzeChapter(chapterText: String): ChapterAnalysisResponse = withContext(Dispatchers.IO) {
    val result = try {
        withTimeout(60_000L) {  // ✅ 60 second timeout
            onnxModel?.analyzeChapter(chapterText) ?: qwenModel?.analyzeChapter(chapterText)
        }
    } catch (e: TimeoutCancellationException) {
        AppLogger.w("QwenStub", "LLM analysis timed out, using fallback")
        null
    } catch (e: Exception) {
        android.util.Log.e("QwenStub", "Error in model analysis, using fallback", e)
        null
    }
    result ?: stubAnalyzeChapter(chapterText)
}
```

