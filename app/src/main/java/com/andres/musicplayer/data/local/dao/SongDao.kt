package com.andres.musicplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.andres.musicplayer.data.local.entity.Song
import com.andres.musicplayer.data.local.entity.SongTagCrossRef
import com.andres.musicplayer.data.local.entity.SongWithTags
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

    /** Removes songs from the library. Tag links cascade-delete via the FK. */
    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM songs ORDER BY dateAdded DESC")
    fun getAll(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM songs ORDER BY dateAdded DESC")
    fun getAllWithTags(): Flow<List<SongWithTags>>

    /** One-shot snapshot for writing tags to files (no Flow). */
    @Transaction
    @Query("SELECT * FROM songs ORDER BY dateAdded DESC")
    suspend fun getAllWithTagsOnce(): List<SongWithTags>

    @Transaction
    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getWithTagsById(id: Long): SongWithTags?

    @Transaction
    @Query(
        "SELECT songs.* FROM songs " +
            "INNER JOIN song_tag_cross_ref ON songs.id = song_tag_cross_ref.songId " +
            "WHERE song_tag_cross_ref.tagId = :tagId " +
            "ORDER BY songs.dateAdded DESC",
    )
    fun getSongsWithTagsByTag(tagId: Long): Flow<List<SongWithTags>>

    /** Links a song to a tag. Ignored if the link already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRef(crossRef: SongTagCrossRef)

    /** Removes the link between a song and a tag (untag). */
    @Query("DELETE FROM song_tag_cross_ref WHERE songId = :songId AND tagId = :tagId")
    suspend fun deleteCrossRef(songId: Long, tagId: Long)
}

/** A library song's id + uri, used to find entries whose files no longer exist. */
data class UriId(val id: Long, val uri: String)

/** Identity of a song independent of its uri, for cross-source duplicate detection. */
data class SongDedupKey(val id: Long, val title: String, val durationMs: Long)
