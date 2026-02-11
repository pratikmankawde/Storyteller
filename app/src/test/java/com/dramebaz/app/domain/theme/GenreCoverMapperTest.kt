package com.dramebaz.app.domain.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class GenreCoverMapperTest {

    @Test
    fun `returns_literature_cover_for_null_or_blank`() {
        assertEquals("images/bookcovers/Literature.png", GenreCoverMapper.mapGenreToCoverPath(null))
        assertEquals("images/bookcovers/Literature.png", GenreCoverMapper.mapGenreToCoverPath("  "))
    }

    @Test
    fun `maps_canonical_fantasy_and_scifi`() {
        assertEquals("images/bookcovers/Fantasy.png", GenreCoverMapper.mapGenreToCoverPath("fantasy"))

        assertEquals("images/bookcovers/Sci-Fi.png", GenreCoverMapper.mapGenreToCoverPath("scifi"))
        assertEquals("images/bookcovers/Sci-Fi.png", GenreCoverMapper.mapGenreToCoverPath("sci-fi"))
        assertEquals("images/bookcovers/Sci-Fi.png", GenreCoverMapper.mapGenreToCoverPath("science_fiction"))
    }

    @Test
    fun `maps_thriller_and_mystery_to_mystery_cover`() {
        assertEquals("images/bookcovers/Mystery.png", GenreCoverMapper.mapGenreToCoverPath("thriller"))
        assertEquals("images/bookcovers/Mystery.png", GenreCoverMapper.mapGenreToCoverPath("mystery"))
    }

    @Test
    fun `maps_classic_and_modern_fiction_to_literature_cover`() {
        assertEquals("images/bookcovers/Literature.png", GenreCoverMapper.mapGenreToCoverPath("classic_literature"))
        assertEquals("images/bookcovers/Literature.png", GenreCoverMapper.mapGenreToCoverPath("modern_fiction"))
    }
}

