# Project Rules for AI Assistant

## Project Overview
- **App Name**: Storyteller (package: `com.dramebaz.app`)
- **Language**: Kotlin
- **Build System**: Gradle with Kotlin DSL
- **Min SDK**: Android
- **Architecture**: MVVM with Fragments, ViewModels, and Repository pattern

## Build Rules

### IMPORTANT: Check for Running Builds
Before starting any build command (`./gradlew`), ALWAYS check if a build is already in progress:
```powershell
Get-Process -Name "java" -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -like "*gradle*" }
```
Or use `list-processes` tool to check for existing terminal processes running gradle.

**Do NOT start a new build if one is already running.**

### Common Build Commands
```powershell
# Compile check
./gradlew :app:compileDebugKotlin --daemon

# Full debug build
./gradlew :app:assembleDebug --daemon

# Clean build
./gradlew clean :app:assembleDebug --daemon
```

### Build in release mode, unless asked to build in debug mode.
### Release builds should be highly optimized for speed.

## Code Style & Patterns

### Kotlin
- Use Kotlin idioms: scope functions (`let`, `apply`, `also`, `run`, `with`)
- Prefer `val` over `var`
- Use coroutines for async operations (`suspend fun`, `withContext`)
- Use `Flow` and `StateFlow` for reactive streams

### Logging
- Use `AppLogger` (not `android.util.Log` directly)
```kotlin
AppLogger.d(TAG, "Debug message")
AppLogger.i(TAG, "Info message")
AppLogger.e(TAG, "Error message", exception)
```

### LLM Models
- Factory pattern via `LlmModelFactory`
- Supported formats: `.gguf`, `.litertlm`, `.task` (MediaPipe)
- Model discovery paths: `/storage/emulated/0/Download`, `/Download/LLM`
- Singleton holder: `LlmModelHolder` manages shared model instance

### UI
- Fragments for screens, ViewModels for logic
- XML layouts in `res/layout/`
- Use ConstraintLayout for complex layouts
- Use `ViewBinding` for view references

## Project Structure
```
app/src/main/java/com/dramebaz/app/
├── ai/           # AI/ML components (LLM, TTS)
│   ├── llm/      # LLM inference engines
│   └── tts/      # Text-to-speech
├── audio/        # Audio processing
├── data/         # Data layer (DB, models, repositories)
├── domain/       # Business logic, use cases
├── pdf/          # PDF handling
├── playback/     # Audio playback engine
├── ui/           # UI components (fragments, viewmodels)
├── utils/        # Utilities (logging, caching, etc.)
└── DramebazApplication.kt
```

## Do
**Important** Always write modular, extensible and reusable code.
- Do not write big monolithic files. Break them down with separation of concerns and responsibilities.
- Use appropriate design patterns.

## Do NOT
- Create documentation files (*.md) unless explicitly requested
- Commit, push, or merge code without explicit permission
- Add dependencies without asking first
- Overwrite entire files - use str-replace-editor for edits
- Start builds without checking for running builds first

## Testing
- Verify changes compile: `./gradlew :app:compileReleaseKotlin --daemon`
- Run unit tests: `./gradlew :app:testReleaseUnitTest --daemon`

## Dependencies
- Use package manager commands (not manual edits to build.gradle.kts)
- For new dependencies, ask user for approval first

## Code Quality Standards

 ### Architecture & Design Patterns
 - Follow **Clean Architecture** principles with clear separation between domain/data/ui layers
 - Use **MVVM pattern** for UI components (ViewModel + StateFlow/LiveData)
 - Apply **Strategy Pattern** for swappable implementations (e.g., LlmModel interface with multiple implementations)
 - Use **Factory Pattern** for object creation when multiple implementations exist
 - Implement **Repository Pattern** for data access abstraction
 - Apply **Facade Pattern** for complex subsystem interfaces (e.g., LlmService)

 ### Modularity & Organization
 - Keep functions small and focused (single responsibility principle)
 - Extract reusable logic into dedicated utility classes or extension functions
 - Organize code in dedicated directories by feature/layer (e.g., `ai/llm/models/`, `ai/llm/prompts/`)
 - Avoid God classes - split large classes (>500 lines) into smaller, cohesive units
 - Use composition over inheritance where appropriate

 ### Kotlin Best Practices
 - Use **proper coroutine scopes** (`viewModelScope`, `lifecycleScope`) - never use `GlobalScope`
 - Handle **cancellation gracefully** with try-catch for `CancellationException`
 - Use `Flow` and `StateFlow` for reactive data streams
 - Mark async operations as `suspend` functions
 - Use `sealed classes` for state management and error handling
 - Leverage Kotlin's null safety features (avoid `!!` operator)
 - Use data classes for DTOs and value objects

 ### Performance & Threading
 - Keep UI responsive - **never block the main thread**
 - Use `Dispatchers.IO` for I/O operations (file, database, network)
 - Use `Dispatchers.Default` for CPU-intensive work (LLM inference, parsing)
 - Use `withContext` to switch dispatchers within suspend functions
 - Implement proper timeout handling for long-running operations

 ### Error Handling & Logging
 - Use custom exception hierarchy (extend `AppException` with specific error types)
 - Provide user-friendly error messages separate from technical logs
 - Log with appropriate levels using `AppLogger` (d/i/w/e)
 - Include context in error messages (operation name, input parameters)
 - Implement graceful degradation with fallbacks where appropriate

 ### Code Documentation
 - Add KDoc comments for public APIs, interfaces, and complex logic
 - Document **why** decisions were made, not just **what** the code does
 - Include usage examples in documentation for non-trivial APIs
 - Mark deprecated code with `@Deprecated` and migration path

 ### Testing & Quality
 - Write unit tests for business logic and data transformations
 - Ensure tests can run in parallel (no shared mutable state)
 - Build must complete with **zero errors and zero warnings**
 - Follow existing code style and naming conventions

 Apply these principles consistently across all code changes, refactoring, and new feature implementations.

## Testing

When modifying code that has existing tests, you must update the corresponding tests to reflect the changes:

 1. **API Signature Changes**: If you modify method signatures (parameters, return types, visibility), update all tests that call those
 methods
 2. **Feature Implementation Changes**: If you change how a feature works internally, update tests to verify the new behavior
 3. **New Features**: When adding new public methods or features, add corresponding test cases
 4. **Removed Features**: When removing methods or features, remove or update obsolete tests

 ## Scope

 This applies to:
 - Unit tests in `app/src/test/java/`
 - Instrumented tests in `app/src/androidTest/java/`
 - Integration tests for multi-component features

 ## When to Apply

 - After refactoring existing code with tests
 - After changing method signatures in tested classes
 - After modifying business logic in use cases, repositories, or services
 - After changing data models that affect serialization/deserialization

 ## Implementation

 1. Identify affected test files by searching for references to modified classes/methods
 2. Update test assertions to match new behavior
 3. Update mock setups if method signatures changed
 4. Add new test cases for new code paths
 5. Run tests to verify they pass: `./gradlew test` (unit) and `./gradlew connectedAndroidTest` (instrumented)
 6. Fix any failing tests before considering the task complete

 ## Examples

 - If you change `LlmService.analyzeChapter()` parameters, update `ChapterAnalysisTest.kt`
 - If you refactor `PlaybackEngine` to use new audio pipeline, update `PlaybackPipelineTest.kt`
 - If you add a new `GenerationParams` field, update `LlmModelTest.kt` to test it

 ## Exceptions

 - Documentation-only changes
 - Changes to private implementation details that don't affect public API
 - Temporary/experimental code marked with TODO
