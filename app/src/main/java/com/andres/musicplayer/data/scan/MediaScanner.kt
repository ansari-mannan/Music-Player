package com.andres.musicplayer.data.scan

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import com.andres.musicplayer.data.local.entity.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads audio files out of a user-picked SAF folder tree and turns them into
 * [Song]s. Does no database work itself — it just discovers and reads files.
 *
 * Not MP3-only: YouTube never serves MP3, so anything downloaded in-app arrives as
 * m4a (AAC) or, occasionally, webm/Opus. Media3 plays all of them natively, so the
 * scanner accepts them rather than the app transcoding — re-encoding lossy audio to
 * lossy MP3 would need a bundled encoder Android doesn't ship, and would sound worse.
 */
class MediaScanner(private val context: Context) {

    /** A newly-discovered song plus the source subfolder it was found in. */
    data class ScannedSong(
        val song: Song,
        val sourceSubfolder: String?,
    )

    data class Result(
        val newSongs: List<ScannedSong>,
        val totalAudioFound: Int,
        /** Every audio uri found in the tree (used to detect deleted/orphaned files). */
        val allFoundUris: List<String>,
        /**
         * False when any folder in the tree couldn't be listed (permission lost, SD card
         * removed, provider error). A partial listing says nothing about which songs are
         * gone, so the caller must not remove anything on the strength of it.
         */
        val complete: Boolean = true,
    )

    /**
     * Walks [treeUri] (recursively, including subfolders), reads metadata from
     * every audio file that isn't already in [knownUris], and returns the new songs.
     * Reports progress through [onProgress] as files are processed. Runs on the
     * IO dispatcher because file/metadata access is blocking.
     */
    suspend fun scan(
        treeUri: Uri,
        knownUris: Set<String>,
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> },
    ): Result =
        withContext(Dispatchers.IO) {
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: return@withContext Result(
                    emptyList(),
                    totalAudioFound = 0,
                    allFoundUris = emptyList(),
                    complete = false,
                )

            val audioFiles = mutableListOf<FoundFile>()
            // Start at the root with no subfolder; it's set once when descending one level.
            val complete = collectAudioFiles(treeUri, rootId, subfolder = null, out = audioFiles)

            val total = audioFiles.size
            onProgress(0, total)

            val allFoundUris = ArrayList<String>(total)
            val newSongs = mutableListOf<ScannedSong>()
            audioFiles.forEachIndexed { index, found ->
                val uriString = found.uri.toString()
                if (uriString in knownUris) {
                    allFoundUris += uriString
                } else {
                    // Null means it turned out to be video (an .mp4/.webm that isn't
                    // audio) or unreadable — leave it out of the library entirely, and
                    // out of allFoundUris so it isn't mistaken for a live song.
                    val song = readSong(found.uri, found.name)
                    if (song != null) {
                        allFoundUris += uriString
                        newSongs += ScannedSong(song, found.subfolder)
                    }
                }
                onProgress(index + 1, total)
            }

            Result(
                newSongs = newSongs,
                totalAudioFound = allFoundUris.size,
                allFoundUris = allFoundUris,
                complete = complete,
            )
        }

    /** A discovered file paired with the root subfolder it lives under (null = loose in root). */
    private data class FoundFile(val uri: Uri, val name: String, val subfolder: String?)

    /**
     * Lists the tree with one [DocumentsContract] query per folder, asking only for the
     * three columns needed. `DocumentFile.listFiles()` returned bare handles, and every
     * `isDirectory` / `name` / `type` call on them was a separate provider round trip —
     * three IPC queries per file, on every app launch.
     *
     * Uris are built exactly as `DocumentFile` builds them (tree uri + document id), so
     * they match the ones already stored in the library.
     *
     * Returns false if any folder in the tree couldn't be listed.
     */
    private fun collectAudioFiles(
        treeUri: Uri,
        parentId: String,
        subfolder: String?,
        out: MutableList<FoundFile>,
    ): Boolean {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val folders = mutableListOf<Pair<String, String>>()
        val cursor = runCatching {
            context.contentResolver.query(childrenUri, CHILD_PROJECTION, null, null, null)
        }.getOrNull() ?: return false
        cursor.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1).orEmpty()
                val mime = cursor.getString(2).orEmpty()
                when {
                    mime == DocumentsContract.Document.MIME_TYPE_DIR -> folders += id to name
                    isSupportedAudio(name, mime) -> out += FoundFile(
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                        name,
                        subfolder,
                    )
                }
            }
        }
        // First descent from root sets the subfolder; deeper levels keep it.
        var complete = true
        folders.forEach { (id, name) ->
            if (!collectAudioFiles(treeUri, id, subfolder ?: name, out)) complete = false
        }
        return complete
    }

    private fun isSupportedAudio(name: String, mime: String): Boolean {
        val extension = name.lowercase().substringAfterLast('.', "")
        return extension in AUDIO_EXTENSIONS || mime in AUDIO_MIME_TYPES
    }

    private companion object {
        val CHILD_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )

        /**
         * `.mp4` and `.webm` are container extensions that can hold video too — a video
         * file dropped in the music folder would be picked up here, but [readSong]
         * discards anything with a video track, so it never reaches the library.
         */
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "m4b", "mp4", "aac", "opus", "ogg", "oga", "webm", "flac", "wav",
        )

        val AUDIO_MIME_TYPES = setOf(
            "audio/mpeg", "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac",
            "audio/ogg", "audio/opus", "audio/webm", "audio/flac", "audio/x-flac",
            "audio/wav", "audio/x-wav",
        )
    }

    /**
     * Reads one file into a [Song], or returns null if it shouldn't be in the library:
     * a container that turned out to hold video, or a file that has no audio at all.
     */
    private fun readSong(uri: Uri, name: String): Song? {
        val displayName = name.ifBlank { "Unknown" }
        val fallbackTitle = displayName.substringBeforeLast(".", displayName)

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val hasVideo = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            if (hasVideo) return null
            val title = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?: fallbackTitle
            val artist = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }
            val album = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() }
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
            Song(
                uri = uri.toString(),
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
            )
        } catch (_: Exception) {
            // Unreadable/corrupt file: still record it (by filename) so it shows
            // up in the library; metadata just stays minimal.
            Song(uri = uri.toString(), title = fallbackTitle, artist = null, durationMs = 0L)
        } finally {
            retriever.release()
        }
    }
}
