# Chapter Analysis Pipeline - Implementation Patterns & Examples

## 1. CREATING A NEW PROMPT DEFINITION

### Pattern: Implement PromptDefinition<I, O>

```kotlin
class MyExtractionPrompt : PromptDefinition<MyPromptInput, MyPromptOutput> {
    
    companion object {
        private const val TAG = "MyExtractionPrompt"
    }
    
    private val gson = Gson()
    
    override val promptId: String = "my_extraction_v1"
    override val displayName: String = "My Extraction"
    override val purpose: String = "Extract something from text"
    override val tokenBudget: TokenBudget = TokenBudget(
        promptTokens = 200,
        inputTokens = 2500,
        outputTokens = 512
    )
    override val temperature: Float = 0.15f
    
    override val systemPrompt: String = """You are an extraction engine. Extract data from text."""
    
    override fun buildUserPrompt(input: MyPromptInput): String {
        return """Extract data from this text:
        
TEXT:
${input.text}"""
    }
    
    override fun prepareInput(input: MyPromptInput): MyPromptInput {
        val maxChars = tokenBudget.maxInputChars
        val truncatedText = if (input.text.length > maxChars) {
            input.text.take(maxChars)
        } else {
            input.text
        }
        return input.copy(text = truncatedText)
    }
    
    override fun parseResponse(response: String): MyPromptOutput {
        return try {
            val json = gson.fromJson(response, MyPromptOutput::class.java)
            json
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to parse response", e)
            MyPromptOutput(emptyList())
        }
    }
}

// Input/Output classes
data class MyPromptInput(val text: String, val pageNumber: Int = 0)
data class MyPromptOutput(val items: List<String>)
```

---

## 2. CREATING A NEW EXTRACTION PASS

### Pattern: Extend BaseExtractionPass<I, O>

```kotlin
class MyExtractionPassV2(
    prompt: MyExtractionPrompt = MyExtractionPrompt()
) : BaseExtractionPass<MyPromptInput, MyPromptOutput>(prompt) {
    
    override fun getDefaultOutput(): MyPromptOutput {
        return MyPromptOutput(emptyList())
    }
}
```

### Pattern: Custom Pass (without PromptDefinition)

```kotlin
class MyCustomPass : AnalysisPass<MyInput, MyOutput> {
    
    companion object {
        private const val TAG = "MyCustomPass"
    }
    
    override val passId: String = "my_custom_pass"
    override val displayName: String = "My Custom Pass"
    
    override suspend fun execute(
        model: LlmModel,
        input: MyInput,
        config: PassConfig
    ): MyOutput {
        AppLogger.d(TAG, "Executing custom pass")
        
        val userPrompt = buildPrompt(input)
        val response = model.generateResponse(
            systemPrompt = "You are a helpful assistant.",
            userPrompt = userPrompt,
            maxTokens = config.maxTokens,
            temperature = config.temperature
        )
        
        return parseResponse(response)
    }
    
    private fun buildPrompt(input: MyInput): String {
        // Build prompt from input
        return "Process: ${input.data}"
    }
    
    private fun parseResponse(response: String): MyOutput {
        // Parse response
        return MyOutput(response)
    }
}
```

---

## 3. CREATING A NEW PIPELINE STEP

### Pattern: Implement PipelineStep<T : PipelineContext>

```kotlin
class MyAnalysisStep : PipelineStep<ChapterAnalysisContext> {
    
    override val name: String = "My Analysis"
    
    override suspend fun execute(
        model: LlmModel,
        context: ChapterAnalysisContext,
        config: PassConfig,
        onSegmentProgress: StepProgressCallback?
    ): ChapterAnalysisContext {
        val pass = MyExtractionPassV2()
        val totalPages = context.cleanedPages.size
        
        for ((pageIndex, pageText) in context.cleanedPages.withIndex()) {
            val input = MyPromptInput(pageText, pageIndex + 1)
            val output = pass.execute(model, input, config)
            
            // Accumulate results in context
            for (item in output.items) {
                // Process item and update context
                val key = item.lowercase()
                context.characters[key] = AccumulatedCharacterData(
                    name = item,
                    pagesAppearing = mutableSetOf(pageIndex + 1)
                )
            }
            
            // Report progress
            onSegmentProgress?.invoke(pageIndex + 1, totalPages)
        }
        
        return context
    }
}
```

---

## 4. CREATING A NEW ANALYSIS TASK

### Pattern: Implement AnalysisTask

```kotlin
class MyAnalysisTask(
    val bookId: Long,
    val chapterId: Long,
    val text: String,
    private val appContext: Context? = null
) : AnalysisTask {
    
    companion object {
        private const val TAG = "MyAnalysisTask"
        const val KEY_RESULT = "result"
    }
    
    override val taskId: String = "my_analysis_${bookId}_${chapterId}"
    override val displayName: String = "My Analysis"
    override val estimatedDurationSeconds: Int = 60
    
    override suspend fun execute(
        model: LlmModel,
        progressCallback: ((TaskProgress) -> Unit)?
    ): TaskResult {
        val startTime = System.currentTimeMillis()
        
        try {
            progressCallback?.invoke(TaskProgress(
                taskId = taskId,
                message = "Starting analysis...",
                percent = 0
            ))
            
            // Execute analysis
            val pass = MyExtractionPassV2()
            val input = MyPromptInput(text)
            val output = pass.execute(model, input, PassConfig())
            
            progressCallback?.invoke(TaskProgress(
                taskId = taskId,
                message = "Analysis complete",
                percent = 100
            ))
            
            return TaskResult(
                success = true,
                taskId = taskId,
                durationMs = System.currentTimeMillis() - startTime,
                resultData = mapOf(KEY_RESULT to output.items)
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Analysis failed", e)
            return TaskResult(
                success = false,
                taskId = taskId,
                durationMs = System.currentTimeMillis() - startTime,
                error = e.message ?: "Unknown error"
            )
        }
    }
}
```

---

## 5. EXECUTING A TASK

### Pattern: Use AnalysisExecutor

```kotlin
// In your workflow or service
val executor = AnalysisExecutor(context)

val task = MyAnalysisTask(
    bookId = 1L,
    chapterId = 1L,
    text = "Some text to analyze",
    appContext = context
)

val result = executor.execute(
    task = task,
    options = AnalysisExecutor.ExecutionOptions(
        forceBackground = true,  // Force background execution
        autoPersist = false      // Don't auto-persist
    ),
    onProgress = { progress ->
        Log.d("MyTask", "Progress: ${progress.percent}% - ${progress.message}")
    }
)

if (result.success) {
    Log.d("MyTask", "Task completed in ${result.durationMs}ms")
    Log.d("MyTask", "Result: ${result.resultData}")
} else {
    Log.e("MyTask", "Task failed: ${result.error}")
}
```

---

## 6. CHECKPOINT PATTERN

### Pattern: Save & Resume Analysis

```kotlin
// Checkpoint is automatically handled by ChapterAnalysisTask
// But here's how to manually manage checkpoints:

// Delete checkpoints for a book
ChapterAnalysisTask.deleteCheckpointsForBook(context, bookId)

// Checkpoints are stored in:
// {appContext.cacheDir}/chapter_analysis_checkpoints/{bookId}_{chapterId}.json

// Checkpoint structure:
data class AnalysisCheckpoint(
    val bookId: Long,
    val chapterId: Long,
    val timestamp: Long,
    val contentHash: Int,
    val lastCompletedStep: Int,  // -1=none, 0=CharExt, 1=DialogExt, 2=complete
    val characters: Map<String, SerializableCharacterData>,
    val totalDialogs: Int,
    val pagesProcessed: Int
)

// Resume logic:
// 1. Load checkpoint
// 2. Validate timestamp (< 24 hours)
// 3. Validate contentHash (matches current content)
// 4. Resume from lastCompletedStep + 1
// 5. Skip already completed steps
```

---

## 7. STEP COMPLETION CALLBACK PATTERN

### Pattern: Save Results After Each Step

```kotlin
val task = ChapterAnalysisTask(
    bookId = bookId,
    chapterId = chapterId,
    cleanedPages = cleanedPages,
    chapterTitle = chapter.title,
    appContext = context,
    onStepCompleted = { stepIndex, stepName, characters ->
        // Called after each step completes
        // stepIndex: 0=CharExt, 1=DialogExt, 2=VoiceProfile
        // stepName: "Character Extraction", "Dialog Extraction", etc.
        // characters: Map<String, AccumulatedCharacterData>
        
        Log.d("Analysis", "Step $stepIndex ($stepName) completed")
        Log.d("Analysis", "Characters: ${characters.size}")
        
        // Save to database immediately
        CoroutineScope(Dispatchers.IO).launch {
            for ((key, charData) in characters) {
                characterDao.insert(Character(
                    bookId = bookId,
                    name = charData.name,
                    traits = charData.traits.joinToString(","),
                    voiceProfileJson = gson.toJson(charData.voiceProfile),
                    speakerId = charData.speakerId,
                    dialogsJson = gson.toJson(charData.dialogs)
                ))
            }
        }
    }
)
```

---

## 8. TOKEN BUDGET MANAGEMENT PATTERN

### Pattern: Prepare Input for Token Budget

```kotlin
// In PromptDefinition.prepareInput():
override fun prepareInput(input: MyPromptInput): MyPromptInput {
    val maxChars = tokenBudget.maxInputChars  // e.g., 10000 chars
    
    val truncatedText = if (input.text.length > maxChars) {
        // Try to truncate at paragraph boundary
        TextCleaner.truncateAtParagraphBoundary(input.text, maxChars)
    } else {
        input.text
    }
    
    return input.copy(text = truncatedText)
}

// Or use TokenBudgetManager:
val preparedText = TokenBudgetManager.prepareInputText(
    text = input.text,
    budget = TokenBudgetManager.PASS1_CHARACTER_EXTRACTION
)
```

---

## 9. RESULT PERSISTENCE PATTERN

### Pattern: Persist Results to Database

```kotlin
// Results are automatically persisted if autoPersist=true
val result = executor.execute(
    task = task,
    options = AnalysisExecutor.ExecutionOptions(autoPersist = true),
    onProgress = { progress -> /* ... */ }
)

// Or manually persist:
val persister = CharacterResultPersister(characterDao, gson)
val persistedCount = persister.persist(result.resultData)
Log.d("Persist", "Persisted $persistedCount characters")

// Result data structure:
val resultData = mapOf(
    ChapterAnalysisTask.KEY_CHARACTERS to charactersJson,
    ChapterAnalysisTask.KEY_CHARACTER_COUNT to 5,
    ChapterAnalysisTask.KEY_DIALOG_COUNT to 42,
    ChapterAnalysisTask.KEY_BOOK_ID to 1L,
    ChapterAnalysisTask.KEY_CHAPTER_ID to 1L
)
```

---

## 10. ERROR HANDLING PATTERN

### Pattern: Retry Logic in BaseExtractionPass

```kotlin
// Automatic retry logic in BaseExtractionPass.execute():
// 1. Try to generate response
// 2. If token limit error:
//    - Reduce prompt by tokenReductionOnRetry tokens
//    - Retry (up to maxRetries times)
// 3. If all retries fail:
//    - Return getDefaultOutput()

// Custom retry logic:
override suspend fun execute(
    model: LlmModel,
    input: I,
    config: PassConfig
): O {
    var attempt = 0
    var lastError: Exception? = null
    
    while (attempt < config.maxRetries) {
        try {
            val response = model.generateResponse(
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                maxTokens = config.maxTokens,
                temperature = config.temperature
            )
            return parseResponse(response)
        } catch (e: Exception) {
            lastError = e
            attempt++
            if (attempt < config.maxRetries) {
                AppLogger.w(TAG, "Attempt $attempt failed, retrying...", e)
                delay(1000)  // Wait before retry
            }
        }
    }
    
    AppLogger.e(TAG, "All attempts failed", lastError)
    return getDefaultOutput()
}
```

---

## 11. PROGRESS REPORTING PATTERN

### Pattern: Report Progress During Execution

```kotlin
// In PipelineStep.execute():
val totalPages = context.cleanedPages.size

for ((pageIndex, pageText) in context.cleanedPages.withIndex()) {
    // Process page
    val output = pass.execute(model, input, config)
    
    // Report segment progress
    onSegmentProgress?.invoke(pageIndex + 1, totalPages)
}

// In AnalysisTask.execute():
progressCallback?.invoke(TaskProgress(
    taskId = taskId,
    message = "Processing page 5 of 10",
    percent = 50,
    currentStep = 1,
    totalSteps = 3,
    stepName = "Character Extraction"
))
```

---

## 12. CONTEXT ACCUMULATION PATTERN

### Pattern: Accumulate Results Across Steps

```kotlin
// Step 1: Accumulate character names
for (name in output.characterNames) {
    val key = name.lowercase()
    val existing = context.characters[key]
    if (existing != null) {
        existing.pagesAppearing.add(pageIndex + 1)
    } else {
        context.characters[key] = AccumulatedCharacterData(
            name = name,
            pagesAppearing = mutableSetOf(pageIndex + 1)
        )
    }
}

// Step 2: Accumulate dialogs
for (dialog in output.dialogs) {
    val key = dialog.speaker.lowercase()
    val charData = context.characters[key]
    if (charData != null) {
        charData.dialogs.add(DialogWithPage(
            pageNumber = pageIndex + 1,
            text = dialog.text,
            emotion = dialog.emotion,
            intensity = dialog.intensity
        ))
        context.totalDialogs++
    }
}

// Step 3: Accumulate voice profiles
for (profile in output.profiles) {
    val key = profile.characterName.lowercase()
    val charData = context.characters[key]
    if (charData != null) {
        charData.voiceProfile = profile
        charData.speakerId = matchSpeaker(profile)
    }
}
```


