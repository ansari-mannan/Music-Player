package com.andres.musicplayer

import com.andres.musicplayer.download.cropOrigin
import com.andres.musicplayer.download.sanitiseFileName
import com.andres.musicplayer.download.squareSide
import com.andres.musicplayer.ui.downloads.parseVideoId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure functions in the download path that are easy to get subtly wrong: what a
 * pasted link resolves to, what a video title turns into on a filesystem, and where a
 * 16:9 thumbnail gets cut to a square.
 */
class DownloadUtilsTest {

    @Test
    fun `parses every shape of youtube link`() {
        val id = "dQw4w9WgXcQ"
        listOf(
            "https://www.youtube.com/watch?v=$id",
            "https://youtube.com/watch?v=$id&list=PL123",
            "https://m.youtube.com/watch?v=$id",
            "https://music.youtube.com/watch?v=$id&si=abc",
            "https://youtu.be/$id",
            "https://youtu.be/$id?t=42",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/embed/$id",
            "https://www.youtube.com/live/$id",
            id,
            "  $id  ",
        ).forEach { input ->
            assertEquals("failed for: $input", id, parseVideoId(input))
        }
    }

    @Test
    fun `rejects things that are not video links`() {
        listOf(
            "",
            "   ",
            "not a link",
            "https://example.com/watch?v=tooshort",
            // A playlist link carries no video id of its own.
            "https://www.youtube.com/playlist?list=PLabcdefghijk",
        ).forEach { input ->
            assertNull("should not have parsed: $input", parseVideoId(input))
        }
    }

    @Test
    fun `strips characters a filesystem rejects`() {
        assertEquals("AC DC Back In Black", sanitiseFileName("AC/DC: Back In Black"))
        // Every character Android/FAT rejects, including a literal backslash.
        assertEquals("what", sanitiseFileName("what?<>|*\":\\"))
        assertEquals("a b", sanitiseFileName("a\\b"))
        assertEquals("tabs and newlines", sanitiseFileName("tabs\tand\nnewlines"))
    }

    @Test
    fun `keeps punctuation that is perfectly legal`() {
        // Hyphens, brackets and apostrophes are all over real titles — an earlier
        // version of this ate hyphens, which mangled nearly every "Artist - Title".
        assertEquals(
            "Daft Punk - Around the World (Official Video)",
            sanitiseFileName("Daft Punk - Around the World (Official Video)"),
        )
        assertEquals("Don't Stop Me Now", sanitiseFileName("Don't Stop Me Now"))
        assertEquals("Song [Remix] #1 & more", sanitiseFileName("Song [Remix] #1 & more"))
    }

    @Test
    fun `collapses whitespace and never ends in a dot or space`() {
        assertEquals("a b", sanitiseFileName("a    b"))
        assertEquals("trailing", sanitiseFileName("trailing...   "))
    }

    @Test
    fun `never returns an empty or overlong name`() {
        assertEquals("download", sanitiseFileName(""))
        assertEquals("download", sanitiseFileName("///:::"))
        val long = sanitiseFileName("x".repeat(500))
        assertEquals(120, long.length)
    }

    @Test
    fun `a 16 by 9 thumbnail crops to a centred square`() {
        // maxresdefault: the crop takes the full height and trims 280px off each side.
        assertEquals(720, squareSide(1280, 720))
        assertEquals(280, cropOrigin(1280, 720))
        assertEquals(0, cropOrigin(720, 720))
    }

    @Test
    fun `an already square image is left where it is`() {
        assertEquals(500, squareSide(500, 500))
        assertEquals(0, cropOrigin(500, 500))
    }

    @Test
    fun `a taller than wide image crops vertically instead`() {
        assertEquals(300, squareSide(300, 900))
        assertEquals(0, cropOrigin(300, 300))
        assertEquals(300, cropOrigin(900, 300))
    }

    @Test
    fun `an odd number of spare pixels never runs past the edge`() {
        // 101 wide, 100 tall: one spare column. Rounding up would ask for pixels 1..101.
        assertEquals(100, squareSide(101, 100))
        assertEquals(0, cropOrigin(101, 100))
    }

    @Test
    fun `a degenerate image is refused rather than cropped`() {
        assertEquals(0, squareSide(0, 720))
        assertEquals(0, squareSide(-1, -1))
    }
}
