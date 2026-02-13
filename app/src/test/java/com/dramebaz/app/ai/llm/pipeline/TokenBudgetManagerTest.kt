package com.dramebaz.app.ai.llm.pipeline

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for TokenBudgetManager.
 * Tests token estimation, budget management, and text preparation.
 */
class TokenBudgetManagerTest {

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

    // Constants tests

    @Test
    fun `CHARS_PER_TOKEN is 4`() {
        assertEquals(4, TokenBudgetManager.CHARS_PER_TOKEN)
    }

    @Test
    fun `TOTAL_TOKEN_BUDGET is 4096`() {
        assertEquals(4096, TokenBudgetManager.TOTAL_TOKEN_BUDGET)
    }

    // PassTokenBudget tests

    @Test
    fun `PassTokenBudget calculates totalTokens correctly`() {
        val budget = TokenBudgetManager.PassTokenBudget(
            promptTokens = 200,
            inputTokens = 1000,
            outputTokens = 500
        )
        assertEquals(1700, budget.totalTokens)
    }

    @Test
    fun `PassTokenBudget calculates inputChars correctly`() {
        val budget = TokenBudgetManager.PassTokenBudget(
            promptTokens = 200,
            inputTokens = 100,
            outputTokens = 500
        )
        assertEquals(400, budget.inputChars) // 100 * 4
    }

    @Test
    fun `PassTokenBudget calculates outputChars correctly`() {
        val budget = TokenBudgetManager.PassTokenBudget(
            promptTokens = 200,
            inputTokens = 100,
            outputTokens = 250
        )
        assertEquals(1000, budget.outputChars) // 250 * 4
    }

    @Test(expected = IllegalArgumentException::class)
    fun `PassTokenBudget rejects budget exceeding total`() {
        TokenBudgetManager.PassTokenBudget(
            promptTokens = 2000,
            inputTokens = 2000,
            outputTokens = 2000
        )
    }

    // Pre-defined budgets tests

    @Test
    fun `PASS1_CHARACTER_EXTRACTION is within budget`() {
        val budget = TokenBudgetManager.PASS1_CHARACTER_EXTRACTION
        assertTrue(budget.totalTokens <= TokenBudgetManager.TOTAL_TOKEN_BUDGET)
    }

    @Test
    fun `PASS2_DIALOG_EXTRACTION is within budget`() {
        val budget = TokenBudgetManager.PASS2_DIALOG_EXTRACTION
        assertTrue(budget.totalTokens <= TokenBudgetManager.TOTAL_TOKEN_BUDGET)
    }

    @Test
    fun `PASS3_VOICE_PROFILE is within budget`() {
        val budget = TokenBudgetManager.PASS3_VOICE_PROFILE
        assertTrue(budget.totalTokens <= TokenBudgetManager.TOTAL_TOKEN_BUDGET)
    }

    // estimateTokens tests

    @Test
    fun `estimateTokens returns 0 for empty string`() {
        assertEquals(0, TokenBudgetManager.estimateTokens(""))
    }

    @Test
    fun `estimateTokens estimates correctly`() {
        assertEquals(1, TokenBudgetManager.estimateTokens("abc")) // 3 chars -> 1 token
        assertEquals(1, TokenBudgetManager.estimateTokens("abcd")) // 4 chars -> 1 token
        assertEquals(2, TokenBudgetManager.estimateTokens("abcde")) // 5 chars -> 2 tokens
        assertEquals(25, TokenBudgetManager.estimateTokens("A".repeat(100))) // 100 chars -> 25 tokens
    }

    // fitsWithinTokens tests

    @Test
    fun `fitsWithinTokens returns true for text under limit`() {
        assertTrue(TokenBudgetManager.fitsWithinTokens("Short", 10))
    }

    @Test
    fun `fitsWithinTokens returns false for text over limit`() {
        assertFalse(TokenBudgetManager.fitsWithinTokens("A".repeat(100), 10))
    }

    @Test
    fun `fitsWithinTokens returns true for exact limit`() {
        assertTrue(TokenBudgetManager.fitsWithinTokens("A".repeat(40), 10))
    }

    // prepareInputText tests

    @Test
    fun `prepareInputText returns original for short text`() {
        val budget = TokenBudgetManager.PassTokenBudget(
            promptTokens = 100,
            inputTokens = 1000, // 4000 chars
            outputTokens = 100
        )
        val shortText = "A short paragraph."
        val result = TokenBudgetManager.prepareInputText(shortText, budget)
        assertEquals(shortText, result)
    }

    @Test
    fun `prepareInputText truncates long text`() {
        val budget = TokenBudgetManager.PassTokenBudget(
            promptTokens = 100,
            inputTokens = 25, // 100 chars
            outputTokens = 100
        )
        val longText = "A".repeat(500)
        val result = TokenBudgetManager.prepareInputText(longText, budget)
        assertTrue(result.length <= budget.inputChars)
    }

    // getMaxInputChars tests

    @Test
    fun `getMaxInputChars returns correct value`() {
        val budget = TokenBudgetManager.PassTokenBudget(
            promptTokens = 100,
            inputTokens = 500, // 2000 chars
            outputTokens = 100
        )
        assertEquals(2000, TokenBudgetManager.getMaxInputChars(budget))
    }

    // getMaxOutputTokens tests

    @Test
    fun `getMaxOutputTokens returns correct value`() {
        val budget = TokenBudgetManager.PassTokenBudget(
            promptTokens = 100,
            inputTokens = 100,
            outputTokens = 750
        )
        assertEquals(750, TokenBudgetManager.getMaxOutputTokens(budget))
    }
}

