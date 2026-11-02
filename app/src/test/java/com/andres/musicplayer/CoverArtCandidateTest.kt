package com.andres.musicplayer

import com.andres.musicplayer.data.youtube.rankThumbnails
import com.andres.musicplayer.download.readAtMost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.Image.ResolutionLevel
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * The two things standing between a downloaded track and its cover art: which thumbnails
 * are worth trying, and how much of one is safe to read.
 *
 * Both used to be a single point of failure. Exactly one URL was tried, so a video
 * without a `maxresdefault` — there are many — got no artwork at all even though a
 * perfectly good smaller image was on offer. And the size cap was read off
 * `Content-Length`, which a chunked response reports as -1.
 */
class CoverArtCandidateTest {

    // --- Ranking ---------------------------------------------------------------------

    @Test
    fun `every thumbnail is offered, biggest first`() {
        // A real video's set: the fallback chain needs all of them, in this order.
        val ranked = rankThumbnails(
            listOf(
                image("default.jpg", 120, 90, ResolutionLevel.LOW),
                image("maxresdefault.jpg", 1280, 720, ResolutionLevel.HIGH),
                image("mqdefault.jpg", 320, 180, ResolutionLevel.MEDIUM),
                image("hqdefault.jpg", 480, 360, ResolutionLevel.MEDIUM),
            ),
        )

        assertEquals(
            listOf("maxresdefault.jpg", "hqdefault.jpg", "mqdefault.jpg", "default.jpg"),
            ranked,
        )
    }

    @Test
    fun `images of unreported size sort last rather than being dropped`() {
        // Still candidates — a last resort beats no artwork.
        val ranked = rankThumbnails(
            listOf(
                image("unknown.jpg", 0, 0, ResolutionLevel.UNKNOWN),
                image("small.jpg", 120, 90, ResolutionLevel.LOW),
            ),
        )
        assertEquals(listOf("small.jpg", "unknown.jpg"), ranked)
    }

    @Test
    fun `the estimated level breaks ties when no dimensions are reported`() {
        val ranked = rankThumbnails(
            listOf(
                image("low.jpg", 0, 0, ResolutionLevel.LOW),
                image("high.jpg", 0, 0, ResolutionLevel.HIGH),
                image("medium.jpg", 0, 0, ResolutionLevel.MEDIUM),
            ),
        )
        assertEquals(listOf("high.jpg", "medium.jpg", "low.jpg"), ranked)
    }

    @Test
    fun `blank urls and duplicates never reach the fallback chain`() {
        val ranked = rankThumbnails(
            listOf(
                image("", 1280, 720, ResolutionLevel.HIGH),
                image("hq.jpg", 480, 360, ResolutionLevel.MEDIUM),
                image("hq.jpg", 480, 360, ResolutionLevel.MEDIUM),
            ),
        )
        assertEquals(listOf("hq.jpg"), ranked)
    }

    @Test
    fun `a video offering no images yields an empty list, not a null candidate`() {
        assertEquals(emptyList<String>(), rankThumbnails(null))
        assertEquals(emptyList<String>(), rankThumbnails(emptyList()))
    }

    // --- The size cap ------------------------------------------------------------------

    @Test
    fun `a stream inside the cap is read whole`() {
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        assertEquals(5000, readAtMost(ByteArrayInputStream(bytes), 8192)?.size)
    }

    @Test
    fun `a stream that never declares its length is still cut off at the cap`() {
        // The bug this replaces: `contentLength()` returns -1 for a chunked response,
        // and -1 is never greater than any cap, so nothing stopped the read at all.
        val bytes = ByteArray(20_000)
        assertNull(readAtMost(ByteArrayInputStream(bytes), 8192))
    }

    @Test
    fun `the cap counts total bytes, not bytes per read`() {
        // Many small reads must accumulate: a stream that dribbles out one byte at a
        // time would otherwise never trip a per-chunk check.
        val dribbling = object : InputStream() {
            private var remaining = 20_000
            override fun read(): Int = if (remaining-- > 0) 0x41 else -1
        }
        assertNull(readAtMost(dribbling, 8192))
    }

    @Test
    fun `exactly the cap is allowed, one byte more is not`() {
        assertEquals(8192, readAtMost(ByteArrayInputStream(ByteArray(8192)), 8192)?.size)
        assertNull(readAtMost(ByteArrayInputStream(ByteArray(8193)), 8192))
    }

    @Test
    fun `an empty response is not artwork`() {
        assertNull(readAtMost(ByteArrayInputStream(ByteArray(0)), 8192))
    }

    @Test
    fun `the stream is closed even when the cap is hit`() {
        // Abandoning an oversize download must not leak the connection.
        var closed = false
        val watched = object : InputStream() {
            private var remaining = 20_000
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun close() { closed = true }
        }
        assertNull(readAtMost(watched, 8192))
        assertEquals(true, closed)
    }

    @Test
    fun `a stream that fails mid-read is not swallowed here`() {
        // CoverArtFetcher catches this and moves to the next candidate; silently
        // returning a truncated image instead would embed a broken JPEG.
        val broken = object : InputStream() {
            private var served = 0
            override fun read(): Int {
                if (served++ > 10) throw IOException("connection reset")
                return 0
            }
        }
        val error = runCatching { readAtMost(broken, 8192) }.exceptionOrNull()
        assertNotNull(error)
    }

    private fun image(url: String, width: Int, height: Int, level: ResolutionLevel) =
        Image(url, height, width, level)
}
