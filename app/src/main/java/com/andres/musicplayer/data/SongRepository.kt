package com.andres.musicplayer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.andres.musicplayer.data.local.AppDatabase
import com.andres.musicplayer.data.local.dao.ExclusionDao
import com.andres.musicplayer.data.local.dao.PlaylistDao
import com.andres.musicplayer.data.local.dao.PlaylistSummary
import com.andres.musicplayer.data.local.dao.SongDao
import com.andres.musicplayer.data.local.entity.ExcludedSong
import com.andres.musicplayer.data.local.entity.Playlist
import com.andres.musicplayer.data.local.entity.PlaylistSongCrossRef
import com.andres.musicplayer.data.local.entity.PlaylistWithSongs
import com.andres.musicplayer.data.local.entity.Song
import com.andres.musicplayer.data.tags.AudioTags
import com.andres.musicplayer.data.tags.Mp4Tag
import com.andres.musicplayer.data.tags.SafeRewrite
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val exclusionDao: ExclusionDao,
) {
    val allSongs: Flow<List<Song>> = songDao.getAll()

    private val _rewrittenUris = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /**
     * Uris of files that were just rewritten in place (tags, artwork, trims). The player
     * listens so a song it has open is reloaded instead of reading bytes that moved under it.
     */
    val rewrittenUris: SharedFlow<String> = _rewrittenUris.asSharedFlow()

    private fun rewritten(uri: String, ok: Boolean): Boolean {
        if (ok) _rewrittenUris.tryEmit(uri)
        return ok
    }

    suspend fun songsByIds(ids: List<Long>): List<Song> =
        if (ids.isEmpty()) emptyList() else ids.chunked(500).flatMap { songDao.getByIds(it) }

    suspend fun insertSong(song: Song): Long = songDao.insert(song)

    /** Inserts new songs, returning their row ids in order (-1 for ignored duplicates). */
    suspend fun insertSongs(songs: List<Song>): List<Long> = songDao.insertAll(songs)

    /** Map of uri → row id for everything in the library (skip + orphan cleanup). */
    suspend fun getKnownUris(): Map<String, Long> =
        songDao.getUriIds().associate { it.uri to it.id }

    /** Edits a song's title/artist/album. */
    suspend fun updateSongInfo(id: Long, title: String, artist: String?, album: String?) =
        songDao.updateInfo(id, title, artist, album)

    /**
     * Removes songs from the library at the user's request (the files are not deleted).
     * They're remembered as excluded so the next folder scan doesn't bring them back.
     */
    suspend fun deleteSongs(ids: List<Long>) {
        val songs = songsByIds(ids)
        exclusionDao.excludeSongs(
            songs.map { ExcludedSong(uri = it.uri, documentId = documentIdOf(it.uri)) },
        )
        songDao.deleteByIds(ids)
    }

    /** Removes entries whose files are gone. Not an exclusion: if the file returns, so does the song. */
    suspend fun deleteSongsFromScan(ids: List<Long>) = ids.chunked(500).forEach { songDao.deleteByIds(it) }

    suspend fun excludedSongs(): List<ExcludedSong> = exclusionDao.excludedSongs()

    val hiddenSongCount: Flow<Int> = exclusionDao.excludedSongCount()

    /** Forgets every exclusion; the next scan adds those songs back. */
    suspend fun restoreHiddenSongs() = exclusionDao.clearExcludedSongs()

    /** Keeps a song (id, playlists, edits) but points it at the file's new uri. */
    suspend fun remapSong(id: Long, uri: String) = songDao.updateUri(id, uri)

    /** Content keys (normalised title + seconds) for the given song ids. */
    suspend fun contentKeysFor(ids: Set<Long>): Map<Long, String> =
        songDao.getDedupKeys()
            .filter { it.id in ids && it.durationMs > 0 }
            .associate { it.id to contentKey(it.title, it.durationMs) }

    private fun documentIdOf(uri: String): String? =
        runCatching { DocumentsContract.getDocumentId(Uri.parse(uri)) }.getOrNull()

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
        rewritten(
            song.uri,
            AudioTags.writeTags(
                context = appContext,
                uriString = song.uri,
                title = song.title,
                artist = song.artist,
                album = song.album,
            ),
        )
    }

    /**
     * Outcome of a bulk metadata write: how many files took it, how many already matched
     * and were left alone, and how many couldn't be written.
     */
    data class MetadataSyncResult(val written: Int, val unchanged: Int, val skipped: Int)

    /**
     * Writes every song's info into its file. Reports progress as (done, total).
     *
     * Files whose tags already say what the library says are not touched. A tag write
     * rewrites the whole file through SAF, so rewriting an unchanged library was megabytes
     * of storage wear per song for nothing; reading the tags back only touches the header.
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
        var unchanged = 0
        all.forEachIndexed { index, song ->
            if (fileTagsMatch(song)) {
                unchanged++
            } else {
                val ok = AudioTags.writeTags(
                    context = appContext,
                    uriString = song.uri,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                )
                if (rewritten(song.uri, ok)) written++
            }
            onProgress(index + 1, all.size)
        }
        MetadataSyncResult(
            written = written,
            unchanged = unchanged,
            skipped = all.size - written - unchanged,
        )
    }

    /** True when the file's own title/artist/album already equal the library's. */
    private fun fileTagsMatch(song: Song): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, Uri.parse(song.uri))
            fun read(key: Int) = retriever.extractMetadata(key)?.trim().orEmpty()
            read(MediaMetadataRetriever.METADATA_KEY_TITLE) == song.title.trim() &&
                read(MediaMetadataRetriever.METADATA_KEY_ARTIST) == song.artist?.trim().orEmpty() &&
                read(MediaMetadataRetriever.METADATA_KEY_ALBUM) == song.album?.trim().orEmpty()
        } catch (_: Exception) {
            // Unreadable here means "unknown", and an unknown file gets the write.
            false
        } finally {
            retriever.release()
        }
    }

    /**
     * One-time fix for M4A files the old tag writer damaged: it put `moov` after the audio
     * fragments, so those tracks played silence until the user seeked, and Android's
     * metadata reader often missed their artist, album and cover art too.
     *
     * Runs once per install (flag set only after a complete pass, so an interrupted one
     * tries again next launch). Returns how many files were rewritten.
     */
    /** Puts back any file a crash interrupted mid-rewrite. Call before scanning. */
    suspend fun recoverInterruptedWrites(): Int = withContext(Dispatchers.IO) {
        SafeRewrite.recover(appContext)
    }

    suspend fun repairDamagedM4aFilesOnce(): Int = withContext(Dispatchers.IO) {
        val flags = appContext.getSharedPreferences(MAINTENANCE_PREFS, Context.MODE_PRIVATE)
        if (flags.getBoolean(KEY_M4A_LAYOUT_REPAIRED, false)) return@withContext 0

        var repaired = 0
        songDao.getAllOnce()
            .filter { it.uri.contains("/document/") }
            .forEach { song ->
                if (rewritten(song.uri, Mp4Tag.repairFragmentedLayout(appContext, song.uri))) {
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
        rewritten(song.uri, AudioTags.writeTags(appContext, song.uri, artwork = jpeg, artworkMime = "image/jpeg"))
    }

    /**
     * Embeds [imageUri] as the cover art of every track in [album] at once. Returns the
     * uris of the files that were updated. Skips non-writable uris.
     */
    suspend fun writeArtworkToAlbumFromUri(album: String, imageUri: Uri): List<String> = withContext(Dispatchers.IO) {
        val jpeg = decodeScaledJpeg(imageUri) ?: return@withContext emptyList()
        val targets = songDao.getAllOnce().filter {
            it.uri.contains("/document/") &&
                (it.album?.takeIf { a -> a.isNotBlank() } ?: "Unknown album") == album
        }
        targets.filter {
            rewritten(it.uri, AudioTags.writeTags(appContext, it.uri, artwork = jpeg, artworkMime = "image/jpeg"))
        }.map { it.uri }
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

    // --- Trim / cut ---

    /** What came of a trim (or an undo), in words for the user. */
    data class TrimOutcome(val ok: Boolean, val message: String)

    private val trimBackupDir get() = java.io.File(appContext.filesDir, "trim_backups").apply { mkdirs() }

    private fun trimBackupsFor(songId: Long): List<java.io.File> =
        trimBackupDir.listFiles { f -> f.name.startsWith("${songId}_") && !f.name.endsWith(".name") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    /** True when the song's pre-trim original is still kept, so the trim can be undone. */
    suspend fun hasTrimBackup(songId: Long): Boolean = withContext(Dispatchers.IO) {
        trimBackupsFor(songId).isNotEmpty()
    }

    /**
     * Replaces a song's file with the trimmed audio in [trimmed] (an .m4a), keeping the
     * song's id, playlists, title / artist / album and cover.
     *
     * - The original is copied aside first, so [undoTrim] can put it back.
     * - An .m4a song is rewritten in place (crash-safe, via [SafeRewrite]).
     * - Any other format becomes a new .m4a next to it (writing AAC into "song.mp3"
     *   would mislabel the file), and the old file is removed once the new one is in.
     */
    suspend fun applyTrim(songId: Long, trimmed: java.io.File): TrimOutcome = withContext(Dispatchers.IO) {
        try {
            val song = songDao.getById(songId) ?: return@withContext TrimOutcome(false, "Song not found.")
            if (!song.uri.contains("/document/")) {
                return@withContext TrimOutcome(false, "Only songs from your music folder can be trimmed.")
            }
            val uri = Uri.parse(song.uri)
            val name = displayNameOf(uri) ?: "song"
            val ext = name.substringAfterLast('.', "").lowercase()

            // Keep the original for undo. Only the newest ten trims are kept around.
            val backup = java.io.File(trimBackupDir, "${songId}_${System.currentTimeMillis()}.${ext.ifBlank { "bin" }}")
            val copied = appContext.contentResolver.openInputStream(uri)?.use { input ->
                backup.outputStream().use { input.copyTo(it) }
            } != null
            if (!copied) return@withContext TrimOutcome(false, "Couldn't read the original file.")
            java.io.File(backup.path + ".name").writeText(name)
            pruneTrimBackups()

            val cover = embeddedPicture(uri)
            val newUri = if (ext in Mp4Tag.EXTENSIONS) {
                val ok = SafeRewrite.run(appContext, song.uri) {
                    writeTaggedCopy(trimmed, song, cover, uri)
                }
                if (!ok) {
                    backup.delete()
                    java.io.File(backup.path + ".name").delete()
                    return@withContext TrimOutcome(false, "Couldn't write the trimmed audio into the file.")
                }
                song.uri
            } else {
                val created = createSibling(uri, name.substringBeforeLast('.', name) + ".m4a", "audio/mp4")
                    ?: return@withContext TrimOutcome(false, "Couldn't create the trimmed file in that folder.")
                if (!writeTaggedCopy(trimmed, song, cover, created)) {
                    runCatching { DocumentsContract.deleteDocument(appContext.contentResolver, created) }
                    return@withContext TrimOutcome(false, "Couldn't write the trimmed file.")
                }
                songDao.updateUri(song.id, created.toString())
                runCatching { DocumentsContract.deleteDocument(appContext.contentResolver, uri) }
                created.toString()
            }

            songDao.updateDuration(song.id, durationOf(trimmed))
            if (newUri == song.uri) rewritten(newUri, true)
            TrimOutcome(true, "Trimmed. You can undo this from the trim screen.")
        } catch (e: Exception) {
            TrimOutcome(false, "Trim failed: ${e.message ?: "unknown error"}")
        } finally {
            trimmed.delete()
        }
    }

    /** Puts back the file as it was before the most recent trim. */
    suspend fun undoTrim(songId: Long): TrimOutcome = withContext(Dispatchers.IO) {
        val song = songDao.getById(songId) ?: return@withContext TrimOutcome(false, "Song not found.")
        val backup = trimBackupsFor(songId).firstOrNull()
            ?: return@withContext TrimOutcome(false, "There's no earlier version to go back to.")
        val nameFile = java.io.File(backup.path + ".name")
        val originalName = runCatching { nameFile.readText() }.getOrNull()
        val backupExt = backup.name.substringAfterLast('.', "")
        val uri = Uri.parse(song.uri)
        val currentExt = displayNameOf(uri)?.substringAfterLast('.', "")?.lowercase().orEmpty()

        val ok = if (backupExt == currentExt) {
            SafeRewrite.run(appContext, song.uri) {
                appContext.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    backup.inputStream().use { it.copyTo(out) }
                } != null
            }.also { if (it) rewritten(song.uri, true) }
        } else {
            val restoredName = originalName ?: "${song.title}.$backupExt"
            val created = createSibling(uri, restoredName, mimeForExtension(backupExt))
            if (created == null) {
                false
            } else {
                val written = appContext.contentResolver.openOutputStream(created, "wt")?.use { out ->
                    backup.inputStream().use { it.copyTo(out) }
                } != null
                if (written) {
                    songDao.updateUri(song.id, created.toString())
                    runCatching { DocumentsContract.deleteDocument(appContext.contentResolver, uri) }
                }
                written
            }
        }
        if (!ok) return@withContext TrimOutcome(false, "Couldn't restore the original.")
        songDao.updateDuration(song.id, durationOf(backup))
        backup.delete()
        nameFile.delete()
        TrimOutcome(true, "Original restored.")
    }

    private fun pruneTrimBackups() {
        trimBackupDir.listFiles { f -> !f.name.endsWith(".name") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(10)
            ?.forEach {
                it.delete()
                java.io.File(it.path + ".name").delete()
            }
    }

    /** Streams [source] into [target] with the song's tags and cover, falling back to a plain copy. */
    private fun writeTaggedCopy(source: java.io.File, song: Song, cover: ByteArray?, target: Uri): Boolean {
        val resolver = appContext.contentResolver
        val tagged = runCatching {
            Mp4Tag.copyTagged(
                source = source,
                title = song.title,
                artist = song.artist,
                album = song.album,
                artwork = cover,
                artworkMime = "image/jpeg",
            ) { resolver.openOutputStream(target, "wt") }
        }.getOrNull()
        if (tagged != null) return true
        return resolver.openOutputStream(target, "wt")?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        } != null
    }

    /** Creates a new document beside [sibling] in the same folder. */
    private fun createSibling(sibling: Uri, displayName: String, mime: String): Uri? = runCatching {
        val treeId = DocumentsContract.getTreeDocumentId(sibling)
        val docId = DocumentsContract.getDocumentId(sibling)
        val parentId = if (docId.contains('/')) docId.substringBeforeLast('/') else treeId
        val parent = DocumentsContract.buildDocumentUriUsingTree(sibling, parentId)
        DocumentsContract.createDocument(appContext.contentResolver, parent, mime, displayName)
    }.getOrNull()

    private fun displayNameOf(uri: Uri): String? = runCatching {
        androidx.documentfile.provider.DocumentFile.fromSingleUri(appContext, uri)?.name
    }.getOrNull()

    private fun embeddedPicture(uri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            retriever.embeddedPicture
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun durationOf(file: java.io.File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }

    private fun mimeForExtension(ext: String) = when (ext.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "mp4", "m4b", "aac" -> "audio/mp4"
        "opus", "ogg", "oga" -> "audio/ogg"
        "webm" -> "audio/webm"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }

    // --- Playlists ---

    val allPlaylistsWithSongs: Flow<List<PlaylistWithSongs>> = playlistDao.getAllWithSongs()

    /** Name, count and four covers per playlist — what list views and pickers need. */
    val playlistSummaries: Flow<List<PlaylistSummary>> = playlistDao.getSummaries()

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
        // After the current last song, whatever ordering scheme the playlist is on — a
        // reordered playlist uses small indices, a never-reordered one timestamps.
        val now = maxOf(System.currentTimeMillis(), playlistDao.maxAddedAt(playlistId) + 1)
        playlistDao.insertCrossRefs(
            songIds.mapIndexed { index, songId ->
                PlaylistSongCrossRef(playlistId = playlistId, songId = songId, addedAt = now + index)
            },
        )
    }

    /**
     * Adds a finished download to the library and, when it came from a synced YouTube
     * playlist, to the in-app playlist of the same name (created on first use) — so the
     * YouTube playlist recreates itself here without any manual sorting.
     */
    suspend fun addDownloadedSong(song: Song, playlistTitle: String?) {
        val inserted = songDao.insert(song)
        val id = if (inserted != -1L) inserted else songDao.getByUri(song.uri)?.id ?: return
        val name = playlistTitle?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val playlistId = getPlaylistByName(name)?.id ?: createPlaylist(name)
        addSongsToPlaylist(playlistId, listOf(id))
    }

    suspend fun removeFromPlaylist(playlistId: Long, songId: Long) =
        playlistDao.removeSong(playlistId, songId)

    /** Persists a new song order for a playlist (rewrites the per-song addedAt keys). */
    suspend fun reorderPlaylist(playlistId: Long, orderedSongIds: List<Long>) {
        // One transaction, so the list observes one change rather than one per song.
        playlistDao.reorder(playlistId, orderedSongIds)
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
                db.exclusionDao(),
            )
        }
    }
}
