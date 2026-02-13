package com.dramebaz.app.ai.llm.pipeline.matching

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for CharacterNameMatcher implementations.
 * Tests fuzzy and strict name matching strategies.
 */
class CharacterNameMatcherTest {

    // FuzzyCharacterNameMatcher tests

    private val fuzzyMatcher = FuzzyCharacterNameMatcher()

    @Test
    fun `fuzzy canonicalize lowercases name`() {
        assertEquals("john smith", fuzzyMatcher.canonicalize("John Smith"))
        assertEquals("harry potter", fuzzyMatcher.canonicalize("HARRY POTTER"))
    }

    @Test
    fun `fuzzy canonicalize trims whitespace`() {
        assertEquals("john", fuzzyMatcher.canonicalize("  John  "))
    }

    @Test
    fun `fuzzy canonicalize normalizes multiple spaces`() {
        assertEquals("john smith", fuzzyMatcher.canonicalize("John   Smith"))
    }

    @Test
    fun `fuzzy canonicalize removes punctuation`() {
        assertEquals("john", fuzzyMatcher.canonicalize("John!"))
        assertEquals("dr smith", fuzzyMatcher.canonicalize("Dr. Smith"))
    }

    @Test
    fun `fuzzy matches exact names`() {
        assertTrue(fuzzyMatcher.matches("John Smith", "john smith"))
        assertTrue(fuzzyMatcher.matches("Harry", "harry"))
    }

    @Test
    fun `fuzzy matches contained names`() {
        assertTrue(fuzzyMatcher.matches("John Smith", "Smith"))
        assertTrue(fuzzyMatcher.matches("Smith", "John Smith"))
    }

    @Test
    fun `fuzzy matches word overlap`() {
        assertTrue(fuzzyMatcher.matches("Harry Potter", "Harry"))
        assertTrue(fuzzyMatcher.matches("Harry", "Harry Potter"))
    }

    @Test
    fun `fuzzy ignores short words for overlap`() {
        // "Mr A" vs "Mr B" - "Mr" is too short (< 3 chars) to create overlap match
        // These should not match since only "mr" overlaps but it's too short
        assertFalse(fuzzyMatcher.matches("Mr A", "Mr B"))
    }

    @Test
    fun `fuzzy matches names with different cases`() {
        assertTrue(fuzzyMatcher.matches("HARRY", "harry"))
        assertTrue(fuzzyMatcher.matches("HaRrY", "hArRy"))
    }

    @Test
    fun `fuzzy does not match unrelated names`() {
        assertFalse(fuzzyMatcher.matches("Alice", "Bob"))
        assertFalse(fuzzyMatcher.matches("John Smith", "Jane Doe"))
    }

    @Test
    fun `fuzzy isVariant checks known variants first`() {
        val knownVariants = setOf("Harry", "Harold")
        assertTrue(fuzzyMatcher.isVariant("Harold", "Harry Potter", knownVariants))
    }

    @Test
    fun `fuzzy isVariant falls back to fuzzy matching`() {
        val knownVariants = emptySet<String>()
        assertTrue(fuzzyMatcher.isVariant("Harry", "Harry Potter", knownVariants))
    }

    // StrictCharacterNameMatcher tests

    private val strictMatcher = StrictCharacterNameMatcher()

    @Test
    fun `strict canonicalize lowercases and trims`() {
        assertEquals("john smith", strictMatcher.canonicalize("  John Smith  "))
    }

    @Test
    fun `strict matches exact names only`() {
        assertTrue(strictMatcher.matches("John", "john"))
        assertTrue(strictMatcher.matches("John Smith", "john smith"))
    }

    @Test
    fun `strict does not match contained names`() {
        assertFalse(strictMatcher.matches("John Smith", "Smith"))
        assertFalse(strictMatcher.matches("Smith", "John Smith"))
    }

    @Test
    fun `strict does not match word overlap`() {
        assertFalse(strictMatcher.matches("Harry Potter", "Harry"))
    }

    @Test
    fun `strict isVariant checks known variants`() {
        val knownVariants = setOf("Harold", "Hal")
        assertTrue(strictMatcher.isVariant("Harold", "Harry", knownVariants))
        assertTrue(strictMatcher.isVariant("Hal", "Harry", knownVariants))
    }

    @Test
    fun `strict isVariant checks exact match`() {
        val knownVariants = emptySet<String>()
        assertTrue(strictMatcher.isVariant("Harry", "harry", knownVariants))
    }

    @Test
    fun `strict isVariant rejects non-variant`() {
        val knownVariants = emptySet<String>()
        assertFalse(strictMatcher.isVariant("Alice", "Bob", knownVariants))
    }

    // Edge cases

    @Test
    fun `fuzzy handles empty string`() {
        assertEquals("", fuzzyMatcher.canonicalize(""))
        assertTrue(fuzzyMatcher.matches("", ""))
    }

    @Test
    fun `fuzzy handles single character names`() {
        assertEquals("x", fuzzyMatcher.canonicalize("X"))
    }

    @Test
    fun `strict handles empty string`() {
        assertEquals("", strictMatcher.canonicalize(""))
        assertTrue(strictMatcher.matches("", ""))
    }
}

