package com.dramebaz.app.ai.llm

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for LlmResponseParser.
 * Tests JSON extraction and parsing of LLM responses into structured data.
 */
class LlmResponseParserTest {

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.d(any(), any(), any()) } returns 0
        every { android.util.Log.i(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.println(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
    }

    // extractJsonFromResponse tests

    @Test
    fun `extractJsonFromResponse returns empty for null-like input`() {
        assertEquals("", LlmResponseParser.extractJsonFromResponse(""))
        assertEquals("", LlmResponseParser.extractJsonFromResponse("   "))
        assertEquals("", LlmResponseParser.extractJsonFromResponse("no json here"))
    }

    @Test
    fun `extractJsonFromResponse extracts simple JSON object`() {
        val input = """{"key": "value"}"""
        val result = LlmResponseParser.extractJsonFromResponse(input)
        assertEquals("""{"key": "value"}""", result)
    }

    @Test
    fun `extractJsonFromResponse removes markdown code block wrapper`() {
        val input = """```json
{"characters": ["Alice", "Bob"]}
```"""
        val result = LlmResponseParser.extractJsonFromResponse(input)
        assertEquals("""{"characters": ["Alice", "Bob"]}""", result)
    }

    @Test
    fun `extractJsonFromResponse handles code block without json specifier`() {
        val input = """```
{"data": 123}
```"""
        val result = LlmResponseParser.extractJsonFromResponse(input)
        assertEquals("""{"data": 123}""", result)
    }

    @Test
    fun `extractJsonFromResponse extracts JSON from text with surrounding content`() {
        val input = """Here is the analysis:
{"chapter_summary": {"title": "Test"}}
End of response."""
        val result = LlmResponseParser.extractJsonFromResponse(input)
        assertEquals("""{"chapter_summary": {"title": "Test"}}""", result)
    }

    // parseCharacterNamesFromResponse tests

    @Test
    fun `parseCharacterNamesFromResponse returns empty list for invalid input`() {
        assertEquals(emptyList<String>(), LlmResponseParser.parseCharacterNamesFromResponse(""))
        assertEquals(emptyList<String>(), LlmResponseParser.parseCharacterNamesFromResponse("not json"))
    }

    @Test
    fun `parseCharacterNamesFromResponse parses characters array`() {
        val input = """{"characters": ["Alice", "Bob", "Charlie"]}"""
        val result = LlmResponseParser.parseCharacterNamesFromResponse(input)
        assertEquals(listOf("Alice", "Bob", "Charlie"), result)
    }

    @Test
    fun `parseCharacterNamesFromResponse parses names array as fallback`() {
        val input = """{"names": ["Hero", "Villain"]}"""
        val result = LlmResponseParser.parseCharacterNamesFromResponse(input)
        assertEquals(listOf("Hero", "Villain"), result)
    }

    @Test
    fun `parseCharacterNamesFromResponse filters blank names`() {
        val input = """{"characters": ["Alice", "", "  ", "Bob"]}"""
        val result = LlmResponseParser.parseCharacterNamesFromResponse(input)
        assertEquals(listOf("Alice", "Bob"), result)
    }

    // parseTraitsFromResponse tests

    @Test
    fun `parseTraitsFromResponse returns empty list for invalid input`() {
        assertEquals(emptyList<String>(), LlmResponseParser.parseTraitsFromResponse(""))
        assertEquals(emptyList<String>(), LlmResponseParser.parseTraitsFromResponse("invalid"))
    }

    @Test
    fun `parseTraitsFromResponse parses traits array`() {
        val input = """{"traits": ["brave", "intelligent", "kind"]}"""
        val result = LlmResponseParser.parseTraitsFromResponse(input)
        assertEquals(listOf("brave", "intelligent", "kind"), result)
    }

    @Test
    fun `parseTraitsFromResponse filters blank traits`() {
        val input = """{"traits": ["brave", "", "kind"]}"""
        val result = LlmResponseParser.parseTraitsFromResponse(input)
        assertEquals(listOf("brave", "kind"), result)
    }

    // parseKeyMomentsFromResponse tests

    @Test
    fun `parseKeyMomentsFromResponse returns empty list for invalid input`() {
        assertEquals(emptyList<Map<String, String>>(), LlmResponseParser.parseKeyMomentsFromResponse(""))
    }

    @Test
    fun `parseKeyMomentsFromResponse parses moments array`() {
        val input = """{"moments": [
            {"chapter": "Ch1", "moment": "Discovery", "significance": "Plot turning point"}
        ]}"""
        val result = LlmResponseParser.parseKeyMomentsFromResponse(input)
        assertEquals(1, result.size)
        assertEquals("Ch1", result[0]["chapter"])
        assertEquals("Discovery", result[0]["moment"])
        assertEquals("Plot turning point", result[0]["significance"])
    }

    // parseRelationshipsFromResponse tests

    @Test
    fun `parseRelationshipsFromResponse returns empty list for invalid input`() {
        assertEquals(emptyList<Map<String, String>>(), LlmResponseParser.parseRelationshipsFromResponse(""))
    }

    @Test
    fun `parseRelationshipsFromResponse parses relationships array`() {
        val input = """{"relationships": [
            {"character": "Bob", "relationship": "friend", "nature": "supportive"}
        ]}"""
        val result = LlmResponseParser.parseRelationshipsFromResponse(input)
        assertEquals(1, result.size)
        assertEquals("Bob", result[0]["character"])
        assertEquals("friend", result[0]["relationship"])
        assertEquals("supportive", result[0]["nature"])
    }

    // parseDialogsFromResponse tests

    @Test
    fun `parseDialogsFromResponse returns empty list for invalid input`() {
        assertEquals(emptyList<Any>(), LlmResponseParser.parseDialogsFromResponse(""))
    }

    @Test
    fun `parseDialogsFromResponse parses dialogs array`() {
        val input = """{"dialogs": [
            {"speaker": "Alice", "text": "Hello!", "emotion": "happy", "intensity": 0.8}
        ]}"""
        val result = LlmResponseParser.parseDialogsFromResponse(input)
        assertEquals(1, result.size)
        assertEquals("Alice", result[0].speaker)
        assertEquals("Hello!", result[0].text)
        assertEquals("happy", result[0].emotion)
        assertEquals(0.8f, result[0].intensity, 0.01f)
    }
}

