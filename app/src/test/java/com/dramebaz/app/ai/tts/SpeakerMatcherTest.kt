package com.dramebaz.app.ai.tts

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for SpeakerMatcher.
 * Tests character trait to speaker matching logic.
 */
class SpeakerMatcherTest {

    @Test
    fun `parseTraits returns empty list for null input`() {
        val result = SpeakerMatcher.parseTraits(null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseTraits returns empty list for blank input`() {
        val result = SpeakerMatcher.parseTraits("   ")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseTraits correctly splits comma-separated traits`() {
        val result = SpeakerMatcher.parseTraits("female, young, british")

        assertEquals(3, result.size)
        assertTrue(result.contains("female"))
        assertTrue(result.contains("young"))
        assertTrue(result.contains("british"))
    }

    @Test
    fun `parseTraits lowercases all tokens`() {
        val result = SpeakerMatcher.parseTraits("MALE, OLD, American")

        assertEquals(3, result.size)
        assertTrue(result.all { it == it.lowercase() })
    }

    @Test
    fun `parseTraits trims whitespace from tokens`() {
        val result = SpeakerMatcher.parseTraits("  female  ,  young  ")

        assertEquals(2, result.size)
        assertEquals("female", result[0])
        assertEquals("young", result[1])
    }

    @Test
    fun `parseTraits filters out empty tokens`() {
        val result = SpeakerMatcher.parseTraits("female, , young, ,")

        assertEquals(2, result.size)
        assertTrue(result.contains("female"))
        assertTrue(result.contains("young"))
    }

    @Test
    fun `suggestSpeakerId returns null for empty traits`() {
        val result = SpeakerMatcher.suggestSpeakerId(null, null, null)
        assertNull(result)
    }

    @Test
    fun `suggestSpeakerId returns valid speaker ID for female traits`() {
        val result = SpeakerMatcher.suggestSpeakerId("female, young", null, null)

        if (result != null) {
            assertTrue(result in LibrittsSpeakerCatalog.MIN_SPEAKER_ID..LibrittsSpeakerCatalog.MAX_SPEAKER_ID)
            val traits = LibrittsSpeakerCatalog.getTraits(result)
            assertNotNull(traits)
            assertTrue(traits!!.isFemale)
        }
    }

    @Test
    fun `suggestSpeakerId returns valid speaker ID for male traits`() {
        val result = SpeakerMatcher.suggestSpeakerId("male, adult", null, null)

        if (result != null) {
            assertTrue(result in LibrittsSpeakerCatalog.MIN_SPEAKER_ID..LibrittsSpeakerCatalog.MAX_SPEAKER_ID)
            val traits = LibrittsSpeakerCatalog.getTraits(result)
            assertNotNull(traits)
            assertTrue(traits!!.isMale)
        }
    }

    @Test
    fun `suggestSpeakerIdFromTraitList combines traits correctly`() {
        val traits = listOf("female", "british", "young")
        val result = SpeakerMatcher.suggestSpeakerIdFromTraitList(traits, null, null)

        if (result != null) {
            assertTrue(result in LibrittsSpeakerCatalog.MIN_SPEAKER_ID..LibrittsSpeakerCatalog.MAX_SPEAKER_ID)
        }
    }

    @Test
    fun `getSimilarSpeakers returns requested number of results`() {
        val results = SpeakerMatcher.getSimilarSpeakers("female", null, null, topN = 10)

        assertTrue(results.size <= 10)
        // Should have some results for a valid trait
        assertTrue(results.isNotEmpty())
    }

    @Test
    fun `getSimilarSpeakers results are sorted by score descending`() {
        val results = SpeakerMatcher.getSimilarSpeakers("female, young", null, null, topN = 20)

        for (i in 0 until results.size - 1) {
            assertTrue(results[i].score >= results[i + 1].score)
        }
    }

    @Test
    fun `filterByGender filters female speakers correctly`() {
        val allSpeakers = LibrittsSpeakerCatalog.allSpeakers()
        val females = SpeakerMatcher.filterByGender(allSpeakers, "female")

        assertTrue(females.isNotEmpty())
        assertTrue(females.all { it.isFemale })
    }

    @Test
    fun `filterByGender filters male speakers correctly`() {
        val allSpeakers = LibrittsSpeakerCatalog.allSpeakers()
        val males = SpeakerMatcher.filterByGender(allSpeakers, "male")

        assertTrue(males.isNotEmpty())
        assertTrue(males.all { it.isMale })
    }

    @Test
    fun `filterByGender returns all speakers for null gender`() {
        val allSpeakers = LibrittsSpeakerCatalog.allSpeakers()
        val result = SpeakerMatcher.filterByGender(allSpeakers, null)

        assertEquals(allSpeakers.size, result.size)
    }

    @Test
    fun `filterByPitch filters by pitch level correctly`() {
        val allSpeakers = LibrittsSpeakerCatalog.allSpeakers()
        val highPitch = SpeakerMatcher.filterByPitch(allSpeakers, LibrittsSpeakerCatalog.PitchLevel.HIGH)

        assertTrue(highPitch.isNotEmpty())
        assertTrue(highPitch.all { it.pitchLevel == LibrittsSpeakerCatalog.PitchLevel.HIGH })
    }

    @Test
    fun `filterByPitch returns all speakers for null pitch level`() {
        val allSpeakers = LibrittsSpeakerCatalog.allSpeakers()
        val result = SpeakerMatcher.filterByPitch(allSpeakers, null)

        assertEquals(allSpeakers.size, result.size)
    }
}
