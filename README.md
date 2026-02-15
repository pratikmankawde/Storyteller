# Dramebaz 📖🎧

**AI-Powered Audiobook Reader with On-Device LLM Analysis and Text-to-Speech**

Dramebaz is an Android audiobook application that uses on-device AI to analyze book content, extract characters and emotions, and read stories aloud with expressive text-to-speech. All AI processing happens locally on your device - no cloud required.

## ✨ Features

### 🤖 On-Device AI Analysis
- **LLM-Powered Chapter Analysis** - Uses LiteRT-LM or GGUF models for on-device inference
- **Character Extraction** - Automatically detects characters, their traits, and relationships
- **Dialog Detection** - Identifies spoken dialog and assigns speakers
- **Emotional Arc Detection** - Analyzes emotional journey across chapters
- **Three-Pass Analysis Pipeline** - Sequential page processing with context aggregation

### 🔊 Text-to-Speech Engine
- **Sherpa-ONNX TTS** - On-device VITS neural text-to-speech (LibriTTS model with 904 speakers)
- **Multi-Voice Support** - Per-character voice assignment with speaker ID mapping
- **Emotion Modifiers** - Speed/pitch/volume adjustments based on detected emotions

### 📚 Book Management
- **PDF Import** - Import books from device storage with automatic chapter detection
- **Demo Book Seeding** - Pre-loaded demo content from Downloads folder
- **Library Organization** - Favourites, Last Read, and Recently Added sections
- **Auto-Trigger Analysis** - Automatic LLM analysis on book import
- **Analysis Queue Manager** - Background processing with foreground service

### 🎨 UI/UX Features
- **Karaoke Text Highlighting** - Word-by-word sync with audio playback using SpannableString
- **Voice Waveform Visualizer** - Real-time audio amplitude visualization
- **Character Avatar View** - Active speaker display during playback
- **Shared Element Transitions** - MaterialContainerTransform for smooth navigation
- **Page Turn Transformer** - Book-fold rotation effects on swipe
- **Shimmer Loading** - Loading animations during LLM analysis

### 🎛️ Audio Pipeline
- **Producer-Consumer Architecture** - Kotlin Channels for streaming audio
- **PlaybackEngine** - MediaPlayer-based playback with progress tracking
- **AudioDirector** - Producer/Transformer/Consumer pipeline with AudioTrack
- **Audio Mixer** - Multi-channel mixing (narration, dialog, SFX, ambience)
- **Background Playback** - Foreground service with media notification controls
- **Auto-Transition** - Seamless page/chapter transitions with position tracking
- **Page-Aware Playback** - Audio state syncs with current reader position

### ⚙️ Settings & Customization
- **Reading Modes** - TEXT, AUDIO, MIXED mode toggle
- **Display Settings** - Theme presets (Light/Sepia/Dark/OLED), font size, line height
- **Feature Toggles** - Enable/disable Smart Casting, Generative Visuals, Deep Analysis, Emotion Modifiers, Karaoke Highlight
- **LLM Settings** - Model selection, backend preference (GPU/CPU)
- **Audio Settings** - Playback speed, voice selection
- **System Benchmark** - Database, tokenization, and memory diagnostics

### 📊 Insights & Analysis
- **Emotional Arc Visualization** - Line graph of emotional journey per chapter
- **Foreshadowing Detection** - LLM-based setup/payoff identification
- **Sentiment Distribution** - Positive/neutral/negative breakdown
- **Plot Outline Extraction** - Story structure timeline
- **Vocabulary Builder** - Word definitions with learned/unlearned tracking
- **Themes and Symbols** - Story theme analysis
- **Reading Statistics** - Chapter count, character count, dialog count

### 🎭 Chapter Management
- **Chapter Manager Dialog** - Reorder, merge, split, rename, delete chapters
- **Batch Chapter Re-Analysis** - Re-analyze selected chapters with LLM
- **Chapter Lookahead** - Pre-analyzes next chapter during reading

### 🌈 Dynamic Theming
- **Dynamic Theme Manager** - Apply themes to UI elements
- **Font Manager** - Custom font loading and application
- **Per-Book Theme Persistence** - Save and restore themes per book

### ✍️ Story Generation
- **Text-to-Story** - Generate stories from text prompts
- **Image-to-Story** - Generate stories from images (requires multimodal model)
- **Story Remix** - Remix existing stories with different styles/endings

### 👥 Character Features
- **Characters List** - View all extracted characters with dialog counts
- **Character Detail** - View traits, summary, relationships, key moments
- **Voice Selector Dialog** - Preview and assign voices to characters
- **Speaker Selection** - Per-character speaker ID assignment (0-903)

### 📖 Reader Features
- **Novel Page Format** - Paginated reading with ViewPager2
- **Time-Aware Recaps** - Contextual recaps based on reading session history
- **Bookmark Support** - Save and restore reading positions
- **Reading Session Tracking** - Track reading progress and time

## 🏗️ Architecture

### LLM Strategy Pattern
Unified interface for multiple LLM backends:
- `LlmModel` - Pure inference wrapper interface
- `LiteRtLmEngineImpl` - Google LiteRT-LM backend (.litertlm files)
- `GgufEngineImpl` - GGUF/llama.cpp backend (.gguf files)
- `LlmModelFactory` - Runtime model discovery and selection

### Key Components
- `LlmService` - Facade for LLM operations
- `SherpaTtsEngine` - Sherpa-ONNX TTS wrapper
- `PlaybackEngine` - Audio playback with segment management
- `AudioDirector` - Producer-Consumer audio pipeline
- `AnalysisQueueManager` - Background analysis orchestration

### Database Optimizations
- **Lightweight Projections** - Avoids loading large BLOB fields when not needed
- **ChapterSummary** - Minimal chapter info for navigation (no body/analysis)
- **ChapterWithAnalysis** - Analysis fields without body text for insights
- **One-at-a-time Loading** - Large books load chapters individually to avoid CursorWindow limits

## 📱 Requirements

- **Android**: API 30+ (Android 11)
- **Architecture**: arm64-v8a, x86_64
- **Storage**: ~2GB for models
- **RAM**: 4GB+ recommended (uses `largeHeap`)

## 🧪 Testing

The project includes instrumented tests:

```bash
# Run all tests
./gradlew connectedAndroidTest
```

**Test Coverage:**
- `AnalysisQueueManagerTest` - Analysis queue functionality
- `DemoBookSeedingTest` - Demo book management
- `QwenTextAnalysisTest` - LLM analysis tests
- `TtsGenerationTest` - TTS engine tests
- `AudioMixerTest` - Audio mixing tests
- `AudioPlaybackTest` - Playback functionality
- `CriticalUserFlowsTest` - UI flow tests

## 🔧 Build

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Install on device
adb install -r app/build/outputs/apk/release/app-release.apk
```

## 📁 Project Structure

```
app/src/main/java/com/dramebaz/app/
├── ai/                  # AI engines
│   ├── audio/           # SFX engine
│   ├── llm/             # LLM engines, prompts, pipelines
│   │   ├── models/      # LlmModel implementations
│   │   ├── pipeline/    # Analysis passes
│   │   └── prompts/     # Prompt templates
│   └── tts/             # Sherpa-ONNX TTS, speaker catalogs
├── audio/               # Audio generation and stitching
├── data/                # Data layer
│   ├── audio/           # Page audio storage
│   ├── db/              # Room database, DAOs, entities
│   ├── models/          # Data models
│   └── repositories/    # Book, Settings repositories
├── domain/              # Business logic
│   └── usecases/        # Import, Analysis, Recap use cases
├── pdf/                 # PDF extraction and chapter detection
├── playback/            # Audio playback
│   ├── engine/          # PlaybackEngine, AudioDirector, Karaoke
│   ├── mixer/           # AudioMixer
│   └── service/         # AudioPlaybackService
├── ui/                  # UI layer
│   ├── bookmarks/       # Bookmarks screen
│   ├── characters/      # Characters list and detail
│   ├── common/          # Shared UI components
│   ├── insights/        # Insights and analytics
│   ├── library/         # Library and book detail
│   ├── main/            # MainActivity
│   ├── player/          # PlayerBottomSheet
│   ├── reader/          # Reader fragment and components
│   ├── settings/        # Settings screens
│   ├── splash/          # Splash screen
│   ├── story/           # Story generation
│   └── theme/           # Dynamic theming
└── utils/               # Utilities and helpers
```

## 📄 License

[Add your license here]

---

*Built with ❤️ using Kotlin, LiteRT-LM, Sherpa-ONNX, and Material Design 3*

