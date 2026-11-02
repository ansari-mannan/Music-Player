package com.andres.musicplayer

import com.andres.musicplayer.data.tags.Mp4Tag
import com.andres.musicplayer.data.youtube.pickBestAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod

/**
 * Which of YouTube's audio streams a download actually takes.
 *
 * This is where cover art, artist and album were being lost. YouTube's Opus stream always
 * out-bitrates its AAC one, so a bitrate-first choice picked webm every time — and no tag
 * writer in this app, or in Android's own `MediaMetadataRetriever`, can read or write
 * metadata in a webm. The download looked perfect and the file arrived anonymous.
 *
 * The bitrates below are the ones YouTube really reports for these itags, so the first
 * test is the exact situation a normal music video presents.
 */
class AudioStreamChoiceTest {

    @Test
    fun `a taggable m4a is chosen over a higher-bitrate opus`() {
        val chosen = pickBestAudio(
            listOf(
                stream("249", MediaFormat.WEBMA_OPUS, 50),
                stream("250", MediaFormat.WEBMA_OPUS, 70),
                stream("139", MediaFormat.M4A, 48),
                stream("140", MediaFormat.M4A, 128),
                stream("251", MediaFormat.WEBMA_OPUS, 160),
            ),
        )

        assertEquals(
            "the 160 kbit/s Opus stream wins on bitrate but cannot carry a tag",
            MediaFormat.M4A,
            chosen?.format,
        )
        assertEquals(128, chosen?.averageBitrate)
    }

    @Test
    fun `whatever is chosen for a normal video is something the tag writer accepts`() {
        // The property the rest of the download path depends on, stated directly: the
        // engine only tags containers in this set, and skips everything else in silence.
        val chosen = pickBestAudio(
            listOf(
                stream("251", MediaFormat.WEBMA_OPUS, 160),
                stream("140", MediaFormat.M4A, 128),
            ),
        )
        assertTrue(chosen?.format?.suffix in Mp4Tag.EXTENSIONS)
    }

    @Test
    fun `bitrate still decides between two m4a streams`() {
        val chosen = pickBestAudio(
            listOf(
                stream("139", MediaFormat.M4A, 48),
                stream("141", MediaFormat.M4A, 256),
                stream("140", MediaFormat.M4A, 128),
            ),
        )
        assertEquals(256, chosen?.averageBitrate)
    }

    @Test
    fun `a video offering no m4a still gets its best stream rather than nothing`() {
        // Preferring m4a must not become requiring it — that would fail the download
        // outright over a container choice the user never made.
        val chosen = pickBestAudio(
            listOf(
                stream("249", MediaFormat.WEBMA_OPUS, 50),
                stream("251", MediaFormat.WEBMA_OPUS, 160),
            ),
        )
        assertEquals(MediaFormat.WEBMA_OPUS, chosen?.format)
        assertEquals(160, chosen?.averageBitrate)
    }

    @Test
    fun `a stream with no URL is never chosen, however good it looks`() {
        val chosen = pickBestAudio(
            listOf(
                stream("140", MediaFormat.M4A, 128, content = ""),
                stream("251", MediaFormat.WEBMA_OPUS, 160),
            ),
        )
        assertEquals("an empty content URL cannot be downloaded", "251", chosen?.id)
    }

    @Test
    fun `nothing on offer means nothing chosen`() {
        assertNull(pickBestAudio(null))
        assertNull(pickBestAudio(emptyList()))
        assertNull(pickBestAudio(listOf(stream("140", MediaFormat.M4A, 128, content = ""))))
    }

    private fun stream(
        itag: String,
        format: MediaFormat,
        bitrate: Int,
        content: String = "https://rr1.googlevideo.com/videoplayback?itag=$itag",
    ): AudioStream = AudioStream.Builder()
        .setId(itag)
        .setContent(content, true)
        .setMediaFormat(format)
        .setAverageBitrate(bitrate)
        .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
        .build()
}
