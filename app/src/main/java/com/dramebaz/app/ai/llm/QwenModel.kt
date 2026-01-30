package com.dramebaz.app.ai.llm

import android.content.Context
import android.os.Build
import android.os.Environment
import com.dramebaz.app.data.models.ChapterSummary
import com.dramebaz.app.utils.AppLogger
import com.dramebaz.app.data.models.Dialog
import com.dramebaz.app.data.models.EmotionalSegment
import com.dramebaz.app.data.models.SoundCueModel
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Real Qwen model implementation using GGUF file.
 * Loads qwen2.5-3b-instruct-q4_k_m.gguf from device Downloads folder.
 */
class QwenModel(private val context: Context) {
    private val gson = Gson()
    private val modelFileName = "qwen2.5-3b-instruct-q4_k_m.gguf"
    
    // Try multiple possible paths for the model file
    private fun getPossibleModelPaths(): List<String> {
        val paths = mutableListOf<String>()
        
        // Standard paths
        paths.add("/sdcard/Download/$modelFileName")
        paths.add("/storage/emulated/0/Download/$modelFileName")
        paths.add("/storage/self/primary/Download/$modelFileName")
        
        // Try using Environment API
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadsDir != null && downloadsDir.exists()) {
                val envPath = File(downloadsDir, modelFileName).absolutePath
                paths.add(envPath)
                android.util.Log.d("QwenModel", "Added Environment path: $envPath")
            }
        } catch (e: Exception) {
            android.util.Log.w("QwenModel", "Could not get Downloads directory from Environment", e)
        }
        
        // Try context.getExternalFilesDir
        try {
            val externalFilesDir = context.getExternalFilesDir(null)
            if (externalFilesDir != null) {
                val contextPath = File(externalFilesDir, modelFileName).absolutePath
                paths.add(contextPath)
                android.util.Log.d("QwenModel", "Added context external files path: $contextPath")
            }
        } catch (e: Exception) {
            android.util.Log.w("QwenModel", "Could not get external files directory", e)
        }
        
        return paths.distinct()
    }
    
    private var modelPath: String? = null
    private var modelLoaded = false
    private var llamaContext: Long = 0 // Native pointer to llama context
    
    init {
        // Try to load llama.cpp native library
        try {
            System.loadLibrary("llama_jni")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.w("QwenModel", "llama.cpp native library not available")
        }
    }
    
    /**
     * GPU layers to offload based on device: -1 = all layers on GPU (when supported), 0 = CPU only.
     * Uses API level and heap size to avoid OOM on low-end devices.
     */
    private fun getGpuLayersForDevice(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return 0 // API 23 and below: CPU only
        val maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        if (maxMemoryMb < 256) return 0 // Very low heap: stay on CPU
        // API 24+ with sufficient heap: offload all layers to GPU when backend supports it
        return -1
    }

    /**
     * Load the GGUF model file. Call this before using analyzeChapter.
     */
    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        try {
            val possibleModelPaths = getPossibleModelPaths()
            android.util.Log.i("QwenModel", "Searching for model file: $modelFileName")
            android.util.Log.d("QwenModel", "Checking ${possibleModelPaths.size} possible paths")
            
            // Find the model file in one of the possible locations
            var foundPath: String? = null
            var foundFile: File? = null
            
            for (path in possibleModelPaths) {
                try {
                    val file = File(path)
                    android.util.Log.d("QwenModel", "Checking path: $path (exists=${file.exists()}, readable=${file.canRead()}, size=${if (file.exists()) file.length() else 0})")
                    
                    if (file.exists() && file.canRead() && file.length() > 0) {
                        foundPath = path
                        foundFile = file
                        android.util.Log.i("QwenModel", "Found model at: $path (size: ${file.length()} bytes)")
                        break
                    }
                } catch (e: Exception) {
                    android.util.Log.w("QwenModel", "Error checking path $path", e)
                }
            }
            
            if (foundPath == null || foundFile == null) {
                android.util.Log.e("QwenModel", "Model file '$modelFileName' not found in any of these locations:")
                possibleModelPaths.forEach { path ->
                    val file = File(path)
                    android.util.Log.e("QwenModel", "  - $path (exists=${file.exists()}, readable=${file.canRead()})")
                }
                
                // Try to list Downloads directory contents for debugging
                try {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (downloadsDir != null && downloadsDir.exists() && downloadsDir.canRead()) {
                        android.util.Log.d("QwenModel", "Downloads directory contents:")
                        downloadsDir.listFiles()?.take(10)?.forEach { file ->
                            android.util.Log.d("QwenModel", "  - ${file.name} (${file.length()} bytes)")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("QwenModel", "Could not list Downloads directory", e)
                }
                
                return@withContext false
            }
            
            modelPath = foundPath
            
            // Initialize llama.cpp model (GPU layers from device config)
            val nGpuLayers = getGpuLayersForDevice()
            val loadStartMs = System.currentTimeMillis()
            android.util.Log.i("QwenModel", "Initializing llama.cpp model from: $foundPath (n_gpu_layers=$nGpuLayers)")
            modelLoaded = try {
                llamaContext = llamaInitFromFile(foundPath, nGpuLayers)
                if (llamaContext != 0L) {
                    AppLogger.logPerformance("QwenModel", "Load model (llamaInitFromFile)", System.currentTimeMillis() - loadStartMs)
                    android.util.Log.i("QwenModel", "Model loaded successfully from: $foundPath")
                } else {
                    android.util.Log.e("QwenModel", "Failed to initialize model (returned null/zero context)")
                    android.util.Log.e("QwenModel", "Model file exists: ${foundFile.exists()}, readable: ${foundFile.canRead()}, size: ${foundFile.length()}")
                }
                llamaContext != 0L
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("QwenModel", "llama.cpp native library not available", e)
                android.util.Log.e("QwenModel", "Make sure llama_jni library is properly built and linked")
                false
            } catch (e: Exception) {
                android.util.Log.e("QwenModel", "Error initializing model from $foundPath", e)
                android.util.Log.e("QwenModel", "Exception type: ${e.javaClass.simpleName}, message: ${e.message}")
                e.printStackTrace()
                false
            }
            
            modelLoaded
        } catch (e: Exception) {
            android.util.Log.e("QwenModel", "Error loading model", e)
            e.printStackTrace()
            false
        }
    }
    
    /**
     * Check if model is loaded.
     */
    fun isModelLoaded(): Boolean = modelLoaded
    
    /**
     * Analyze a chapter and return structured response.
     */
    suspend fun analyzeChapter(chapterText: String): ChapterAnalysisResponse? = withContext(Dispatchers.IO) {
        if (!modelLoaded) {
            android.util.Log.w("QwenModel", "Model not loaded, cannot analyze chapter")
            return@withContext null
        }
        
        try {
            val t0 = System.currentTimeMillis()
            val prompt = buildAnalysisPrompt(chapterText)
            val response = generateResponse(prompt, 2048, 0.3f)
            val result = parseAnalysisResponse(response, chapterText)
            AppLogger.logPerformance("QwenModel", "analyzeChapter (full)", System.currentTimeMillis() - t0)
            result
        } catch (e: Exception) {
            android.util.Log.e("QwenModel", "Error analyzing chapter", e)
            null
        }
    }
    
    /**
     * Extended analysis - themes, symbols, foreshadowing, vocabulary.
     */
    suspend fun extendedAnalysisJson(chapterText: String): String? = withContext(Dispatchers.IO) {
        if (!modelLoaded) {
            return@withContext null
        }
        
        try {
            val t0 = System.currentTimeMillis()
            val prompt = buildExtendedAnalysisPrompt(chapterText)
            val response = generateResponse(prompt, 1024, 0.3f)
            val result = parseExtendedAnalysisResponse(response, chapterText)
            AppLogger.logPerformance("QwenModel", "extendedAnalysisJson", System.currentTimeMillis() - t0)
            result
        } catch (e: Exception) {
            android.util.Log.e("QwenModel", "Error in extended analysis", e)
            null
        }
    }

    /**
     * Extract all characters and their traits from one segment in a single LLM call.
     * - skipNamesWithTraits: do not list these characters (already extracted with traits).
     * - namesNeedingTraits: we have these names but no traits yet; try to infer traits from this segment.
     * Returns list of (name, traits) for characters found in this segment (excluding skipped).
     */
    suspend fun extractCharactersAndTraitsInSegment(
        segmentText: String,
        skipNamesWithTraits: Collection<String>,
        namesNeedingTraits: Collection<String>
    ): List<Pair<String, List<String>>> = withContext(Dispatchers.IO) {
        if (!modelLoaded) return@withContext emptyList()
        try {
            val t0 = System.currentTimeMillis()
            val prompt = buildExtractCharactersAndTraitsPrompt(segmentText, skipNamesWithTraits, namesNeedingTraits)
            val response = generateResponse(prompt, 512, 0.2f)
            val result = parseCharactersAndTraitsFromResponse(response)
            AppLogger.logPerformance("QwenModel", "extractCharactersAndTraitsInSegment (segLen=${segmentText.length}, skip=${skipNamesWithTraits.size}, needTraits=${namesNeedingTraits.size})", System.currentTimeMillis() - t0)
            result
        } catch (e: Exception) {
            android.util.Log.e("QwenModel", "Error extracting characters and traits in segment", e)
            emptyList()
        }
    }

    /**
     * Detect character names on a single page of text (legacy; prefer extractCharactersAndTraitsInSegment).
     * Returns list of character names mentioned on this page. Output: JSON {"names": ["Alice", "Bob"]}.
     */
    suspend fun detectCharactersOnPage(pageText: String): List<String> = withContext(Dispatchers.IO) {
        if (!modelLoaded) return@withContext emptyList()
        try {
            val t0 = System.currentTimeMillis()
            val prompt = buildDetectCharactersOnPagePrompt(pageText)
            val response = generateResponse(prompt, 256, 0.2f)
            val names = parseCharacterNamesFromResponse(response)
            AppLogger.logPerformance("QwenModel", "detectCharactersOnPage (pageLen=${pageText.length})", System.currentTimeMillis() - t0)
            names
        } catch (e: Exception) {
            android.util.Log.e("QwenModel", "Error detecting characters on page", e)
            emptyList()
        }
    }

    /**
     * Infer personality traits for a character from an excerpt (legacy; prefer extractCharactersAndTraitsInSegment).
     * Returns list of trait strings. Output: JSON {"traits": ["brave", "young", "female"]}.
     */
    suspend fun inferTraitsForCharacter(characterName: String, excerpt: String): List<String> = withContext(Dispatchers.IO) {
        if (!modelLoaded) return@withContext emptyList()
        try {
            val t0 = System.currentTimeMillis()
            val prompt = buildInferTraitsPrompt(characterName, excerpt)
            val response = generateResponse(prompt, 256, 0.2f)
            val result = parseTraitsFromResponse(response)
            AppLogger.logPerformance("QwenModel", "inferTraitsForCharacter ($characterName)", System.currentTimeMillis() - t0)
            result
        } catch (e: Exception) {
            android.util.Log.e("QwenModel", "Error inferring traits for $characterName", e)
            emptyList()
        }
    }

    /**
     * Suggest voice_profile JSON for each character given names and traits (workflow: TTS-ready output).
     * Input: JSON array of {name, traits}. Output: JSON {characters: [{name, voice_profile: {pitch, speed, energy, emotion_bias}}]}.
     */
    suspend fun suggestVoiceProfilesJson(charactersWithTraitsJson: String): String? = withContext(Dispatchers.IO) {
        if (!modelLoaded) return@withContext null
        try {
            val t0 = System.currentTimeMillis()
            val prompt = buildSuggestVoiceProfilesPrompt(charactersWithTraitsJson)
            val response = generateResponse(prompt, 512, 0.2f)
            val json = extractJsonFromResponse(response)
            AppLogger.logPerformance("QwenModel", "suggestVoiceProfilesJson (inputLen=${charactersWithTraitsJson.length})", System.currentTimeMillis() - t0)
            if (json.isNotBlank()) json else null
        } catch (e: Exception) {
            android.util.Log.e("QwenModel", "Error suggesting voice profiles", e)
            null
        }
    }

    private fun buildExtractCharactersAndTraitsPrompt(
        segmentText: String,
        skipNamesWithTraits: Collection<String>,
        namesNeedingTraits: Collection<String>
    ): String {
        val skipLine = if (skipNamesWithTraits.isEmpty()) "" else "\nSKIP these characters (already extracted with traits): ${skipNamesWithTraits.joinToString(", ")}. Do not list them."
        val needTraitsLine = if (namesNeedingTraits.isEmpty()) "" else "\nFor these characters we only have the name (traits unknown); try to infer traits from this segment: ${namesNeedingTraits.joinToString(", ")}. Include them in your output with traits if you can."
        val systemPrompt = """From this segment of text, list every character (name) and their voice-relevant traits. Include: anyone who speaks, is mentioned, referred to by role ("the Captain", "Mother"), nickname, or full name. For each character provide traits when inferable: gender (female, male), age (young, old, middle-aged), accent (British, American, etc.). If no traits can be inferred, use empty list. Do not invent names.$skipLine$needTraitsLine
Output ONLY JSON: {"characters": [{"name": "Name1", "traits": ["trait1", "trait2"]}, {"name": "Name2", "traits": []}]}. Nothing else.$noThinkingInstruction"""
        val userPrompt = """Text:
\"\"\"
${segmentText.take(6000)}
\"\"\"
List every character in this segment with their traits. Skip any character already listed in the "SKIP" list. JSON only: {"characters": [{"name": "...", "traits": ["..."]}]}."""
        return "<|im_start|>system\n$systemPrompt<|im_end|>\n<|im_start|>user\n$userPrompt<|im_end|>\n<|im_start|>assistant\n"
    }

    private fun buildDetectCharactersOnPagePrompt(pageText: String): String {
        val systemPrompt = """List ALL character names from the text. Include: anyone who speaks, is mentioned, referred to by role ("the Captain", "Mother"), nickname, or full name. Include minor characters. Do not invent names. Output ONLY JSON: {"names": ["Name1", "Name2"]}. If none: {"names": []}. Nothing else.$noThinkingInstruction"""
        val userPrompt = """Text:
\"\"\"
${pageText.take(6000)}
\"\"\"
List every character name (speakers, mentioned, titles, nicknames). JSON only: {"names": ["..."]}."""
        return "<|im_start|>system\n$systemPrompt<|im_end|>\n<|im_start|>user\n$userPrompt<|im_end|>\n<|im_start|>assistant\n"
    }

    private fun buildInferTraitsPrompt(characterName: String, excerpt: String): String {
        val systemPrompt = """Infer voice-relevant traits from the excerpt. Include when inferable: gender (female, male), age (young, old, middle-aged), accent (British, American, Scottish, Irish). Output ONLY JSON: {"traits": ["trait1", "trait2"]}. Nothing else.$noThinkingInstruction"""
        val userPrompt = """Character: $characterName

Excerpt:
\"\"\"
${excerpt.take(4000)}
\"\"\"
Traits for $characterName. JSON: {"traits": ["..."]}."""
        return "<|im_start|>system\n$systemPrompt<|im_end|>\n<|im_start|>user\n$userPrompt<|im_end|>\n<|im_start|>assistant\n"
    }

    private fun buildSuggestVoiceProfilesPrompt(charactersWithTraitsJson: String): String {
        val systemPrompt = """For each character suggest TTS profile: pitch, speed, energy (0.5-1.5), emotion_bias. Output ONLY JSON: {"characters": [{"name": "...", "voice_profile": {"pitch": 1.0, "speed": 1.0, "energy": 1.0, "emotion_bias": {}}}]}. Nothing else.$noThinkingInstruction"""
        val userPrompt = """$charactersWithTraitsJson

Voice profile JSON for each. JSON only."""
        return "<|im_start|>system\n$systemPrompt<|im_end|>\n<|im_start|>user\n$userPrompt<|im_end|>\n<|im_start|>assistant\n"
    }

    private fun parseCharactersAndTraitsFromResponse(response: String): List<Pair<String, List<String>>> {
        return try {
            val json = extractJsonFromResponse(response)
            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val arr = obj["characters"] as? List<*> ?: return emptyList()
            val result = mutableListOf<Pair<String, List<String>>>()
            val seen = mutableSetOf<String>()
            for (item in arr) {
                val map = item as? Map<*, *> ?: continue
                val name = (map["name"] as? String)?.trim() ?: continue
                if (name.isBlank() || !seen.add(name.lowercase())) continue
                @Suppress("UNCHECKED_CAST")
                val traits = (map["traits"] as? List<*>)?.mapNotNull { it as? String }?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                result.add(name to traits)
            }
            result
        } catch (e: Exception) {
            android.util.Log.w("QwenModel", "Failed to parse characters and traits", e)
            emptyList()
        }
    }

    private fun parseCharacterNamesFromResponse(response: String): List<String> {
        return try {
            val json = extractJsonFromResponse(response)
            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val raw = (obj["names"] as? List<*>)?.mapNotNull { it as? String }?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            raw.distinctBy { it.lowercase() }
        } catch (e: Exception) {
            android.util.Log.w("QwenModel", "Failed to parse character names", e)
            emptyList()
        }
    }

    private fun parseTraitsFromResponse(response: String): List<String> {
        return try {
            val json = extractJsonFromResponse(response)
            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            (obj["traits"] as? List<*>)?.mapNotNull { it as? String }?.filter { it.isNotBlank() } ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.w("QwenModel", "Failed to parse traits", e)
            emptyList()
        }
    }

    /** Qwen2.5: turn off thinking / chain-of-thought; output final answer only. */
    private val noThinkingInstruction = "\nDo not use chain-of-thought or thinking. Output only the final answer."

    private fun buildAnalysisPrompt(chapterText: String): String {
        // Qwen2.5 ChatML format: <|im_start|>role\ncontent<|im_end|> (matches FullFeatured app Instructions.md 2.0, 2.1)
        val systemPrompt = """You are an NLP engine running on a mobile device.
Your task is to analyze a chapter of a novel and produce structured JSON.

Follow these rules:
1. Extract ALL characters: everyone who speaks, is mentioned, referred to by role ("the Captain"), nickname, or name. Include minor characters. Do not invent.
2. For each character, infer personality traits based only on this chapter. Include voice-relevant traits when inferable: gender (e.g. female, male), age (e.g. young, old, middle-aged), accent or origin (e.g. British, American, Scottish, Irish) so TTS can match a fitting speaker voice.
3. Build a voice-trait profile compatible with SherpaTTS:
   - pitch (0.5-1.5)
   - speed (0.5-1.5)
   - energy (0.5-1.5)
   - emotion_bias: {happy, sad, angry, fear, surprise, neutral} (0-1)
4. Extract all dialogs:
   - Identify the speaker (best guess if ambiguous)
   - Extract the dialog text
   - Determine emotion + intensity (0-1)
   - Determine prosody hints: pitchvariation, speed, stresspattern
5. Extract sound cues for sound effects and ambience:
   - event description
   - sound_prompt (short natural language description)
   - duration in seconds (1-8)
   - category: "effect" or "ambience"
6. Provide a short chapter summary and key events.
7. Output ONLY valid JSON. No explanations.
8. Do not invent characters not present in the chapter.
9. If speaker is unknown, set speaker = "unknown".

Return ONLY valid JSON with this structure:
{
  "chapter_summary": {
    "title": "string",
    "short_summary": "string",
    "main_events": ["string"],
    "emotional_arc": [{"segment": "string", "emotion": "string", "intensity": 0.5}]
  },
  "characters": [{
    "name": "string",
    "traits": ["string"],
    "voice_profile": {"pitch": 1.0, "speed": 1.0, "energy": 1.0, "emotion_bias": {}}
  }],
  "dialogs": [{
    "speaker": "string",
    "dialog": "string",
    "emotion": "string",
    "intensity": 0.5,
    "prosody": {"pitch_variation": "string", "speed": "string", "stress_pattern": "string"}
  }],
  "sound_cues": [{
    "event": "string",
    "sound_prompt": "string",
    "duration": 2.0,
    "category": "effect"
  }]
}$noThinkingInstruction"""

        val userPrompt = """CHAPTER_TEXT:
${'"'}${'"'}${'"'}
$chapterText
${'"'}${'"'}${'"'}

TASK:
Analyze this chapter and produce JSON following the required schema."""

        // ChatML (Qwen2.5 compatible): system + user + assistant invitation
        return "<|im_start|>system\n$systemPrompt<|im_end|>\n<|im_start|>user\n$userPrompt<|im_end|>\n<|im_start|>assistant\n"
    }
    
    private fun buildExtendedAnalysisPrompt(chapterText: String): String {
        val systemPrompt = """You are an NLP engine analyzing literary elements.
Analyze the chapter and return a JSON object with:
- themes: array of theme strings
- symbols: array of symbol descriptions
- foreshadowing: array of foreshadowing hints
- vocabulary: array of {word, definition} objects

Return ONLY valid JSON, no markdown formatting.$noThinkingInstruction"""

        val userPrompt = """Analyze this chapter for themes, symbols, foreshadowing, and vocabulary:

$chapterText"""

        // ChatML (Qwen2.5 compatible)
        return "<|im_start|>system\n$systemPrompt<|im_end|>\n<|im_start|>user\n$userPrompt<|im_end|>\n<|im_start|>assistant\n"
    }
    
    /** Default generation: 1024 tokens, temp 0.3 for balanced speed/accuracy. */
    private fun generateResponse(prompt: String): String = generateResponse(prompt, 1024, 0.3f)

    /** Generate with explicit maxTokens and temperature. Use lower maxTokens (512) and temp (0.2) for short JSON. */
    private fun generateResponse(prompt: String, maxTokens: Int, temperature: Float): String {
        return try {
            val promptWithNoThink = prompt + "\n/no_think\n"
            val t0 = System.currentTimeMillis()
            val out = llamaGenerate(llamaContext, promptWithNoThink, maxTokens, temperature, 0.9f, 1.0f) as? String ?: ""
            AppLogger.logPerformance("QwenModel", "LLM generate (maxTokens=$maxTokens)", System.currentTimeMillis() - t0)
            out
        } catch (e: Exception) {
            android.util.Log.e("QwenModel", "Error generating response", e)
            ""
        }
    }
    
    /**
     * T12.1: Generate a story based on user prompt.
     * Configured to only allow story creation.
     */
    suspend fun generateStory(userPrompt: String): String = withContext(Dispatchers.IO) {
        if (!modelLoaded) {
            android.util.Log.w("QwenModel", "Model not loaded, returning stub story")
            return@withContext stubGenerateStory(userPrompt)
        }
        
        val systemPrompt = """You are a creative story writer. Your task is to generate a complete, engaging story based on the user's prompt.

Rules:
1. Generate ONLY story content - no explanations, no meta-commentary, no JSON
2. Write a complete story with a beginning, middle, and end
3. Include dialogue, character development, and descriptive scenes
4. Make the story engaging and well-written
5. The story should be substantial (at least 1000 words)
6. Write in third person narrative style
7. Do not include any instructions or notes, only the story text itself$noThinkingInstruction

Generate the story now:"""
        
        // ChatML (Qwen2.5 compatible)
        val fullPrompt = "<|im_start|>system\n$systemPrompt<|im_end|>\n<|im_start|>user\n$userPrompt<|im_end|>\n<|im_start|>assistant\n"
        
        val t0 = System.currentTimeMillis()
        val response = generateResponse(fullPrompt)
        AppLogger.logPerformance("QwenModel", "generateStory (full)", System.currentTimeMillis() - t0)
        
        // Clean up response - remove any markdown code blocks or extra formatting
        val cleaned = response.trim()
            .replace(Regex("```[\\w]*\\n"), "")
            .replace(Regex("```"), "")
            .trim()
        
        if (cleaned.isEmpty()) {
            android.util.Log.w("QwenModel", "Empty response, using stub")
            stubGenerateStory(userPrompt)
        } else {
            cleaned
        }
    }
    
    private fun stubGenerateStory(prompt: String): String {
        return """Once upon a time, there was a brave adventurer who set out on a quest.

The prompt was: "$prompt"

This is a stub story. Please ensure the Qwen model is properly loaded for real story generation.

The adventurer traveled far and wide, encountering many challenges along the way. They met interesting characters, faced dangerous obstacles, and learned valuable lessons. In the end, they achieved their goal and returned home wiser and stronger.

The end."""
    }
    
    private fun parseAnalysisResponse(response: String, chapterText: String): ChapterAnalysisResponse? {
        return try {
            // Try to parse JSON response
            val json = extractJsonFromResponse(response)
            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: throw Exception("Invalid JSON")
            
            ChapterAnalysisResponse(
                chapterSummary = parseChapterSummary(obj["chapter_summary"]),
                characters = parseCharacters(obj["characters"]),
                dialogs = parseDialogs(obj["dialogs"]),
                soundCues = parseSoundCues(obj["sound_cues"])
            )
        } catch (e: Exception) {
            android.util.Log.w("QwenModel", "Failed to parse response", e)
            null
        }
    }
    
    private fun parseExtendedAnalysisResponse(response: String, chapterText: String): String? {
        return try {
            val json = extractJsonFromResponse(response)
            // Validate it's valid JSON
            gson.fromJson(json, Map::class.java)
            json
        } catch (e: Exception) {
            android.util.Log.w("QwenModel", "Failed to parse extended analysis", e)
            null
        }
    }
    
    private fun extractJsonFromResponse(response: String): String {
        // Extract JSON from response (might have markdown code blocks or extra text)
        var json = response.trim()
        if (json.startsWith("```json")) {
            json = json.removePrefix("```json").trim()
        }
        if (json.startsWith("```")) {
            json = json.removePrefix("```").trim()
        }
        if (json.endsWith("```")) {
            json = json.removeSuffix("```").trim()
        }
        // Find first { and last }
        val start = json.indexOf('{')
        val end = json.lastIndexOf('}')
        if (start >= 0 && end > start) {
            json = json.substring(start, end + 1)
        }
        return json
    }
    
    private fun parseChapterSummary(obj: Any?): ChapterSummary? {
        if (obj !is Map<*, *>) return null
        return ChapterSummary(
            title = obj["title"] as? String ?: "Chapter",
            shortSummary = obj["short_summary"] as? String ?: "",
            mainEvents = (obj["main_events"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            emotionalArc = (obj["emotional_arc"] as? List<*>)?.mapNotNull {
                if (it is Map<*, *>) {
                    EmotionalSegment(
                        segment = it["segment"] as? String ?: "",
                        emotion = it["emotion"] as? String ?: "neutral",
                        intensity = (it["intensity"] as? Number)?.toFloat() ?: 0.5f
                    )
                } else null
            } ?: emptyList()
        )
    }
    
    private fun parseCharacters(obj: Any?): List<CharacterStub>? {
        if (obj !is List<*>) return null
        return obj.mapNotNull {
            if (it is Map<*, *>) {
                val voiceProfileMap = (it["voice_profile"] as? Map<*, *>)?.mapValues { (_, v) ->
                    when (v) {
                        is Number -> v.toDouble() as Any
                        is String -> (v.toDoubleOrNull() ?: 1.0) as Any
                        else -> 1.0 as Any
                    }
                } as? Map<String, Any>
                CharacterStub(
                    name = it["name"] as? String ?: "Unknown",
                    traits = (it["traits"] as? List<*>)?.mapNotNull { t -> t as? String },
                    voiceProfile = voiceProfileMap
                )
            } else null
        }
    }
    
    private fun parseDialogs(obj: Any?): List<Dialog>? {
        if (obj !is List<*>) return null
        return obj.mapNotNull {
            if (it is Map<*, *>) {
                val prosodyObj = it["prosody"] as? Map<*, *>
                val prosody = prosodyObj?.let { p ->
                    com.dramebaz.app.data.models.ProsodyHints(
                        pitchVariation = p["pitch_variation"] as? String ?: "normal",
                        speed = p["speed"] as? String ?: "normal",
                        stressPattern = p["stress_pattern"] as? String ?: ""
                    )
                }
                
                Dialog(
                    speaker = it["speaker"] as? String ?: it["character"] as? String ?: "unknown",
                    dialog = it["dialog"] as? String ?: it["text"] as? String ?: "",
                    emotion = it["emotion"] as? String ?: "neutral",
                    intensity = (it["intensity"] as? Number)?.toFloat() ?: 0.5f,
                    prosody = prosody
                )
            } else null
        }
    }
    
    private fun parseSoundCues(obj: Any?): List<SoundCueModel>? {
        if (obj !is List<*>) return null
        return obj.mapNotNull {
            if (it is Map<*, *>) {
                SoundCueModel(
                    event = it["event"] as? String ?: it["type"] as? String ?: "effect",
                    soundPrompt = it["sound_prompt"] as? String ?: it["description"] as? String ?: "",
                    duration = (it["duration"] as? Number)?.toFloat() ?: (it["timestamp"] as? Number)?.toFloat() ?: 2f,
                    category = it["category"] as? String ?: "effect"
                )
            } else null
        }
    }
    
    fun release() {
        if (llamaContext != 0L) {
            try {
                llamaFree(llamaContext)
            } catch (e: Exception) {
                android.util.Log.e("QwenModel", "Error releasing model", e)
            }
            llamaContext = 0
            modelLoaded = false
        }
    }
    
    // Native methods for llama.cpp (JNI)
    private external fun llamaInitFromFile(modelPath: String, nGpuLayers: Int): Long
    private external fun llamaGenerate(context: Long, prompt: String, maxTokens: Int, temperature: Float, topP: Float, topK: Float): String
    private external fun llamaFree(context: Long)
    
    companion object {
        init {
            try {
                System.loadLibrary("llama_jni")
            } catch (e: UnsatisfiedLinkError) {
                // Native library not available - will use fallback
            }
        }
    }
    
}
