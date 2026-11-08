package com.andres.musicplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.andres.musicplayer.data.local.entity.Song
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {

    /** Returns the new row id, or -1 if a song with the same uri already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(song: Song): Long

    /**
     * Bulk insert (used by the folder scan). Existing uris are ignored. Returns the new
     * row id for each input in order (-1 for any that were ignored as duplicates).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(songs: List<Song>): List<Long>

    /** All file uris already in the library, for cheap duplicate detection. */
    @Query("SELECT uri FROM songs")
    suspend fun getAllUris(): List<String>

    /** Each library song's uri paired with its row id (for orphan cleanup). */
    @Query("SELECT id, uri FROM songs")
    suspend fun getUriIds(): List<UriId>

    /** Title + duration of every song, for matching the same file across SAF/MediaStore. */
    @Query("SELECT id, title, durationMs FROM songs")
    suspend fun getDedupKeys(): List<SongDedupKey>

    /** Edits the user-facing metadata for one song. */
    @Transaction
    @Query("UPDATE songs SET title = :title, artist = :artist, album = :album WHERE id = :id")
    suspend fun updateInfo(id: Long, title: String, artist: String?, album: String?)

    /**
     * Fills in metadata a scan couldn't read, without overwriting anything already there
     * (including the user's own edits).
     */
    @Query(
        "UPDATE songs SET artist = COALESCE(artist, :artist), album = COALESCE(album, :album), " +
            "durationMs = CASE WHEN durationMs > 0 THEN durationMs ELSE :durationMs END " +
            "WHERE id = :id",
    )
    suspend fun fillMissingInfo(id: Long, artist: String?, album: String?, durationMs: Long)

    /** Removes songs from the library. Playlist links cascade-delete via the FK. */
    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM songs ORDER BY dateAdded DESC")
    fun getAll(): Flow<List<Song>>

    /** One-shot snapshot for writing metadata to files (no Flow). */
    @Query("SELECT * FROM songs ORDER BY dateAdded DESC")
    suspend fun getAllOnce(): List<Song>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getById(id: Long): Song?

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Song>

    @Query("SELECT * FROM songs WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): Song?

    /**
     * Points an existing song at the file's new uri, keeping its id — and with it every
     * playlist it's in and every edit made to it. Used when the same file turns up under a
     * different uri (folder re-picked, or the file moved).
     */
    @Query("UPDATE songs SET uri = :uri WHERE id = :id")
    suspend fun updateUri(id: Long, uri: String)

    @Query("UPDATE songs SET durationMs = :durationMs WHERE id = :id")
    suspend fun updateDuration(id: Long, durationMs: Long)
}

/** A library song's id + uri, used to find entries whose files no longer exist. */
data class UriId(val id: Long, val uri: String)

/** Identity of a song independent of its uri, for cross-source duplicate detection. */
data class SongDedupKey(val id: Long, val title: String, val durationMs: Long)
