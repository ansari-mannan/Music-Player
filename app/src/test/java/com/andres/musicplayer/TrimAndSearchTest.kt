package com.andres.musicplayer

import com.andres.musicplayer.data.local.entity.Song
import com.andres.musicplayer.data.trim.AudioTrimmer
import com.andres.musicplayer.data.trim.TrimMode
import com.andres.musicplayer.data.trim.TrimPlan
import com.andres.musicplayer.ui.songs.SongSort
import com.andres.musicplayer.ui.songs.filterAndSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimAndSearchTest {

    // --- Trim segments ---------------------------------------------------------------

    @Test
    fun keepModeKeepsOnlyTheSelection() {
        val segments = AudioTrimmer.segments(TrimPlan(TrimMode.KEEP, 10_000, 200_000), 240_000)
        assertEquals(listOf(10_000L until 200_000L), segments)
        assertEquals(190_000L, AudioTrimmer.resultDurationMs(TrimPlan(TrimMode.KEEP, 10_000, 200_000), 240_000))
    }

    @Test
    fun cutModeKeepsBothSidesOfTheSelection() {
        val plan = TrimPlan(TrimMode.CUT, 60_000, 90_000)
        assertEquals(listOf(0L until 60_000L, 90_000L until 240_000L), AudioTrimmer.segments(plan, 240_000))
        assertEquals(210_000L, AudioTrimmer.resultDurationMs(plan, 240_000))
    }

    @Test
    fun cutTouchingAnEdgeLeavesOneSegment() {
        // Cutting the intro: nothing before it to keep.
        val plan = TrimPlan(TrimMode.CUT, 0, 30_000)
        assertEquals(listOf(30_000L until 240_000L), AudioTrimmer.segments(plan, 240_000))
    }

    @Test
    fun handlesOutsideTheTrackAreClamped() {
        val plan = TrimPlan(TrimMode.KEEP, -500, 999_999)
        assertEquals(listOf(0L until 240_000L), AudioTrimmer.segments(plan, 240_000))
    }

    @Test
    fun slivers_are_dropped_rather_than_exported() {
        // A 20 ms leftover before the cut is noise, not music.
        val plan = TrimPlan(TrimMode.CUT, 20, 100_000)
        assertEquals(listOf(100_000L until 240_000L), AudioTrimmer.segments(plan, 240_000))
    }

    // --- Search & sort ---------------------------------------------------------------

    private val songs = listOf(
        Song(id = 1, uri = "a", title = "Bohemian Rhapsody", artist = "Queen", album = "A Night at the Opera", durationMs = 355_000, dateAdded = 3),
        Song(id = 2, uri = "b", title = "Yellow", artist = "Coldplay", album = "Parachutes", durationMs = 266_000, dateAdded = 1),
        Song(id = 3, uri = "c", title = "Live Intro", artist = null, album = null, durationMs = 60_000, dateAdded = 2),
    )

    @Test
    fun searchMatchesEveryWordAcrossFields() {
        assertEquals(listOf(1L), filterAndSort(songs, "queen opera", SongSort.TITLE).map { it.id })
        assertEquals(listOf(2L), filterAndSort(songs, "  YELLOW ", SongSort.TITLE).map { it.id })
        assertTrue(filterAndSort(songs, "queen yellow", SongSort.TITLE).isEmpty())
    }

    @Test
    fun blankSearchShowsEverything() {
        assertEquals(3, filterAndSort(songs, "", SongSort.RECENT).size)
    }

    @Test
    fun sortsAsAdvertised() {
        assertEquals(listOf(1L, 3L, 2L), filterAndSort(songs, "", SongSort.RECENT).map { it.id })
        assertEquals(listOf(1L, 3L, 2L), filterAndSort(songs, "", SongSort.TITLE).map { it.id })
        // Songs without an artist go last rather than first.
        assertEquals(listOf(2L, 1L, 3L), filterAndSort(songs, "", SongSort.ARTIST).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), filterAndSort(songs, "", SongSort.LONGEST).map { it.id })
    }
}
