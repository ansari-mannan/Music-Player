package com.andres.musicplayer.data.scan

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.andres.musicplayer.data.id3.Id3Tag
import com.andres.musicplayer.data.local.entity.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads MP3 files out of a user-picked SAF folder tree and turns them into
 * [Song]s. Does no database work itself — it just discovers and reads files.
 */
class MediaScanner(private val context: Context) {

    /** A newly-discovered song plus its source subfolder and any moods saved in the file. */
    data class ScannedSong(
        val song: Song,
        val sourceSubfolder: String?,
        val moods: List<String>,
    )

    data class Result(
        val newSongs: List<ScannedSong>,
        val totalMp3Found: Int,
        /** Every MP3 uri found in the tree (used to detect deleted/orphaned files). */
        val allFoundUris: List<String>,
    )

    /**
     * Walks [treeUri] (recursively, including subfolders), reads metadata from
     * every .mp3 that isn't already in [knownUris], and returns the new songs.
     * Reports progress through [onProgress] as files are processed. Runs on the
     * IO dispatcher because file/metadata access is blocking.
     */
    suspend fun scan(
        treeUri: Uri,
        knownUris: Set<String>,
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> },
    ): Result =
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext Result(emptyList(), totalMp3Found = 0, allFoundUris = emptyList())

            val mp3Files = mutableListOf<FoundFile>()
            // Start at the root with no subfolder; it's set once when descending one level.
            collectMp3Files(root, subfolder = null, out = mp3Files)

            val total = mp3Files.size
            onProgress(0, total)

            val allFoundUris = ArrayList<String>(total)
            val newSongs = mutableListOf<ScannedSong>()
            mp3Files.forEachIndexed { index, found ->
                val uriString = found.file.uri.toString()
                allFoundUris += uriString
                if (uriString !in knownUris) {
                    val moods = Id3Tag.readMoods(context, uriString)
                    newSongs += ScannedSong(readSong(found.file), found.subfolder, moods)
                }
                onProgress(index + 1, total)
            }

            Result(newSongs = newSongs, totalMp3Found = total, allFoundUris = allFoundUris)
        }

    /** A discovered file paired with the root subfolder it lives under (null = loose in root). */
    private data class FoundFile(val file: DocumentFile, val subfolder: String?)

    private fun collectMp3Files(dir: DocumentFile, subfolder: String?, out: MutableList<FoundFile>) {
        for (child in dir.listFiles()) {
            when {
                // First descent from root sets the subfolder; deeper levels keep it.
                child.isDirectory -> collectMp3Files(child, subfolder ?: child.name, out)
                isMp3(child) -> out += FoundFile(child, subfolder)
            }
        }
    }

    private fun isMp3(file: DocumentFile): Boolean {
        if (!file.isFile) return false
        val name = file.name?.lowercase().orEmpty()
        return name.endsWith(".mp3") || file.type == "audio/mpeg"
    }

    private fun readSong(file: DocumentFile): Song {
        val uri = file.uri
        val displayName = file.name ?: "Unknown"
        val fallbackTitle = displayName.substringBeforeLast(".", displayName)

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
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
            // up and can be tagged; metadata just stays minimal.
            Song(uri = uri.toString(), title = fallbackTitle, artist = null, durationMs = 0L)
        } finally {
            retriever.release()
        }
    }
}
