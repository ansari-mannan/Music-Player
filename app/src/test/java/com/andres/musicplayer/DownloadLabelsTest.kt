package com.andres.musicplayer

import com.andres.musicplayer.data.local.entity.DownloadEntry
import com.andres.musicplayer.data.local.entity.DownloadEntryWithSource
import com.andres.musicplayer.ui.downloads.progressLabel
import com.andres.musicplayer.ui.downloads.sizeLabel
import com.andres.musicplayer.ui.downloads.sourceLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/** The strings the Download Manager puts in front of the user. */
class DownloadLabelsTest {

    private fun row(playlistTitle: String?, sourcePlaylistId: String?) =
        DownloadEntryWithSource(
            entry = DownloadEntry(
                id = "v1",
                videoId = "v1",
                title = "Song",
                thumbnailUrl = "",
                sourcePlaylistId = sourcePlaylistId,
            ),
            playlistTitle = playlistTitle,
        )

    @Test
    fun `a synced download names its playlist`() {
        assertEquals("Road Trip", sourceLabel(row("Road Trip", "PL1")))
    }

    @Test
    fun `a hand-started download says so instead of showing a blank`() {
        assertEquals("Added by hand", sourceLabel(row(null, null)))
    }

    @Test
    fun `a download whose playlist has gone is distinguished from a manual one`() {
        // The playlist row is gone (deleted on YouTube, or account disconnected) but the
        // history row survives — that is exactly why the foreign key was dropped.
        assertEquals("Playlist no longer synced", sourceLabel(row(null, "PL_GONE")))
    }

    @Test
    fun `progress shows a percentage once the total size is known`() {
        assertEquals("50% · 1.0 MB of 2.0 MB", progressLabel(1_048_576, 2_097_152))
        assertEquals("0% · 0.0 MB of 2.0 MB", progressLabel(0, 2_097_152))
        assertEquals("100% · 2.0 MB of 2.0 MB", progressLabel(2_097_152, 2_097_152))
    }

    @Test
    fun `progress omits the percentage while the total is unknown`() {
        assertEquals("1.0 MB", progressLabel(1_048_576, null))
        assertEquals("1.0 MB", progressLabel(1_048_576, 0))
    }

    @Test
    fun `a server over-reporting bytes cannot push the percentage past 100`() {
        assertEquals("100% · 3.0 MB of 2.0 MB", progressLabel(3_145_728, 2_097_152))
    }

    @Test
    fun `large files do not overflow the percentage arithmetic`() {
        // 4 GB downloaded of 8 GB: bytes * 100 must stay inside a Long.
        assertEquals("50% · 4096.0 MB of 8192.0 MB", progressLabel(4L shl 30, 8L shl 30))
    }

    @Test
    fun `size reads as a plain figure when there is no total`() {
        assertEquals("3.5 MB", sizeLabel(3_670_016, null))
        assertEquals("3.5 MB of 7.0 MB", sizeLabel(3_670_016, 7_340_032))
    }
}
