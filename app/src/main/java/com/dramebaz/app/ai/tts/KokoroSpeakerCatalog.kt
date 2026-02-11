package com.dramebaz.app.ai.tts

import com.dramebaz.app.utils.AppLogger

/**
 * Voice metadata catalog for Kokoro TTS model.
 * 
 * The Kokoro model (kokoro-int8-en-v0_19) includes pre-trained voice embeddings
 * that can be selected by voice ID. This catalog provides metadata about each voice.
 * 
 * Voice information based on the Kokoro model documentation.
 */
object KokoroSpeakerCatalog {
    
    private const val TAG = "KokoroSpeakerCatalog"
    
    /**
     * Voice metadata for Kokoro model.
     */
    data class KokoroVoice(
        val id: Int,
        val name: String,
        val gender: String, // "male", "female", "neutral"
        val style: String,  // Description of voice style
        val language: String = "en"
    )
    
    /**
     * Available voices in the Kokoro model.
     * 
     * Note: The actual voice count and metadata depends on the specific model version.
     * These are placeholder entries - update based on actual model documentation.
     */
    private val voices = listOf(
        KokoroVoice(0, "af_bella", "female", "American Female - Bella"),
        KokoroVoice(1, "af_nicole", "female", "American Female - Nicole"),
        KokoroVoice(2, "af_sarah", "female", "American Female - Sarah"),
        KokoroVoice(3, "af_sky", "female", "American Female - Sky"),
        KokoroVoice(4, "am_adam", "male", "American Male - Adam"),
        KokoroVoice(5, "am_michael", "male", "American Male - Michael"),
        KokoroVoice(6, "bf_emma", "female", "British Female - Emma"),
        KokoroVoice(7, "bf_isabella", "female", "British Female - Isabella"),
        KokoroVoice(8, "bm_george", "male", "British Male - George"),
        KokoroVoice(9, "bm_lewis", "male", "British Male - Lewis")
    )
    
    private val voiceById = voices.associateBy { it.id }
    private val voiceByName = voices.associateBy { it.name.lowercase() }
    
    /**
     * Get all available voices.
     */
    fun getAllVoices(): List<KokoroVoice> = voices
    
    /**
     * Get voice count.
     */
    fun getVoiceCount(): Int = voices.size
    
    /**
     * Get voice by ID.
     */
    fun getVoiceById(id: Int): KokoroVoice? = voiceById[id]
    
    /**
     * Get voice by name (case-insensitive).
     */
    fun getVoiceByName(name: String): KokoroVoice? = voiceByName[name.lowercase()]
    
    /**
     * Get voices by gender.
     */
    fun getVoicesByGender(gender: String): List<KokoroVoice> =
        voices.filter { it.gender.equals(gender, ignoreCase = true) }
    
    /**
     * Get a default narrator voice (typically a neutral, clear voice).
     */
    fun getDefaultNarratorVoice(): KokoroVoice = voices.firstOrNull { it.name == "af_bella" } ?: voices.first()
    
    /**
     * Find the best matching voice for character traits.
     * 
     * @param gender Preferred gender ("male", "female", or null for any)
     * @param style Preferred style/description (optional)
     * @return Best matching voice, or default if no match
     */
    fun findMatchingVoice(gender: String? = null, style: String? = null): KokoroVoice {
        var candidates = voices.toList()
        
        // Filter by gender if specified
        if (!gender.isNullOrBlank()) {
            val genderMatches = candidates.filter { it.gender.equals(gender, ignoreCase = true) }
            if (genderMatches.isNotEmpty()) {
                candidates = genderMatches
            }
        }
        
        // If style specified, try to match
        if (!style.isNullOrBlank()) {
            val styleLower = style.lowercase()
            val styleMatches = candidates.filter { 
                it.style.lowercase().contains(styleLower) || 
                it.name.lowercase().contains(styleLower) 
            }
            if (styleMatches.isNotEmpty()) {
                return styleMatches.first()
            }
        }
        
        return candidates.firstOrNull() ?: voices.first()
    }
    
    /**
     * Log all available voices for debugging.
     */
    fun logAllVoices() {
        AppLogger.d(TAG, "Kokoro voices (${voices.size} total):")
        voices.forEach { voice ->
            AppLogger.d(TAG, "  [${voice.id}] ${voice.name} - ${voice.gender} - ${voice.style}")
        }
    }
}

