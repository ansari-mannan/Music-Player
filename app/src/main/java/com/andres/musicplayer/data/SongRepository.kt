package com.andres.musicplayer.data

import android.content.Context
import com.andres.musicplayer.data.id3.Id3Tag
import com.andres.musicplayer.data.local.AppDatabase
import com.andres.musicplayer.data.local.dao.PlaylistDao
import com.andres.musicplayer.data.local.dao.SongDao
import com.andres.musicplayer.data.local.dao.TagDao
import com.andres.musicplayer.data.local.entity.Playlist
import com.andres.musicplayer.data.local.entity.PlaylistSongCrossRef
import com.andres.musicplayer.data.local.entity.PlaylistWithSongs
import com.andres.musicplayer.data.local.entity.Song
import com.andres.musicplayer.data.local.entity.SongTagCrossRef
import com.andres.musicplayer.data.local.entity.SongWithTags
import com.andres.musicplayer.data.local.entity.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Single entry point for the rest of the app to read/write library data.
 * Keeps Room (DAOs) hidden behind plain function calls.
 */
class SongRepository(
    private val appContext: Context,
    private val songDao: SongDao,
    private val tagDao: TagDao,
    private val playlistDao: PlaylistDao,
) {
    val allSongs: Flow<List<Song>> = songDao.getAll()
    val allSongsWithTags: Flow<List<SongWithTags>> = songDao.getAllWithTags()
    val allTags: Flow<List<Tag>> = tagDao.getAll()

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

    suspend fun insertTag(tag: Tag): Long = tagDao.insert(tag)

    /** Links an existing song to an existing tag. */
    suspend fun tagSong(songId: Long, tagId: Long) =
        songDao.insertCrossRef(SongTagCrossRef(songId = songId, tagId = tagId))

    /** Removes a tag from a song. */
    suspend fun untagSong(songId: Long, tagId: Long) =
        songDao.deleteCrossRef(songId, tagId)

    suspend fun getTagByName(name: String): Tag? = tagDao.getByName(name)

    suspend fun renameTag(id: Long, name: String) = tagDao.rename(id, name)

    /** Deletes a tag; song links are removed automatically via the FK CASCADE. */
    suspend fun deleteTag(id: Long) = tagDao.deleteById(id)

    fun songsByTag(tagId: Long): Flow<List<SongWithTags>> =
        songDao.getSongsWithTagsByTag(tagId)

    // --- Writing tags into the actual MP3 files (so edits/moods survive a DB wipe) ---

    /**
     * Writes one song's current title/artist/album + moods into its file's ID3 tag.
     * No-op (returns false) for non-SAF uris (e.g. Browse/MediaStore imports) or
     * read-only folders. Runs off the main thread.
     */
    suspend fun writeMetadataToFile(songId: Long): Boolean = withContext(Dispatchers.IO) {
        val item = songDao.getWithTagsById(songId) ?: return@withContext false
        if (!item.song.uri.contains("/document/")) return@withContext false
        Id3Tag.writeTags(
            context = appContext,
            uriString = item.song.uri,
            title = item.song.title,
            artist = item.song.artist,
            album = item.song.album,
            moods = item.tags.map { it.name },
        )
    }

    /**
     * Writes every song's tags into its file. Reports progress as (done, total) and
     * returns how many were successfully written. Skips non-writable uris.
     */
    suspend fun syncAllTagsToFiles(
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = withContext(Dispatchers.IO) {
        val all = songDao.getAllWithTagsOnce().filter { it.song.uri.contains("/document/") }
        var written = 0
        all.forEachIndexed { index, item ->
            val ok = Id3Tag.writeTags(
                context = appContext,
                uriString = item.song.uri,
                title = item.song.title,
                artist = item.song.artist,
                album = item.song.album,
                moods = item.tags.map { it.name },
            )
            if (ok) written++
            onProgress(index + 1, all.size)
        }
        written
    }

    // --- Playlists ---

    val allPlaylistsWithSongs: Flow<List<PlaylistWithSongs>> = playlistDao.getAllWithSongs()

    fun playlist(id: Long): Flow<Playlist?> = playlistDao.getPlaylist(id)

    fun playlistSongs(id: Long): Flow<List<SongWithTags>> = playlistDao.songsInPlaylist(id)

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
        fun from(context: Context): SongRepository {
            val db = AppDatabase.getInstance(context)
            return SongRepository(
                context.applicationContext,
                db.songDao(),
                db.tagDao(),
                db.playlistDao(),
            )
        }
    }
}
