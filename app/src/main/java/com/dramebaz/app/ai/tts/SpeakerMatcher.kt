package com.dramebaz.app.ai.tts

import android.content.Context
import com.dramebaz.app.utils.AppLogger

/**
 * Matches a character (with traits extracted by Qwen from chapters) to a TTS speaker ID
 * by scoring each speaker's gender, age bucket, and accent/region against the character's traits.
 *
 * Now model-aware: uses the appropriate speaker catalog (LibriTTS, VCTK, etc.) based on
 * the currently selected TTS model.
 */
object SpeakerMatcher {

    private const val TAG = "SpeakerMatcher"

    /** Currently active model ID for speaker matching. Set by TTS initialization. */
    @Volatile
    var activeModelId: String? = null

    /** Application context for accessing TtsModelRegistry when activeModelId is not set. */
    @Volatile
    private var appContext: Context? = null

    /**
     * Initialize SpeakerMatcher with application context.
     * This allows it to query TtsModelRegistry for the selected model
     * even before TTS is fully initialized.
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        AppLogger.d(TAG, "SpeakerMatcher initialized with context")

        // Try to set activeModelId from registry if not already set
        if (activeModelId == null) {
            tryLoadModelIdFromRegistry()
        }
    }

    /**
     * Try to load the selected model ID from TtsModelRegistry.
     * Called during initialization and when activeModelId is null.
     */
    private fun tryLoadModelIdFromRegistry() {
        val ctx = appContext ?: return
        try {
            val registry = TtsModelRegistry(ctx)
            registry.init()
            val selectedModel = registry.getSelectedModel()
            if (selectedModel != null) {
                activeModelId = selectedModel.id
                AppLogger.d(TAG, "Loaded activeModelId from registry: ${selectedModel.id}")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to load model ID from registry: ${e.message}")
        }
    }

    /** Normalized traits string (comma-separated) to list of lowercased tokens. */
    fun parseTraits(traits: String?): List<String> {
        if (traits.isNullOrBlank()) return emptyList()
        return traits.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    }

    /**
     * Get the active speaker catalog based on the current TTS model.
     * If activeModelId is not set, tries to load it from TtsModelRegistry.
     */
    fun getActiveCatalog(): SpeakerCatalog {
        // If activeModelId is null, try to load from registry
        if (activeModelId == null) {
            tryLoadModelIdFromRegistry()
        }
        return SpeakerCatalog.forModel(activeModelId)
    }

    /**
     * Picks a suggested speaker ID for a character based on traits and optional name.
     * Uses the active TTS model's speaker catalog.
     * Prefer gender match, then accent/age keywords. Returns null if no preference (caller can use default).
     * When multiple speakers have the same score, uses character name hash for deterministic selection.
     *
     * @param traits Character traits string (comma-separated)
     * @param personalitySummary Character personality summary
     * @param name Character name
     * @param catalog Optional catalog override; defaults to active model's catalog
     */
    fun suggestSpeakerId(
        traits: String?,
        personalitySummary: String?,
        name: String?,
        catalog: SpeakerCatalog = getActiveCatalog()
    ): Int? {
        val traitTokens = parseTraits(traits).toMutableList()
        personalitySummary?.split(Regex("\\s+"))?.map { it.trim().lowercase() }?.filter { it.length > 2 }?.let { traitTokens.addAll(it) }
        name?.lowercase()?.let { traitTokens.add(it) }
        if (traitTokens.isEmpty()) return null

        val scores = IntArray(catalog.speakerCount) { 0 }
        for (sid in catalog.minSpeakerId..catalog.maxSpeakerId) {
            val s = catalog.getTraits(sid) ?: continue
            var score = 0
            for (t in traitTokens) {
                when {
                    // Gender
                    t in setOf("female", "woman", "lady", "girl", "she", "her") && s.isFemale -> score += 10
                    t in setOf("male", "man", "gentleman", "boy", "he", "him") && s.isMale -> score += 10
                    t in setOf("female", "woman", "lady") && s.isMale -> score -= 8
                    t in setOf("male", "man", "gentleman") && s.isFemale -> score -= 8
                    // Age - granular matching
                    t in setOf("toddler", "baby", "infant") && s.ageBucket == "toddler" -> score += 5
                    t in setOf("child", "kid", "young child") && s.ageBucket == "child" -> score += 5
                    t in setOf("preteen", "pre-teen", "tween") && s.ageBucket == "preteen" -> score += 5
                    t in setOf("teen", "teenager", "adolescent", "youth") && s.ageBucket == "teen" -> score += 5
                    t in setOf("young", "early twenties") && s.ageBucket == "young" -> score += 5
                    t in setOf("young adult", "adult", "twenties", "thirties") && s.ageBucket == "young_adult" -> score += 5
                    t in setOf("middle-aged", "middle aged", "mature", "forties") && s.ageBucket == "middle_aged" -> score += 5
                    t in setOf("senior", "older", "aged", "fifties", "sixties") && s.ageBucket == "senior" -> score += 5
                    t in setOf("elderly", "old", "elder", "very old", "ancient") && s.ageBucket == "elderly" -> score += 5
                    // British / English
                    (t in setOf("british", "english", "uk", "britain") && (s.accent.equals("English", ignoreCase = true) || s.region.contains("England", ignoreCase = true))) -> score += 6
                    (t in setOf("scottish", "scotland") && s.accent.equals("Scottish", ignoreCase = true)) -> score += 6
                    (t in setOf("irish", "ireland") && (s.accent.equals("Irish", ignoreCase = true) || s.accent.equals("Northern Irish", ignoreCase = true))) -> score += 6
                    (t in setOf("welsh", "wales") && s.accent.equals("Welsh", ignoreCase = true)) -> score += 6
                    // American / other
                    (t in setOf("american", "american accent", "us") && s.accent.equals("American", ignoreCase = true)) -> score += 6
                    (t in setOf("australian", "australia") && s.accent.equals("Australian", ignoreCase = true)) -> score += 6
                    (t in setOf("canadian", "canada") && s.accent.equals("Canadian", ignoreCase = true)) -> score += 6
                    (t in setOf("indian") && s.accent.equals("Indian", ignoreCase = true)) -> score += 6
                    (t in setOf("south african") && s.accent.contains("South African", ignoreCase = true)) -> score += 5
                    (t in setOf("new zealand") && s.accent.equals("New Zealand", ignoreCase = true)) -> score += 5
                    // Region keywords in trait (e.g. "Southern", "London")
                    s.region.contains(t, ignoreCase = true) -> score += 3
                    s.accent.contains(t, ignoreCase = true) -> score += 3
                }
            }
            scores[sid] = score
        }

        val maxScore = scores.maxOrNull() ?: 0
        if (maxScore <= 0) return null
        val best = scores.indices.filter { scores[it] == maxScore }
        // Use deterministic selection based on character name hash instead of random
        // This ensures the same character always gets the same speaker
        val nameHash = (name?.hashCode() ?: 0).let { kotlin.math.abs(it) }
        return best[nameHash % best.size]
    }

    /**
     * Same as [suggestSpeakerId] but accepts a list of trait strings (e.g. from CharacterStub.traits).
     */
    fun suggestSpeakerIdFromTraitList(
        traits: List<String>?,
        personalitySummary: String?,
        name: String?,
        catalog: SpeakerCatalog = getActiveCatalog()
    ): Int? {
        val combined = traits?.joinToString(",") ?: ""
        return suggestSpeakerId(combined, personalitySummary, name, catalog)
    }

    /**
     * Returns all speakers sorted by how well their traits match the character's (LLM-extracted) traits.
     * Best matches first, so the UI can show "similar to character" speakers at the top.
     */
    fun speakersSortedByMatch(
        traits: String?,
        personalitySummary: String?,
        name: String?,
        catalog: SpeakerCatalog = getActiveCatalog()
    ): List<SpeakerCatalog.SpeakerTraits> {
        val traitTokens = parseTraits(traits).toMutableList()
        personalitySummary?.split(Regex("\\s+"))?.map { it.trim().lowercase() }?.filter { it.length > 2 }?.let { traitTokens.addAll(it) }
        name?.lowercase()?.let { traitTokens.add(it) }

        val scored = catalog.allSpeakers().map { s ->
            s to calculateMatchScore(s, traitTokens)
        }
        return scored.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * AUG-014: Get similar speakers for a character, returning top N matches.
     * Used for smart speaker filtering in the selection UI.
     * @param traits Character traits string (comma-separated)
     * @param personalitySummary Character personality summary
     * @param name Character name
     * @param topN Number of top matches to return (default 30)
     * @param catalog Speaker catalog to use; defaults to active model's catalog
     * @return List of top matching speakers with their scores
     */
    fun getSimilarSpeakers(
        traits: String?,
        personalitySummary: String?,
        name: String?,
        topN: Int = 30,
        catalog: SpeakerCatalog = getActiveCatalog()
    ): List<ScoredSpeaker> {
        val traitTokens = parseTraits(traits).toMutableList()
        personalitySummary?.split(Regex("\\s+"))?.map { it.trim().lowercase() }?.filter { it.length > 2 }?.let { traitTokens.addAll(it) }
        name?.lowercase()?.let { traitTokens.add(it) }

        val scored = catalog.allSpeakers().map { s ->
            ScoredSpeaker(s, calculateMatchScore(s, traitTokens))
        }
        return scored.sortedByDescending { it.score }.take(topN)
    }

    /**
     * Data class for speaker with match score.
     */
    data class ScoredSpeaker(
        val speaker: SpeakerCatalog.SpeakerTraits,
        val score: Int
    )

    /**
     * Calculate match score for a speaker against trait tokens.
     */
    private fun calculateMatchScore(s: SpeakerCatalog.SpeakerTraits, traitTokens: List<String>): Int {
        var score = 0
        for (t in traitTokens) {
            when {
                // Gender matching (+10 for match, -8 for mismatch)
                t in setOf("female", "woman", "lady", "girl", "she", "her") && s.isFemale -> score += 10
                t in setOf("male", "man", "gentleman", "boy", "he", "him") && s.isMale -> score += 10
                t in setOf("female", "woman", "lady") && s.isMale -> score -= 8
                t in setOf("male", "man", "gentleman") && s.isFemale -> score -= 8

                // Age matching (+5) - granular categories
                t in setOf("toddler", "baby", "infant") && s.ageBucket == "toddler" -> score += 5
                t in setOf("child", "kid", "young child") && s.ageBucket == "child" -> score += 5
                t in setOf("preteen", "pre-teen", "tween") && s.ageBucket == "preteen" -> score += 5
                t in setOf("teen", "teenager", "adolescent", "youth") && s.ageBucket == "teen" -> score += 5
                t in setOf("young", "early twenties") && s.ageBucket == "young" -> score += 5
                t in setOf("young adult", "adult", "twenties", "thirties") && s.ageBucket == "young_adult" -> score += 5
                t in setOf("middle-aged", "middle aged", "mature", "forties") && s.ageBucket == "middle_aged" -> score += 5
                t in setOf("senior", "older", "aged", "fifties", "sixties") && s.ageBucket == "senior" -> score += 5
                t in setOf("elderly", "old", "elder", "very old", "ancient") && s.ageBucket == "elderly" -> score += 5

                // Accent/region matching (+6)
                (t in setOf("british", "english", "uk", "britain") && (s.accent.equals("English", ignoreCase = true) || s.region.contains("England", ignoreCase = true))) -> score += 6
                (t in setOf("scottish", "scotland") && s.accent.equals("Scottish", ignoreCase = true)) -> score += 6
                (t in setOf("irish", "ireland") && (s.accent.equals("Irish", ignoreCase = true) || s.accent.equals("Northern Irish", ignoreCase = true))) -> score += 6
                (t in setOf("welsh", "wales") && s.accent.equals("Welsh", ignoreCase = true)) -> score += 6
                (t in setOf("american", "american accent", "us") && s.accent.equals("American", ignoreCase = true)) -> score += 6
                (t in setOf("australian", "australia") && s.accent.equals("Australian", ignoreCase = true)) -> score += 6
                (t in setOf("canadian", "canada") && s.accent.equals("Canadian", ignoreCase = true)) -> score += 6
                (t in setOf("indian") && s.accent.equals("Indian", ignoreCase = true)) -> score += 6
                (t in setOf("south african") && s.accent.contains("South African", ignoreCase = true)) -> score += 5
                (t in setOf("new zealand") && s.accent.equals("New Zealand", ignoreCase = true)) -> score += 5

                // Region/accent keyword matching (+3)
                s.region.contains(t, ignoreCase = true) -> score += 3
                s.accent.contains(t, ignoreCase = true) -> score += 3

                // AUG-014: Personality-based voice quality matching (+2)
                (t in setOf("authoritative", "commanding", "stern") && s.ageBucket in setOf("senior", "elderly")) -> score += 2
                (t in setOf("energetic", "lively", "enthusiastic") && s.ageBucket in setOf("teen", "young", "young_adult")) -> score += 2
                (t in setOf("warm", "friendly", "kind")) -> score += 1  // Generic positive
                (t in setOf("cold", "distant", "formal") && s.ageBucket in setOf("middle_aged", "senior")) -> score += 1
            }
        }
        return score
    }

    /**
     * AUG-014: Filter speakers by gender.
     */
    fun filterByGender(speakers: List<SpeakerCatalog.SpeakerTraits>, gender: String?): List<SpeakerCatalog.SpeakerTraits> {
        if (gender.isNullOrBlank()) return speakers
        return when (gender.lowercase()) {
            "f", "female", "woman" -> speakers.filter { it.isFemale }
            "m", "male", "man" -> speakers.filter { it.isMale }
            else -> speakers
        }
    }

    /**
     * AUG-014: Filter speakers by pitch level.
     */
    fun filterByPitch(speakers: List<SpeakerCatalog.SpeakerTraits>, pitchLevel: SpeakerCatalog.PitchLevel?): List<SpeakerCatalog.SpeakerTraits> {
        if (pitchLevel == null) return speakers
        return speakers.filter { it.pitchLevel == pitchLevel }
    }
}
