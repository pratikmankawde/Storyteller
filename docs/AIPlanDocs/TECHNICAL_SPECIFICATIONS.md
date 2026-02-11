# Technical Specifications
**Detailed Implementation Specs for Complex Features**

## 0. Modular TTS Pipeline Architecture (Updated 2026-02-06)

### Overview

The TTS system uses a modular architecture that supports multiple TTS models and easy switching between them. The architecture separates model configuration, engine implementation, and model discovery.

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                    SherpaTtsEngine (Facade)                  │
│        Maintains backward compatibility with existing code   │
└─────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┴───────────────┐
              ▼                               ▼
┌─────────────────────────┐     ┌─────────────────────────┐
│    TtsModelRegistry     │     │    TtsEngineFactory     │
│  (Model Discovery)      │     │  (Engine Creation)      │
└─────────────────────────┘     └─────────────────────────┘
                                          │
              ┌───────────────────────────┴───────────────────────────┐
              ▼                                                       ▼
┌─────────────────────────────┐                 ┌─────────────────────────────┐
│    VitsPiperTtsEngine       │                 │      KokoroTtsEngine        │
│  (VITS-Piper models)        │                 │  (Kokoro models)            │
└─────────────────────────────┘                 └─────────────────────────────┘
              │                                                       │
              └───────────────────────┬───────────────────────────────┘
                                      ▼
                    ┌─────────────────────────────┐
                    │       BaseTtsEngine         │
                    │  (Caching, WAV, Scaling)    │
                    └─────────────────────────────┘
```

### Key Components

#### TtsEngine Interface (`TtsEngine.kt`)
```kotlin
interface TtsEngine {
    suspend fun init(): Boolean
    fun isInitialized(): Boolean
    fun release()
    fun getSampleRate(): Int
    fun getSpeakerCount(): Int
    fun getModelInfo(): TtsModelInfo
    suspend fun speak(text: String, voiceProfile: VoiceProfile?, speakerId: Int?, onComplete: (() -> Unit)?): Result<File?>
    fun getCacheStats(): Pair<Int, Long>
    fun clearCache()
}
```

#### TtsModelConfig (`TtsModelConfig.kt`)
Sealed class hierarchy for model configurations:
- `TtsModelConfig.VitsPiper` - For VITS-Piper models with espeak-ng
- `TtsModelConfig.Kokoro` - For Kokoro models with voice embeddings

#### BaseTtsEngine (`BaseTtsEngine.kt`)
Abstract base class with common functionality:
- Audio caching with MD5 key generation
- LRU cache eviction (100MB limit)
- WAV file generation from float samples
- Energy scaling post-processing
- Asset copying utilities

#### Model-Specific Engines
- `VitsPiperTtsEngine` - VITS-Piper implementation (904 LibriTTS speakers)
- `KokoroTtsEngine` - Kokoro implementation (10 voices with embeddings)

### Configuration File (`tts_model_config.json`)

Located at `app/src/main/assets/tts/tts_model_config.json`:

```json
{
  "models": [
    {
      "id": "libritts-904",
      "type": "vits_piper",
      "displayName": "LibriTTS (904 speakers)",
      "modelPath": "models/tts/sherpa/en_US-libritts-high.onnx",
      "tokensPath": "models/tts/sherpa/tokens.txt",
      "espeakDataPath": "models/tts/sherpa/espeak-ng-data",
      "isAsset": true,
      "sampleRate": 22050,
      "defaultSpeakerId": 0
    },
    {
      "id": "kokoro-en-v0.19",
      "type": "kokoro",
      "displayName": "Kokoro English v0.19",
      "modelPath": "D:\\Learning\\Ai\\Models\\TTS\\Sherpa\\kokoro-int8-en-v0_19\\kokoro-int8-en-v0_19\\model.int8.onnx",
      "tokensPath": "D:\\Learning\\Ai\\Models\\TTS\\Sherpa\\kokoro-int8-en-v0_19\\kokoro-int8-en-v0_19\\tokens.txt",
      "voicesPath": "D:\\Learning\\Ai\\Models\\TTS\\Sherpa\\kokoro-int8-en-v0_19\\kokoro-int8-en-v0_19\\voices.bin",
      "espeakDataPath": "D:\\Learning\\Ai\\Models\\TTS\\Sherpa\\kokoro-int8-en-v0_19\\kokoro-int8-en-v0_19\\espeak-ng-data",
      "isAsset": false,
      "sampleRate": 24000,
      "defaultVoiceId": 0,
      "defaultSpeed": 1.0
    }
  ],
  "selected": "kokoro-en-v0.19"
}
```

### Switching Models

**Programmatically:**
```kotlin
// Get the TTS engine facade
val ttsEngine = SherpaTtsEngine(context)

// Switch to a different model
ttsEngine.switchModel("kokoro-en-v0.19")  // or "libritts-904"

// Get current model info
val modelInfo = ttsEngine.getModelInfo()
println("Using: ${modelInfo?.displayName}")
```

**Via Configuration:**
Edit `tts_model_config.json` and change the `"selected"` field.

### Adding New TTS Models

1. **Create a new TtsModelConfig subclass** (if needed):
```kotlin
data class NewModel(
    override val id: String,
    override val displayName: String,
    // ... model-specific fields
) : TtsModelConfig()
```

2. **Create a new TtsEngine implementation**:
```kotlin
class NewModelTtsEngine(context: Context, config: TtsModelConfig.NewModel) : BaseTtsEngine(context) {
    override suspend fun init(): Boolean { /* ... */ }
    override fun speak(...): Result<File?> { /* ... */ }
    // ...
}
```

3. **Update TtsEngineFactory**:
```kotlin
fun create(context: Context, config: TtsModelConfig): TtsEngine? {
    return when (config) {
        is TtsModelConfig.VitsPiper -> VitsPiperTtsEngine(context, config)
        is TtsModelConfig.Kokoro -> KokoroTtsEngine(context, config)
        is TtsModelConfig.NewModel -> NewModelTtsEngine(context, config)
    }
}
```

4. **Add model to `tts_model_config.json`**

### Kokoro Voice Catalog

The Kokoro model includes 10 pre-trained voices with different characteristics:

| Voice ID | Name | Gender | Style |
|----------|------|--------|-------|
| 0 | af_bella | Female | American, warm |
| 1 | af_nicole | Female | American, professional |
| 2 | af_sarah | Female | American, friendly |
| 3 | af_sky | Female | American, youthful |
| 4 | am_adam | Male | American, confident |
| 5 | am_michael | Male | American, authoritative |
| 6 | bf_emma | Female | British, elegant |
| 7 | bf_isabella | Female | British, sophisticated |
| 8 | bm_george | Male | British, distinguished |
| 9 | bm_lewis | Male | British, warm |

---

## 1. VITS TTS Workarounds for Missing Features

### Problem: VITS Has No Runtime Pitch/Energy Control

**VITS Architecture Limitation:**
- VITS (Variational Inference with adversarial learning for end-to-end Text-to-Speech) generates audio directly from text
- The model architecture does NOT support runtime pitch or energy adjustment
- Only speed (length_scale) is controllable at inference time

**Research Findings:**
- Sherpa-ONNX VITS implementation: Only `speed` parameter available
- Piper TTS (based on VITS): Same limitation
- Other VITS implementations: Pitch/energy baked into model weights

### Solution 1: Energy Control via Post-Processing

```kotlin
/**
 * Apply energy (volume) scaling to generated audio samples.
 * Since VITS doesn't support runtime energy control, we post-process the audio.
 */
fun applyEnergyScaling(samples: FloatArray, energyScale: Float): FloatArray {
    require(energyScale in 0.5f..1.5f) { "Energy scale must be between 0.5 and 1.5" }

    return samples.map { sample ->
        // Scale sample by energy
        val scaled = sample * energyScale

        // Prevent clipping (samples must stay in [-1.0, 1.0])
        scaled.coerceIn(-1.0f, 1.0f)
    }.toFloatArray()
}

// Usage in SherpaTtsEngine.kt
val audio = ttsInstance.generate(text, sid, speed)
val energyScale = params.energy.coerceIn(0.5f, 1.5f)
val scaledSamples = applyEnergyScaling(audio.samples, energyScale)
```

**Performance Impact:** ~10-20ms for typical audio (< 5 seconds)

### Solution 2: Pitch Variation via Speaker Selection

**Strategy:** Pre-categorize 904 LibriTTS speakers by pitch level

```kotlin
enum class PitchLevel { HIGH, MEDIUM, LOW }

data class LibrittsSpeakerTraits(
    val id: Int,
    val gender: String,  // "M", "F"
    val age: String?,    // null for LibriTTS (not available)
    val accent: String,  // "American" (LibriTTS is US English)
    val pitchLevel: PitchLevel  // ✅ Categorized by gender pattern
)

object LibrittsSpeakerCatalog {
    // Automatic categorization based on gender and speaker ID pattern
    // 904 speakers (0-903) from LibriTTS-R dataset

    fun getSpeakersByPitch(gender: String?, pitchLevel: PitchLevel): List<Int> {
        return allSpeakers()
            .filter { speaker ->
                (gender == null || speaker.gender == gender) &&
                speaker.pitchLevel == pitchLevel
            }
            .map { it.speakerId }
    }
}

// Usage in ProsodyController
fun selectSpeakerForPitch(baseGender: String, pitchVariation: String?): Int {
    val pitchLevel = when (pitchVariation) {
        "high" -> PitchLevel.HIGH
        "low" -> PitchLevel.LOW
        else -> PitchLevel.MEDIUM
    }

    return LibrittsSpeakerCatalog.getSpeakersByPitch(baseGender, pitchLevel)
        .randomOrNull() ?: 0
}
```

### Solution 3: Emotion via Speed + Speaker Combination

```kotlin
fun emotionToTtsParams(emotion: String, intensity: Float, baseVoice: VoiceProfile): TtsParams {
    val speedMultiplier = when (emotion) {
        "excited", "angry" -> 1.1f + (intensity * 0.2f)  // Faster
        "sad", "tired" -> 0.9f - (intensity * 0.1f)      // Slower
        "fearful" -> 1.15f + (intensity * 0.15f)         // Much faster
        else -> 1.0f
    }

    val energyMultiplier = when (emotion) {
        "excited", "angry" -> 1.1f + (intensity * 0.3f)  // Louder
        "sad", "tired" -> 0.8f - (intensity * 0.2f)      // Quieter
        "fearful" -> 0.9f                                 // Slightly quieter
        else -> 1.0f
    }

    // Optionally select different speaker for extreme emotions
    val speakerId = if (intensity > 0.8f && emotion in listOf("angry", "excited")) {
        // Use higher-pitched speaker for intense emotions
        LibrittsSpeakerCatalog.getSpeakersByPitch(baseVoice.gender, PitchLevel.HIGH)
            .randomOrNull() ?: baseVoice.speakerId
    } else {
        baseVoice.speakerId
    }

    return TtsParams(
        speakerId = speakerId,
        speed = (baseVoice.speed * speedMultiplier).coerceIn(0.5f, 2.0f),
        energy = (baseVoice.energy * energyMultiplier).coerceIn(0.5f, 1.5f),
        pitch = baseVoice.pitch  // Not used, but kept for compatibility
    )
}
```

---

## 2. Text-Audio Sync with Actual Durations

### Problem: WPM Estimates Drift from Actual Audio

**Current Implementation:**
```kotlin
// TextAudioSync.kt - Uses estimates
val estimatedDurationMs = (wordCount / wpm) * 60 * 1000
```

**Issue:** TTS speed varies by:
- Speaker characteristics
- Text complexity (numbers, punctuation)
- Prosody adjustments
- Actual model inference time

### Solution: Use Actual Audio Duration

```kotlin
data class TextSegment(
    val text: String,
    val type: SegmentType,
    val audioStartMs: Long,
    val audioEndMs: Long,
    val audioFile: File? = null,  // ✅ NEW: Reference to generated audio
    val actualDurationMs: Long? = null  // ✅ NEW: Actual duration from audio file
)

class TextAudioSync {
    fun buildSegments(
        chapterText: String,
        dialogs: List<Dialog>? = null,
        narrationWPM: Int = 150,
        dialogWPM: Int = 180
    ): List<TextSegment> {
        // Initial build with estimates
        val segments = buildSegmentsWithEstimates(chapterText, dialogs, narrationWPM, dialogWPM)
        return segments
    }

    /**
     * Update segment timing with actual audio duration after TTS generation.
     * Call this after each segment's audio is generated.
     */
    fun updateSegmentWithActualDuration(
        segments: List<TextSegment>,
        segmentIndex: Int,
        audioFile: File
    ): List<TextSegment> {
        val actualDurationMs = getAudioDuration(audioFile)

        val updatedSegments = segments.toMutableList()
        val segment = segments[segmentIndex]

        // Update this segment
        updatedSegments[segmentIndex] = segment.copy(
            audioFile = audioFile,
            actualDurationMs = actualDurationMs,
            audioEndMs = segment.audioStartMs + actualDurationMs
        )

        // Shift all subsequent segments
        var cumulativeShift = actualDurationMs - (segment.audioEndMs - segment.audioStartMs)
        for (i in (segmentIndex + 1) until updatedSegments.size) {
            val seg = updatedSegments[i]
            updatedSegments[i] = seg.copy(
                audioStartMs = seg.audioStartMs + cumulativeShift,
                audioEndMs = seg.audioEndMs + cumulativeShift
            )
        }

        return updatedSegments
    }

    private fun getAudioDuration(audioFile: File): Long {
        // Read WAV header to get duration
        // Format: samples / sampleRate * 1000
        val mediaPlayer = MediaPlayer()
        return try {
            mediaPlayer.setDataSource(audioFile.absolutePath)
            mediaPlayer.prepare()
            mediaPlayer.duration.toLong()
        } finally {
            mediaPlayer.release()
        }
    }
}

// Usage in PlaybackEngine
fun generateAudioForSegment(segment: TextSegment, index: Int) {
    val audioFile = ttsEngine.synthesize(segment.text, voiceProfile)

    // Update timing with actual duration
    segments = textAudioSync.updateSegmentWithActualDuration(segments, index, audioFile)

    // Now highlighting will be accurate
}
```

---

## 3. Smart Speaker Filtering Algorithm

### Problem: 904 Speakers Overwhelming for Users

### Solution: Multi-Factor Scoring System

```kotlin
data class SpeakerMatch(
    val speaker: LibrittsSpeakerTraits,
    val score: Int,
    val matchReasons: List<String>
)

object SpeakerMatcher {
    fun getSimilarSpeakers(
        characterTraits: List<String>,
        personalitySummary: String,
        characterName: String,
        topN: Int = 20
    ): List<SpeakerMatch> {
        val allSpeakers = LibrittsSpeakerCatalog.allSpeakers()

        val scoredSpeakers = allSpeakers.map { speaker ->
            var score = 0
            val reasons = mutableListOf<String>()

            // 1. Gender match (+10 points)
            val gender = extractGender(characterTraits, characterName)
            if (gender != null && speaker.gender == gender) {
                score += 10
                reasons.add("Gender match")
            }

            // 2. Age match (+5 points)
            val age = extractAge(characterTraits, personalitySummary)
            if (age != null && speaker.age == age) {
                score += 5
                reasons.add("Age match")
            }

            // 3. Accent/region match (+3 points)
            val accent = extractAccent(characterTraits, personalitySummary)
            if (accent != null && speaker.accent.contains(accent, ignoreCase = true)) {
                score += 3
                reasons.add("Accent match")
            }

            // 4. Personality keywords (+2 points each)
            val personalityKeywords = listOf("gentle", "harsh", "warm", "cold", "energetic", "calm")
            for (keyword in personalityKeywords) {
                if (personalitySummary.contains(keyword, ignoreCase = true)) {
                    // Match keyword to speaker traits (would need speaker personality metadata)
                    score += 2
                    reasons.add("Personality: $keyword")
                }
            }

            // 5. Voice quality match (+2 points)
            val voiceQuality = extractVoiceQuality(characterTraits)
            if (voiceQuality != null) {
                // Would need speaker voice quality metadata
                score += 2
                reasons.add("Voice quality")
            }

            SpeakerMatch(speaker, score, reasons)
        }

        return scoredSpeakers
            .sortedByDescending { it.score }
            .take(topN)
    }

    private fun extractGender(traits: List<String>, name: String): String? {
        // Check traits
        if (traits.any { it.contains("male", ignoreCase = true) && !it.contains("female", ignoreCase = true) }) {
            return "male"
        }
        if (traits.any { it.contains("female", ignoreCase = true) }) {
            return "female"
        }

        // Check name (simple heuristic)
        val maleNames = setOf("john", "james", "robert", "michael", "william")
        val femaleNames = setOf("mary", "patricia", "jennifer", "linda", "elizabeth")

        val lowerName = name.lowercase()
        if (maleNames.any { lowerName.contains(it) }) return "male"
        if (femaleNames.any { lowerName.contains(it) }) return "female"

        return null
    }

    private fun extractAge(traits: List<String>, summary: String): String? {
        val combined = (traits + summary).joinToString(" ").lowercase()

        return when {
            combined.contains("young") || combined.contains("child") || combined.contains("teen") -> "young"
            combined.contains("old") || combined.contains("elderly") || combined.contains("aged") -> "old"
            else -> "middle"
        }
    }

    private fun extractAccent(traits: List<String>, summary: String): String? {
        val combined = (traits + summary).joinToString(" ").lowercase()

        return when {
            combined.contains("british") || combined.contains("english") -> "british"
            combined.contains("scottish") || combined.contains("scots") -> "scottish"
            combined.contains("irish") -> "irish"
            combined.contains("american") -> "american"
            else -> null
        }
    }

    private fun extractVoiceQuality(traits: List<String>): String? {
        val combined = traits.joinToString(" ").lowercase()

        return when {
            combined.contains("deep") || combined.contains("low") -> "deep"
            combined.contains("high") || combined.contains("shrill") -> "high"
            combined.contains("smooth") || combined.contains("silky") -> "smooth"
            combined.contains("rough") || combined.contains("raspy") -> "rough"
            else -> null
        }
    }
}
```

---

## 4. LLM Prompt Engineering for Better Extraction

### Character Key Moments Extraction

```kotlin
private fun buildCharacterKeyMomentsPrompt(chapterText: String, characterName: String): String {
    val text = chapterText.take(maxInputChars)
    val systemPrompt = """You are a literary analysis engine. Extract key moments for a specific character."""

    val userPrompt = """Analyze the chapter and extract 2-3 key moments for the character "$characterName".

For each moment, provide:
1. A brief description of what happened
2. Why it's significant for the character
3. The approximate location in the chapter (beginning/middle/end)

Return ONLY valid JSON in this format:
{
  "key_moments": [
    {
      "description": "Brief description of the moment",
      "significance": "Why this matters for the character",
      "location": "beginning|middle|end",
      "emotional_impact": "high|medium|low"
    }
  ]
}

<CHAPTER_TEXT>
$text
</CHAPTER_TEXT>

Focus on moments that reveal character development, important decisions, or significant interactions."""

    return "<|im_start|>system\n$systemPrompt<|im_end|>\n<|im_start|>user\n$userPrompt<|im_end|>\n<|im_start|>assistant\n"
}
```

### Character Relationships Extraction

```kotlin
private fun buildCharacterRelationshipsPrompt(chapterText: String): String {
    val text = chapterText.take(maxInputChars)
    val systemPrompt = """You are a relationship analysis engine. Extract character relationships from text."""

    val userPrompt = """Analyze the chapter and extract relationships between characters.

For each relationship, provide:
1. The two characters involved
2. The type of relationship
3. The nature/description of their relationship

Return ONLY valid JSON in this format:
{
  "relationships": [
    {
      "character1": "Character Name",
      "character2": "Other Character Name",
      "type": "family|friend|enemy|romantic|professional|other",
      "nature": "Brief description of their relationship",
      "strength": "strong|moderate|weak"
    }
  ]
}

<CHAPTER_TEXT>
$text
</CHAPTER_TEXT>

Only include relationships that are clearly evident in the text."""

    return "<|im_start|>system\n$systemPrompt<|im_end|>\n<|im_start|>user\n$userPrompt<|im_end|>\n<|im_start|>assistant\n"
}
```

---

## 5. Performance Optimization Strategies

### LLM Caching

```kotlin
object LlmResponseCache {
    private val cache = LruCache<String, String>(50)  // Cache last 50 responses

    fun getCached(prompt: String): String? {
        val key = prompt.hashCode().toString()
        return cache.get(key)
    }

    fun put(prompt: String, response: String) {
        val key = prompt.hashCode().toString()
        cache.put(key, response)
    }
}

// Usage in OnnxQwenModel
suspend fun analyzeChapter(chapterText: String): ChapterAnalysisResponse? {
    val prompt = buildAnalysisPrompt(chapterText)

    // Check cache first
    val cached = LlmResponseCache.getCached(prompt)
    if (cached != null) {
        AppLogger.d(tag, "Using cached LLM response")
        return parseAnalysisResponse(cached, chapterText)
    }

    // Generate new response
    val response = generateResponse(prompt, 2048, 0.15f)
    LlmResponseCache.put(prompt, response)

    return parseAnalysisResponse(response, chapterText)
}
```

### TTS Audio Caching

```kotlin
object TtsAudioCache {
    private val cacheDir = File(context.cacheDir, "tts_audio")
    private val maxCacheSizeBytes = 100 * 1024 * 1024  // 100 MB

    init {
        cacheDir.mkdirs()
    }

    fun getCacheKey(text: String, speakerId: Int, speed: Float, energy: Float): String {
        return "$text|$speakerId|$speed|$energy".hashCode().toString()
    }

    fun get(key: String): File? {
        val file = File(cacheDir, "$key.wav")
        return if (file.exists()) file else null
    }

    fun put(key: String, audioFile: File) {
        val cacheFile = File(cacheDir, "$key.wav")
        audioFile.copyTo(cacheFile, overwrite = true)

        // Evict old files if cache too large
        evictIfNeeded()
    }

    private fun evictIfNeeded() {
        val files = cacheDir.listFiles() ?: return
        val totalSize = files.sumOf { it.length() }

        if (totalSize > maxCacheSizeBytes) {
            // Delete oldest files
            files.sortedBy { it.lastModified() }
                .take((files.size * 0.3).toInt())  // Remove 30% oldest
                .forEach { it.delete() }
        }
    }
}
```

---

## 6. Model Compatibility Summary

| Feature            | Qwen3-1.7B Support | VITS-Piper Support  | Implementation                |
| ------------------ | ------------------ | ------------------- | ----------------------------- |
| JSON Extraction    | ✅ Excellent        | N/A                 | Direct                        |
| Character Analysis | ✅ Good             | N/A                 | Direct                        |
| Emotion Detection  | ✅ Good             | N/A                 | Direct                        |
| Multi-speaker TTS  | N/A                | ✅ Excellent (904)   | Direct                        |
| Speed Control      | N/A                | ✅ Native (0.5-2.0x) | Direct                        |
| Pitch Control      | N/A                | ❌ Not supported     | Workaround: Speaker selection |
| Energy Control     | N/A                | ❌ Not supported     | Workaround: Post-processing   |
| Emotion Presets    | N/A                | ❌ Not supported     | Workaround: Speed + Speaker   |
| Context Length     | ✅ 32K tokens       | N/A                 | Use 10K chars for performance |
| Batch Processing   | ✅ Supported        | ✅ Supported         | Parallel with limits          |

**Key Takeaway:** All features are implementable with documented workarounds for VITS limitations.
