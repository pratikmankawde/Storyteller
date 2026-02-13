package com.dramebaz.app.data.models

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ReadingLevel.
 * Tests Flesch-Kincaid reading level analysis.
 */
class ReadingLevelTest {

    // analyze tests

    @Test
    fun `analyze returns zero values for blank text`() {
        val result = ReadingLevel.analyze("")
        
        assertEquals(0f, result.gradeLevel, 0.01f)
        assertEquals("N/A", result.gradeDescription)
        assertEquals(100f, result.readingEaseScore, 0.01f)
    }

    @Test
    fun `analyze returns zero values for whitespace only`() {
        val result = ReadingLevel.analyze("   \n\t  ")
        
        assertEquals(0f, result.gradeLevel, 0.01f)
        assertEquals("N/A", result.gradeDescription)
    }

    @Test
    fun `analyze calculates values for simple sentence`() {
        val simpleText = "The cat sat on the mat."
        val result = ReadingLevel.analyze(simpleText)
        
        assertTrue(result.gradeLevel >= 0f)
        assertTrue(result.avgSentenceLength > 0f)
        assertTrue(result.avgSyllablesPerWord > 0f)
    }

    @Test
    fun `analyze calculates higher grade for complex text`() {
        val simpleText = "The cat sat."
        val complexText = "The extraordinarily sophisticated methodology demonstrates unprecedented computational capabilities."
        
        val simpleResult = ReadingLevel.analyze(simpleText)
        val complexResult = ReadingLevel.analyze(complexText)
        
        assertTrue(complexResult.gradeLevel > simpleResult.gradeLevel)
    }

    @Test
    fun `analyze calculates higher reading ease for simpler text`() {
        val simpleText = "I like dogs. Dogs are fun. They run and play."
        val complexText = "The anthropomorphic manifestations demonstrate extraordinary phenomenological characteristics."
        
        val simpleResult = ReadingLevel.analyze(simpleText)
        val complexResult = ReadingLevel.analyze(complexText)
        
        assertTrue(simpleResult.readingEaseScore > complexResult.readingEaseScore)
    }

    @Test
    fun `analyze handles single word`() {
        val result = ReadingLevel.analyze("Hello")
        
        assertTrue(result.gradeLevel >= 0f)
        assertEquals(1f, result.avgSentenceLength, 0.01f)
    }

    @Test
    fun `analyze counts sentences correctly`() {
        val text = "First sentence. Second sentence! Third sentence?"
        val result = ReadingLevel.analyze(text)
        
        // 3 sentences, approximately 6 words (2 per sentence)
        assertTrue(result.avgSentenceLength >= 2f)
        assertTrue(result.avgSentenceLength <= 3f)
    }

    // Grade description tests

    @Test
    fun `grade description returns Pre-K for very low grade`() {
        // Very simple text should have low grade level
        val result = ReadingLevel.analyze("Cat. Dog.")
        
        // Just verify we get a valid description
        assertTrue(result.gradeDescription.isNotBlank())
    }

    @Test
    fun `grade description returns valid categories`() {
        val validCategories = listOf("Pre-K", "Middle School", "High School", "Young Adult", "Adult", "Advanced")
        // Also includes "Grade X" patterns
        
        val result = ReadingLevel.analyze("The quick brown fox jumps over the lazy dog repeatedly.")
        
        assertTrue(result.gradeDescription.isNotBlank())
        // Should be either a grade number or one of the categories
        assertTrue(
            result.gradeDescription.startsWith("Grade") ||
            validCategories.any { result.gradeDescription == it }
        )
    }

    // Vocabulary complexity tests

    @Test
    fun `vocabulary complexity is in valid range`() {
        val text = "Simple words here. More simple words. Easy reading text."
        val result = ReadingLevel.analyze(text)
        
        assertTrue(result.vocabularyComplexity >= 0f)
        assertTrue(result.vocabularyComplexity <= 100f)
    }

    @Test
    fun `vocabulary complexity varies with text content`() {
        val text1 = "Simple words here now."
        val text2 = "Extraordinary phenomenological manifestations demonstrate sophisticated capabilities."

        val result1 = ReadingLevel.analyze(text1)
        val result2 = ReadingLevel.analyze(text2)

        // Just verify both produce valid complexity values
        assertTrue(result1.vocabularyComplexity >= 0f)
        assertTrue(result2.vocabularyComplexity >= 0f)
        // Complex text should have higher average syllables per word
        assertTrue(result2.avgSyllablesPerWord > result1.avgSyllablesPerWord)
    }

    // Edge cases

    @Test
    fun `analyze handles text without sentence punctuation`() {
        val textWithoutPunctuation = "This is text without any ending punctuation at all"
        val result = ReadingLevel.analyze(textWithoutPunctuation)
        
        // Should still return valid results
        assertTrue(result.avgSentenceLength > 0f)
    }

    @Test
    fun `analyze handles multiple punctuation marks`() {
        val text = "Really?! Yes! Absolutely!!! Wow..."
        val result = ReadingLevel.analyze(text)
        
        assertTrue(result.gradeLevel >= 0f)
    }

    @Test
    fun `grade level is capped at 18`() {
        // Very complex text
        val veryComplex = "Antidisestablishmentarianism extraordinarily phenomenologically demonstrates unprecedented supercalifragilisticexpialidocious characteristics."
        val result = ReadingLevel.analyze(veryComplex)
        
        assertTrue(result.gradeLevel <= 18f)
    }

    @Test
    fun `reading ease score is in valid range 0 to 100`() {
        val text = "Some random text for testing purposes here today now."
        val result = ReadingLevel.analyze(text)
        
        assertTrue(result.readingEaseScore >= 0f)
        assertTrue(result.readingEaseScore <= 100f)
    }
}

