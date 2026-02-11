package com.dramebaz.app.ai.llm.pipeline.matching

/**
 * Strategy interface for character name matching.
 * Allows different matching algorithms to be plugged in.
 */
interface CharacterNameMatcher {
    /**
     * Canonicalize a name for consistent matching.
     */
    fun canonicalize(name: String): String
    
    /**
     * Check if two names refer to the same character.
     */
    fun matches(name1: String, name2: String): Boolean
    
    /**
     * Check if a name is a variant of an existing character.
     * @param name The name to check
     * @param existingName The existing character's primary name
     * @param knownVariants Set of known name variants for the existing character
     */
    fun isVariant(name: String, existingName: String, knownVariants: Set<String>): Boolean
}

/**
 * Default implementation using fuzzy matching with word overlap.
 * 
 * Handles common cases:
 * - "John Smith" vs "Smith" (containment)
 * - "Harry Potter" vs "Harry" (word overlap)
 * - Case-insensitive matching
 * - Punctuation normalization
 */
class FuzzyCharacterNameMatcher : CharacterNameMatcher {
    
    companion object {
        /** Minimum word length to consider for matching */
        private const val MIN_WORD_LENGTH = 3
    }
    
    override fun canonicalize(name: String): String {
        return name.lowercase()
            .trim()
            .replace(Regex("\\s+"), " ")  // Normalize whitespace
            .replace(Regex("[^a-z0-9 ]"), "")  // Remove punctuation
    }
    
    override fun matches(name1: String, name2: String): Boolean {
        val canonical1 = canonicalize(name1)
        val canonical2 = canonicalize(name2)
        
        // Exact match
        if (canonical1 == canonical2) return true
        
        // Containment check
        if (canonical1.contains(canonical2) || canonical2.contains(canonical1)) {
            return true
        }
        
        // Word overlap check
        val words1 = canonical1.split(" ").filter { it.length >= MIN_WORD_LENGTH }
        val words2 = canonical2.split(" ").filter { it.length >= MIN_WORD_LENGTH }
        
        return words1.intersect(words2.toSet()).isNotEmpty()
    }
    
    override fun isVariant(name: String, existingName: String, knownVariants: Set<String>): Boolean {
        val nameLower = name.lowercase().trim()
        
        // Check against known variants first
        if (knownVariants.any { it.lowercase() == nameLower }) {
            return true
        }
        
        // Fall back to fuzzy matching
        return matches(name, existingName)
    }
}

/**
 * Strict name matcher that only matches exact canonical names.
 * Useful for testing or when fuzzy matching causes issues.
 */
class StrictCharacterNameMatcher : CharacterNameMatcher {
    
    override fun canonicalize(name: String): String {
        return name.lowercase().trim()
    }
    
    override fun matches(name1: String, name2: String): Boolean {
        return canonicalize(name1) == canonicalize(name2)
    }
    
    override fun isVariant(name: String, existingName: String, knownVariants: Set<String>): Boolean {
        val nameLower = name.lowercase().trim()
        return knownVariants.any { it.lowercase() == nameLower } || 
               canonicalize(name) == canonicalize(existingName)
    }
}

