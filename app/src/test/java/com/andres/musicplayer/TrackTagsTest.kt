package com.andres.musicplayer

import com.andres.musicplayer.data.youtube.MusicMetadata
import com.andres.musicplayer.download.deriveTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a downloaded file ends up called in the library.
 *
 * These are guesses by nature — YouTube has no artist or album field — so what the tests
 * pin is that the guesses are made in the right order of trust, and that the risky one
 * (splitting a title on a dash) refuses the cases where it would do damage.
 */
class TrackTagsTest {

    @Test
    fun `a Topic channel is the artist, and its title is left alone`() {
        // Auto-generated YouTube Music upload: the channel name is the real artist and
        // the video title is already just the song.
        val tags = deriveTags("Around the World", "Daft Punk - Topic", "Road Trip")
        assertEquals("Around the World", tags.title)
        assertEquals("Daft Punk", tags.artist)
        assertEquals("Road Trip", tags.album)
    }

    @Test
    fun `a Topic channel beats a dash in the title`() {
        // The channel is authoritative; the title's dash is part of the song name.
        val tags = deriveTags("Sing - Live at Wembley", "Ed Sheeran - Topic", null)
        assertEquals("Sing - Live at Wembley", tags.title)
        assertEquals("Ed Sheeran", tags.artist)
    }

    @Test
    fun `an Artist - Title video splits, in preference to the uploading channel`() {
        // "DaftPunkVEVO" is a label account, not a name worth browsing by.
        val tags = deriveTags("Daft Punk - Around the World", "DaftPunkVEVO", null)
        assertEquals("Around the World", tags.title)
        assertEquals("Daft Punk", tags.artist)
        assertNull(tags.album)
    }

    @Test
    fun `only the first separator splits`() {
        val tags = deriveTags("Queen - Bohemian Rhapsody - Live Aid 1985", "Queen Official", null)
        assertEquals("Queen", tags.artist)
        assertEquals("Bohemian Rhapsody - Live Aid 1985", tags.title)
    }

    @Test
    fun `en and em dashes separate too`() {
        assertEquals("Sia", deriveTags("Sia – Chandelier", null, null).artist)
        assertEquals("Sia", deriveTags("Sia — Chandelier", null, null).artist)
    }

    @Test
    fun `hyphenated names are never cut in half`() {
        // The separator needs whitespace on both sides, or every Jay-Z track loses a Z.
        val tags = deriveTags("Jay-Z 99 Problems", "HipHopChannel", null)
        assertEquals("Jay-Z 99 Problems", tags.title)
        assertEquals("HipHopChannel", tags.artist)
    }

    @Test
    fun `a long left-hand side is a sentence, not an artist`() {
        val title = "Every single song I listened to during the summer of 2019 in order - a mix"
        val tags = deriveTags(title, "SomeChannel", null)
        assertEquals("the whole title should survive", title, tags.title)
        assertEquals("SomeChannel", tags.artist)
    }

    @Test
    fun `the channel is used when the title says nothing`() {
        val tags = deriveTags("Chandelier", "Sia", null)
        assertEquals("Chandelier", tags.title)
        assertEquals("Sia", tags.artist)
    }

    @Test
    fun `no channel and no separator means no artist rather than a bad guess`() {
        val tags = deriveTags("Some Song", null, null)
        assertEquals("Some Song", tags.title)
        assertNull(tags.artist)
    }

    @Test
    fun `blank inputs never become blank tags`() {
        // Mp4Tag reads null as "leave this field alone"; an empty string would wipe it.
        val tags = deriveTags("  Song  ", "   ", "  ")
        assertEquals("Song", tags.title)
        assertNull(tags.artist)
        assertNull(tags.album)
    }

    @Test
    fun `a dangling separator does not produce an empty half`() {
        assertEquals("Song -", deriveTags("Song -", null, null).title)
        assertEquals("- Song", deriveTags("- Song", null, null).title)
        assertNull(deriveTags("Song -", null, null).artist)
    }

    @Test
    fun `noise in brackets is kept, because a rule that removed it would remove more`() {
        // "(Official Video)" is junk but "(Remix)" and "(feat. …)" are not, and nothing
        // distinguishes them reliably.
        val tags = deriveTags("Daft Punk - Around the World (Official Video)", null, null)
        assertEquals("Around the World (Official Video)", tags.title)
    }

    @Test
    fun `a hand-added download gets no album`() {
        assertNull(deriveTags("Some Song", "Some Channel", null).album)
    }

    // --- When YouTube Music has a real answer ------------------------------------------

    @Test
    fun `real music metadata beats every guess`() {
        // The title says "DJ Snake - Taki Taki", the channel is a label, and the sync
        // pass would have called the album "Road Trip". YouTube Music knows better.
        val tags = deriveTags(
            videoTitle = "DJ Snake - Taki Taki (Official Video)",
            uploader = "DJSnakeVEVO",
            playlistTitle = "Road Trip",
            music = MusicMetadata(
                track = "Taki Taki",
                artist = "DJ Snake, Selena Gomez, Ozuna, Cardi B",
                album = "Carte Blanche",
            ),
        )

        assertEquals("Taki Taki", tags.title)
        assertEquals("DJ Snake, Selena Gomez, Ozuna, Cardi B", tags.artist)
        assertEquals("Carte Blanche", tags.album)
        assertTrue("the log has to be able to say which path won", tags.fromMusicMetadata)
    }

    @Test
    fun `a missing album falls back to the playlist rather than wiping it`() {
        // Field by field, not all-or-nothing: a lookup that names only the artist must
        // not cost the track the album the playlist would have given it.
        val tags = deriveTags(
            videoTitle = "Some Song",
            uploader = "Some Channel",
            playlistTitle = "Road Trip",
            music = MusicMetadata(artist = "The Real Artist"),
        )

        assertEquals("The Real Artist", tags.artist)
        assertEquals("Road Trip", tags.album)
        assertEquals("Some Song", tags.title)
    }

    @Test
    fun `an empty lookup leaves the heuristics exactly as they were`() {
        val heuristic = deriveTags("Daft Punk - Around the World", "DaftPunkVEVO", "Road Trip")
        listOf(null, MusicMetadata(), MusicMetadata(track = "Around the World")).forEach { music ->
            val tags = deriveTags(
                videoTitle = "Daft Punk - Around the World",
                uploader = "DaftPunkVEVO",
                playlistTitle = "Road Trip",
                music = music,
            )
            assertEquals("failed for: $music", heuristic.copy(), tags)
            assertFalse(tags.fromMusicMetadata)
        }
    }

    @Test
    fun `blank music fields never overwrite a good guess with nothing`() {
        val tags = deriveTags(
            videoTitle = "Daft Punk - Around the World",
            uploader = null,
            playlistTitle = "Road Trip",
            music = MusicMetadata(track = "  ", artist = "Daft Punk", album = "   "),
        )

        assertEquals("Around the World", tags.title)
        assertEquals("Daft Punk", tags.artist)
        assertEquals("Road Trip", tags.album)
    }
}
