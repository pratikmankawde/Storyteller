package com.dramebaz.app.data.models

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ScenePrompt.
 * Tests scene visualization prompt generation.
 */
class ScenePromptTest {

    // Default values tests

    @Test
    fun `default negative prompt is set`() {
        val prompt = ScenePrompt(prompt = "A castle")
        
        assertTrue(prompt.negativePrompt.isNotBlank())
        assertTrue(prompt.negativePrompt.contains("blurry"))
    }

    @Test
    fun `default style is set`() {
        val prompt = ScenePrompt(prompt = "A castle")
        
        assertEquals("detailed digital illustration", prompt.style)
    }

    @Test
    fun `default mood is neutral`() {
        val prompt = ScenePrompt(prompt = "A castle")
        
        assertEquals("neutral", prompt.mood)
    }

    @Test
    fun `default aspect ratio is 16 to 9`() {
        val prompt = ScenePrompt(prompt = "A castle")
        
        assertEquals("16:9", prompt.aspectRatio)
    }

    @Test
    fun `default characters list is empty`() {
        val prompt = ScenePrompt(prompt = "A castle")
        
        assertTrue(prompt.characters.isEmpty())
    }

    // toFullPrompt tests

    @Test
    fun `toFullPrompt includes main prompt`() {
        val prompt = ScenePrompt(prompt = "A medieval castle")
        val fullPrompt = prompt.toFullPrompt()
        
        assertTrue(fullPrompt.contains("A medieval castle"))
    }

    @Test
    fun `toFullPrompt includes style`() {
        val prompt = ScenePrompt(
            prompt = "A castle",
            style = "oil painting"
        )
        val fullPrompt = prompt.toFullPrompt()
        
        assertTrue(fullPrompt.contains("oil painting"))
    }

    @Test
    fun `toFullPrompt excludes neutral mood`() {
        val prompt = ScenePrompt(
            prompt = "A castle",
            mood = "neutral"
        )
        val fullPrompt = prompt.toFullPrompt()
        
        // Should not contain "neutral atmosphere"
        assertFalse(fullPrompt.contains("neutral atmosphere"))
    }

    @Test
    fun `toFullPrompt includes non-neutral mood`() {
        val prompt = ScenePrompt(
            prompt = "A castle",
            mood = "mysterious"
        )
        val fullPrompt = prompt.toFullPrompt()
        
        assertTrue(fullPrompt.contains("mysterious atmosphere"))
    }

    @Test
    fun `toFullPrompt includes time of day`() {
        val prompt = ScenePrompt(
            prompt = "A castle",
            timeOfDay = "sunset"
        )
        val fullPrompt = prompt.toFullPrompt()
        
        assertTrue(fullPrompt.contains("sunset lighting"))
    }

    @Test
    fun `toFullPrompt excludes blank time of day`() {
        val prompt = ScenePrompt(
            prompt = "A castle",
            timeOfDay = ""
        )
        val fullPrompt = prompt.toFullPrompt()
        
        assertFalse(fullPrompt.contains("lighting"))
    }

    @Test
    fun `toFullPrompt combines all parts with commas`() {
        val prompt = ScenePrompt(
            prompt = "A castle",
            style = "watercolor",
            mood = "romantic",
            timeOfDay = "dawn"
        )
        val fullPrompt = prompt.toFullPrompt()
        
        // Should contain commas separating parts
        assertTrue(fullPrompt.contains(","))
        assertEquals(3, fullPrompt.count { it == ',' })
    }

    // STYLE_PRESETS tests

    @Test
    fun `STYLE_PRESETS contains fantasy`() {
        assertTrue(ScenePrompt.STYLE_PRESETS.containsKey("fantasy"))
        assertTrue(ScenePrompt.STYLE_PRESETS["fantasy"]!!.contains("fantasy"))
    }

    @Test
    fun `STYLE_PRESETS contains scifi`() {
        assertTrue(ScenePrompt.STYLE_PRESETS.containsKey("scifi"))
        assertTrue(ScenePrompt.STYLE_PRESETS["scifi"]!!.contains("futuristic"))
    }

    @Test
    fun `STYLE_PRESETS has expected number of entries`() {
        assertEquals(6, ScenePrompt.STYLE_PRESETS.size)
    }

    // DEFAULT_NEGATIVE tests

    @Test
    fun `DEFAULT_NEGATIVE contains quality terms`() {
        assertTrue(ScenePrompt.DEFAULT_NEGATIVE.contains("blurry"))
        assertTrue(ScenePrompt.DEFAULT_NEGATIVE.contains("low quality"))
    }

    // Data class tests

    @Test
    fun `data class equality works`() {
        val prompt1 = ScenePrompt(prompt = "Test", mood = "happy")
        val prompt2 = ScenePrompt(prompt = "Test", mood = "happy")
        val prompt3 = ScenePrompt(prompt = "Test", mood = "sad")
        
        assertEquals(prompt1, prompt2)
        assertNotEquals(prompt1, prompt3)
    }

    @Test
    fun `copy works correctly`() {
        val original = ScenePrompt(prompt = "A castle", mood = "dark")
        val modified = original.copy(mood = "bright")
        
        assertEquals("A castle", modified.prompt)
        assertEquals("bright", modified.mood)
    }
}

