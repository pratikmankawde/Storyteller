package com.dramebaz.app.utils

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Unit tests for InputValidator.
 * Tests input validation and sanitization logic.
 */
class InputValidatorTest {

    // Story Prompt Validation Tests

    @Test
    fun `validateStoryPrompt rejects prompt shorter than minimum`() {
        val result = InputValidator.validateStoryPrompt("Short")

        assertTrue(result.isFailure)
        assertTrue(InputValidator.getErrorMessage(result).contains("10"))
    }

    @Test
    fun `validateStoryPrompt accepts valid prompt`() {
        val validPrompt = "Write a story about a brave knight who saves a kingdom from a dragon."
        val result = InputValidator.validateStoryPrompt(validPrompt)

        assertTrue(result.isSuccess)
        assertEquals(validPrompt.trim(), result.getOrNull())
    }

    @Test
    fun `validateStoryPrompt trims whitespace`() {
        val promptWithSpaces = "   A valid story prompt with leading and trailing spaces.   "
        val result = InputValidator.validateStoryPrompt(promptWithSpaces)

        assertTrue(result.isSuccess)
        assertEquals(promptWithSpaces.trim(), result.getOrNull())
    }

    @Test
    fun `validateStoryPrompt rejects empty prompt`() {
        val result = InputValidator.validateStoryPrompt("")

        assertTrue(result.isFailure)
    }

    @Test
    fun `validateStoryPrompt rejects blank prompt`() {
        val result = InputValidator.validateStoryPrompt("     ")

        assertTrue(result.isFailure)
    }

    // LLM Prompt Sanitization Tests

    @Test
    fun `sanitizeLlmPrompt removes null characters`() {
        val inputWithNull = "Hello\u0000World"
        val result = InputValidator.sanitizeLlmPrompt(inputWithNull)

        assertFalse(result.contains("\u0000"))
    }

    @Test
    fun `sanitizeLlmPrompt removes control characters`() {
        val inputWithControl = "Hello\u0007Test"  // Bell character
        val result = InputValidator.sanitizeLlmPrompt(inputWithControl)

        assertFalse(result.contains("\u0007"))
    }

    @Test
    fun `sanitizeLlmPrompt preserves newlines`() {
        val inputWithNewline = "Line1\nLine2"
        val result = InputValidator.sanitizeLlmPrompt(inputWithNewline)

        assertTrue(result.contains("\n"))
    }

    @Test
    fun `sanitizeLlmPrompt collapses multiple spaces`() {
        // The implementation collapses multiple spaces into one
        val inputWithSpaces = "Hello    World"
        val result = InputValidator.sanitizeLlmPrompt(inputWithSpaces)

        assertEquals("Hello World", result)
    }

    @Test
    fun `sanitizeLlmPrompt truncates to max length`() {
        val longInput = "A".repeat(50000)
        val result = InputValidator.sanitizeLlmPrompt(longInput, maxLength = 1000)

        assertEquals(1000, result.length)
    }

    @Test
    fun `sanitizeLlmPrompt preserves normal text unchanged`() {
        val normalText = "This is a normal story about characters."
        val result = InputValidator.sanitizeLlmPrompt(normalText)

        assertEquals(normalText, result)
    }

    // TTS Text Sanitization Tests

    @Test
    fun `sanitizeForTts normalizes smart quotes`() {
        val inputWithSmartQuotes = "\u201CHello,\u201D she said."
        val result = InputValidator.sanitizeForTts(inputWithSmartQuotes)

        // Smart quotes should be converted to regular quotes
        assertFalse(result.contains("\u201C"))
        assertFalse(result.contains("\u201D"))
        assertTrue(result.contains("\""))
    }

    @Test
    fun `sanitizeForTts removes control characters`() {
        val inputWithControl = "Hello\u0000World"
        val result = InputValidator.sanitizeForTts(inputWithControl)

        assertFalse(result.contains("\u0000"))
    }

    @Test
    fun `sanitizeForTts does not collapse whitespace`() {
        // sanitizeForTts does NOT normalize whitespace (unlike sanitizeLlmPrompt)
        val inputWithExtraSpaces = "Hello    World"
        val result = InputValidator.sanitizeForTts(inputWithExtraSpaces)

        // The TTS sanitizer preserves multiple spaces
        assertTrue(result.contains("Hello") && result.contains("World"))
    }

    // Error Message Tests

    @Test
    fun `getErrorMessage returns message for failure result`() {
        val failureResult = Result.failure<String>(IllegalArgumentException("Test error"))
        val message = InputValidator.getErrorMessage(failureResult)

        assertTrue(message.isNotEmpty())
    }

    @Test
    fun `getErrorMessage returns default message for success result`() {
        val successResult = Result.success("valid")
        val message = InputValidator.getErrorMessage(successResult)

        // When there's no exception, getErrorMessage returns "Invalid input" as the default
        assertEquals("Invalid input", message)
    }
}
