package com.dramebaz.app.data.models

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ReadingMode enum.
 * Tests reading mode properties and conversion methods.
 */
class ReadingModeTest {

    // Enum properties tests

    @Test
    fun `TEXT mode has correct display name`() {
        assertEquals("Text", ReadingMode.TEXT.displayName)
    }

    @Test
    fun `AUDIO mode has correct display name`() {
        assertEquals("Audio", ReadingMode.AUDIO.displayName)
    }

    @Test
    fun `MIXED mode has correct display name`() {
        assertEquals("Mixed", ReadingMode.MIXED.displayName)
    }

    @Test
    fun `all modes have non-empty icon resource names`() {
        ReadingMode.entries.forEach { mode ->
            assertTrue(mode.iconResName.isNotBlank())
        }
    }

    @Test
    fun `all modes have non-empty descriptions`() {
        ReadingMode.entries.forEach { mode ->
            assertTrue(mode.description.isNotBlank())
        }
    }

    // fromLegacyString tests

    @Test
    fun `fromLegacyString converts reading to TEXT`() {
        assertEquals(ReadingMode.TEXT, ReadingMode.fromLegacyString("reading"))
    }

    @Test
    fun `fromLegacyString converts listening to AUDIO`() {
        assertEquals(ReadingMode.AUDIO, ReadingMode.fromLegacyString("listening"))
    }

    @Test
    fun `fromLegacyString converts mixed to MIXED`() {
        assertEquals(ReadingMode.MIXED, ReadingMode.fromLegacyString("mixed"))
    }

    @Test
    fun `fromLegacyString is case insensitive`() {
        assertEquals(ReadingMode.TEXT, ReadingMode.fromLegacyString("READING"))
        assertEquals(ReadingMode.TEXT, ReadingMode.fromLegacyString("Reading"))
        assertEquals(ReadingMode.AUDIO, ReadingMode.fromLegacyString("LISTENING"))
    }

    @Test
    fun `fromLegacyString converts text to TEXT`() {
        assertEquals(ReadingMode.TEXT, ReadingMode.fromLegacyString("text"))
    }

    @Test
    fun `fromLegacyString converts audio to AUDIO`() {
        assertEquals(ReadingMode.AUDIO, ReadingMode.fromLegacyString("audio"))
    }

    @Test
    fun `fromLegacyString returns MIXED for null`() {
        assertEquals(ReadingMode.MIXED, ReadingMode.fromLegacyString(null))
    }

    @Test
    fun `fromLegacyString returns MIXED for unknown string`() {
        assertEquals(ReadingMode.MIXED, ReadingMode.fromLegacyString("unknown"))
        assertEquals(ReadingMode.MIXED, ReadingMode.fromLegacyString(""))
        assertEquals(ReadingMode.MIXED, ReadingMode.fromLegacyString("invalid"))
    }

    // toLegacyString tests

    @Test
    fun `toLegacyString converts TEXT to reading`() {
        assertEquals("reading", ReadingMode.toLegacyString(ReadingMode.TEXT))
    }

    @Test
    fun `toLegacyString converts AUDIO to listening`() {
        assertEquals("listening", ReadingMode.toLegacyString(ReadingMode.AUDIO))
    }

    @Test
    fun `toLegacyString converts MIXED to mixed`() {
        assertEquals("mixed", ReadingMode.toLegacyString(ReadingMode.MIXED))
    }

    @Test
    fun `toLegacyString and fromLegacyString are reversible`() {
        ReadingMode.entries.forEach { mode ->
            val legacy = ReadingMode.toLegacyString(mode)
            val restored = ReadingMode.fromLegacyString(legacy)
            assertEquals(mode, restored)
        }
    }

    // next tests

    @Test
    fun `next cycles TEXT to AUDIO`() {
        assertEquals(ReadingMode.AUDIO, ReadingMode.next(ReadingMode.TEXT))
    }

    @Test
    fun `next cycles AUDIO to MIXED`() {
        assertEquals(ReadingMode.MIXED, ReadingMode.next(ReadingMode.AUDIO))
    }

    @Test
    fun `next cycles MIXED to TEXT`() {
        assertEquals(ReadingMode.TEXT, ReadingMode.next(ReadingMode.MIXED))
    }

    @Test
    fun `next cycles through all modes and returns to start`() {
        var current = ReadingMode.TEXT
        current = ReadingMode.next(current) // AUDIO
        current = ReadingMode.next(current) // MIXED
        current = ReadingMode.next(current) // TEXT
        
        assertEquals(ReadingMode.TEXT, current)
    }

    // all tests

    @Test
    fun `all returns all three modes in order`() {
        val modes = ReadingMode.all()
        
        assertEquals(3, modes.size)
        assertEquals(ReadingMode.TEXT, modes[0])
        assertEquals(ReadingMode.AUDIO, modes[1])
        assertEquals(ReadingMode.MIXED, modes[2])
    }
}

