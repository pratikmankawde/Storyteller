package com.dramebaz.app.ai.llm.pipeline.merging

import com.dramebaz.app.ai.llm.prompts.ExtractedVoiceProfile
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for VoiceProfileMerger implementations.
 * Tests voice profile merging strategies.
 */
class VoiceProfileMergerTest {

    // PreferDetailedVoiceProfileMerger tests

    private val detailedMerger = PreferDetailedVoiceProfileMerger()

    @Test
    fun `detailed merger returns null for both null`() {
        val result = detailedMerger.merge(null, null)
        assertNull(result)
    }

    @Test
    fun `detailed merger returns new when existing is null`() {
        val newProfile = ExtractedVoiceProfile("female", "young", "british", 1.2f, 0.9f)
        val result = detailedMerger.merge(null, newProfile)
        assertEquals(newProfile, result)
    }

    @Test
    fun `detailed merger returns existing when new is null`() {
        val existingProfile = ExtractedVoiceProfile("female", "young", "british", 1.2f, 0.9f)
        val result = detailedMerger.merge(existingProfile, null)
        assertEquals(existingProfile, result)
    }

    @Test
    fun `detailed merger prefers non-default gender`() {
        val existing = ExtractedVoiceProfile("male", "young", "neutral", 1.0f, 1.0f)
        val new = ExtractedVoiceProfile("female", "middle-aged", "neutral", 1.0f, 1.0f)
        
        val result = detailedMerger.merge(existing, new)!!
        
        assertEquals("female", result.gender) // new is non-default
    }

    @Test
    fun `detailed merger keeps existing when new is default`() {
        val existing = ExtractedVoiceProfile("female", "young", "british", 1.2f, 0.9f)
        val new = ExtractedVoiceProfile("male", "middle-aged", "neutral", 1.0f, 1.0f) // all defaults
        
        val result = detailedMerger.merge(existing, new)!!
        
        assertEquals("female", result.gender)
        assertEquals("young", result.age)
        assertEquals("british", result.accent)
        assertEquals(1.2f, result.pitch, 0.01f)
        assertEquals(0.9f, result.speed, 0.01f)
    }

    @Test
    fun `detailed merger prefers non-default accent`() {
        val existing = ExtractedVoiceProfile("male", "middle-aged", "american", 1.0f, 1.0f)
        val new = ExtractedVoiceProfile("male", "middle-aged", "british", 1.0f, 1.0f)
        
        val result = detailedMerger.merge(existing, new)!!
        
        assertEquals("british", result.accent) // new is non-default
    }

    @Test
    fun `detailed merger prefers non-default pitch`() {
        val existing = ExtractedVoiceProfile("male", "middle-aged", "neutral", 1.0f, 1.0f)
        val new = ExtractedVoiceProfile("male", "middle-aged", "neutral", 0.8f, 1.0f)
        
        val result = detailedMerger.merge(existing, new)!!
        
        assertEquals(0.8f, result.pitch, 0.01f)
    }

    // PreferNewVoiceProfileMerger tests

    private val preferNewMerger = PreferNewVoiceProfileMerger()

    @Test
    fun `prefer new merger returns new when both present`() {
        val existing = ExtractedVoiceProfile("male", "old", "neutral", 1.0f, 1.0f)
        val new = ExtractedVoiceProfile("female", "young", "british", 0.8f, 1.2f)
        
        val result = preferNewMerger.merge(existing, new)
        
        assertEquals(new, result)
    }

    @Test
    fun `prefer new merger returns existing when new is null`() {
        val existing = ExtractedVoiceProfile("male", "old", "neutral", 1.0f, 1.0f)
        
        val result = preferNewMerger.merge(existing, null)
        
        assertEquals(existing, result)
    }

    @Test
    fun `prefer new merger returns null when both null`() {
        val result = preferNewMerger.merge(null, null)
        assertNull(result)
    }

    // PreferExistingVoiceProfileMerger tests

    private val preferExistingMerger = PreferExistingVoiceProfileMerger()

    @Test
    fun `prefer existing merger returns existing when both present`() {
        val existing = ExtractedVoiceProfile("male", "old", "neutral", 1.0f, 1.0f)
        val new = ExtractedVoiceProfile("female", "young", "british", 0.8f, 1.2f)
        
        val result = preferExistingMerger.merge(existing, new)
        
        assertEquals(existing, result)
    }

    @Test
    fun `prefer existing merger returns new when existing is null`() {
        val new = ExtractedVoiceProfile("female", "young", "british", 0.8f, 1.2f)
        
        val result = preferExistingMerger.merge(null, new)
        
        assertEquals(new, result)
    }

    @Test
    fun `prefer existing merger returns null when both null`() {
        val result = preferExistingMerger.merge(null, null)
        assertNull(result)
    }
}

