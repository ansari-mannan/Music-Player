package com.andres.musicplayer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.andres.musicplayer.data.local.AppDatabase
import com.andres.musicplayer.data.local.dao.PlaylistDao
import com.andres.musicplayer.data.local.dao.SongDao
import com.andres.musicplayer.data.local.entity.Playlist
import com.andres.musicplayer.data.local.entity.PlaylistSongCrossRef
import com.andres.musicplayer.data.local.entity.PlaylistWithSongs
import com.andres.musicplayer.data.local.entity.Song
import com.andres.musicplayer.data.tags.AudioTags
import com.andres.musicplayer.data.tags.Mp4Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Single entry point for the rest of the app to read/write library data.
 * Keeps Room (DAOs) hidden behind plain function calls.
 */
class SongRepository(
    private val appContext: Context,
    private val songDao: SongDao,
    private val playlistDao: PlaylistDao,
) {
    val allSongs: Flow<List<Song>> = songDao.getAll()

    suspend fun insertSong(song: Song): Long = songDao.insert(song)

    /** Inserts new songs, returning their row ids in order (-1 for ignored duplicates). */
    suspend fun insertSongs(songs: List<Song>): List<Long> = songDao.insertAll(songs)

    /** Map of uri → row id for everything in the library (skip + orphan cleanup). */
    suspend fun getKnownUris(): Map<String, Long> =
        songDao.getUriIds().associate { it.uri to it.id }

    /** Edits a song's title/artist/album. */
    suspend fun updateSongInfo(id: Long, title: String, artist: String?, album: String?) =
        songDao.updateInfo(id, title, artist, album)

    /** Removes songs from the library (does not delete the files). */
    suspend fun deleteSongs(ids: List<Long>) = songDao.deleteByIds(ids)

    /**
     * Ensures each [songs] entry exists in the library and returns their library ids in
     * order. Used when adding device/Browse files to a playlist, which can only reference
     * real library rows.
     *
     * Dedup is two layers, because the same file has different uris under SAF (the scanned
     * folder) and MediaStore (Browse): first an exact uri match, then a content match on
     * title + duration so a file already in the library is reused instead of duplicated.
     */
    suspend fun importAndGetIds(songs: List<Song>): List<Long> {
        val byUri = getKnownUris().toMutableMap()
        val byContent = HashMap<String, Long>()
        songDao.getDedupKeys().forEach { k ->
            byContent.getOrPut(contentKey(k.title, k.durationMs)) { k.id }
        }
        val ids = mutableListOf<Long>()
        for (s in songs) {
            byUri[s.uri]?.let { ids += it; continue }
            val key = contentKey(s.title, s.durationMs)
            byContent[key]?.let { ids += it; continue }

            val fresh = s.copy(id = 0, dateAdded = System.currentTimeMillis())
            val newId = songDao.insert(fresh)
            val id = if (newId != -1L) newId else getKnownUris()[s.uri]
            if (id != null) {
                byUri[s.uri] = id
                byContent[key] = id
                ids += id
            }
        }
        return ids
    }

    /** Uri-independent identity for a track: normalised title + duration (to the second). */
    private fun contentKey(title: String, durationMs: Long): String =
        "${title.trim().lowercase()}|${durationMs / 1000}"

    // --- Writing metadata into the audio files themselves (so edits survive a DB wipe) ---

    /**
     * Writes one song's current title/artist/album into its file's tag — ID3 for MP3,
     * iTunes atoms for M4A. No-op (returns false) for non-SAF uris (e.g. Browse/
     * MediaStore imports), read-only folders, or containers with no tag writer.
     * Runs off the main thread.
     */
    suspend fun writeMetadataToFile(songId: Long): Boolean = withContext(Dispatchers.IO) {
        val song = songDao.getById(songId) ?: return@withContext false
        if (!song.uri.contains("/document/")) return@withContext false
        AudioTags.writeTags(
            context = appContext,
            uriString = song.uri,
            title = song.title,
            artist = song.artist,
            album = song.album,
        )
    }

    /** Outcome of a bulk metadata write: how many files took it, and how many couldn't. */
    data class MetadataSyncResult(val written: Int, val skipped: Int)

    /**
     * Writes every song's info into its file. Reports progress as (done, total).
     *
     * [MetadataSyncResult.skipped] counts songs whose container has no tag writer
     * (Opus/WebM/FLAC) or whose file rejected the write, so the UI can say so instead of
     * quietly reporting a smaller number than the user expected.
     */
    suspend fun syncAllMetadataToFiles(
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): MetadataSyncResult = withContext(Dispatchers.IO) {
        val all = songDao.getAllOnce().filter { it.uri.contains("/document/") }
        var written = 0
        all.forEachIndexed { index, song ->
            val ok = AudioTags.writeTags(
                context = appContext,
                uriString = song.uri,
                title = song.title,
                artist = song.artist,
                album = song.album,
            )
            if (ok) written++
            onProgress(index + 1, all.size)
        }
        MetadataSyncResult(written = written, skipped = all.size - written)
    }

    /**
     * One-time fix for M4A files the old tag writer damaged: it put `moov` after the audio
     * fragments, so those tracks played silence until the user seeked, and Android's
     * metadata reader often missed their artist, album and cover art too.
     *
     * Runs once per install (flag set only after a complete pass, so an interrupted one
     * tries again next launch). Returns how many files were rewritten.
     */
    suspend fun repairDamagedM4aFilesOnce(): Int = withContext(Dispatchers.IO) {
        val flags = appContext.getSharedPreferences(MAINTENANCE_PREFS, Context.MODE_PRIVATE)
        if (flags.getBoolean(KEY_M4A_LAYOUT_REPAIRED, false)) return@withContext 0

        var repaired = 0
        songDao.getAllOnce()
            .filter { it.uri.contains("/document/") }
            .forEach { song ->
                if (Mp4Tag.repairFragmentedLayout(appContext, song.uri)) {
                    repaired++
                    refreshMissingInfo(song)
                }
            }
        flags.edit().putBoolean(KEY_M4A_LAYOUT_REPAIRED, true).apply()
        repaired
    }

    /** Re-reads a repaired file and fills in whatever the first scan couldn't see. */
    private suspend fun refreshMissingInfo(song: Song) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, Uri.parse(song.uri))
            songDao.fillMissingInfo(
                id = song.id,
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.takeIf { it.isNotBlank() },
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?.takeIf { it.isNotBlank() },
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
            )
        } catch (_: Exception) {
            // The file itself is fixed either way; the library row just stays as it was.
        } finally {
            retriever.release()
        }
    }

    // --- Cover art (embedded permanently into the MP3 files) ---

    /**
     * Embeds the image at [imageUri] as one song's cover art (APIC frame), so the new
     * thumbnail lives in the file itself and survives a DB wipe / reinstall. Returns
     * false for non-writable (non-SAF) uris or unreadable images. Runs off the main thread.
     */
    suspend fun writeArtworkFromUri(songId: Long, imageUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val song = songDao.getById(songId) ?: return@withContext false
        if (!song.uri.contains("/document/")) return@withContext false
        val jpeg = decodeScaledJpeg(imageUri) ?: return@withContext false
        AudioTags.writeTags(appContext,song.uri, artwork = jpeg, artworkMime = "image/jpeg")
    }

    /**
     * Embeds [imageUri] as the cover art of every track in [album] at once. Returns how
     * many files were updated. Skips non-writable uris.
     */
    suspend fun writeArtworkToAlbumFromUri(album: String, imageUri: Uri): Int = withContext(Dispatchers.IO) {
        val jpeg = decodeScaledJpeg(imageUri) ?: return@withContext 0
        val targets = songDao.getAllOnce().filter {
            it.uri.contains("/document/") &&
                (it.album?.takeIf { a -> a.isNotBlank() } ?: "Unknown album") == album
        }
        var written = 0
        targets.forEach {
            if (AudioTags.writeTags(appContext,it.uri, artwork = jpeg, artworkMime = "image/jpeg")) written++
        }
        written
    }

    /** Decodes a picked image and re-encodes it as a reasonably-sized JPEG for embedding. */
    private fun decodeScaledJpeg(imageUri: Uri): ByteArray? = runCatching {
        val resolver = appContext.contentResolver
        val original = resolver.openInputStream(imageUri)?.use { BitmapFactory.decodeStream(it) }
            ?: return null
        val max = 1000
        val longest = maxOf(original.width, original.height)
        val scale = if (longest > max) max.toFloat() / longest else 1f
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                original,
                (original.width * scale).toInt().coerceAtLeast(1),
                (original.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            original
        }
        ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
    }.getOrNull()

    // --- Playlists ---

    val allPlaylistsWithSongs: Flow<List<PlaylistWithSongs>> = playlistDao.getAllWithSongs()

    fun playlist(id: Long): Flow<Playlist?> = playlistDao.getPlaylist(id)

    fun playlistSongs(id: Long): Flow<List<Song>> = playlistDao.songsInPlaylist(id)

    suspend fun createPlaylist(name: String): Long =
        playlistDao.insert(Playlist(name = name))

    suspend fun deletePlaylist(id: Long) = playlistDao.deleteById(id)

    /** Finds a playlist by exact name, or null. Used by subfolder→playlist auto-mapping. */
    suspend fun getPlaylistByName(name: String): Playlist? = playlistDao.getByName(name)

    suspend fun renamePlaylist(id: Long, name: String) = playlistDao.rename(id, name)

    /** Adds songs to a playlist, keeping the given order (skips duplicates). */
    suspend fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>) {
        val now = System.currentTimeMillis()
        playlistDao.insertCrossRefs(
            songIds.mapIndexed { index, songId ->
                PlaylistSongCrossRef(playlistId = playlistId, songId = songId, addedAt = now + index)
            },
        )
    }

    suspend fun removeFromPlaylist(playlistId: Long, songId: Long) =
        playlistDao.removeSong(playlistId, songId)

    /** Persists a new song order for a playlist (rewrites the per-song addedAt keys). */
    suspend fun reorderPlaylist(playlistId: Long, orderedSongIds: List<Long>) {
        orderedSongIds.forEachIndexed { index, songId ->
            playlistDao.updateAddedAt(playlistId, songId, index.toLong())
        }
    }

    companion object {
        private const val MAINTENANCE_PREFS = "library_maintenance"
        private const val KEY_M4A_LAYOUT_REPAIRED = "m4a_fragment_layout_repaired_v1"

        fun from(context: Context): SongRepository {
            val db = AppDatabase.getInstance(context)
            return SongRepository(
                context.applicationContext,
                db.songDao(),
                db.playlistDao(),
            )
        }
    }
}
