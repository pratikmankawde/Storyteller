package com.dramebaz.app.ai.llm.workflows

import android.content.Context
import com.dramebaz.app.ai.llm.Qwen3Model
import com.dramebaz.app.ai.llm.StubFallbacks
import com.dramebaz.app.ai.tts.SpeakerMatcher
import com.dramebaz.app.utils.AppLogger

/**
 * Three-Pass Character Analysis Workflow using Qwen3-1.7B model.
 * 
 * Workflow:
 * - Pass 1: Extract character names from each page, track page appearances
 * - Pass 2: Extract dialogs and narrator text from each page (2 characters at a time)
 * - Pass 3: Extract traits AND suggest voice profile using aggregated context (2 chars at a time)
 * 
 * Features:
 * - Uses llama.cpp JNI for Qwen3-1.7B-Q4_K_M.gguf model inference
 * - Sequential page processing
 * - Context aggregation for richer trait extraction
 * - Dialog extraction with speaker attribution and emotion detection
 * - SpeakerMatcher integration for VCTK speaker ID assignment (0-108)
 * 
 * Design Pattern: Strategy Pattern - implements AnalysisWorkflow interface.
 */
class ThreePassWorkflow : AnalysisWorkflow {
    companion object {
        private const val TAG = "ThreePassWorkflow"
        private const val MAX_CONTEXT_CHARS = 10_000
        private const val PAGE_SIZE_CHARS = 10_000
    }

    override val passCount: Int = 3
    override val workflowName: String = "Qwen 3-Pass Workflow"

    private var qwenModel: Qwen3Model? = null
    private var appContext: Context? = null

    override suspend fun initialize(context: Context): Boolean {
        appContext = context.applicationContext
        qwenModel = Qwen3Model(context)
        val success = qwenModel?.loadModel() ?: false
        if (success) {
            AppLogger.i(TAG, "✅ $workflowName initialized: ${getExecutionProvider()}")
        } else {
            AppLogger.e(TAG, "❌ Failed to initialize $workflowName")
        }
        return success
    }

    override fun isReady(): Boolean = qwenModel?.isModelLoaded() == true

    override fun release() {
        qwenModel?.release()
        qwenModel = null
    }

    override fun getExecutionProvider(): String = qwenModel?.getExecutionProvider() ?: "unknown"

    override suspend fun analyzeChapter(
        bookId: Long,
        chapterText: String,
        chapterIndex: Int,
        totalChapters: Int,
        onProgress: ((String) -> Unit)?,
        onCharacterProcessed: ((String) -> Unit)?
    ): List<CharacterAnalysisResult> {
        val model = qwenModel ?: return emptyList()
        
        val chapterPrefix = "Chapter ${chapterIndex + 1}/$totalChapters"
        val pages = segmentIntoPages(chapterText)
        
        // Accumulated data across all pages
        val characterPages = mutableMapOf<String, MutableList<String>>() // name -> list of page texts
        val characterDialogs = mutableMapOf<String, MutableList<DialogEntry>>()
        val allCharacterNames = mutableSetOf<String>()
        
        // ============ Pass 1: Extract character names from each page ============
        onProgress?.invoke("$chapterPrefix: Pass 1 - Extracting character names...")
        AppLogger.i(TAG, "🔄 Starting Pass 1: Extract character names (${pages.size} pages)")
        
        for ((pageIdx, pageText) in pages.withIndex()) {
            onProgress?.invoke("$chapterPrefix: Pass 1 - Page ${pageIdx + 1}/${pages.size}")
            
            val names = model.extractCharacterNames(pageText)
            
            for (name in names) {
                val key = name.lowercase()
                allCharacterNames.add(name)
                
                // Track which pages each character appears on
                if (!characterPages.containsKey(key)) {
                    characterPages[key] = mutableListOf()
                }
                characterPages[key]?.add(pageText)
            }
        }
        
        if (allCharacterNames.isEmpty()) {
            AppLogger.w(TAG, "⚠️ No characters found in Pass 1, using stub fallback")
            val stubNames = StubFallbacks.detectCharactersOnPage(chapterText)
            allCharacterNames.addAll(stubNames)
            for (name in stubNames) {
                characterPages[name.lowercase()] = mutableListOf(chapterText.take(MAX_CONTEXT_CHARS))
            }
        }
        
        AppLogger.i(TAG, "   Pass 1 complete: ${allCharacterNames.size} characters found")
        
        // ============ Pass 2: Extract dialogs from each page ============
        onProgress?.invoke("$chapterPrefix: Pass 2 - Extracting dialogs...")
        AppLogger.i(TAG, "🔄 Starting Pass 2: Extract dialogs")
        
        val characterNamesList = allCharacterNames.toList()
        for ((pageIdx, pageText) in pages.withIndex()) {
            onProgress?.invoke("$chapterPrefix: Pass 2 - Page ${pageIdx + 1}/${pages.size}")
            
            val dialogs = model.extractDialogsFromPage(pageText, characterNamesList)
            
            for (dialog in dialogs) {
                val key = dialog.speaker.lowercase()
                if (!characterDialogs.containsKey(key)) {
                    characterDialogs[key] = mutableListOf()
                }
                characterDialogs[key]?.add(
                    DialogEntry(dialog.speaker, dialog.text, dialog.emotion, dialog.intensity)
                )
            }
        }
        
        AppLogger.i(TAG, "   Pass 2 complete: dialogs extracted")
        
        // ============ Pass 3: Extract traits and voice profiles ============
        onProgress?.invoke("$chapterPrefix: Pass 3 - Analyzing traits...")
        AppLogger.i(TAG, "🔄 Starting Pass 3: Extract traits and voice profiles")
        
        val results = mutableListOf<CharacterAnalysisResult>()
        val characterList = allCharacterNames.toList()
        
        // Process 2 characters at a time for efficiency
        for (i in characterList.indices step 2) {
            val char1Name = characterList[i]
            val char2Name = characterList.getOrNull(i + 1)
            
            onProgress?.invoke("$chapterPrefix: Pass 3 - Processing ${char1Name}${if (char2Name != null) " & $char2Name" else ""}")
            
            // Aggregate context from all pages where each character appears
            val char1Context = characterPages[char1Name.lowercase()]
                ?.joinToString("\n---\n")
                ?.take(MAX_CONTEXT_CHARS) ?: ""
            val char2Context = char2Name?.let { 
                characterPages[it.lowercase()]
                    ?.joinToString("\n---\n")
                    ?.take(MAX_CONTEXT_CHARS)
            }
            
            val pass3Results = model.pass3ExtractTraitsAndVoiceProfile(
                char1Name, char1Context,
                char2Name, char2Context
            )
            
            for ((name, data) in pass3Results) {
                val (traits, voiceProfile) = data
                val key = name.lowercase()

                // Apply fallback if no traits extracted
                val finalTraits = if (traits.isEmpty()) {
                    AppLogger.d(TAG, "   '$name' no traits, using fallback")
                    StubFallbacks.inferTraitsFromName(name)
                } else {
                    traits
                }

                // Get speaker ID from voice profile or use SpeakerMatcher
                val speakerId = (voiceProfile["speaker_id"] as? Number)?.toInt()
                    ?: SpeakerMatcher.suggestSpeakerIdFromTraitList(finalTraits, null, name) ?: 45

                val dialogs = characterDialogs[key] ?: emptyList()

                results.add(CharacterAnalysisResult(
                    name = name,
                    traits = finalTraits,
                    voiceProfile = voiceProfile.toMutableMap().apply {
                        put("speaker_id", speakerId)
                    },
                    dialogs = dialogs,
                    speakerId = speakerId
                ))

                onCharacterProcessed?.invoke(name)
                AppLogger.d(TAG, "   ✅ Processed: $name (${finalTraits.size} traits, ${dialogs.size} dialogs)")
            }
        }

        // Handle characters that weren't processed in Pass 3 (fallback)
        for (name in allCharacterNames) {
            val key = name.lowercase()
            if (results.none { it.name.equals(name, ignoreCase = true) }) {
                val (traits, profile) = StubFallbacks.singleCharacterTraitsAndProfile(name)
                val dialogs = characterDialogs[key] ?: emptyList()
                results.add(CharacterAnalysisResult(
                    name = name,
                    traits = traits,
                    voiceProfile = profile,
                    dialogs = dialogs,
                    speakerId = (profile["speaker_id"] as? Int) ?: -1
                ))
            }
        }

        onProgress?.invoke("$chapterPrefix: Analysis complete")
        AppLogger.i(TAG, "✅ Analysis complete: ${results.size} characters")

        return results
    }

    /**
     * Segment chapter text into pages of approximately PAGE_SIZE_CHARS each.
     */
    private fun segmentIntoPages(text: String): List<String> {
        if (text.length <= PAGE_SIZE_CHARS) {
            return listOf(text)
        }

        val pages = mutableListOf<String>()
        var remaining = text

        while (remaining.isNotEmpty()) {
            if (remaining.length <= PAGE_SIZE_CHARS) {
                pages.add(remaining)
                break
            }

            // Find a good break point (paragraph or sentence boundary)
            var breakPoint = PAGE_SIZE_CHARS
            val searchStart = (PAGE_SIZE_CHARS * 0.8).toInt()

            // Try paragraph break first
            val paragraphBreak = remaining.lastIndexOf("\n\n", PAGE_SIZE_CHARS)
            if (paragraphBreak > searchStart) {
                breakPoint = paragraphBreak + 2
            } else {
                // Try sentence break
                val sentenceEnds = listOf(". ", "! ", "? ", ".\n", "!\n", "?\n")
                for (end in sentenceEnds) {
                    val idx = remaining.lastIndexOf(end, PAGE_SIZE_CHARS)
                    if (idx > searchStart) {
                        breakPoint = idx + end.length
                        break
                    }
                }
            }

            pages.add(remaining.substring(0, breakPoint))
            remaining = remaining.substring(breakPoint)
        }

        return pages
    }
}

