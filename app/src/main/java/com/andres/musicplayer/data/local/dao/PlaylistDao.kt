package com.andres.musicplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.andres.musicplayer.data.local.entity.Playlist
import com.andres.musicplayer.data.local.entity.PlaylistSongCrossRef
import com.andres.musicplayer.data.local.entity.PlaylistWithSongs
import com.andres.musicplayer.data.local.entity.Song
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Insert
    suspend fun insert(playlist: Playlist): Long

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Transaction
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllWithSongs(): Flow<List<PlaylistWithSongs>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun getPlaylist(id: Long): Flow<Playlist?>

    /** One-off lookup by exact name (used by subfolder→playlist auto-mapping). */
    @Query("SELECT * FROM playlists WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): Playlist?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRefs(refs: List<PlaylistSongCrossRef>)

    @Query("DELETE FROM playlist_song_cross_ref WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSong(playlistId: Long, songId: Long)

    @Query(
        "UPDATE playlist_song_cross_ref SET addedAt = :addedAt " +
            "WHERE playlistId = :playlistId AND songId = :songId",
    )
    suspend fun updateAddedAt(playlistId: Long, songId: Long, addedAt: Long)

    @Query(
        "SELECT songs.* FROM songs " +
            "INNER JOIN playlist_song_cross_ref ON songs.id = playlist_song_cross_ref.songId " +
            "WHERE playlist_song_cross_ref.playlistId = :playlistId " +
            "ORDER BY playlist_song_cross_ref.addedAt ASC",
    )
    fun songsInPlaylist(playlistId: Long): Flow<List<Song>>
}
