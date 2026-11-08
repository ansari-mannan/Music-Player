package com.andres.musicplayer.data.tags

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.andres.musicplayer.data.id3.Id3Tag

/**
 * Writes song metadata into the file itself, picking the right tag format for the
 * container. There is no universal one: MP3 carries ID3 frames, MP4/M4A carries iTunes
 * atoms in its box tree, and the two have nothing in common beyond intent.
 *
 * Formats with a third scheme again (Vorbis comments in Opus/Ogg, Matroska tags in
 * WebM) are deliberately unsupported — they'd each need their own writer, and the
 * downloader prefers m4a, so they're rare. [supports] lets callers report honestly
 * rather than silently doing nothing.
 */
object AudioTags {

    /** True if metadata can actually be written into this file's container. */
    fun supports(context: Context, uriString: String): Boolean =
        formatOf(context, uriString) != Format.UNSUPPORTED

    /**
     * Writes the given fields into the file. Non-null values replace what's there; null
     * leaves that field untouched. Returns false when the container has no writer, the
     * uri isn't writable, or the file couldn't be parsed — never a partial write.
     */
    fun writeTags(
        context: Context,
        uriString: String,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        artwork: ByteArray? = null,
        artworkMime: String? = null,
    ): Boolean {
        if (title == null && artist == null && album == null && artwork == null) return true
        val format = formatOf(context, uriString)
        if (format == Format.UNSUPPORTED) return false
        // Every in-place rewrite goes through SafeRewrite, so a crash mid-write costs the
        // edit, never the song.
        return SafeRewrite.run(context, uriString) {
            when (format) {
                Format.ID3 -> Id3Tag.writeTags(
                    context = context,
                    uriString = uriString,
                    title = title,
                    artist = artist,
                    album = album,
                    artwork = artwork,
                    artworkMime = artworkMime,
                )
                Format.MP4 -> Mp4Tag.writeTags(
                    context = context,
                    uriString = uriString,
                    title = title,
                    artist = artist,
                    album = album,
                    artwork = artwork,
                    artworkMime = artworkMime,
                )
                Format.UNSUPPORTED -> false
            }
        }
    }

    private enum class Format { ID3, MP4, UNSUPPORTED }

    private fun formatOf(context: Context, uriString: String): Format {
        val name = displayName(context, uriString)?.lowercase().orEmpty()
        return when (name.substringAfterLast('.', "")) {
            "mp3" -> Format.ID3
            in Mp4Tag.EXTENSIONS -> Format.MP4
            else -> Format.UNSUPPORTED
        }
    }

    /**
     * SAF uris don't carry the filename in their path, so the extension has to come from
     * the document's own display name.
     */
    private fun displayName(context: Context, uriString: String): String? = runCatching {
        DocumentFile.fromSingleUri(context, Uri.parse(uriString))?.name
    }.getOrNull()
}
