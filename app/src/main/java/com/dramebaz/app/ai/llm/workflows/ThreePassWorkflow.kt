package com.dramebaz.app.ai.llm.workflows

import android.content.Context
import com.dramebaz.app.ai.llm.GgufEngine
import com.dramebaz.app.ai.llm.StubFallbacks
import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.models.GgufEngineImpl
import com.dramebaz.app.ai.llm.prompts.ExtractionPrompts
import com.dramebaz.app.ai.tts.SpeakerMatcher
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson

/**
 * Three-Pass Character Analysis Workflow using GGUF model (via llama.cpp).
 *
 * This workflow uses the pure inference approach - all pass-specific logic
 * (prompts, parsing) is contained here, and the engine is used only for
 * raw text generation via generateResponse().
 *
 * Workflow:
 * - Pass 1: Extract character names from each page, track page appearances
 * - Pass 2: Extract dialogs and narrator text from each page
 * - Pass 3: Extract traits AND suggest voice profile using aggregated context
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
    override val workflowName: String = "GGUF 3-Pass Workflow"

    private var model: LlmModel? = null
    private var appContext: Context? = null
    private val gson = Gson()

    override suspend fun initialize(context: Context): Boolean {
        appContext = context.applicationContext
        model = GgufEngineImpl(context)
        val success = model?.loadModel() ?: false
        if (success) {
            AppLogger.i(TAG, "✅ $workflowName initialized: ${getExecutionProvider()}")
        } else {
            AppLogger.e(TAG, "❌ Failed to initialize $workflowName")
        }
        return success
    }

    override fun isReady(): Boolean = model?.isModelLoaded() == true

    override fun release() {
        model?.release()
        model = null
    }

    override fun getExecutionProvider(): String = model?.getExecutionProvider() ?: "unknown"

    override suspend fun analyzeChapter(
        bookId: Long,
        chapterText: String,
        chapterIndex: Int,
        totalChapters: Int,
        onProgress: ((String) -> Unit)?,
        onCharacterProcessed: ((String) -> Unit)?
    ): List<CharacterAnalysisResult> {
        val llm = model ?: return emptyList()

        val chapterPrefix = "Chapter ${chapterIndex + 1}/$totalChapters"
        val pages = segmentIntoPages(chapterText)

        // Accumulated data across all pages
        val characterPages = mutableMapOf<String, MutableList<String>>()
        val characterDialogs = mutableMapOf<String, MutableList<DialogEntry>>()
        val allCharacterNames = mutableSetOf<String>()

        // ============ Pass 1: Extract character names ============
        onProgress?.invoke("$chapterPrefix: Pass 1 - Extracting character names...")
        AppLogger.i(TAG, "🔄 Starting Pass 1: Extract character names (${pages.size} pages)")

        for ((pageIdx, pageText) in pages.withIndex()) {
            onProgress?.invoke("$chapterPrefix: Pass 1 - Page ${pageIdx + 1}/${pages.size}")

            val names = extractCharacterNames(llm, pageText)

            for (name in names) {
                val key = name.lowercase()
                allCharacterNames.add(name)
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

        // ============ Pass 2: Extract dialogs ============
        onProgress?.invoke("$chapterPrefix: Pass 2 - Extracting dialogs...")
        AppLogger.i(TAG, "🔄 Starting Pass 2: Extract dialogs")

        val characterNamesList = allCharacterNames.toList()
        for ((pageIdx, pageText) in pages.withIndex()) {
            onProgress?.invoke("$chapterPrefix: Pass 2 - Page ${pageIdx + 1}/${pages.size}")

            val dialogs = extractDialogs(llm, pageText, characterNamesList)

            for (dialog in dialogs) {
                val key = dialog.speaker.lowercase()
                if (!characterDialogs.containsKey(key)) {
                    characterDialogs[key] = mutableListOf()
                }
                characterDialogs[key]?.add(dialog)
            }
        }

        AppLogger.i(TAG, "   Pass 2 complete: dialogs extracted")

        // ============ Pass 3: Extract traits and voice profiles ============
        onProgress?.invoke("$chapterPrefix: Pass 3 - Analyzing traits...")
        AppLogger.i(TAG, "🔄 Starting Pass 3: Extract traits and voice profiles")

        val results = mutableListOf<CharacterAnalysisResult>()
        val characterList = allCharacterNames.toList()

        for (charName in characterList) {
            onProgress?.invoke("$chapterPrefix: Pass 3 - Processing $charName")

            val charContext = characterPages[charName.lowercase()]
                ?.joinToString("\n---\n")
                ?.take(MAX_CONTEXT_CHARS) ?: ""

            val (traits, voiceProfile) = extractTraitsAndVoiceProfile(llm, charName, charContext)
            val key = charName.lowercase()

            val finalTraits = if (traits.isEmpty()) {
                AppLogger.d(TAG, "   '$charName' no traits, using fallback")
                StubFallbacks.inferTraitsFromName(charName)
            } else {
                traits
            }

            val speakerId = (voiceProfile["speaker_id"] as? Number)?.toInt()
                ?: SpeakerMatcher.suggestSpeakerIdFromTraitList(finalTraits, null, charName) ?: 45

            val dialogs = characterDialogs[key] ?: emptyList()

            results.add(CharacterAnalysisResult(
                name = charName,
                traits = finalTraits,
                voiceProfile = voiceProfile.toMutableMap().apply { put("speaker_id", speakerId) },
                dialogs = dialogs,
                speakerId = speakerId
            ))

            onCharacterProcessed?.invoke(charName)
            AppLogger.d(TAG, "   ✅ Processed: $charName (${finalTraits.size} traits, ${dialogs.size} dialogs)")
        }

        onProgress?.invoke("$chapterPrefix: Analysis complete")
        AppLogger.i(TAG, "✅ Analysis complete: ${results.size} characters")

        return results
    }

    // ==================== Pass 1: Character Name Extraction ====================

    private suspend fun extractCharacterNames(llm: LlmModel, pageText: String): List<String> {
        val userPrompt = ExtractionPrompts.buildPass1ExtractNamesPrompt(pageText)
        val response = llm.generateResponse(
            systemPrompt = ExtractionPrompts.PASS1_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            maxTokens = 256,
            temperature = 0.1f
        )
        return parseNamesResponse(response)
    }

    private fun parseNamesResponse(response: String): List<String> {
        return try {
            val json = extractJsonFromResponse(response)
            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val raw = ((obj["characters"] as? List<*>) ?: (obj["names"] as? List<*>))
                ?.mapNotNull { it as? String }?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            raw.distinctBy { it.lowercase() }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Pass-1: Failed to parse names response", e)
            emptyList()
        }
    }

    // ==================== Pass 2: Dialog Extraction ====================

    private suspend fun extractDialogs(llm: LlmModel, pageText: String, characterNames: List<String>): List<DialogEntry> {
        val userPrompt = ExtractionPrompts.buildPass2ExtractDialogsPrompt(pageText, characterNames)
        val response = llm.generateResponse(
            systemPrompt = ExtractionPrompts.PASS2_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            maxTokens = 512,
            temperature = 0.15f
        )
        return parseDialogsResponse(response)
    }

    private fun parseDialogsResponse(response: String): List<DialogEntry> {
        return try {
            val json = extractJsonFromResponse(response)
            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val dialogsList = obj["dialogs"] as? List<*> ?: return emptyList()
            dialogsList.mapNotNull { item ->
                val dialogMap = item as? Map<*, *> ?: return@mapNotNull null
                val speaker = (dialogMap["speaker"] as? String)?.trim() ?: return@mapNotNull null
                val text = (dialogMap["text"] as? String)?.trim() ?: return@mapNotNull null
                if (text.isBlank()) return@mapNotNull null
                val emotion = (dialogMap["emotion"] as? String)?.trim()?.lowercase() ?: "neutral"
                val intensity = (dialogMap["intensity"] as? Number)?.toFloat() ?: 0.5f
                DialogEntry(speaker, text, emotion, intensity.coerceIn(0f, 1f))
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Pass-2: Failed to parse dialogs response", e)
            emptyList()
        }
    }

    // ==================== Pass 3: Traits and Voice Profile ====================

    private suspend fun extractTraitsAndVoiceProfile(
        llm: LlmModel,
        characterName: String,
        context: String
    ): Pair<List<String>, Map<String, Any>> {
        val userPrompt = ExtractionPrompts.buildPass3TraitsPrompt(characterName, context)
        val response = llm.generateResponse(
            systemPrompt = ExtractionPrompts.PASS3_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            maxTokens = 384,
            temperature = 0.1f
        )
        return parseTraitsAndVoiceProfileResponse(response, characterName)
    }

    private fun parseTraitsAndVoiceProfileResponse(
        response: String,
        characterName: String
    ): Pair<List<String>, Map<String, Any>> {
        return try {
            val json = extractJsonFromResponse(response)
            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *>
                ?: return Pair(emptyList(), emptyMap())

            val traits = (obj["traits"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() && it.length <= 50 }
                ?: emptyList()

            val voiceProfile = obj["voice_profile"] as? Map<*, *>
            val result = mutableMapOf<String, Any>()

            if (voiceProfile != null) {
                voiceProfile["gender"]?.let { result["gender"] = it }
                voiceProfile["age"]?.let { result["age"] = it }
                voiceProfile["tone"]?.let { result["tone"] = it }
                voiceProfile["accent"]?.let { result["accent"] = it }
                voiceProfile["speaker_id"]?.let { result["speaker_id"] = it }
                result["pitch"] = (voiceProfile["pitch"] as? Number)?.toDouble() ?: 1.0
                result["speed"] = (voiceProfile["speed"] as? Number)?.toDouble() ?: 1.0
                result["energy"] = (voiceProfile["energy"] as? Number)?.toDouble() ?: 1.0
            }

            AppLogger.d(TAG, "Pass-3: '$characterName' -> ${traits.size} traits")
            Pair(traits, result)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Pass-3: Failed to parse response for $characterName", e)
            Pair(emptyList(), emptyMap())
        }
    }

    // ==================== Utility Methods ====================

    private fun extractJsonFromResponse(response: String): String {
        var json = response.trim()
        if (json.startsWith("```json")) json = json.removePrefix("```json").trim()
        if (json.startsWith("```")) json = json.removePrefix("```").trim()
        if (json.endsWith("```")) json = json.removeSuffix("```").trim()
        val start = json.indexOf('{')
        val end = json.lastIndexOf('}')
        if (start >= 0 && end > start) json = json.substring(start, end + 1)
        return json
    }

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

            var breakPoint = PAGE_SIZE_CHARS
            val searchStart = (PAGE_SIZE_CHARS * 0.8).toInt()

            val paragraphBreak = remaining.lastIndexOf("\n\n", PAGE_SIZE_CHARS)
            if (paragraphBreak > searchStart) {
                breakPoint = paragraphBreak + 2
            } else {
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

