package com.dramebaz.app.ai.llm.pipeline.merging

import com.dramebaz.app.ai.llm.prompts.ExtractedVoiceProfile

/**
 * Strategy interface for merging voice profiles from multiple extractions.
 * Allows different merging strategies to be plugged in.
 */
interface VoiceProfileMerger {
    /**
     * Merge two voice profiles, keeping the most detailed values.
     * @param existing The existing voice profile (may be null)
     * @param new The new voice profile to merge (may be null)
     * @return The merged voice profile, or null if both inputs are null
     */
    fun merge(existing: ExtractedVoiceProfile?, new: ExtractedVoiceProfile?): ExtractedVoiceProfile?
}

/**
 * Default implementation that prefers non-default values.
 * 
 * Default values are:
 * - gender: "male"
 * - age: "middle-aged"
 * - accent: "neutral"
 * - pitch: 1.0f
 * - speed: 1.0f
 */
class PreferDetailedVoiceProfileMerger : VoiceProfileMerger {
    
    companion object {
        // Default values to detect "non-specific" extractions
        private const val DEFAULT_GENDER = "male"
        private const val DEFAULT_AGE = "middle-aged"
        private const val DEFAULT_ACCENT = "neutral"
        private const val DEFAULT_PITCH = 1.0f
        private const val DEFAULT_SPEED = 1.0f
    }
    
    override fun merge(existing: ExtractedVoiceProfile?, new: ExtractedVoiceProfile?): ExtractedVoiceProfile? {
        // Handle null cases
        if (existing == null && new == null) return null
        if (existing == null) return new
        if (new == null) return existing
        
        // Merge fields, preferring non-default values
        return ExtractedVoiceProfile(
            gender = selectNonDefault(new.gender, existing.gender, DEFAULT_GENDER),
            age = selectNonDefault(new.age, existing.age, DEFAULT_AGE),
            accent = selectNonDefault(new.accent, existing.accent, DEFAULT_ACCENT),
            pitch = selectNonDefault(new.pitch, existing.pitch, DEFAULT_PITCH),
            speed = selectNonDefault(new.speed, existing.speed, DEFAULT_SPEED)
        )
    }
    
    private fun selectNonDefault(newValue: String, existingValue: String, default: String): String {
        return if (newValue != default) newValue else existingValue
    }
    
    private fun selectNonDefault(newValue: Float, existingValue: Float, default: Float): Float {
        return if (newValue != default) newValue else existingValue
    }
}

/**
 * Merger that always prefers the new voice profile over the existing one.
 * Useful when later extractions are expected to be more accurate.
 */
class PreferNewVoiceProfileMerger : VoiceProfileMerger {
    
    override fun merge(existing: ExtractedVoiceProfile?, new: ExtractedVoiceProfile?): ExtractedVoiceProfile? {
        return new ?: existing
    }
}

/**
 * Merger that always keeps the existing voice profile.
 * Useful when first extractions are expected to be more accurate.
 */
class PreferExistingVoiceProfileMerger : VoiceProfileMerger {
    
    override fun merge(existing: ExtractedVoiceProfile?, new: ExtractedVoiceProfile?): ExtractedVoiceProfile? {
        return existing ?: new
    }
}

