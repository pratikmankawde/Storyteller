package com.dramebaz.app.ai.tts

import kotlin.math.min

/**
 * Matches a character (with traits extracted by Qwen from chapters) to a VCTK TTS speaker ID (0–108)
 * by scoring each speaker's gender, age bucket, and accent/region against the character's traits.
 */
object SpeakerMatcher {

    /** Normalized traits string (comma-separated) to list of lowercased tokens. */
    fun parseTraits(traits: String?): List<String> {
        if (traits.isNullOrBlank()) return emptyList()
        return traits.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    }

    /**
     * Picks a suggested VCTK speaker ID (0–108) for a character based on traits and optional name.
     * Prefer gender match, then accent/age keywords. Returns null if no preference (caller can use default).
     */
    fun suggestSpeakerId(traits: String?, personalitySummary: String?, name: String?): Int? {
        val traitTokens = parseTraits(traits).toMutableList()
        personalitySummary?.split(Regex("\\s+"))?.map { it.trim().lowercase() }?.filter { it.length > 2 }?.let { traitTokens.addAll(it) }
        name?.lowercase()?.let { traitTokens.add(it) }
        if (traitTokens.isEmpty()) return null

        val scores = IntArray(VctkSpeakerCatalog.SPEAKER_COUNT) { 0 }
        for (sid in VctkSpeakerCatalog.MIN_SPEAKER_ID..VctkSpeakerCatalog.MAX_SPEAKER_ID) {
            val s = VctkSpeakerCatalog.getTraits(sid) ?: continue
            var score = 0
            for (t in traitTokens) {
                when {
                    // Gender
                    t in setOf("female", "woman", "lady", "girl", "she", "her") && s.isFemale -> score += 10
                    t in setOf("male", "man", "gentleman", "boy", "he", "him") && s.isMale -> score += 10
                    t in setOf("female", "woman", "lady") && s.isMale -> score -= 8
                    t in setOf("male", "man", "gentleman") && s.isFemale -> score -= 8
                    // Age
                    t in setOf("young", "teen", "youth", "child", "kid") && s.ageBucket == "young" -> score += 5
                    t in setOf("old", "elder", "aged", "senior") && s.ageBucket == "older" -> score += 5
                    t in setOf("middle-aged", "adult", "mature") && s.ageBucket == "middle" -> score += 5
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
        return best.random()
    }

    /**
     * Same as [suggestSpeakerId] but accepts a list of trait strings (e.g. from CharacterStub.traits).
     */
    fun suggestSpeakerIdFromTraitList(traits: List<String>?, personalitySummary: String?, name: String?): Int? {
        val combined = traits?.joinToString(",") ?: ""
        return suggestSpeakerId(combined, personalitySummary, name)
    }
}
