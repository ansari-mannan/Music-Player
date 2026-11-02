package com.andres.musicplayer.download

import com.andres.musicplayer.data.youtube.MusicMetadata

/** Metadata to write into a downloaded file. A null field is left as the file has it. */
internal data class TrackTags(
    val title: String,
    val artist: String?,
    val album: String?,
    /**
     * True when YouTube Music told us what this is, false when it was worked out from the
     * title. Only used to say which path won in the log — the two are indistinguishable
     * in the finished file, which is exactly why the log has to say.
     */
    val fromMusicMetadata: Boolean = false,
)

/**
 * Works out sensible track metadata from the three things YouTube actually tells us: the
 * video title, the channel that uploaded it, and (for a synced download) the playlist it
 * came from.
 *
 * There is no album field anywhere in a YouTube video's metadata, so the playlist name is
 * used — for a library built entirely out of synced playlists that groups tracks the way
 * the user themselves grouped them, and a hand-added download simply gets no album.
 *
 * The artist is the interesting one, and comes from whichever source is most trustworthy:
 *
 * - An **"… - Topic"** channel is YouTube Music's auto-generated upload for a real
 *   release. The channel name is the artist, exactly, and the video title is already just
 *   the song name — nothing to parse.
 * - Otherwise a **"Artist - Title"** video title is the near-universal convention, and is
 *   more useful than the channel, which is typically a label or a VEVO account
 *   ("DaftPunkVEVO") rather than a name anyone wants to browse by.
 * - Failing both, the channel name is better than nothing.
 *
 * Nothing is stripped from the title — "(Official Video)" is noise, but "(Remix)",
 * "(Live)" and "(feat. …)" are not, and no rule tells them apart reliably. The file name
 * on disk keeps the full original title either way, so a wrong guess here is always
 * recoverable.
 *
 * When [music] is present it is what YouTube Music itself says the track is, which beats
 * every guess below — but only field by field. A response that names an artist and no
 * album must not wipe out the playlist name that would otherwise have been the album.
 */
internal fun deriveTags(
    videoTitle: String,
    uploader: String?,
    playlistTitle: String?,
    music: MusicMetadata? = null,
): TrackTags {
    val guessed = guessTags(videoTitle, uploader, playlistTitle)
    if (music == null || !music.isUsable) return guessed

    return TrackTags(
        title = music.track?.trimOrNull() ?: guessed.title,
        artist = music.artist?.trimOrNull() ?: guessed.artist,
        album = music.album?.trimOrNull() ?: guessed.album,
        fromMusicMetadata = true,
    )
}

/** The title/channel/playlist heuristics, with nothing but YouTube's own words to go on. */
private fun guessTags(
    videoTitle: String,
    uploader: String?,
    playlistTitle: String?,
): TrackTags {
    val title = videoTitle.trim()
    val channel = uploader?.trimOrNull()
    val album = playlistTitle?.trimOrNull()

    val topicArtist = channel?.takeIf { it.endsWith(TOPIC_SUFFIX, ignoreCase = true) }
        ?.dropLast(TOPIC_SUFFIX.length)
        ?.trimOrNull()
    if (topicArtist != null) {
        return TrackTags(title = title, artist = topicArtist, album = album)
    }

    val split = splitArtistAndTitle(title)
    return TrackTags(
        title = split?.second ?: title,
        artist = split?.first ?: channel,
        album = album,
    )
}

private fun String.trimOrNull(): String? = trim().takeIf { it.isNotEmpty() }

/**
 * Splits `"Artist - Song"` into its two halves, or returns null when the title isn't
 * shaped like that.
 *
 * The separator has to have spaces on both sides, so hyphenated words ("Jay-Z",
 * "Re-Wired") are never cut in half. Only the first separator counts: in
 * "Artist - Song - Live" the artist is the part before the first one.
 */
private fun splitArtistAndTitle(title: String): Pair<String, String>? {
    val at = SEPARATOR.find(title) ?: return null
    val artist = title.substring(0, at.range.first).trim()
    val song = title.substring(at.range.last + 1).trim()
    if (artist.isEmpty() || song.isEmpty()) return null
    // A long left-hand side is a sentence, not a name — most likely a title that merely
    // happens to contain a dash ("Every song I heard this summer - a mix").
    if (artist.length > MAX_ARTIST_LENGTH) return null
    return artist to song
}

/** Hyphen, en dash or em dash, with whitespace either side. */
private val SEPARATOR = Regex("""\s+[-–—]\s+""")

private const val TOPIC_SUFFIX = " - Topic"
private const val MAX_ARTIST_LENGTH = 60
