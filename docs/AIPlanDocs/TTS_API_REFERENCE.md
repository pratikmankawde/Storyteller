# TTS API Reference & Code Examples

## SherpaTtsEngine API

### Core Methods

```kotlin
// Initialize TTS engine (called once at app startup)
fun init(): Boolean

// Check if initialized
fun isInitialized(): Boolean

// Main synthesis method
suspend fun speak(
    text: String,
    voiceProfile: VoiceProfile?,
    onComplete: (() -> Unit)? = null,
    speakerId: Int? = null  // 0-903 for LibriTTS
): Result<File?>

// Retry initialization after failure
suspend fun retryInit(): Boolean

// Release resources
fun release()

// Stop ongoing synthesis
fun stop()

// Cache management
fun getCacheStats(): Pair<Int, Long>  // (file count, total bytes)
fun clearCache()

// Cleanup on app exit
fun cleanup()
```

## Usage Examples

### Basic Synthesis
```kotlin
val app = context.applicationContext as DramebazApplication
val result = app.ttsEngine.speak(
    text = "Hello, world!",
    voiceProfile = null,
    speakerId = 0  // Default speaker
)

result.onSuccess { audioFile ->
    audioFile?.let { file ->
        // Play audio file
        mediaPlayer.setDataSource(file.absolutePath)
        mediaPlayer.prepare()
        mediaPlayer.start()
    }
}.onFailure { error ->
    Log.e("TTS", "Synthesis failed", error)
}
```

### With Voice Profile
```kotlin
val voiceProfile = VoiceProfile(
    pitch = 1.2f,
    speed = 0.9f,
    energy = 1.1f,
    emotionBias = mapOf("happy" to 0.7f, "neutral" to 0.3f)
)

val result = app.ttsEngine.speak(
    text = "I'm so happy!",
    voiceProfile = voiceProfile,
    speakerId = 42  // Female speaker
)
```

### With Completion Callback
```kotlin
val result = app.ttsEngine.speak(
    text = "This is a test",
    voiceProfile = null,
    onComplete = {
        Log.d("TTS", "Synthesis complete!")
        updateUI()
    },
    speakerId = null  // Use default
)
```

## Speaker Selection API

### LibrittsSpeakerCatalog
```kotlin
// Get speaker metadata
val traits = LibrittsSpeakerCatalog.getTraits(speakerId)
// Returns: SpeakerTraits(gender, age, accent, region, pitchLevel)

// Get all speakers
val allSpeakers = LibrittsSpeakerCatalog.allSpeakers()

// Filter by gender
val femaleSpeakers = LibrittsSpeakerCatalog.femaleSpeakerIds()
val maleSpeakers = LibrittsSpeakerCatalog.maleSpeakerIds()

// Filter by pitch level
val highPitchSpeakers = LibrittsSpeakerCatalog.speakerIdsByPitch(
    LibrittsSpeakerCatalog.PitchLevel.HIGH
)

// Get speakers by traits
val speakers = LibrittsSpeakerCatalog.getSpeakersByTraits(
    gender = "F",
    pitchLevel = PitchLevel.MEDIUM,
    accent = "American"
)

// Find speaker with different pitch
val highPitchVariant = LibrittsSpeakerCatalog.findSpeakerWithDifferentPitch(
    baseSpeakerId = 10,
    targetPitch = PitchLevel.HIGH
)
```

### SpeakerMatcher
```kotlin
// Suggest speaker ID based on character traits
val speakerId = SpeakerMatcher.suggestSpeakerId(
    traits = "female, young, cheerful",
    personalitySummary = "optimistic and energetic",
    name = "Alice"
)

// Get top N similar speakers for UI
val similarSpeakers = SpeakerMatcher.getSimilarSpeakers(
    traits = "male, old, wise",
    personalitySummary = "thoughtful and calm",
    name = "Gandalf",
    topN = 100
)
// Returns: List<ScoredSpeaker> with speaker and match score

// Get all speakers sorted by match
val sorted = SpeakerMatcher.speakersSortedByMatch(
    traits = "female, young",
    personalitySummary = null,
    name = "Emma"
)
```

### VoiceConsistencyChecker
```kotlin
val checker = VoiceConsistencyChecker(characterDao)

// Check all characters
val result = checker.checkAllCharacters()
// Returns: ConsistencyResult with valid/invalid characters

// Check single speaker ID
val isValid = checker.isValidSpeakerId(speakerId)

// Get invalid characters
val invalid = result.invalidCharacters
// Each has: character, reason (NOT_ASSIGNED, OUT_OF_RANGE, NOT_IN_CATALOG)
```

## Voice Profile API

### VoiceProfileMapper
```kotlin
// Convert VoiceProfile to TTS parameters
val params = VoiceProfileMapper.toTtsParams(voiceProfile)
// Returns: TtsParams(pitch, speed, energy, emotionPreset, speakerId)

// With emotion modifiers
val emotionalParams = VoiceProfileMapper.toTtsParamsWithEmotion(
    profile = voiceProfile,
    emotion = "happy"
)

// Apply emotion modifiers to existing params
val modified = params.withEmotionModifiers(emotion = "sad")
```

## Data Models

### VoiceProfile
```kotlin
data class VoiceProfile(
    val pitch: Float = 1.0f,        // 0.5-1.5
    val speed: Float = 1.0f,        // 0.5-1.5
    val energy: Float = 1.0f,       // 0.5-1.5
    val emotionBias: Map<String, Float> = emptyMap()
)
```

### SpeakerTraits
```kotlin
data class SpeakerTraits(
    val speakerId: Int,             // 0-903
    val gender: String,             // "M" or "F"
    val ageYears: Int?,
    val accent: String,             // "American"
    val region: String,
    val pitchLevel: PitchLevel      // HIGH, MEDIUM, LOW
)
```

### TtsParams
```kotlin
data class TtsParams(
    val pitch: Float,
    val speed: Float,
    val energy: Float,
    val emotionPreset: String,
    val speakerId: Int? = null
)
```

## Constants

```kotlin
// Speaker ID ranges
LibrittsSpeakerCatalog.MIN_SPEAKER_ID = 0
LibrittsSpeakerCatalog.MAX_SPEAKER_ID = 903
LibrittsSpeakerCatalog.SPEAKER_COUNT = 904

// Sample rate
SherpaTtsEngine.sampleRate = 22050

// Cache settings
SherpaTtsEngine.maxCacheSizeBytes = 100L * 1024 * 1024  // 100MB

// Parameter ranges
speed: 0.5f to 2.0f
energy: 0.5f to 1.5f
pitch: 0.5f to 1.5f
```

## Error Handling

```kotlin
// Check TTS status
if (!app.ttsEngine.isInitialized()) {
    app.ttsEngine.init()
}

// Handle synthesis failure
result.onFailure { error ->
    when (error) {
        is IllegalArgumentException -> {
            // Empty text
        }
        else -> {
            // Synthesis error
            Log.e("TTS", "Error: ${error.message}", error)
        }
    }
}

// Retry after failure
if (!app.ttsEngine.isInitialized()) {
    val success = app.ttsEngine.retryInit()
    if (!success) {
        // Fall back to system TTS or text-only mode
    }
}
```

